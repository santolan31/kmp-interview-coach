package dev.kmpcoach.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import dev.kmpcoach.core.Blocks
import dev.kmpcoach.core.CodeBlock
import dev.kmpcoach.core.DiffParser
import dev.kmpcoach.core.Json
import dev.kmpcoach.core.Plan
import dev.kmpcoach.core.PlanParser
import dev.kmpcoach.core.PlanStep
import dev.kmpcoach.core.asList
import dev.kmpcoach.core.asMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Contexto del proyecto que funciona con cualquier IA, porque solo lee archivos:
 * - el **plan** en Markdown (por defecto el más reciente de `docs/plans/`), y en qué paso vas;
 * - el **código nuevo** según `git diff HEAD` más los archivos sin seguimiento, agrupado en bloques;
 * - qué bloques ya comprobaste que entiendes, y tus aciertos al predecir.
 * Se guarda en el espacio de trabajo del proyecto (`.idea/workspace.xml`), no en el repositorio.
 */
@Service(Service.Level.PROJECT)
@State(name = "KmpCoachProjectLearning", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectContext(private val project: Project, private val cs: CoroutineScope) :
    SimplePersistentStateComponent<ProjectContext.Data>(Data()), Disposable {

    class Data : BaseState() {
        /** Ruta del plan relativa al proyecto; vacía = el más reciente de docs/plans. */
        var planPath by string("")
        /** Paso elegido a mano; -1 = el primero sin terminar. */
        var stepIndex by property(-1)
        /** JSON [id] de bloques que comprobaste que entiendes. */
        var understood by string("[]")
        /** JSON [id] de bloques que marcaste como "no necesita revisión" (código generado, configuración…). */
        var ignored by string("[]")
        /** JSON {"<plan>#<paso>": "tu respuesta"} a «¿qué archivos crees que toca este paso?». */
        var guesses by string("{}")
        var predictions by property(0)
        var predictionsRight by property(0)
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var blocks: List<CodeBlock> = emptyList()
    @Volatile var changedPaths: List<String> = emptyList()
        private set
    @Volatile var gitError: String? = null
        private set
    @Volatile var refreshedAt: Long = 0
        private set
    private var refreshJob: Job? = null
    private var cachedPlan: Triple<String, Long, Plan>? = null

    val basePath: String? get() = project.basePath

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun fire() = listeners.forEach { it() }

    fun start() {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { e -> e.path.endsWith(".kt") || e.path.endsWith(".kts") || e.path.endsWith(".md") }) scheduleRefresh(2_500)
            }
        })
        cs.launch {
            // Respaldo: los commits hechos fuera del IDE no siempre generan eventos de archivos.
            while (isActive) {
                refreshNow()
                delay(120_000)
            }
        }
    }

    fun scheduleRefresh(delayMs: Long = 0) {
        refreshJob?.cancel()
        refreshJob = cs.launch {
            delay(delayMs)
            refreshNow()
        }
    }

    // ---------- plan ----------
    private fun plansDir(): File? = basePath?.let { File(it, "docs/plans") }

    /** Planes disponibles en docs/plans, del más reciente al más antiguo, más el elegido a mano. */
    fun availablePlans(): List<String> {
        val base = basePath ?: return emptyList()
        val files = plansDir()?.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { it.relativeTo(File(base)).path }
        val chosen = state.planPath.orEmpty()
        return if (chosen.isNotEmpty() && chosen !in files) listOf(chosen) + files else files
    }

    fun planPath(): String? = state.planPath?.takeIf { it.isNotEmpty() } ?: availablePlans().firstOrNull()

    fun planFile(): VirtualFile? {
        val base = basePath ?: return null
        val rel = planPath() ?: return null
        val io = File(base, rel)
        // Un plan que una IA acaba de crear fuera del IDE puede no estar aún en el sistema de archivos del IDE.
        return LocalFileSystem.getInstance().findFileByIoFile(io)
            ?: io.takeIf { it.isFile }?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
    }

    /** Lee el plan desde el editor si está abierto (así ve los cambios sin guardar) o desde el disco. */
    fun plan(): Plan? {
        val vf = planFile() ?: return null
        val doc = FileDocumentManager.getInstance().getCachedDocument(vf)
        val stamp = doc?.modificationStamp ?: vf.modificationStamp
        cachedPlan?.let { (p, s, plan) -> if (p == vf.path && s == stamp) return plan }
        val text = doc?.text ?: runCatching { String(vf.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: return null
        return PlanParser.parse(text, vf.nameWithoutExtension).also { cachedPlan = Triple(vf.path, stamp, it) }
    }

    fun currentStep(): PlanStep? {
        val plan = plan() ?: return null
        return plan.steps.getOrNull(state.stepIndex) ?: plan.firstPending ?: plan.steps.lastOrNull()
    }

    /** Lo que se le cuenta al tutor sobre lo que intentas hacer. Corto, para no gastar tokens. */
    fun goal(): String? {
        val plan = plan() ?: return null
        val step = currentStep() ?: return null
        return PlanParser.goal(plan, step)
    }

    fun choosePlan(relativePath: String?) {
        state.planPath = relativePath.orEmpty()
        state.stepIndex = -1
        cachedPlan = null
        fire()
    }

    fun moveStep(delta: Int) {
        val plan = plan() ?: return
        val current = currentStep()?.index ?: 0
        state.stepIndex = (current + delta).coerceIn(0, (plan.steps.size - 1).coerceAtLeast(0))
        fire()
    }

    fun followPending() {
        state.stepIndex = -1
        fire()
    }

    private fun stepKey(): String? = currentStep()?.let { "${planPath()}#${it.index}:${it.text.take(40)}" }

    fun guessForStep(): String? = stepKey()?.let { guesses()[it] }

    fun saveGuess(answer: String) {
        val k = stepKey() ?: return
        val all = guesses().toMutableMap()
        all[k] = answer
        state.guesses = Json.write(all)
        fire()
    }

    private fun guesses(): Map<String, String> =
        runCatching { Json.parse(state.guesses ?: "{}").asMap().mapValues { it.value.toString() } }.getOrDefault(emptyMap())

    // ---------- código nuevo según git ----------
    /** Corre git en segundo plano y recalcula los bloques de código nuevo. */
    private suspend fun refreshNow() {
        val base = basePath ?: return
        if (!CoachSettings.getInstance().state.trackNewCode) return
        val result = withContext(Dispatchers.IO) { computeBlocks(base) }
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            blocks = result.first
            changedPaths = result.second
            gitError = result.third
            refreshedAt = System.currentTimeMillis()
            restartOpenEditors()
            fire()
        }
    }

    private fun computeBlocks(base: String): Triple<List<CodeBlock>, List<String>, String?> {
        // --relative: rutas relativas a la carpeta del proyecto, aunque el repositorio git empiece más arriba.
        val diff = git(base, "diff", "HEAD", "--relative", "--unified=0", "--no-color", "--no-ext-diff", "--", "*.kt", "*.kts")
            ?: return Triple(emptyList(), emptyList(), "No pude leer git en este proyecto. El seguimiento de código nuevo necesita un repositorio git con al menos un commit.")
        val untracked = git(base, "ls-files", "--others", "--exclude-standard", "--", "*.kt", "*.kts").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val diffs = DiffParser.parse(diff).toMutableList()
        val changed = diffs.map { it.path }.toMutableList()
        val out = ArrayList<CodeBlock>()
        for (d in diffs) {
            val lines = readLines(base, d.path) ?: continue
            out += Blocks.fromAdded(d.path, d.added, lines)
        }
        for (path in untracked) {
            val lines = readLines(base, path) ?: continue
            changed += path
            out += Blocks.fromAdded(path, (1..lines.size).toList(), lines)
        }
        return Triple(out.take(MAX_BLOCKS), changed.distinct(), null)
    }

    private fun readLines(base: String, path: String): List<String>? {
        val f = File(base, path)
        if (!f.isFile || f.length() > 400_000) return null
        return runCatching { f.readLines(Charsets.UTF_8) }.getOrNull()
    }

    private fun git(base: String, vararg args: String): String? = runCatching {
        val cmd = GeneralCommandLine("git", *args).withWorkDirectory(base).withCharset(Charsets.UTF_8)
        val out = ExecUtil.execAndGetOutput(cmd, 15_000)
        if (out.exitCode != 0 || out.isTimeout) null else out.stdout
    }.getOrNull()

    private fun restartOpenEditors() {
        val psi = PsiManager.getInstance(project)
        FileEditorManager.getInstance(project).openFiles.forEach { vf ->
            ReadAction.compute<com.intellij.psi.PsiFile?, RuntimeException> { psi.findFile(vf) }?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
        }
    }

    fun relativePath(file: VirtualFile): String? = basePath?.let { b -> file.path.takeIf { it.startsWith("$b/") }?.removePrefix("$b/") }

    /** Bloques de código nuevo de un archivo, con la línea donde están ahora en el editor (puede tener cambios sin guardar). */
    fun blocksIn(file: VirtualFile, documentLines: List<String>): List<Pair<CodeBlock, Int>> {
        val rel = relativePath(file) ?: return emptyList()
        return blocks.filter { it.path == rel }.mapNotNull { b -> Blocks.locate(b, documentLines)?.let { b to it } }
    }

    fun allBlocks(): List<CodeBlock> = blocks
    fun pendingBlocks(): List<CodeBlock> = blocks.filter { !isUnderstood(it) && !isIgnored(it) }

    private fun ids(json: String?): Set<String> = runCatching { Json.parse(json ?: "[]").asList().map { it.toString() }.toSet() }.getOrDefault(emptySet())

    fun isUnderstood(b: CodeBlock) = b.id in ids(state.understood)
    fun isIgnored(b: CodeBlock) = b.id in ids(state.ignored)

    fun markUnderstood(b: CodeBlock) {
        state.understood = Json.write((ids(state.understood) + b.id).toList().takeLast(2000))
        restartOpenEditors()
        fire()
    }

    fun markIgnored(b: CodeBlock) {
        state.ignored = Json.write((ids(state.ignored) + b.id).toList().takeLast(2000))
        restartOpenEditors()
        fire()
    }

    fun recordPrediction(right: Boolean) {
        state.predictions = state.predictions + 1
        if (right) state.predictionsRight = state.predictionsRight + 1
        fire()
    }

    override fun dispose() {}

    companion object {
        private const val MAX_BLOCKS = 300
        fun getInstance(project: Project): ProjectContext = project.service()
    }
}

