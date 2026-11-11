package dev.kmpcoach.ide

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.kmpcoach.core.TodoState
import java.util.function.Function
import javax.swing.JComponent

/**
 * Barra sobre el editor con la guía de pasos: en qué paso vas, cómo van sus TODO y qué hacer ahora
 * (poner el esqueleto, ir al código, comprobar, avanzar). Solo en archivos con el tutor encendido.
 */
class StepGuideNotifications : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!TutorService.isKotlin(file) || !TutorService.getInstance(project).isTutorOn(file)) return null
        if (!CoachSettings.getInstance().state.readAiSession) return null
        val guide = StepGuideService.getInstance(project)
        val view = runCatching { guide.view(file) }.getOrNull() ?: return null
        return Function { _ -> banner(project, file, guide, view) }
    }

    private fun banner(project: Project, file: VirtualFile, guide: StepGuideService, view: StepGuideService.View): JComponent {
        val cur = view.current
        val step = cur.step
        val total = view.steps.size
        val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
        val here = cur.edits.filter { it.edit.path == file.path }
        val position = "Paso ${step.number} (${cur.index + 1} de $total) · ${step.title}"
        val verdict = cur.verdict?.let { v -> if (v.ok) " · Tutor: cumple ✓" else " · Tutor: ${v.missing.ifBlank { "todavía no cumple" }}" }.orEmpty()
        step.verify?.let { panel.toolTipText = "Cómo verificarlo: $it" }

        when {
            cur.complete -> {
                panel.text = "✓ $position · completo$verdict"
                val next = view.next
                if (next != null) {
                    val where = next.edits.firstOrNull()?.fileName?.let { " · $it" }.orEmpty()
                    panel.createActionLabel("Avanzar al paso ${next.number}$where ›") { guide.advance(view) }
                } else {
                    panel.createActionLabel("Terminaste los pasos de esta guía") {}
                }
                if (cur.verdict == null && cur.edits.isNotEmpty()) addVerify(panel, guide, step)
            }
            cur.edits.isEmpty() -> {
                panel.text = "$position" + (step.verify?.let { " · $it" } ?: "")
                panel.createActionLabel("Hecho") { guide.markDone(step) }
            }
            here.isEmpty() -> {
                val target = cur.edits.firstOrNull { !it.status.complete } ?: cur.edits.first()
                panel.text = "$position · sigue en ${target.edit.fileName}"
                panel.createActionLabel("Ir ›") { guide.goTo(target) }
            }
            else -> {
                val todos = here.flatMap { it.status.todos }
                val marks = todos.joinToString(" · ") { t ->
                    t.label + when (t.state) { TodoState.DONE -> " ✓"; TodoState.PENDING -> " ○"; TodoState.UNKNOWN -> " ?" }
                }
                panel.text = position + (if (marks.isNotEmpty()) " · $marks" else "") + verdict
                here.firstOrNull { it.status.canPlaceSkeleton }?.let { ev ->
                    panel.createActionLabel("Poner el esqueleto") { guide.placeSkeleton(ev) }
                }
                val target = here.firstOrNull { !it.status.complete } ?: here.first()
                val pending = todos.any { it.state == TodoState.PENDING }
                panel.createActionLabel(if (pending) "Ir al siguiente TODO" else "Ir al código") { guide.goTo(target) }
                addVerify(panel, guide, step)
                panel.createActionLabel("Explícame este paso") { guide.explain(step, file) }
            }
        }
        return panel
    }

    private fun addVerify(panel: EditorNotificationPanel, guide: StepGuideService, step: dev.kmpcoach.core.GuideStep) {
        if (guide.isVerifying(step)) panel.createActionLabel("Comprobando…") {}
        else panel.createActionLabel("¿Cumple el paso?") { guide.verify(step) }
    }
}
