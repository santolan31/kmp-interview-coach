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
