package dev.kmpcoach.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ide.util.PropertiesComponent
import dev.kmpcoach.core.AntigravityPlans
import dev.kmpcoach.core.AntigravityTranscripts
import dev.kmpcoach.core.ClaudeSessions
import dev.kmpcoach.core.CopilotSessions
import dev.kmpcoach.core.SourceKind
import dev.kmpcoach.core.nfc
import dev.kmpcoach.core.nfcAndNfd
import dev.kmpcoach.core.SessionTurn
import dev.kmpcoach.core.StepParser
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contexto de la conversación del estudiante con otra IA en este proyecto: en qué paso va y qué le pidió la IA.
 * Lee conversaciones de Claude Code, de GitHub Copilot (en VS Code) y de Antigravity (IDE, app y CLI), los planes de
 * Antigravity y un plan pegado a mano. Todo es local: solo lee archivos de tu disco.
 * Solo lee el final de cada sesión (las sesiones pesan megas) y recuerda lo leído mientras el archivo no cambie.
 * Las búsquedas leen disco: llamar a [find] fuera del hilo de la interfaz; el panel usa [peek].
 */
@Service(Service.Level.PROJECT)
class AiSessionContext(private val project: Project) {

    /** Sesión elegida y lo que el tutor recibe de ella. [source]: «Claude Code», «GitHub Copilot», «Antigravity» o «Plan pegado». */
    class Found(val file: File, val excerpt: String, val lastRequest: String?, val automatic: Boolean) {
        val source: String get() = SourceKind.ofPath(file.path).label
    }

    /** Cuántas conversaciones de este proyecto se encontraron en cada IA: el panel lo dice cuando no hay plan. */
    data class Searched(val claude: Int = 0, val copilot: Int = 0, val antigravity: Int = 0)

    private class Cached(val length: Long, val modified: Long, val turns: List<SessionTurn>)

    /** El último mensaje de la IA que trae pasos («## Paso 5.1 …»), para la guía del editor. */
    class StepsSource(val file: File, val text: String, val at: String)

    /** Un plan de Antigravity: su archivo Markdown, su texto y cuándo cambió. */
    class Plan(val file: File, val text: String, val modified: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val planCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val peeked = ConcurrentHashMap<String, Found>()
    private val peekedAt = ConcurrentHashMap<String, Long>()
    private val computing = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Sesión elegida a mano en el panel; null = automática (la más reciente que habla del archivo abierto). */
    @Volatile var chosen: File? = null
        private set

    /** Lo último buscado, para decir dónde se buscó si no se encontró nada. */
    @Volatile var lastSearch = Searched()
        private set

    private val belongs = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    @Volatile private var copilotDirs: Pair<Long, List<File>>? = null

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    fun enabled(): Boolean = CoachSettings.getInstance().state.readAiSession

    /** Carpeta de Claude Code del proyecto. Con tildes en la ruta se prueban las dos formas de escribirlas. */
    private fun dir(): File? = project.basePath?.let { base ->
        nfcAndNfd(base).map { File(System.getProperty("user.home"), ".claude/projects/" + ClaudeSessions.projectDirName(it)) }
            .firstOrNull(File::isDirectory)
    }

    /**
     * Conversaciones recientes del proyecto en todas las IA (Claude Code, GitHub Copilot, Antigravity), de la más
     * reciente a la más antigua. Anota cuántas hubo en cada una.
     */
    fun candidates(): List<File> {
        val limit = System.currentTimeMillis() - RECENT_MS
        val claude = dir()?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && f.lastModified() >= limit }.orEmpty().toList()
        val copilot = copilotDirs().flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.lastModified() >= limit }.orEmpty().toList() }
        val antigravity = AntigravityPlans.BRAIN_DIRS.flatMap { File(System.getProperty("user.home"), it).listFiles()?.toList().orEmpty() }
            .map { File(it, AntigravityTranscripts.FILE) }
            .filter { it.isFile && it.lastModified() >= limit && belongsHere(it) }
        lastSearch = Searched(claude.size, copilot.size, antigravity.size)
        return (claude + copilot + antigravity).sortedByDescending { it.lastModified() }.take(MAX_SESSIONS)
    }

    /**
     * Carpetas `chatSessions` de GitHub Copilot para este proyecto en VS Code (y VS Code Insiders): la de
     * `workspaceStorage/<id>` cuyo `workspace.json` apunta a esta carpeta. Se recalcula como mucho cada minuto.
     */
    private fun copilotDirs(): List<File> {
        copilotDirs?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second }
        val base = project.basePath?.trimEnd('/')?.let(::nfc) ?: return emptyList()
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val userDirs = listOf("Code", "Code - Insiders").map { app ->
            when {
                "mac" in os -> File(home, "Library/Application Support/$app/User")
                "win" in os -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "$app/User")
                else -> File(home, ".config/$app/User")
            }
        }
        val dirs = userDirs.flatMap { File(it, "workspaceStorage").listFiles()?.toList().orEmpty() }.filter { ws ->
            runCatching { CopilotSessions.folderOf(File(ws, "workspace.json").readText()) }.getOrNull() == base
        }.map { File(it, "chatSessions") }.filter { it.isDirectory }
        copilotDirs = System.currentTimeMillis() to dirs
        return dirs
    }

    /** Una conversación de Antigravity es de este proyecto si menciona su carpeta. Se recuerda mientras no cambie. */
    private fun belongsHere(f: File): Boolean {
        belongs[f.path]?.takeIf { it.first == f.lastModified() }?.let { return it.second }
        val yes = runCatching { AntigravityTranscripts.belongsTo(tail(f, TAIL_BYTES), project.basePath.orEmpty()) }.getOrDefault(false)
        belongs[f.path] = f.lastModified() to yes
        return yes
    }

    /** Mensajes de la conversación, sea de la IA que sea. Si el archivo no cambió desde la última lectura, no vuelve a leerlo. */
    fun turns(f: File): List<SessionTurn> {
        val length = f.length()
        val modified = f.lastModified()
        cache[f.path]?.takeIf { it.length == length && it.modified == modified }?.let { return it.turns }
        val turns = runCatching {
            when (SourceKind.ofPath(f.path)) {
                SourceKind.COPILOT -> CopilotSessions.parse(f.readText()) // un JSON entero: no se puede leer solo el final
                SourceKind.ANTIGRAVITY -> AntigravityTranscripts.parse(tail(f, TAIL_BYTES).lineSequence())
                else -> ClaudeSessions.parse(tail(f, TAIL_BYTES).lineSequence())
            }
        }.getOrDefault(emptyList())
        cache[f.path] = Cached(length, modified, turns)
        return turns
    }

    // ---------- plan pegado a mano ----------

    /** El plan que pegaste (de cualquier IA), guardado para este proyecto. */
    fun pastedPlan(): String? = PropertiesComponent.getInstance(project).getValue(PASTED_KEY)?.takeIf { it.isNotBlank() }

    /** Guarda el plan pegado y lo elige como lo que lee el Mentor. */
    fun setPastedPlan(text: String?) {
        val props = PropertiesComponent.getInstance(project)
        if (text.isNullOrBlank()) props.unsetValue(PASTED_KEY) else props.setValue(PASTED_KEY, text)
        choose(if (text.isNullOrBlank()) null else PASTED)
    }

    private fun pasted(): String? = if (chosen?.path == PASTED.path) pastedPlan() else null

    private fun tail(f: File, bytes: Long): String = RandomAccessFile(f, "r").use { raf ->
        val start = (raf.length() - bytes).coerceAtLeast(0)
        raf.seek(start)
        val buf = ByteArray((raf.length() - start).toInt())
        raf.readFully(buf)
        String(buf, Charsets.UTF_8)
    }

    /**
     * Extracto de la sesión que corresponde al archivo: la elegida a mano, o la más reciente cuya IA haya mencionado
     * el archivo; si ninguna lo menciona, la más reciente. Lee disco.
     */
    fun find(fileName: String?): Found? {
        if (!enabled()) return null
        pasted()?.let { return Found(PASTED, AntigravityPlans.excerpt(it).replace("plan de Antigravity", "plan pegado"), AntigravityPlans.title(it) ?: "plan pegado", false) }
        chosenPlan()?.let { p -> return Found(p.file, AntigravityPlans.excerpt(p.text), planLabel(p), false) }
        val claude = pick(fileName)
        // Si el plan más reciente que habla del archivo es de Antigravity, el Mentor lee ese.
        antigravityFirst(fileName, claude)?.let { p ->
            return Found(p.file, AntigravityPlans.excerpt(p.text), planLabel(p), true)
        }
        val (f, automatic) = claude ?: return null
        val t = turns(f)
        return ClaudeSessions.excerpt(t, fileName)?.let { Found(f, it, ClaudeSessions.lastRequest(t), automatic) }
    }

    /**
     * Planes de Antigravity de la última semana: los Markdown del «cerebro» de cada conversación (sus conversaciones
     * se guardan en binario y no se pueden leer). Del más reciente al más antiguo. Lee disco.
     */
    fun antigravityPlans(): List<Plan> {
        val home = System.getProperty("user.home")
        val limit = System.currentTimeMillis() - PLAN_RECENT_MS
        return AntigravityPlans.BRAIN_DIRS.flatMap { File(home, it).listFiles()?.toList().orEmpty() }
            .flatMap { dir -> dir.listFiles { f -> f.isFile && AntigravityPlans.isPlanFile(f.name) && f.lastModified() >= limit }.orEmpty().toList() }
            .mapNotNull { f ->
                val text = planCache[f.path]?.takeIf { it.first == f.lastModified() }?.second
                    ?: runCatching { f.readText() }.getOrNull()?.also { planCache[f.path] = f.lastModified() to it }
                    ?: return@mapNotNull null
                if (AntigravityPlans.isPlan(text)) Plan(f, text, f.lastModified()) else null
            }
            .sortedByDescending { it.modified }
    }

    /**
     * El plan de Antigravity que habla del archivo, si es más nuevo que la última vez que Claude Code lo mencionó. Si
     * elegiste una conversación de Claude a mano, manda la tuya.
     */
    private fun antigravityFirst(fileName: String?, claude: Pair<File, Boolean>?): Plan? {
        if (fileName == null || claude?.second == false) return null
        val plan = antigravityPlans().firstOrNull { AntigravityPlans.mentions(it.text, fileName) } ?: return null
        val claudeAt = claude?.first?.let { ClaudeSessions.lastMention(turns(it), fileName) }
        return plan.takeIf { claudeAt == null || iso(it.modified) > claudeAt }
    }

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    /** El nombre del plan (su título); de qué IA viene lo dice [Found.source], con su ícono. */
    private fun planLabel(p: Plan) = AntigravityPlans.title(p.text) ?: p.file.parentFile.name

    /** El plan de Antigravity que elegiste a mano en el panel, si elegiste uno. */
    private fun chosenPlan(): Plan? = chosen?.takeIf { it.isFile && it.extension == "md" }?.let { f ->
        runCatching { f.readText() }.getOrNull()?.takeIf(AntigravityPlans::isPlan)?.let { Plan(f, it, f.lastModified()) }
    }

    /** La sesión elegida a mano o, si no, la más reciente que habla del archivo. El booleano dice si fue automática. */
    private fun pick(fileName: String?): Pair<File, Boolean>? {
        chosen?.takeIf { it.isFile && it.extension in setOf("jsonl", "json") }?.let { return it to false }
        val sessions = candidates()
        val best = fileName?.let { name ->
            sessions.mapNotNull { f -> ClaudeSessions.lastMention(turns(f), name)?.let { f to it } }.maxByOrNull { it.second }?.first
        } ?: sessions.firstOrNull { turns(it).isNotEmpty() } ?: return null
        return best to true
    }

    /** El último mensaje con pasos de la sesión que corresponde al archivo. Lee disco: fuera del hilo de la interfaz. */
    fun latestSteps(fileName: String?): StepsSource? {
        if (!enabled()) return null
        pasted()?.let { return StepsSource(PASTED, AntigravityPlans.toSteps(it), "pegado#${it.hashCode()}") }
        chosenPlan()?.let { p -> return StepsSource(p.file, AntigravityPlans.toSteps(p.text), iso(p.modified)) }
        val claude = pick(fileName)
        // Un plan de Antigravity se traduce al formato de la guía: cada archivo que cambia es un paso.
        antigravityFirst(fileName, claude)?.let { p -> return StepsSource(p.file, AntigravityPlans.toSteps(p.text), iso(p.modified)) }
        val (f, _) = claude ?: return null
        val turn = turns(f).lastOrNull { it.role == "assistant" && StepParser.hasSteps(it.text) } ?: return null
        return StepsSource(f, turn.text, turn.at)
    }

    /** Para el panel: lo último calculado para ese archivo, y lo recalcula en segundo plano sin bloquear la interfaz. */
    fun peek(fileName: String?): Found? {
        val key = fileName ?: ""
        // Como máximo una lectura cada 10 s: el panel se refresca mientras escribes.
        val now = System.currentTimeMillis()
        val fresh = now - (peekedAt[key] ?: 0L) < PEEK_EVERY_MS
        if (enabled() && !fresh && computing.compareAndSet(false, true)) {
            peekedAt[key] = now
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val found = runCatching { find(fileName) }.getOrNull()
                    val before = peeked[key]
                    if (found == null) peeked.remove(key) else peeked[key] = found
                    if (before?.file != found?.file || before?.lastRequest != found?.lastRequest) listeners.forEach { it() }
                } finally {
                    computing.set(false)
                }
            }
        }
        return peeked[key]
    }

    /** Elige la sesión a mano (null = automática). */
    fun choose(f: File?) {
        chosen = f
        peeked.clear()
        peekedAt.clear()
        listeners.forEach { it() }
    }

    /** Etiqueta de una sesión para elegirla: su último pedido y hace cuánto se usó. */
    fun label(f: File): String {
        val mins = (System.currentTimeMillis() - f.lastModified()) / 60_000
        val ago = when {
            mins < 60 -> "hace $mins min"
            mins < 48 * 60 -> "hace ${mins / 60} h"
            else -> "hace ${mins / 1440} días"
        }
        if (f.path == PASTED.path) return "Plan pegado: " + (pastedPlan()?.let(AntigravityPlans::title) ?: "sin título")
        if (f.extension == "md") return "Plan: " + (runCatching { AntigravityPlans.title(f.readText()) }.getOrNull() ?: f.parentFile.name) + " · " + ago
        return (ClaudeSessions.lastRequest(turns(f)) ?: f.nameWithoutExtension) + " · " + ago
    }

    companion object {
        private const val TAIL_BYTES = 600_000L
        private const val RECENT_MS = 3L * 24 * 3600 * 1000
        /** Un plan dura más que una conversación: se sigue días después de escribirlo. */
        private const val PLAN_RECENT_MS = 7L * 24 * 3600 * 1000
        private const val MAX_SESSIONS = 12
        private const val PEEK_EVERY_MS = 10_000L
        private const val PASTED_KEY = "mentor.pastedPlan"
        /** «Archivo» del plan pegado (no existe en disco: solo marca que se eligió). */
        val PASTED = File(SourceKind.PASTED_PATH)
        fun getInstance(project: Project): AiSessionContext = project.service()
    }
}
