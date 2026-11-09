package dev.kmpcoach.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

private fun AnActionEvent.kotlinFile() = getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { TutorService.isKotlin(it) }

/** Ctrl+Alt+Mayús+K: el tutor revisa el archivo actual y deja comentarios bajo las líneas. */
class ReviewNowAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.kotlinFile() != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.kotlinFile() ?: return
        val tutor = TutorService.getInstance(project)
        tutor.requestReview(file)
        tutor.showToolWindow()
    }
}

/** Siguiente escalón de la escalera de pistas para el archivo actual. */
class HintAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.kotlinFile() != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.kotlinFile() ?: return
        val tutor = TutorService.getInstance(project)
        if (tutor.nextHintLevel(file) == null) {
            tutor.showToolWindow()
            return
        }
        tutor.requestHint(file)
        tutor.showToolWindow()
    }
}

/** «Explícame esto»: con código seleccionado, el tutor te hace preguntas para comprobar que lo entiendes. */
class ExplainAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isVisible = e.project != null && editor != null && e.kotlinFile() != null
        e.presentation.isEnabled = e.presentation.isVisible && editor?.selectionModel?.hasSelection() == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.kotlinFile() ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val sel = editor.selectionModel
        val text = sel.selectedText ?: return
        val doc = editor.document
        TutorService.getInstance(project).explain(file, text, doc.getLineNumber(sel.selectionStart) + 1, doc.getLineNumber(sel.selectionEnd) + 1)
    }
}

/** Menú contextual del editor: escribe en el chat una pregunta sobre la selección o la línea actual. */
class AskTutorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null && e.kotlinFile() != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val doc = editor.document
        val sel = editor.selectionModel
        val (a, b) = if (sel.hasSelection()) doc.getLineNumber(sel.selectionStart) to doc.getLineNumber(sel.selectionEnd)
        else editor.caretModel.logicalPosition.line.let { it to it }
        val where = if (a == b) "Sobre la línea ${a + 1}: " else "Sobre las líneas ${a + 1}–${b + 1}: "
        TutorService.getInstance(project).prefill(where)
    }
}
