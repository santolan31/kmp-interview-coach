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
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
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
import com.intellij.psi.util.PsiTreeUtil
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.Analyzer
import dev.kmpcoach.core.ChatMessage
import dev.kmpcoach.core.CodeGuard
import dev.kmpcoach.core.CommentType
import dev.kmpcoach.core.Hint
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
 * (comentarios bajo la línea y avisos en el código), mantiene el chat por archivo y el modo en vivo.
 */
@Service(Service.Level.PROJECT)
class TutorService(private val project: Project, private val cs: CoroutineScope) : Disposable {

    class LiveComment(val marker: RangeMarker, val type: CommentType, val message: String)

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
        @Volatile var giveUp: String? = null
        @Volatile var error: String? = null
        @Volatile var marker: RangeMarker? = null
        @Volatile var showInEditor = true
        /** Errores del IDE cuando se pidió la última pista: si después llegan a cero, el problema se resolvió y la escalera empieza de nuevo. */
        @Volatile var problemsAtLastHint = 0
        @Volatile var lastAuto = false
    }

    interface Listener {
        fun changed() {}
        fun prefill(text: String) {}
    }

    private val reviews = ConcurrentHashMap<String, FileReview>()
    private val inlays = HashMap<String, MutableList<Inlay<*>>>()          // solo EDT
    private val generation = ConcurrentHashMap<String, Long>()
    private val seq = AtomicLong()
    private val reviewJobs = ConcurrentHashMap<String, Job>()
    private val errors = ConcurrentHashMap<String, String>()
    private val waitingForSyntax = ConcurrentHashMap.newKeySet<String>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private val chats = ConcurrentHashMap<String, MutableList<ChatMessage>>()
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
    fun isWaitingForSyntax(file: VirtualFile?) = file != null && file.url in waitingForSyntax
    fun chatFor(file: VirtualFile?): List<ChatMessage> = chats[key(file)]?.toList() ?: emptyList()
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
            runReadAction {
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
        waitingForSyntax.remove(url)
        lastAttempt[url] = System.currentTimeMillis()
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
                    if (generation[url] == gen) reviewJobs.remove(url)
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
        if (fr == null && (hint == null || hintMarker == null)) return
        val created = ArrayList<Inlay<*>>()
        for (editor in EditorFactory.getInstance().getEditors(doc, project)) {
            fr?.comments?.forEach { c ->
                if (!c.marker.isValid) return@forEach
                val line = doc.getLineNumber(c.marker.startOffset)
                val renderer = TutorInlayRenderer(editor, line, c.type, c.message) { fr.stale }
                editor.inlayModel.addBlockElement(doc.getLineEndOffset(line), true, false, 0, renderer)?.let(created::add)
            }
            if (hint != null && hintMarker != null) {
                val line = doc.getLineNumber(hintMarker.startOffset)
                val label = "Pista ${hint.level.number}/${HintLevel.entries.size} · ${hint.level.label}"
                val renderer = TutorInlayRenderer(editor, line, CommentType.PREGUNTA, hint.text, label) { false }
                editor.inlayModel.addBlockElement(doc.getLineEndOffset(line), true, false, 1, renderer)?.let(created::add)
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
    fun requestHint(file: VirtualFile, auto: Boolean = false) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val text = document.text
        val url = file.url
        if (text.isBlank() || hintJobs.containsKey(url)) return
        val problems = ideProblems(file)
        val session = hintSessions.getOrPut(url) { HintSession() }
        if (session.hints.isNotEmpty() && session.problemsAtLastHint > 0 && problems.isEmpty()) resetLadder(session)
        val previous = session.hints.toList()
        val level = previous.lastOrNull()?.let { it.level.next ?: return } ?: HintLevel.QUESTION
        session.error = null
        session.showInEditor = true
        val focus = caretLine(file)
        val lineCount = document.lineCount
        val settings = CoachSettings.getInstance()
        val options = settings.promptOptions()
        val job = cs.launch {
            var cfg: dev.kmpcoach.core.AiConfig? = null
            try {
                val (client, c) = withContext(Dispatchers.IO) { settings.createClient() }
                cfg = c
                val usage = UsageTracker.getInstance()
                val system = HintPrompts.hintSystem(options, level)
                val user = HintPrompts.hintUser(text, file.name, problems, previous, focus, level)
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
                    session.problemsAtLastHint = problems.size
                    session.lastAuto = auto
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
                    fire()
                }
            }
        }
        hintJobs[url] = job
        fire()
    }

    private fun resetLadder(session: HintSession) {
        session.hints.clear()
        session.giveUp = null
        session.error = null
        session.problemsAtLastHint = 0
        session.marker?.dispose()
        session.marker = null
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
        history += ChatMessage("user", "Explícame esto ($range):\n```kotlin\n${selection.trimEnd()}\n```")
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
                history += ChatMessage("assistant", answer)
                chatBusy -= k
                fire()
            }
        }
    }

    // ---------- modo en vivo ----------
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
        if (!s.liveMode) return
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
     * Qué hace el tutor en vivo tras una pausa:
     * - Con errores del IDE o un `TODO()`: espera a que lleves un rato sin reducirlos y te da UNA pista (el primer escalón).
     * - Sin errores: revisa tu enfoque como «Revisar mi código», respetando el tiempo mínimo entre revisiones.
     */
    private fun liveTick(file: VirtualFile, doc: Document) {
        val s = CoachSettings.getInstance().state
        val url = file.url
        waitingStuck.remove(url)
        if (project.isDisposed || !s.liveMode || !FileEditorManager.getInstance(project).isFileOpen(file) || doc.text.isBlank()) {
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

        val last = maxOf(reviews[url]?.at ?: 0L, lastAttempt[url] ?: 0L)
        val waitReview = s.minIntervalSec.coerceAtLeast(5) * 1000L - (now - last)
        if (waitReview > 0) {
            scheduleTick(file, doc, waitReview)
            fire()
            return
        }
        if (canAutoReview(file, doc)) requestReview(file, auto = true) else fire()
    }

    private fun canAutoReview(file: VirtualFile, doc: Document): Boolean {
        if (project.isDisposed || !CoachSettings.getInstance().state.liveMode) return false
        if (reviewJobs.containsKey(file.url) || !FileEditorManager.getInstance(project).isFileOpen(file)) return false
        val text = doc.text
        if (text.isBlank() || reviews[file.url]?.text == text) return false
        val challenge = ChallengeRepository.getInstance(project).challengeFor(text)
        if (challenge != null && text.substringAfter('\n').trim() == challenge.starter.trim()) return false
        val pdm = PsiDocumentManager.getInstance(project)
        pdm.commitDocument(doc)
        val psi = pdm.getPsiFile(doc) ?: return false
        // Si el resaltado aún no marcó un error de sintaxis, no gastes una revisión completa en código que no compila.
        if (PsiTreeUtil.hasErrorElements(psi)) {
            if (waitingForSyntax.add(file.url)) fire()
            return false
        }
        waitingForSyntax.remove(file.url)
        return true
    }

    // ---------- chat ----------
    fun ask(file: VirtualFile?, question: String) {
        val q = question.trim()
        val k = key(file)
        if (q.isEmpty() || k in chatBusy) return
        val text = file?.let { FileDocumentManager.getInstance().getDocument(it)?.text }.orEmpty()
        val history = chats.getOrPut(k) { mutableListOf() }
        history += ChatMessage("user", q)
        val snapshot = history.takeLast(10).dropWhile { it.role != "user" }
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
                history += ChatMessage("assistant", answer)
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
    }

    companion object {
        const val TOOL_WINDOW_ID = "KMP Coach"
        const val NOTIFICATION_GROUP = "KMP Interview Coach"
        fun getInstance(project: Project): TutorService = project.service()
        fun isKotlin(file: VirtualFile) = file.extension.equals("kt", true)
    }
}
