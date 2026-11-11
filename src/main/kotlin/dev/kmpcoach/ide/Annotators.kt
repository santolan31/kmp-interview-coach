package dev.kmpcoach.ide

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import dev.kmpcoach.core.Analyzer
import dev.kmpcoach.core.Challenge
import dev.kmpcoach.core.CommentType
import dev.kmpcoach.core.Diagnostic
import dev.kmpcoach.core.Severity

private fun html(prefix: String, message: String): String =
    "<html><b>$prefix</b> " + StringUtil.escapeXmlEntities(message).replace(Regex("`([^`]+)`"), "<code>$1</code>") + "</html>"

private fun plain(message: String) = message.replace("`", "")

/**
 * Análisis local: corre en cada pasada del resaltado, sin red y en milisegundos.
 * Marca patrones problemáticos en cualquier archivo Kotlin y las reglas propias del reto si el archivo es un reto.
 */
class CoachLocalAnnotator : ExternalAnnotator<CoachLocalAnnotator.Input, List<Diagnostic>>(), DumbAware {

    class Input(val text: String, val challenge: Challenge?)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? = collect(file)
    override fun collectInformation(file: PsiFile): Input? = collect(file)

    private fun collect(file: PsiFile): Input? {
        val vf = file.virtualFile ?: file.viewProvider.virtualFile
        if (!TutorService.isKotlin(vf) || vf is LightVirtualFile) return null
        val text = file.text
        val challenge = ChallengeRepository.getInstance(file.project).challengeFor(text)
        if (challenge == null && !CoachSettings.getInstance().state.analyzeAllKotlin) return null
        return Input(text, challenge)
    }

    override fun doAnnotate(input: Input?): List<Diagnostic>? =
        input?.let { Analyzer.analyze(it.text, it.challenge, lexical = false).diagnostics }

    override fun apply(file: PsiFile, result: List<Diagnostic>?, holder: AnnotationHolder) {
        val length = file.textLength
        for (d in result.orEmpty()) {
            if (d.from >= length) continue
            val range = TextRange(d.from, maxOf(d.to, d.from + 1).coerceAtMost(length))
            val severity = if (d.severity == Severity.ERROR) HighlightSeverity.WARNING else HighlightSeverity.WEAK_WARNING
            holder.newAnnotation(severity, "Mentor · ${d.severity.label}: ${plain(d.message)}")
                .range(range)
                .tooltip(html("Mentor · ${d.severity.label}:", d.message))
                .withFix(AskTutorFix("El analizador marcó esto en la línea ${d.line + 1}: «${plain(d.message)}». ¿Por qué es un problema en mi código y cómo lo pienso?"))
                .create()
        }
    }
}

/** Muestra los comentarios de la última revisión del tutor como avisos en el código (y en la vista Problemas). */
class CoachAiAnnotator : ExternalAnnotator<List<CoachAiAnnotator.Item>, List<CoachAiAnnotator.Item>>(), DumbAware {

    class Item(val range: TextRange, val type: CommentType, val message: String, val line: Int)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean) = collect(file)
    override fun collectInformation(file: PsiFile) = collect(file)

    private fun collect(file: PsiFile): List<Item>? {
        val vf = file.virtualFile ?: return null
        val comments = TutorService.getInstance(file.project).commentsInEditor(vf)
        if (comments.isEmpty()) return null
        val doc = file.viewProvider.document ?: return null
        val chars = doc.charsSequence
        return comments.filter { it.marker.isValid && it.type != CommentType.BIEN }.map { c ->
            val line = doc.getLineNumber(c.marker.startOffset)
            var s = doc.getLineStartOffset(line)
            var e = doc.getLineEndOffset(line)
            while (s < e && chars[s].isWhitespace()) s++
            while (e > s && chars[e - 1].isWhitespace()) e--
            Item(TextRange(s, maxOf(e, s)), c.type, c.message, line)
        }
    }

    override fun doAnnotate(collectedInfo: List<Item>?) = collectedInfo

    override fun apply(file: PsiFile, annotationResult: List<Item>?, holder: AnnotationHolder) {
        val length = file.textLength
        for (it in annotationResult.orEmpty()) {
            if (it.range.endOffset > length) continue
            val severity = if (it.type == CommentType.ERROR) HighlightSeverity.WARNING else HighlightSeverity.WEAK_WARNING
            val b = holder.newAnnotation(severity, "Tutor IA · ${it.type.label}: ${plain(it.message)}")
                .tooltip(html("Tutor IA · ${it.type.label}:", it.message))
                .withFix(AskTutorFix("Sobre tu comentario en la línea ${it.line + 1} («${plain(it.message)}»): "))
            (if (it.range.isEmpty) b.afterEndOfLine().range(TextRange(it.range.startOffset, it.range.startOffset)) else b.range(it.range)).create()
        }
    }
}

/**
 * Alt+Enter → «Mentor: preguntarle por qué». Si [question] termina en «: » solo la escribe en el chat;
 * si no, la envía directamente.
 */
class AskTutorFix(private val question: String) : IntentionAction, LowPriorityAction {
    override fun getText() = "Mentor: preguntarle por qué"
    override fun getFamilyName() = "Mentor"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true
    override fun startInWriteAction() = false
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val tutor = TutorService.getInstance(project)
        if (question.endsWith(": ")) tutor.prefill(question) else tutor.ask(file?.virtualFile, question)
    }
}
