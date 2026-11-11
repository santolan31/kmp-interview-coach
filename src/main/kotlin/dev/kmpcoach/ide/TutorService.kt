package dev.kmpcoach.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.ide.scratch.ScratchUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.Analyzer
import dev.kmpcoach.core.ChatMessage
import dev.kmpcoach.core.CodeGuard
import dev.kmpcoach.core.CommentType
import dev.kmpcoach.core.HelpPolicy
import dev.kmpcoach.core.Hint
import dev.kmpcoach.core.Mastery
import dev.kmpcoach.core.HintLevel
import dev.kmpcoach.core.HintParser
import dev.kmpcoach.core.HintPrompts
import dev.kmpcoach.core.IdeProblem
import dev.kmpcoach.core.IdeProblems
import dev.kmpcoach.core.Prompts
import dev.kmpcoach.core.ReviewParser
import dev.kmpcoach.core.StuckTracker
import dev.kmpcoach.core.TutorReview
import dev.kmpcoach.core.UsageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Tutor de IA del proyecto: pide revisiones y pistas al proveedor configurado, las ancla a líneas del editor
 * (comentarios bajo la línea y avisos en el código), mantiene la conversación por archivo y acompaña los archivos donde encendiste el tutor.
 */
@Service(Service.Level.PROJECT)
class TutorService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    class LiveComment(val marker: RangeMarker, val type: CommentType, val message: String) {
        val id: String = java.util.UUID.randomUUID().toString().take(8)
        /** Ya lo respondiste o lo descartaste: deja de dibujarse en el editor. */
        @Volatile var resolved = false
        @Volatile var answered = false
    }

    /** Una entrada de la conversación del panel, en orden de llegada: revisión, pista, solución explicada o mensaje del chat. */
    sealed class Entry(val at: Long) {
        class Review(val review: FileReview) : Entry(review.at)
        class HintGiven(val hint: Hint, at: Long) : Entry(at)
        class GiveUp(val text: String, at: Long) : Entry(at)
        class Chat(val message: ChatMessage, at: Long) : Entry(at)
    }

    private class Turn(val message: ChatMessage, val at: Long)
    private class Pending(val marker: RangeMarker, val text: String)

    class FileReview(
        val review: TutorReview,
        val comments: List<LiveComment>,
        val text: String,
        val at: Long,
        val modelLabel: String,
        val auto: Boolean,
        val call: UsageTracker.LastCall?,
    ) {
        @Volatile var stale = false
    }

    /** Escalera de pistas de un archivo: las pistas que ya diste, la explicación de «Me rindo» y la última pista anclada al editor. */
    class HintSession {
        val hints = CopyOnWriteArrayList<Hint>()
        /** Hora de cada pista, en el mismo orden que [hints]: ordena la conversación del panel. */
        val hintTimes = CopyOnWriteArrayList<Long>()
        @Volatile var giveUp: String? = null
        @Volatile var giveUpAt = 0L
        @Volatile var error: String? = null
        @Volatile var marker: RangeMarker? = null
        @Volatile var showInEditor = true
        /** Errores del IDE cuando se pidió la última pista: si después llegan a cero, el problema se resolvió y la escalera empieza de nuevo. */
        @Volatile var problemsAtLastHint = 0
        @Volatile var lastAuto = false
        /** Código y hora de la última pista: si pides otra sin cambiar nada, primero se te pide un intento. */
        @Volatile var textAtLastHint: String? = null
        @Volatile var lastHintAt = 0L
        /** Aviso para el panel y la pista en gris, por ejemplo «intenta algo antes de otra pista». */
        @Volatile var notice: String? = null
    }

    interface Listener {
        fun changed() {}
        fun prefill(text: String) {}
        /** El estudiante quiere responder a este comentario de la revisión. */
        fun reply(file: VirtualFile, comment: LiveComment) {}
    }

    private val reviews = ConcurrentHashMap<String, FileReview>()
    private val inlays = HashMap<String, MutableList<Inlay<*>>>()          // solo EDT
    private val generation = ConcurrentHashMap<String, Long>()
    private val seq = AtomicLong()
    private val reviewJobs = ConcurrentHashMap<String, Job>()
    private val errors = ConcurrentHashMap<String, String>()
    private val chats = ConcurrentHashMap<String, MutableList<Turn>>()
    /** Archivos donde encendiste el tutor. No se guarda: al abrir el IDE está apagado en todos. */
    private val tutorOn = ConcurrentHashMap.newKeySet<String>()
    /** Archivos donde apagaste el tutor con comentarios a la vista: se conservan en el panel, pero no se dibujan en el editor. */
    private val hiddenInEditor = ConcurrentHashMap.newKeySet<String>()
    /** Aviso «pensando…» en la línea mientras llega la respuesta del modelo. */
    private val pending = ConcurrentHashMap<String, Pending>()
    private val chatBusy = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val started = AtomicBoolean(false)
    private val liveJobs = ConcurrentHashMap<String, Job>()
    private val reviewNames = ConcurrentHashMap<String, String>()
    private val hintSessions = ConcurrentHashMap<String, HintSession>()
    private val hintJobs = ConcurrentHashMap<String, Job>()
    private val giveUpJobs = ConcurrentHashMap<String, Job>()
    private val stuck = ConcurrentHashMap<String, StuckTracker>()
    private val waitingStuck = ConcurrentHashMap.newKeySet<String>()

    // ---------- consultas para la UI ----------
    fun reviewFor(file: VirtualFile?): FileReview? = file?.let { reviews[it.url] }
    fun isBusy(file: VirtualFile?) = file != null && reviewJobs.containsKey(file.url)
    fun errorFor(file: VirtualFile?) = file?.let { errors[it.url] }
    fun chatFor(file: VirtualFile?): List<ChatMessage> = chats[key(file)]?.map { it.message } ?: emptyList()
    fun isTutorOn(file: VirtualFile?) = file != null && file.url in tutorOn

    /** Comentarios de la revisión que deben verse en el editor: ni resueltos ni ocultos por haber apagado el tutor. */
    fun commentsInEditor(file: VirtualFile): List<LiveComment> =
        if (file.url in hiddenInEditor) emptyList() else reviews[file.url]?.comments?.filter { !it.resolved }.orEmpty()

    fun findComment(file: VirtualFile, id: String): LiveComment? = reviews[file.url]?.comments?.firstOrNull { it.id == id }

    /** Todo lo que el tutor y tú se han dicho sobre este archivo, del más antiguo al más reciente. Llamar desde el hilo de UI. */
    fun timeline(file: VirtualFile?): List<Entry> {
        val out = ArrayList<Entry>()
        if (file != null) {
            reviews[file.url]?.let { out += Entry.Review(it) }
            hintSessions[file.url]?.let { s ->
                val times = s.hintTimes.toList()
                s.hints.toList().forEachIndexed { i, h -> out += Entry.HintGiven(h, times.getOrElse(i) { 0L }) }
                s.giveUp?.let { out += Entry.GiveUp(it, s.giveUpAt) }
            }
        }
        chats[key(file)]?.forEach { out += Entry.Chat(it.message, it.at) }
        return out.sortedBy { it.at }
    }
    fun isChatBusy(file: VirtualFile?) = key(file) in chatBusy
    /** Nombres de los archivos que el tutor está revisando ahora mismo (puede ser otro distinto del que tienes seleccionado). */
    fun filesUnderReview(): List<String> = reviewJobs.keys.mapNotNull { reviewNames[it] }
    /** Hay una acción en vivo programada para este archivo, esperando la pausa, el intervalo mínimo o el tiempo de atasco. */
    fun isLiveScheduled(file: VirtualFile?) = file != null && liveJobs[file.url]?.isActive == true

    fun hintSession(file: VirtualFile?): HintSession? = file?.let { hintSessions[it.url] }
    fun isHintBusy(file: VirtualFile?) = file != null && hintJobs.containsKey(file.url)
    fun isGiveUpBusy(file: VirtualFile?) = file != null && giveUpJobs.containsKey(file.url)
    /** En vivo: hay errores y el tutor espera a que lleves un rato sin avanzar antes de darte una pista. */
    fun isWaitingStuck(file: VirtualFile?) = file != null && file.url in waitingStuck

    /** Siguiente escalón de la escalera, o null si ya diste los cuatro (solo queda «Me rindo»). */
    fun nextHintLevel(file: VirtualFile?): HintLevel? {
        val s = hintSession(file) ?: return HintLevel.QUESTION
        val last = s.hints.lastOrNull() ?: return HintLevel.QUESTION
        return last.level.next
    }

    /** La pista en gris está visible en este archivo. */
    fun isGhostVisible(file: VirtualFile): Boolean {
        if (!CoachSettings.getInstance().state.ghostHints) return false
        val s = hintSessions[file.url] ?: return false
        return s.showInEditor && s.hints.isNotEmpty() && s.marker?.isValid == true
    }

    /** Tab pide la siguiente pista solo si la pista en gris está en la línea del cursor y el cursor está al final del código de esa línea. */
    fun ghostAcceptsTab(editor: com.intellij.openapi.editor.Editor, file: VirtualFile): Boolean {
        if (!isGhostVisible(file) || nextHintLevel(file) == null || isHintBusy(file)) return false
        val marker = hintSessions[file.url]?.marker ?: return false
        val doc = editor.document
        val caret = editor.caretModel.offset
        val line = doc.getLineNumber(caret)
        if (line != doc.getLineNumber(marker.startOffset)) return false
        val after = doc.charsSequence.subSequence(caret, doc.getLineEndOffset(line))
        return after.isBlank()
    }

    fun addListener(parent: Disposable, l: Listener) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun key(file: VirtualFile?) = file?.url ?: "_sin_archivo"
    private fun fire() = listeners.forEach { it.changed() }

    // ---------- arranque ----------
    fun start() {
        if (!started.compareAndSet(false, true)) return
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = onDocumentChanged(event.document)
        }, this)
        // Clic sobre un comentario del tutor en el editor: abre la respuesta en el panel.
        EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                if (event.editor.project != project) return
                val comment = (event.inlay?.renderer as? TutorInlayRenderer)?.comment ?: return
                val file = FileDocumentManager.getInstance().getFile(event.editor.document) ?: return
                event.consume()
                replyTo(file, comment)
            }
        }, this)
    }

    // ---------- interruptor por archivo ----------
    /**
     * Enciende o apaga el tutor en un archivo. Encendido: te da una primera ayuda ahora (una pista si hay errores, una
     * revisión si no la hay) y después solo una pista cuando te atascas. Apagado: no hace nada por su cuenta y quita
     * del editor lo que había; la conversación se conserva en el panel. Llamar desde el hilo de UI.
     */
    fun setTutorOn(file: VirtualFile, on: Boolean) {
        val url = file.url
        if (on) {
            if (!tutorOn.add(url)) return
            hiddenInEditor.remove(url)
            hintSessions[url]?.let { if (it.hints.isNotEmpty()) it.showInEditor = true }
            refreshInlays(file)
            restartHighlighting(file)
            firstHelp(file)
        } else {
            if (!tutorOn.remove(url)) return
            liveJobs.remove(url)?.cancel()
            waitingStuck.remove(url)
            stuck.remove(url)
            hiddenInEditor += url
            hintSessions[url]?.showInEditor = false
            refreshInlays(file)
            restartHighlighting(file)
        }
        fire()
    }

    private fun firstHelp(file: VirtualFile) {
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return
        if (doc.text.isBlank()) return
        val url = file.url
        val count = IdeProblems.relevant(ideProblems(file), ScratchUtil.isScratch(file)).size + if (IdeProblems.hasTodo(doc.text)) 1 else 0
        if (count > 0) {
            // Esta pista ya cuenta como la ayuda de este atasco: la automática espera a que cambie algo.
            val stuckMs = CoachSettings.getInstance().state.stuckAfterSec.coerceAtLeast(15) * 1000L
            stuck[url] = StuckTracker(stuckMs).also { it.update(count, System.currentTimeMillis()); it.markSpoken() }
            if (hintSessions[url]?.hints.isNullOrEmpty() && !isHintBusy(file)) requestHint(file)
        } else if (reviews[url] == null && !isBusy(file)) {
            requestReview(file)
        }
    }

    private fun setPending(file: VirtualFile, document: Document, line: Int, text: String) {
        pending.remove(file.url)?.marker?.dispose()
        if (document.lineCount == 0) return
        val l = (line - 1).coerceIn(0, document.lineCount - 1)
        pending[file.url] = Pending(document.createRangeMarker(document.getLineStartOffset(l), document.getLineEndOffset(l)), text)
        refreshInlays(file)
    }

    private fun clearPending(file: VirtualFile) {
        pending.remove(file.url)?.marker?.dispose()
        refreshInlays(file)
    }

    // ---------- errores del IDE ----------
    /**
     * Errores que el IDE marca ahora en el archivo (compilador y sintaxis), con su línea. Los avisos del Coach no aparecen
     * porque se registran como advertencias, no como errores. Llamar desde el hilo de UI.
     */
    fun ideProblems(file: VirtualFile): List<IdeProblem> {
        val doc = FileDocumentManager.getInstance().getCachedDocument(file) ?: return emptyList()
        val out = LinkedHashSet<IdeProblem>()
        runCatching {
            ReadAction.run<RuntimeException> {
                DaemonCodeAnalyzerEx.processHighlights(doc, project, HighlightSeverity.ERROR, 0, doc.textLength) { info ->
                    val msg = info.description
                    if (!msg.isNullOrBlank() && info.startOffset <= doc.textLength) {
                        out += IdeProblem(doc.getLineNumber(info.startOffset) + 1, msg)
                    }
                    out.size < 20
                }
            }
        }
        return out.toList()
    }

    private fun caretLine(file: VirtualFile): Int? =
        FileEditorManager.getInstance(project).selectedTextEditor?.takeIf { it.virtualFile == file }?.caretModel?.logicalPosition?.line?.plus(1)

    private fun relativePath(file: VirtualFile): String =
        if (ScratchUtil.isScratch(file)) "${file.name} (borrador del IDE)"
        else project.basePath?.let { file.path.removePrefix(it).trimStart('/') } ?: file.path

    // ---------- revisión ----------
    /** Pide una revisión del archivo. Llamar desde el hilo de UI. */
    fun requestReview(file: VirtualFile, auto: Boolean = false) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val text = document.text
        if (text.isBlank()) return
        val url = file.url
        val gen = seq.incrementAndGet()
        generation[url] = gen
        reviewJobs.remove(url)?.cancel()
        errors.remove(url)
        hiddenInEditor.remove(url)
        setPending(file, document, caretLine(file) ?: 1, "revisando el archivo…")
        val settings = CoachSettings.getInstance()
        val options = settings.promptOptions()
        val challenge = ChallengeRepository.getInstance(project).challengeFor(text)
        val job = cs.launch {
            var cfg: dev.kmpcoach.core.AiConfig? = null
            try {
                val (client, c) = withContext(Dispatchers.IO) { settings.createClient() }
                cfg = c
                val analysis = Analyzer.analyze(text, challenge, lexical = false)
                val system = Prompts.reviewSystem(options)
                val user = Prompts.reviewUser(challenge, text, analysis, file.name)
                val reply = client.complete(system, listOf(ChatMessage("user", user)), 1800).await()
                val usage = UsageTracker.getInstance()
                usage.record(if (auto) UsageKind.LIVE else UsageKind.REVIEW, file.name, reply.usage, system + user, reply.text, reply.limits)
                val call = usage.lastCall
                val review = ReviewParser.parse(reply.text, text.count { it == '\n' } + 1, options.maxComments)
                withContext(Dispatchers.EDT) {
                    if (generation[url] == gen) install(file, document, review, text, settings.modelLabel(), auto, call)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = AiClients.friendly(e, cfg)
                if (generation[url] == gen) errors[url] = msg
                if (!auto) withContext(Dispatchers.EDT) { notifyError(msg) }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (generation[url] == gen) {
                        reviewJobs.remove(url)
                        clearPending(file)
                    }
                    fire()
                }
            }
        }
        reviewNames[url] = file.name
        reviewJobs[url] = job
        fire()
    }

    fun cancelReview(file: VirtualFile) {
        generation[file.url] = seq.incrementAndGet()
        reviewJobs.remove(file.url)?.cancel()
        clearPending(file)
        fire()
    }

    private fun install(
        file: VirtualFile, document: Document, review: TutorReview, text: String, label: String,
        auto: Boolean, call: UsageTracker.LastCall?,
    ) {
        val url = file.url
        reviews.remove(url)?.comments?.forEach { it.marker.dispose() }
        val last = (document.lineCount - 1).coerceAtLeast(0)
        val comments = review.comments.map { c ->
            val line = (c.line - 1).coerceIn(0, last)
            LiveComment(document.createRangeMarker(document.getLineStartOffset(line), document.getLineEndOffset(line)), c.type, c.message)
        }
        val fr = FileReview(review, comments, text, System.currentTimeMillis(), label, auto, call)
        fr.stale = document.text != text
        reviews[url] = fr
        refreshInlays(file)
        restartHighlighting(file)
    }

    fun clearReview(file: VirtualFile) {
        val url = file.url
        reviews.remove(url)?.comments?.forEach { it.marker.dispose() }
        refreshInlays(file)
        restartHighlighting(file)
        fire()
    }

    /** Vuelve a dibujar bajo cada línea los comentarios del tutor y la última pista, en todos los editores abiertos del archivo. */
    fun refreshInlays(file: VirtualFile) {
        val url = file.url
        inlays.remove(url)?.forEach { Disposer.dispose(it) }
        val doc = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
        val fr = reviews[url]
        val session = hintSessions[url]?.takeIf { it.showInEditor }
        val hint = session?.hints?.lastOrNull()
        val hintMarker = session?.marker?.takeIf { it.isValid }
        val wait = pending[url]?.takeIf { it.marker.isValid }
        val comments = commentsInEditor(file)
        if (comments.isEmpty() && (hint == null || hintMarker == null) && wait == null) return
        val created = ArrayList<Inlay<*>>()
        for (editor in EditorFactory.getInstance().getEditors(doc, project)) {
            comments.forEach { c ->
                if (!c.marker.isValid) return@forEach
                val line = doc.getLineNumber(c.marker.startOffset)
                val footer = if (c.type == CommentType.BIEN) null else "↩ clic para responder"
                val renderer = TutorInlayRenderer(editor, line, c.type, c.message, comment = c, footer = footer) { fr?.stale == true }
                editor.inlayModel.addBlockElement(doc.getLineEndOffset(line), true, false, 0, renderer)?.let(created::add)
            }
            if (wait != null) {
                // Desde el primer instante se ve que el tutor está trabajando, aunque la respuesta tarde.
                val line = doc.getLineNumber(wait.marker.startOffset)
                editor.inlayModel.addAfterLineEndElement(doc.getLineEndOffset(line), true, GhostHintRenderer(wait.text, ""))?.let(created::add)
            } else if (hint != null && hintMarker != null) {
                val line = doc.getLineNumber(hintMarker.startOffset)
                val label = "Pista ${hint.level.number}/${HintLevel.entries.size} · ${hint.level.label}"
                val text = session.notice ?: hint.text
                val ghostOn = CoachSettings.getInstance().state.ghostHints
                if (ghostOn) {
                    // Como la sugerencia de un autocompletado, pero con una pregunta en vez de código.
                    val keys = if (nextHintLevel(file) != null) "⇥ otra pista · esc" else "esc ocultar"
                    editor.inlayModel.addAfterLineEndElement(doc.getLineEndOffset(line), true, GhostHintRenderer(text, keys))?.let(created::add)
                }
                // Si no cabe en una línea, el texto completo va debajo.
                if (!ghostOn || text.length > GhostHintRenderer.MAX) {
                    val renderer = TutorInlayRenderer(editor, line, CommentType.PREGUNTA, text, label) { false }
                    editor.inlayModel.addBlockElement(doc.getLineEndOffset(line), true, false, 1, renderer)?.let(created::add)
                }
            }
        }
        inlays[url] = created
    }

    private fun restartHighlighting(file: VirtualFile) {
        PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it) }
    }

    // ---------- pistas ----------
    /**
     * Pide el siguiente escalón de la escalera de pistas para el archivo. Si el modelo devuelve código del ejercicio,
     * se le pide una vez que lo reescriba; si insiste, ese fragmento se oculta. Llamar desde el hilo de UI.
     */
    fun requestHint(file: VirtualFile, auto: Boolean = false, force: Boolean = false) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val text = document.text
        val url = file.url
        if (text.isBlank() || hintJobs.containsKey(url)) return
        val problems = ideProblems(file)
        val session = hintSessions.getOrPut(url) { HintSession() }
        if (session.hints.isNotEmpty() && session.problemsAtLastHint > 0 && problems.isEmpty()) resolveSession(session)
        val previous = session.hints.toList()
        val level = previous.lastOrNull()?.let { it.level.next ?: return } ?: HintLevel.QUESTION
        // Abuso de ayuda: pedir pistas seguidas sin intentar nada. Primero se pide un intento.
        if (!auto && !force && HelpPolicy.shouldNudge(session.textAtLastHint, text, session.lastHintAt, System.currentTimeMillis())) {
            session.notice = "Intenta algo con la pista anterior, aunque esté mal: equivocarse también enseña."
            session.showInEditor = true
            refreshInlays(file)
            fire()
            return
        }
        session.notice = null
        session.error = null
        session.showInEditor = true
        hiddenInEditor.remove(url)
        val focus = caretLine(file)
        val lineCount = document.lineCount
        val settings = CoachSettings.getInstance()
        val options = settings.promptOptions()
        val goal = ProjectContext.getInstance(project).goal()
        val mastered = LearnerProfile.getInstance().mastered()
        val job = cs.launch {
            var cfg: dev.kmpcoach.core.AiConfig? = null
            try {
                val (client, c) = withContext(Dispatchers.IO) { settings.createClient() }
                cfg = c
                val usage = UsageTracker.getInstance()
                val system = HintPrompts.hintSystem(options, level)
                val user = HintPrompts.hintUser(text, file.name, problems, previous, focus, level, goal, mastered)
                val fallbackLine = focus ?: problems.firstOrNull()?.line ?: 1
                var reply = client.complete(system, listOf(ChatMessage("user", user)), 500).await()
                usage.record(UsageKind.HINT, file.name, reply.usage, system + user, reply.text, reply.limits)
                var hint = HintParser.parse(reply.text, lineCount, level, fallbackLine)
                if (CodeGuard.leaks(hint.text, text, level)) {
                    val retry = listOf(ChatMessage("user", user), ChatMessage("assistant", reply.text), ChatMessage("user", HintPrompts.LEAK_RETRY))
                    reply = client.complete(system, retry, 500).await()
                    usage.record(UsageKind.HINT, file.name, reply.usage, system + user + reply.text + HintPrompts.LEAK_RETRY, reply.text, reply.limits)
                    hint = HintParser.parse(reply.text, lineCount, level, fallbackLine)
                    if (CodeGuard.leaks(hint.text, text, level)) hint = hint.copy(text = CodeGuard.redact(hint.text, text, level), redacted = true)
                }
                withContext(Dispatchers.EDT) {
                    session.hints += hint
                    session.hintTimes += System.currentTimeMillis()
                    session.problemsAtLastHint = problems.size
                    session.lastAuto = auto
                    session.textAtLastHint = text
                    session.lastHintAt = System.currentTimeMillis()
                    // Si necesitaste llegar a la herramienta, es un concepto que conviene repasar en unos días.
                    if (level >= HintLevel.TOOL) hint.concept?.let { LearnerProfile.getInstance().addReview(it) }
                    session.marker?.dispose()
                    val line = (hint.line - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
                    session.marker = document.createRangeMarker(document.getLineStartOffset(line), document.getLineEndOffset(line))
                    refreshInlays(file)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = AiClients.friendly(e, cfg)
                session.error = msg
                if (!auto) withContext(Dispatchers.EDT) { notifyError(msg) }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    hintJobs.remove(url)
                    clearPending(file)
                    fire()
                }
            }
        }
        hintJobs[url] = job
        setPending(file, document, focus ?: problems.firstOrNull()?.line ?: 1, "pensando la pista…")
        fire()
    }

    private fun resetLadder(session: HintSession) {
        session.hints.clear()
        session.hintTimes.clear()
        session.giveUp = null
        session.error = null
        session.notice = null
        session.textAtLastHint = null
        session.problemsAtLastHint = 0
        session.marker?.dispose()
        session.marker = null
    }

    /**
     * Resolviste el problema de la escalera (los errores llegaron a cero): cuenta para el dominio de sus conceptos,
     * así la ayuda se retira en lo que ya sabes. Si te rendiste, no cuenta como resuelto con poca ayuda.
     */
    private fun resolveSession(session: HintSession) {
        val hints = session.hints.toList()
        val used = if (session.giveUp != null) HintLevel.entries.size + 1 else hints.size
        hints.mapNotNull { it.concept }.distinctBy { Mastery.key(it) }.forEach { LearnerProfile.getInstance().recordSolved(it, used) }
        resetLadder(session)
    }

    /** «Nuevo problema»: empieza la escalera desde la primera pista. */
    fun resetHints(file: VirtualFile) {
        hintJobs.remove(file.url)?.cancel()
        giveUpJobs.remove(file.url)?.cancel()
        hintSessions.remove(file.url)?.let(::resetLadder)
        refreshInlays(file)
        fire()
    }

    /** Quita la pista del editor sin borrarla del panel. */
    fun hideHintInEditor(file: VirtualFile) {
        hintSessions[file.url]?.showInEditor = false
        refreshInlays(file)
        fire()
    }

    /** «Me rindo», opción A: el mismo modelo explica la solución línea por línea. */
    fun giveUp(file: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val text = document.text
        val url = file.url
        if (text.isBlank() || giveUpJobs.containsKey(url)) return
        val session = hintSessions.getOrPut(url) { HintSession() }
        session.error = null
        val problems = ideProblems(file)
        val hints = session.hints.toList()
        val focus = caretLine(file)
        val settings = CoachSettings.getInstance()
        val options = settings.promptOptions()
        val job = cs.launch {
            var cfg: dev.kmpcoach.core.AiConfig? = null
            try {
                val (client, c) = withContext(Dispatchers.IO) { settings.createClient() }
                cfg = c
                val system = HintPrompts.giveUpSystem(options)
                val user = HintPrompts.giveUpUser(text, file.name, problems, hints, focus)
                val reply = client.complete(system, listOf(ChatMessage("user", user)), 1200).await()
                UsageTracker.getInstance().record(UsageKind.GIVE_UP, file.name, reply.usage, system + user, reply.text, reply.limits)
                session.giveUp = reply.text.trim()
                session.giveUpAt = System.currentTimeMillis()
                // Lo que te hizo rendirte es lo primero que conviene repasar.
                hints.mapNotNull { it.concept }.forEach { LearnerProfile.getInstance().addReview(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = AiClients.friendly(e, cfg)
                session.error = msg
                withContext(Dispatchers.EDT) { notifyError(msg) }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    giveUpJobs.remove(url)
                    fire()
                }
            }
        }
        giveUpJobs[url] = job
        showToolWindow()
        fire()
    }

    /** «Me rindo», opción B: copia un mensaje listo para pegar en el chat de Claude, con archivo, errores, pistas y código. */
    fun copyHandoff(file: VirtualFile) {
        val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: return
        val hints = hintSessions[file.url]?.hints?.toList().orEmpty()
        val message = HintPrompts.handoff(relativePath(file), text, ideProblems(file), hints, caretLine(file))
        CopyPasteManager.getInstance().setContents(StringSelection(message))
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Copiado para el chat", "Pégalo en tu chat con Claude: lleva el archivo, los errores, las pistas que recibiste y el código.", NotificationType.INFORMATION)
            .notify(project)
    }

    // ---------- explícame esto ----------
    /** Seleccionas código (tuyo o de una IA) y el tutor te hace 2 o 3 preguntas para comprobar que lo entiendes. Respondes en el chat. */
    fun explain(file: VirtualFile, selection: String, firstLine: Int, lastLine: Int) {
        val k = key(file)
        if (selection.isBlank() || k in chatBusy) return
        val history = chats.getOrPut(k) { mutableListOf() }
        val range = if (firstLine == lastLine) "línea $firstLine" else "líneas $firstLine–$lastLine"
        history += turn("user", "Explícame esto ($range):\n```kotlin\n${selection.trimEnd()}\n```")
        chatBusy += k
        fire()
        showToolWindow()
        val settings = CoachSettings.getInstance()
        val options = settings.promptOptions()
        cs.launch {
            var cfg: dev.kmpcoach.core.AiConfig? = null
            val answer = try {
                val (client, c) = withContext(Dispatchers.IO) { settings.createClient() }
                cfg = c
                val system = HintPrompts.explainSystem(options)
                val user = HintPrompts.explainUser(selection, file.name, firstLine)
                val reply = client.complete(system, listOf(ChatMessage("user", user)), 500).await()
                UsageTracker.getInstance().record(UsageKind.EXPLAIN, file.name, reply.usage, system + user, reply.text, reply.limits)
                val questions = HintParser.questions(reply.text)
                "Antes de explicártelo, comprueba si ya lo entiendes. Respóndeme aquí abajo:\n" +
                    questions.mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "⚠ " + AiClients.friendly(e, cfg)
            }
            withContext(NonCancellable + Dispatchers.EDT) {
                history += turn("assistant", answer)
                chatBusy -= k
                fire()
            }
        }
    }

    // ---------- tutor encendido: pista si te atascas ----------
    private fun onDocumentChanged(doc: Document) {
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        if (!isKotlin(file) || !FileEditorManager.getInstance(project).isFileOpen(file)) return
        reviews[file.url]?.let { fr ->
            if (!fr.stale) {
                fr.stale = true
                inlays[file.url]?.forEach { it.repaint() }
                fire()
            }
        }
        scheduleLive(file, doc)
    }

    private fun scheduleLive(file: VirtualFile, doc: Document) {
        val s = CoachSettings.getInstance().state
        if (!isTutorOn(file)) return
        val wasScheduled = isLiveScheduled(file)
        scheduleTick(file, doc, s.liveDelayMs.toLong().coerceAtLeast(500))
        if (!wasScheduled) fire()
    }

    private fun scheduleTick(file: VirtualFile, doc: Document, delayMs: Long) {
        liveJobs.remove(file.url)?.cancel()
        liveJobs[file.url] = cs.launch {
            delay(delayMs)
            withContext(Dispatchers.EDT) {
                liveJobs.remove(file.url)
                liveTick(file, doc)
            }
        }
    }

    /**
     * Qué hace el tutor, encendido en este archivo, tras una pausa al escribir:
     * - Con errores del IDE o un `TODO()`: espera a que lleves un rato sin reducirlos y te da UNA pista.
     * - Sin errores: nada. Los comentarios de la revisión no se reemplazan solos, porque quizá los estás leyendo;
     *   el panel avisa de que el código cambió y tú decides cuándo actualizarla.
     */
    private fun liveTick(file: VirtualFile, doc: Document) {
        val s = CoachSettings.getInstance().state
        val url = file.url
        waitingStuck.remove(url)
        if (project.isDisposed || !isTutorOn(file) || !FileEditorManager.getInstance(project).isFileOpen(file) || doc.text.isBlank()) {
            fire()
            return
        }
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val now = System.currentTimeMillis()
        val problems = IdeProblems.relevant(ideProblems(file), ScratchUtil.isScratch(file))
        val count = problems.size + if (IdeProblems.hasTodo(doc.text)) 1 else 0
        val stuckMs = s.stuckAfterSec.coerceAtLeast(15) * 1000L
        val tracker = stuck.compute(url) { _, t -> if (t == null || t.stuckAfterMs != stuckMs) StuckTracker(stuckMs) else t }!!
        val wait = tracker.update(count, now)

        if (count > 0) {
            when {
                wait == null -> {}                       // ya te dio una pista en este atasco; pide otra si la quieres
                wait > 0 -> {
                    waitingStuck += url
                    scheduleTick(file, doc, wait)
                }
                !isHintBusy(file) && nextHintLevel(file) != null -> {
                    tracker.markSpoken()
                    requestHint(file, auto = true)
                }
            }
            fire()
            return
        }

        // Sin errores después de haber recibido pistas: lo resolviste. Cuenta para el dominio y la escalera se reinicia.
        hintSessions[url]?.takeIf { it.hints.isNotEmpty() && it.problemsAtLastHint > 0 }?.let {
            resolveSession(it)
            refreshInlays(file)
        }
        fire()
    }

    // ---------- responder a un comentario ----------
    /** Abre en el panel la respuesta a un comentario de la revisión. */
    fun replyTo(file: VirtualFile, comment: LiveComment) {
        showToolWindow()
        listeners.forEach { it.reply(file, comment) }
    }

    /** Envía tu respuesta a un comentario: el tutor la evalúa en el chat y el comentario sale del editor. */
    fun answerComment(file: VirtualFile, comment: LiveComment, answer: String) {
        if (answer.isBlank() || isChatBusy(file)) return
        val line = comment.marker.takeIf { it.isValid }?.let { m ->
            FileDocumentManager.getInstance().getCachedDocument(file)?.getLineNumber(m.startOffset)?.plus(1)
        }
        comment.answered = true
        comment.resolved = true
        refreshInlays(file)
        restartHighlighting(file)
        ask(file, Prompts.replyToComment(line, comment.type, comment.message, answer))
    }

    /** Descarta un comentario sin responderlo: sale del editor y queda marcado en el panel. */
    fun resolveComment(file: VirtualFile, comment: LiveComment) {
        comment.resolved = true
        refreshInlays(file)
        restartHighlighting(file)
        fire()
    }

    // ---------- chat ----------
    private fun turn(role: String, text: String) = Turn(ChatMessage(role, text), System.currentTimeMillis())

    fun ask(file: VirtualFile?, question: String) {
        val q = question.trim()
        val k = key(file)
        if (q.isEmpty() || k in chatBusy) return
        val text = file?.let { FileDocumentManager.getInstance().getDocument(it)?.text }.orEmpty()
        val history = chats.getOrPut(k) { mutableListOf() }
        history += turn("user", q)
        val snapshot = history.takeLast(10).map { it.message }.dropWhile { it.role != "user" }
        chatBusy += k
        fire()
        showToolWindow()
        val settings = CoachSettings.getInstance()
        val options = settings.promptOptions()
        val challenge = ChallengeRepository.getInstance(project).challengeFor(text)
        cs.launch {
            var cfg: dev.kmpcoach.core.AiConfig? = null
            val answer = try {
                val (client, c) = withContext(Dispatchers.IO) { settings.createClient() }
                cfg = c
                val analysis = if (text.isBlank()) null else Analyzer.analyze(text, challenge, lexical = false)
                val system = Prompts.chatSystem(options, challenge, text, analysis, file?.name)
                val reply = client.complete(system, snapshot, 900).await()
                UsageTracker.getInstance().record(UsageKind.CHAT, file?.name, reply.usage, system + snapshot.joinToString { it.content }, reply.text, reply.limits)
                reply.text.trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "⚠ " + AiClients.friendly(e, cfg)
            }
            withContext(NonCancellable + Dispatchers.EDT) {
                history += turn("assistant", answer)
                chatBusy -= k
                fire()
            }
        }
    }

    /** Consulta el saldo de la cuenta, si el proveedor lo expone (hoy, OpenRouter). */
    fun refreshBalance() {
        val settings = CoachSettings.getInstance()
        val providerId = settings.preset.id
        cs.launch {
            var cfg: dev.kmpcoach.core.AiConfig? = null
            try {
                val (client, c) = withContext(Dispatchers.IO) { settings.createClient() }
                cfg = c
                UsageTracker.getInstance().setBalance(providerId, client.balance().await(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UsageTracker.getInstance().setBalance(providerId, null, AiClients.friendly(e, cfg))
            }
        }
    }

    fun clearChat(file: VirtualFile?) {
        chats.remove(key(file))
        fire()
    }

    /** Escribe una pregunta en el chat del panel sin enviarla. */
    fun prefill(text: String) {
        showToolWindow()
        listeners.forEach { it.prefill(text) }
    }

    fun showToolWindow() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    private fun notifyError(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Tutor de IA", message, NotificationType.WARNING)
            .addAction(NotificationAction.createSimple("Abrir ajustes") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CoachConfigurable::class.java)
            })
            .notify(project)
    }

    override fun dispose() {
        reviews.values.forEach { r -> r.comments.forEach { it.marker.dispose() } }
        reviews.clear()
        hintSessions.values.forEach { it.marker?.dispose() }
        hintSessions.clear()
        pending.values.forEach { it.marker.dispose() }
        pending.clear()
    }

    companion object {
        const val TOOL_WINDOW_ID = "KMP Coach"
        const val NOTIFICATION_GROUP = "KMP Interview Coach"
        fun getInstance(project: Project): TutorService = project.service()
        fun isKotlin(file: VirtualFile) = file.extension.equals("kt", true)
    }
}
