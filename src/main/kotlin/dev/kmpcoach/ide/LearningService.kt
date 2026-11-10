package dev.kmpcoach.ide

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.kmpcoach.core.AiClient
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.ChatMessage
import dev.kmpcoach.core.CodeBlock
import dev.kmpcoach.core.Evaluation
import dev.kmpcoach.core.LearningParser
import dev.kmpcoach.core.LearningPrompts
import dev.kmpcoach.core.TraceQuestion
import dev.kmpcoach.core.UsageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Flujos para comprobar que entiendes el código, basados en la investigación sobre aprender con código generado por IA:
 * - **Predecir** (Trace-and-Predict): con una entrada concreta, ¿qué hace el bloque?
 * - **Explicar sin mirar** (Lead-and-Reveal): el bloque se pliega, explicas qué debería hacer y luego se revela.
 * - **Predecir antes de ver los tests** (PRIMM).
 * - **Repaso espaciado** de los conceptos que te costaron.
 */
@Service(Service.Level.PROJECT)
class LearningService(private val project: Project, private val cs: CoroutineScope) {

    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var status: String? = null
        private set
    @Volatile private var reviewBusy = false

    fun isBusy(block: CodeBlock) = block.id in busy
    fun isReviewBusy() = reviewBusy

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun fire() = listeners.forEach { it() }

    private val ctx get() = ProjectContext.getInstance(project)

    private suspend fun client(): AiClient = withContext(Dispatchers.IO) { CoachSettings.getInstance().createClient().first }

    private suspend fun call(kind: UsageKind, system: String, user: String, fileName: String?, maxTokens: Int = 500): String {
        val reply = client().complete(system, listOf(ChatMessage("user", user)), maxTokens).await()
        UsageTracker.getInstance().record(kind, fileName, reply.usage, system + user, reply.text, reply.limits)
        return reply.text
    }

    private fun fileOf(block: CodeBlock): VirtualFile? =
        ctx.basePath?.let { LocalFileSystem.getInstance().findFileByIoFile(File(it, block.path)) }

    private fun name(block: CodeBlock) = "${block.path.substringAfterLast('/')} L${block.startLine}–${block.endLine}"

    /** Pregunta en un diálogo. Devuelve null si el estudiante cancela. */
    private suspend fun ask(title: String, message: String): String? = withContext(Dispatchers.EDT) {
        Messages.showMultilineInputDialog(project, message, title, "", Messages.getQuestionIcon(), null)?.trim()?.ifEmpty { null }
    }

    private suspend fun tell(title: String, message: String, ok: Boolean) = withContext(Dispatchers.EDT) {
        if (ok) Messages.showInfoMessage(project, message, title) else Messages.showWarningDialog(project, message, title)
    }

    private fun feedbackText(ev: Evaluation): String = buildString {
        append(ev.feedback)
        if (ev.hint.isNotBlank()) append("\n\nPista: ").append(ev.hint)
    }.replace("`", "")

    private suspend fun guarded(block: CodeBlock?, statusText: String, body: suspend () -> Unit) {
        status = statusText
        withContext(Dispatchers.EDT) { fire() }
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = AiClients.friendly(e, runCatching { CoachSettings.getInstance().aiConfig() }.getOrNull())
            withContext(Dispatchers.EDT) { Messages.showWarningDialog(project, msg, "Tutor de IA") }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) {
                block?.let { busy.remove(it.id) }
                status = null
                fire()
            }
        }
    }

    // ---------- predecir (Trace-and-Predict) ----------
    /** Muestra el bloque, te hace una pregunta con una entrada concreta y evalúa tu predicción. Dos intentos. Llamar desde EDT. */
    fun predict(block: CodeBlock) {
        if (!busy.add(block.id)) return
        val file = fileOf(block)
        file?.let { OpenFileDescriptor(project, it, block.startLine - 1, 0).navigate(true) }
        val goal = ctx.goal()
        val o = CoachSettings.getInstance().promptOptions()
        cs.launch {
            guarded(block, "Preparando una pregunta sobre ${name(block)}…") {
                val q = LearningParser.trace(call(UsageKind.COMPREHENSION, LearningPrompts.traceSystem(o), LearningPrompts.traceUser(block, goal), file?.name))
                var previous = ""
                for (attempt in 1..2) {
                    val answer = ask("Predice · ${name(block)}", (previous + q.question).replace("`", "")) ?: return@guarded
                    val ev = LearningParser.evaluation(call(UsageKind.COMPREHENSION, LearningPrompts.evaluateSystem(o), LearningPrompts.evaluateTraceUser(block, q, answer), file?.name))
                    if (ev.correct) {
                        withContext(Dispatchers.EDT) {
                            ctx.markUnderstood(block)
                            ctx.recordPrediction(true)
                        }
                        tell("Acertaste", ev.feedback.replace("`", ""), ok = true)
                        return@guarded
                    }
                    if (attempt == 1) {
                        previous = "Tu primera respuesta: $answer\n${feedbackText(ev)}\n\nInténtalo otra vez:\n"
                    } else {
                        withContext(Dispatchers.EDT) {
                            ctx.recordPrediction(false)
                            if (q.concept.isNotBlank()) LearnerProfile.getInstance().addReview(q.concept)
                        }
                        reveal(q)
                    }
                }
            }
        }
    }

    private suspend fun reveal(q: TraceQuestion) {
        val msg = buildString {
            append("La respuesta era: ").append(q.answer)
            if (q.why.isNotBlank()) append("\n\nPor qué: ").append(q.why)
            append("\n\nVuelve a leer el bloque con esto en mente y pídele una pista al tutor sobre la parte que no esperabas.")
            if (q.concept.isNotBlank()) append("\nAgregué «${q.concept}» a tu repaso.")
        }
        tell("Veamos la respuesta", msg.replace("`", ""), ok = false)
    }

    // ---------- explicar sin mirar (Lead-and-Reveal) ----------
    /** Pliega el bloque, te pide que expliques qué hace y para qué, y lo revela al final. Llamar desde EDT. */
    fun explain(block: CodeBlock) {
        if (!busy.add(block.id)) return
        val file = fileOf(block)
        val editor = file?.let { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it, block.startLine - 1, 0), true) }
        val region = editor?.let { fold(it, block) }
        val goal = ctx.goal()
        val o = CoachSettings.getInstance().promptOptions()
        cs.launch {
            try {
                guarded(block, "Esperando tu explicación de ${name(block)}…") {
                    var previous = if (region == null) "(No pude plegar el bloque: intenta no mirarlo).\n\n" else ""
                    for (attempt in 1..2) {
                        val prompt = previous + "Sin mirar el bloque plegado (${name(block)}), explica con tus palabras qué hace y para qué está ahí."
                        val answer = ask("Explícalo sin mirar", prompt) ?: return@guarded
                        val ev = LearningParser.evaluation(call(UsageKind.COMPREHENSION, LearningPrompts.evaluateSystem(o), LearningPrompts.evaluateExplainUser(block, goal, answer), file?.name))
                        if (ev.correct) {
                            withContext(Dispatchers.EDT) { unfold(editor, region); ctx.markUnderstood(block) }
                            tell("Lo entiendes", ev.feedback.replace("`", ""), ok = true)
                            return@guarded
                        }
                        if (attempt == 1) {
                            previous = "Tu explicación: $answer\n${feedbackText(ev)}\n\nCompleta tu explicación:\n"
                        } else {
                            withContext(Dispatchers.EDT) { unfold(editor, region) }
                            tell("Ahora míralo", (feedbackText(ev) + "\n\nLee el bloque: ¿qué parte no esperabas? Pregúntale al tutor por esa parte.").replace("`", ""), ok = false)
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) { unfold(editor, region) }
            }
        }
    }

    private fun fold(editor: Editor, block: CodeBlock): FoldRegion? {
        val doc = editor.document
        val first = (block.startLine - 1).coerceIn(0, doc.lineCount - 1)
        val last = (block.endLine - 1).coerceIn(first, doc.lineCount - 1)
        var region: FoldRegion? = null
        editor.foldingModel.runBatchFoldingOperation {
            region = editor.foldingModel.addFoldRegion(doc.getLineStartOffset(first), doc.getLineEndOffset(last), "⟨ bloque oculto: explica qué hace ⟩")
            region?.isExpanded = false
        }
        return region
    }

    private fun unfold(editor: Editor?, region: FoldRegion?) {
        if (editor == null || region == null || editor.isDisposed || !region.isValid) return
        editor.foldingModel.runBatchFoldingOperation { editor.foldingModel.removeFoldRegion(region) }
    }

    // ---------- repaso espaciado ----------
    /** Si hoy toca un repaso y no lo has hecho, lo ofrece con una notificación discreta. */
    fun offerDailyReview() {
        val profile = LearnerProfile.getInstance()
        if (!CoachSettings.getInstance().state.dailyReview || profile.reviewedToday()) return
        val card = profile.dueReview() ?: return
        NotificationGroupManager.getInstance().getNotificationGroup(TutorService.NOTIFICATION_GROUP)
            .createNotification("Repaso de 30 segundos", "Hoy toca repasar «${card.concept}», un concepto que te costó.", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Responder") { reviewNow() })
            .addAction(NotificationAction.createSimpleExpiring("Hoy no") {})
            .notify(project)
    }

    fun reviewNow() {
        val profile = LearnerProfile.getInstance()
        val card = profile.dueReview() ?: return
        if (reviewBusy) return
        reviewBusy = true
        val o = CoachSettings.getInstance().promptOptions()
        cs.launch {
            try {
                guarded(null, "Preparando el repaso de «${card.concept}»…") {
                    val q = LearningParser.trace(call(UsageKind.SPACED, LearningPrompts.reviewSystem(o), LearningPrompts.reviewUser(card.concept), null))
                    val answer = ask("Repaso · ${card.concept}", q.question.replace("`", "")) ?: return@guarded
                    val ev = LearningParser.evaluation(call(UsageKind.SPACED, LearningPrompts.evaluateSystem(o), LearningPrompts.evaluateReviewUser(q, answer), null))
                    withContext(Dispatchers.EDT) { profile.answerReview(card, ev.correct) }
                    if (ev.correct) tell("Bien recordado", ev.feedback.replace("`", "") + "\n\nVolverá a aparecer en unos días.", ok = true)
                    else tell("Casi", (ev.feedback + "\n\nLa respuesta: " + q.answer + (if (q.why.isNotBlank()) "\nPor qué: " + q.why else "") + "\n\nTe lo vuelvo a preguntar mañana.").replace("`", ""), ok = false)
                }
            } finally {
                reviewBusy = false
            }
        }
    }

    // ---------- predecir antes de ver los tests (PRIMM) ----------
    @Volatile private var testPrediction: Boolean? = null
    @Volatile private var testNotification: Notification? = null

    fun onTestsStarted() {
        testNotification?.expire()
        testPrediction = null
        if (!CoachSettings.getInstance().state.predictTests) return
        val n = NotificationGroupManager.getInstance().getNotificationGroup(TutorService.NOTIFICATION_GROUP)
            .createNotification("Antes de ver el resultado", "¿Qué crees que va a pasar con los tests?", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Pasarán todos") { testPrediction = true })
            .addAction(NotificationAction.createSimpleExpiring("Fallará alguno") { testPrediction = false })
        testNotification = n
        n.notify(project)
    }

    /** [failed] son los nombres de los tests que fallaron; [total], cuántos tests corrieron. */
    fun onTestsFinished(failed: List<String>, total: Int) {
        testNotification?.expire()
        testNotification = null
        val predicted = testPrediction ?: return
        testPrediction = null
        if (total == 0) return
        val passedAll = failed.isEmpty()
        val right = predicted == passedAll
        ctx.recordPrediction(right)
        val names = failed.take(3).joinToString()
        val (title, body) = when {
            right && passedAll -> "Acertaste" to "Pasaron todos, como esperabas."
            right -> "Acertaste" to "Falló ${failed.size} (${names}), como esperabas. ¿Sabes ya por qué?"
            predicted -> "No era lo que esperabas" to "Esperabas que pasaran todos y falló ${failed.size}: $names. Antes de leer el error, ¿qué crees que falló y por qué?"
            else -> "No era lo que esperabas" to "Esperabas un fallo y pasaron todos. ¿Qué comprueba realmente ese test? ¿Está probando lo que crees?"
        }
        val n = NotificationGroupManager.getInstance().getNotificationGroup(TutorService.NOTIFICATION_GROUP)
            .createNotification(title, body, if (right) NotificationType.INFORMATION else NotificationType.WARNING)
        if (!right && !passedAll) {
            n.addAction(NotificationAction.createSimpleExpiring("Contarle mi hipótesis al tutor") {
                TutorService.getInstance(project).prefill("Falló el test $names. Mi hipótesis de por qué falló: ")
            })
        }
        n.notify(project)
    }

    companion object {
        fun getInstance(project: Project): LearningService = project.service()
    }
}

