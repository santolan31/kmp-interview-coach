package dev.kmpcoach.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader

private fun AnActionEvent.kotlinFile() = getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { TutorService.isKotlin(it) }

/**
 * Interruptor del Mentor en el archivo actual. Aparece como botón «Mentor: activo / apagado» en la barra de pestañas
 * del editor, en el menú Mentor y con atajo. Apagado, el tutor no hace nada por su cuenta en ese archivo.
 */
class ToggleTutorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.kotlinFile()
        e.presentation.isEnabledAndVisible = project != null && file != null
        if (project == null || file == null) return
        val on = TutorService.getInstance(project).isTutorOn(file)
        // En la barra se lee como estado; en un menú, como la acción que vas a hacer.
        e.presentation.text = when {
            e.isFromActionToolbar -> if (on) "Mentor: activo" else "Mentor: apagado"
            on -> "Apagar el Mentor en este archivo"
            else -> "Encender el Mentor en este archivo"
        }
        e.presentation.description =
            if (on) "El tutor acompaña este archivo: te da una pista si te atascas. Pulsa para apagarlo."
            else "El tutor está apagado en este archivo: no aparece nada solo. Pulsa para encenderlo."
        e.presentation.icon = if (on) ICON else ICON_OFF
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.kotlinFile() ?: return
        val tutor = TutorService.getInstance(project)
        val on = !tutor.isTutorOn(file)
        tutor.setTutorOn(file, on)
        if (on) tutor.showToolWindow()
    }

    companion object {
        private val ICON = IconLoader.getIcon("/icons/coach.svg", ToggleTutorAction::class.java)
        private val ICON_OFF = IconLoader.getDisabledIcon(ICON)
    }
}

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
