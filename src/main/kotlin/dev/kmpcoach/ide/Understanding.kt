package dev.kmpcoach.ide

import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.kmpcoach.core.CodeBlock
import javax.swing.Icon

/**
 * Marca en el margen el primer renglón de cada bloque de código nuevo (según git) que aún no comprobaste que entiendes.
 * Al pulsar la marca eliges cómo comprobarlo. Combate la "ilusión de competencia": creer que entiendes código
 * que solo leíste, sobre todo si lo escribió una IA.
 */
class UnderstandingAnnotator : ExternalAnnotator<UnderstandingAnnotator.Input, UnderstandingAnnotator.Input>(), DumbAware {

    class Input(val items: List<Pair<CodeBlock, Int>>)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? = collect(file)
    override fun collectInformation(file: PsiFile): Input? = collect(file)

    private fun collect(file: PsiFile): Input? {
        if (!CoachSettings.getInstance().state.trackNewCode) return null
        val vf = file.virtualFile ?: return null
        if (!vf.extension.equals("kt", true) && !vf.extension.equals("kts", true)) return null
        val doc = file.viewProvider.document ?: return null
        val ctx = ProjectContext.getInstance(file.project)
        val items = ctx.blocksIn(vf, doc.text.lines()).filter { (b, _) -> !ctx.isUnderstood(b) && !ctx.isIgnored(b) }
        return if (items.isEmpty()) null else Input(items)
    }

    override fun doAnnotate(collectedInfo: Input?): Input? = collectedInfo

    override fun apply(file: PsiFile, result: Input?, holder: AnnotationHolder) {
        val doc = file.viewProvider.document ?: return
        for ((block, start) in result?.items.orEmpty()) {
            val line = start - 1
            if (line !in 0 until doc.lineCount) continue
            var s = doc.getLineStartOffset(line)
            val e = doc.getLineEndOffset(line)
            while (s < e && doc.charsSequence[s].isWhitespace()) s++
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(s, maxOf(s, e)))
                .gutterIconRenderer(UnderstandingGutter(block, start))
                .create()
        }
    }
}

class UnderstandingGutter(private val block: CodeBlock, private val startLine: Int) : GutterIconRenderer(), DumbAware {
    private val endLine = startLine + block.lineCount - 1

    override fun getIcon(): Icon = AllIcons.General.ContextHelp

    override fun getTooltipText(): String =
        "<html><b>Mentor · código nuevo sin comprobar</b> (líneas $startLine–$endLine)<br>" +
            "Este bloque es nuevo según git (lo escribieras tú o una IA). Haz clic para comprobar que entiendes qué hace:<br>" +
            "predecir su resultado o explicarlo sin mirarlo. Si no hace falta, márcalo y desaparece.</html>"

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            showChoices(project, block, e)
        }
    }

    override fun equals(other: Any?): Boolean = other is UnderstandingGutter && other.block.id == block.id && other.startLine == startLine
    override fun hashCode(): Int = block.id.hashCode() * 31 + startLine

    companion object {
        private const val PREDICT = "Predecir qué hace con un ejemplo"
        private const val EXPLAIN = "Explicarlo sin mirarlo (se pliega)"
        private const val IGNORE = "No necesita revisión (generado o configuración)"
        private const val TURN_OFF = "No mostrar estas marcas (se reactivan en Ajustes › Mentor)"

        fun showChoices(project: Project, block: CodeBlock, e: AnActionEvent?) {
            val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(listOf(PREDICT, EXPLAIN, IGNORE, TURN_OFF))
                .setTitle("Mentor · ¿Entiendes este código nuevo? (${block.lineCount} líneas)")
                .setItemChosenCallback { choice ->
                    val learning = LearningService.getInstance(project)
                    when (choice) {
                        PREDICT -> learning.predict(block)
                        EXPLAIN -> learning.explain(block)
                        IGNORE -> ProjectContext.getInstance(project).markIgnored(block)
                        TURN_OFF -> {
                            CoachSettings.getInstance().state.trackNewCode = false
                            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                        }
                    }
                }
                .createPopup()
            if (e != null) popup.showInBestPositionFor(e.dataContext) else popup.showCenteredInCurrentWindow(project)
        }
    }
}
