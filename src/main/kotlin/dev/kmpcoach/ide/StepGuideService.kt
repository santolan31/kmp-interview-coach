package dev.kmpcoach.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.ChatMessage
import dev.kmpcoach.core.CommentType
import dev.kmpcoach.core.EditStatus
import dev.kmpcoach.core.GuideStep
import dev.kmpcoach.core.IdeProblems
import dev.kmpcoach.core.StepEdit
import dev.kmpcoach.core.StepMatcher
import dev.kmpcoach.core.StepParser
import dev.kmpcoach.core.StepPrompts
import dev.kmpcoach.core.StepVerdict
import dev.kmpcoach.core.UsageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Guía de pasos: convierte el último mensaje con pasos de tu conversación con Claude Code («## Paso 5.1 …» con sus
 * diffs) en una barra sobre el editor que te dice en qué paso vas, te lleva al archivo y a la línea, pone el esqueleto,
 * comprueba el avance y te deja avanzar al siguiente paso.
 *
 * Rendimiento: la barra se calcula con el permiso de lectura del IDE tomado, así que nunca lee las sesiones ahí; las
 * busca en segundo plano como máximo cada 10 s y la barra usa lo último encontrado.
 */
@Service(Service.Level.PROJECT)
class StepGuideService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    class EditView(val edit: StepEdit, val status: EditStatus, val file: VirtualFile?)

    /** [verdict]: respuesta del tutor a «¿Cumple el paso?», solo si tu código no cambió desde entonces. */
    class StepView(val step: GuideStep, val index: Int, val edits: List<EditView>, val complete: Boolean, val verdict: StepVerdict?)

    /** Marca en el editor sobre una línea (0-based): lo que falta en rojo, o la confirmación del tutor en verde. */
    class Marker(val line: Int, val type: CommentType, val label: String, val text: String, val stale: Boolean)

    class View(val steps: List<GuideStep>, val current: StepView, val next: GuideStep?)

    /** Líneas (0-based) del paso comprobado en un archivo: ahí habla solo el paso, no los comentarios de la revisión. */
    data class Coverage(val stepLabel: String, val lines: Set<Int>)

    private class Source(val at: Long, val value: AiSessionContext.StepsSource?)

    private val sources = ConcurrentHashMap<String, Source>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var parsedKey: String? = null
    @Volatile private var parsed: List<GuideStep> = emptyList()
    /** Cambios donde ya viste el esqueleto puesto: si un TODO desaparece, cuenta como hecho. */
    private val seenPlaced = ConcurrentHashMap.newKeySet<String>()
    /** Pasos sin código (correr la app, mirar el Preview…) que marcaste como hechos. */
    private val manualDone = ConcurrentHashMap.newKeySet<String>()
    /** Respuesta del tutor a «¿Cumple el paso?», con una huella del código: si lo cambias, deja de valer. */
    private val verdicts = ConcurrentHashMap<String, Pair<Int, StepVerdict>>()
    private val verifying = ConcurrentHashMap.newKeySet<String>()
    /** Pasos donde pulsaste «¿Cumple el paso?»: desde entonces lo que falta se marca en rojo en el código. */
    private val checked = ConcurrentHashMap.newKeySet<String>()
    /** Archivos con marcas dibujadas, para quitarlas cuando ya no correspondan. */
    private val markedFiles = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var viewedId: String? = null
    private var refreshJob: Job? = null
    /** Por ruta de archivo; se recalcula en cada [refreshMarkers]. Lo lee el anotador, que corre en otro hilo. */
    private val covered = ConcurrentHashMap<String, Coverage>()

    fun coverage(file: VirtualFile): Coverage? = covered[file.path]

    fun isVerifying(step: GuideStep) = step.id in verifying

    fun start() {
        project.messageBus.connect(this).subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished() = refresh()
        })
        TutorService.getInstance(project).addListener(this, object : TutorService.Listener {
            override fun changed() = refresh()
        })
        AiSessionContext.getInstance(project).addListener(this) {
            sources.clear()
            refresh()
        }
    }

    /** Vuelve a dibujar las barras, agrupando los avisos seguidos en uno. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = cs.launch {
            delay(400)
            if (project.isDisposed) return@launch
            EditorNotifications.getInstance(project).updateAllNotifications()
            withContext(Dispatchers.EDT) { refreshMarkers() }
        }
    }

    /** Vuelve a dibujar las marcas del paso comprobado, y quita las de archivos que ya no las tienen. */
    private fun refreshMarkers() {
        if (project.isDisposed) return
        val tutor = TutorService.getInstance(project)
        val fem = FileEditorManager.getInstance(project)
        val coverageChanged = HashSet<String>()
        val now = fem.openFiles.filter { f ->
            val markers = markersFor(f)
            val c = coverageFor(f, markers)
            val before = if (c == null) covered.remove(f.path) else covered.put(f.path, c)
            if (before != c) coverageChanged += f.path
            markers.isNotEmpty()
        }.map { it.path }.toSet()
        // Archivos cerrados: su cobertura ya no hace falta.
        covered.keys.retainAll(fem.openFiles.map { it.path }.toSet())
        (now + markedFiles + coverageChanged).forEach { p -> vfOf(p)?.takeIf { fem.isFileOpen(it) }?.let(tutor::refreshInlays) }
        // Los comentarios que ahora cubre el paso (o que dejó de cubrir) también cambian su subrayado y el panel.
        coverageChanged.forEach { p -> vfOf(p)?.takeIf { fem.isFileOpen(it) }?.let(tutor::restartHighlighting) }
        if (coverageChanged.isNotEmpty()) tutor.notifyChanged()
        markedFiles.clear()
        markedFiles += now
    }

    /** Qué líneas de este archivo son del paso comprobado: dónde está su código y cada marca de lo que falta. */
    private fun coverageFor(file: VirtualFile, markers: List<Marker>): Coverage? {
        val src = sources[file.name]?.value ?: return null
        val step = steps(src).firstOrNull { it.id == viewedId } ?: return null
        if (step.id !in checked) return null
        val edits = step.edits.filter { it.path == file.path }
        if (edits.isEmpty()) return null
        val lines = linesOf(file.path, file)
        val set = HashSet<Int>()
        edits.forEach { set += StepMatcher.covered(lines, it) }
        markers.forEach { set += it.line }
        return if (set.isEmpty()) null else Coverage(step.label, set)
    }

    /**
     * Marcas del paso actual en este archivo, después de pulsar «¿Cumple el paso?»: cada TODO que falta (comprobación
     * gratis) y lo que el tutor dijo que falta, en rojo; o su confirmación, en verde. Hilo de la interfaz.
     */
    fun markersFor(file: VirtualFile): List<Marker> {
        val src = sources[file.name]?.value ?: return emptyList()
        val steps = steps(src)
        val idx = steps.indexOfFirst { it.id == viewedId }.takeIf { it >= 0 } ?: return emptyList()
        val step = steps[idx]
        if (step.id !in checked || step.edits.none { it.path == file.path }) return emptyList()
        val lines = HashMap<String, List<String>>()
        val sv = stepView(steps, idx, lines)
        val here = sv.edits.filter { it.edit.path == file.path }
        val out = ArrayList<Marker>()
        here.forEach { ev ->
            ev.status.todos.filter { it.missing && it.line != null }.forEach { t ->
                out += Marker(t.line!!, CommentType.ERROR, "Falta · ${t.label}", t.text, stale = false)
            }
        }
        val stored = verdicts[step.id] ?: return out
        val stale = stored.first != fingerprint(step, lines)
        val v = stored.second
        val fallback = here.firstNotNullOfOrNull { it.status.cursorLine } ?: (here.first().edit.line - 1).coerceAtLeast(0)
        if (v.ok) {
            out += Marker(fallback, CommentType.BIEN, "Mentor", "Tu código cumple el ${step.label}.", stale)
        } else {
            v.gaps.filter { it.file == null || it.file == file.name }.forEach { g ->
                val line = g.line?.minus(1) ?: fallback
                // Si la comprobación gratis ya marcó esa línea, no la repite.
                if (out.none { it.line == line }) out += Marker(line, CommentType.ERROR, "Falta (Mentor)", g.text, stale)
            }
        }
        return out
    }

    /** Lo último encontrado para ese archivo; si está viejo, lo busca de nuevo en segundo plano. */
    private fun source(fileName: String): AiSessionContext.StepsSource? {
        val cached = sources[fileName]
        val stale = cached == null || System.currentTimeMillis() - cached.at > 10_000
        if (stale && loading.add(fileName)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val found = runCatching { AiSessionContext.getInstance(project).latestSteps(fileName) }.getOrNull()
                    val old = sources.put(fileName, Source(System.currentTimeMillis(), found))
                    if (old?.value?.at != found?.at || old?.value?.file != found?.file) refresh()
                } finally {
                    loading.remove(fileName)
                }
            }
        }
        return cached?.value
    }

    private fun steps(src: AiSessionContext.StepsSource): List<GuideStep> {
        val key = src.file.path + "#" + src.at
        if (key != parsedKey) {
            parsed = StepParser.parse(src.text, project.basePath)
            parsedKey = key
            viewedId = null
        }
        return parsed
    }

    private fun vfOf(path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)

    /** Líneas actuales del archivo: del editor si está abierto (con cambios sin guardar), si no, del disco. */
    private fun linesOf(path: String, vf: VirtualFile?): List<String> {
        vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }?.let { return it.text.lines() }
        return runCatching { File(path).takeIf { it.isFile }?.readLines() }.getOrNull().orEmpty()
    }

    private fun hasErrors(vf: VirtualFile?): Boolean {
        if (vf == null || !FileEditorManager.getInstance(project).isFileOpen(vf)) return false
        return IdeProblems.relevant(TutorService.getInstance(project).ideProblems(vf), false).isNotEmpty()
    }

    private fun stepView(steps: List<GuideStep>, index: Int, lines: MutableMap<String, List<String>>): StepView {
        val step = steps[index]
        val edits = step.edits.map { e ->
            val vf = vfOf(e.path)
            val l = lines.getOrPut(e.path) { linesOf(e.path, vf) }
            val st = StepMatcher.status(l, e, hasErrors(vf), e.key in seenPlaced)
            if (st.skeletonPlaced) seenPlaced += e.key
            EditView(e, st, vf)
        }
        // La respuesta del tutor vale mientras no cambies el código de los archivos del paso.
        val verdict = verdicts[step.id]?.takeIf { it.first == fingerprint(step, lines) }?.second
        val complete = when {
            step.id in manualDone -> true
            verdict?.ok == true -> true
            edits.isEmpty() -> false
            else -> edits.all { it.status.complete }
        }
        return StepView(step, index, edits, complete, verdict)
    }

    private fun fingerprint(step: GuideStep, lines: MutableMap<String, List<String>>): Int =
        step.edits.map { it.path }.distinct().joinToString("\u0000") { p -> lines.getOrPut(p) { linesOf(p, vfOf(p)) }.joinToString("\n") }.hashCode()

    /**
     * Lo que muestra la barra en este archivo, o null si no hay guía. No lee sesiones (eso va en segundo plano):
     * se puede llamar desde la barra del editor.
     */
    fun view(file: VirtualFile): View? {
        val src = source(file.name) ?: return null
        val steps = steps(src)
        if (steps.isEmpty()) return null
        val lines = HashMap<String, List<String>>()
        val viewed = steps.indexOfFirst { it.id == viewedId }
        val index = if (viewed >= 0) viewed else {
            // Sin elegir: el primer paso sin terminar. Queda fijo hasta que pulses «Avanzar», para que veas «✓ completo».
            (steps.indices.firstOrNull { !stepView(steps, it, lines).complete } ?: steps.lastIndex).also { viewedId = steps[it].id }
        }
        val current = stepView(steps, index, lines)
        return View(steps, current, steps.getOrNull(index + 1))
    }

    // ---------- acciones (hilo de la interfaz) ----------

    /** Abre el archivo del cambio y deja el cursor en lo primero que falta (o donde va el código). */
    fun goTo(ev: EditView) {
        val vf = ev.file ?: return notify("No encontré ${ev.edit.fileName} en el proyecto.")
        goToLine(vf, ev.status.cursorLine ?: (ev.edit.line - 1).coerceAtLeast(0))
    }

    /** Abre el archivo y deja el cursor al inicio del texto de la línea (0-based). */
    fun goToLine(vf: VirtualFile, line: Int) {
        OpenFileDescriptor(project, vf, line, 0).navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor?.takeIf { it.virtualFile == vf } ?: return
        val doc = editor.document
        if (line < doc.lineCount) {
            val start = doc.getLineStartOffset(line)
            val text = doc.charsSequence.subSequence(start, doc.getLineEndOffset(line))
            editor.caretModel.moveToOffset(start + text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0))
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    /** Pone el esqueleto del cambio (solo los TODO, nunca una solución). Se deshace con Cmd+Z. */
    fun placeSkeleton(ev: EditView) {
        val vf = ev.file ?: return
        val doc = TutorService.documentOf(vf) ?: return
        val r = StepMatcher.placeSkeleton(doc.text.lines(), ev.edit)
            ?: return notify("El código ya no coincide con el que citó la IA, así que no sé dónde poner el esqueleto. Cópialo desde el chat.")
        WriteCommandAction.runWriteCommandAction(project, "Poner el esqueleto del paso", null, {
            val start = doc.getLineStartOffset(r.from)
            val end = doc.getLineEndOffset(r.to)
            doc.replaceString(start, end, r.lines.joinToString("\n"))
        })
        seenPlaced += ev.edit.key
        val lines = doc.text.lines()
        goTo(EditView(ev.edit, StepMatcher.status(lines, ev.edit, false, true), vf))
        refresh()
    }

    /** Avanza al siguiente paso: abre su archivo con el tutor encendido y deja el cursor donde va el código. */
    fun advance(view: View) {
        val next = view.next ?: return
        viewedId = next.id
        refreshMarkers()
        val lines = HashMap<String, List<String>>()
        val nextView = stepView(view.steps, view.current.index + 1, lines)
        val target = nextView.edits.firstOrNull { !it.status.complete } ?: nextView.edits.firstOrNull()
        target?.file?.let { vf ->
            TutorService.getInstance(project).setTutorOn(vf, true, withFirstHelp = false)
            goTo(target)
        }
        refresh()
    }

    fun markDone(step: GuideStep) {
        manualDone += step.id
        refresh()
    }

    /** «Explícame este paso»: el tutor explica qué lograr en cada TODO y por qué, sin darte el código. Responde en el panel. */
    fun explain(step: GuideStep, file: VirtualFile) {
        val todos = step.edits.flatMap { it.todos }.joinToString("; ") { "${it.first}: ${it.second}" }
        TutorService.getInstance(project).ask(file,
            "Explícame el ${step.label}: qué tengo que lograr" + (if (todos.isNotEmpty()) " en cada TODO ($todos)" else "") +
                " y por qué, punto por punto, sin darme el código.")
    }

    /** «¿Cumple el paso?»: el tutor compara tu código con el paso. Una llamada corta; la respuesta aparece en la barra. */
    fun verify(step: GuideStep) {
        if (!verifying.add(step.id)) return
        // Desde ahora lo que falta se marca en rojo, también lo que encuentra la comprobación gratis.
        checked += step.id
        refresh()
        val lines = HashMap<String, List<String>>()
        val files = step.edits.map { it.path }.distinct().map { p -> p.substringAfterLast('/') to lines.getOrPut(p) { linesOf(p, vfOf(p)) }.joinToString("\n") }
        val print = fingerprint(step, lines)
        val settings = CoachSettings.getInstance()
        val options = settings.promptOptions()
        cs.launch {
            var cfg: dev.kmpcoach.core.AiConfig? = null
            try {
                val (client, c) = withContext(Dispatchers.IO) { settings.createClient() }
                cfg = c
                val rules = withContext(Dispatchers.IO) { runCatching { ProjectRulesReader.getInstance(project).read() }.getOrNull() }
                val system = StepPrompts.verifySystem(options)
                val user = StepPrompts.verifyUser(step, files, rules)
                val reply = client.complete(system, listOf(ChatMessage("user", user)), 300).await()
                UsageTracker.getInstance().record(UsageKind.STEP, step.edits.firstOrNull()?.fileName, reply.usage, system + user, reply.text, reply.limits)
                verdicts[step.id] = print to StepPrompts.parseVerdict(reply.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = AiClients.friendly(e, cfg)
                withContext(Dispatchers.EDT) { notify(msg) }
            } finally {
                verifying.remove(step.id)
                refresh()
            }
        }
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(TutorService.NOTIFICATION_GROUP)
            .createNotification("Guía de pasos", message, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): StepGuideService = project.service()
    }
}
