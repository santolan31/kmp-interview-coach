package dev.kmpcoach.ide

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.kmpcoach.core.GuideStep
import dev.kmpcoach.core.TodoState
import dev.kmpcoach.core.nfc
import java.util.function.Function
import javax.swing.JComponent

/**
 * Barra sobre el editor con la guía de pasos. Dice en palabras qué te falta del paso actual y te lleva ahí;
 * azul mientras trabajas, amarilla si el tutor encontró algo que falta, verde cuando el paso está completo.
 * Solo en archivos con el tutor encendido.
 */
class StepGuideNotifications : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!TutorService.isKotlin(file) || !TutorService.getInstance(project).isTutorOn(file)) return null
        if (!CoachSettings.getInstance().state.readAiSession) return null
        val guide = StepGuideService.getInstance(project)
        val view = runCatching { guide.view(file) }.getOrNull() ?: return null
        return Function { _ -> banner(file, guide, view) }
    }

    private fun clip(s: String, max: Int) = s.replace("`", "").replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max - 1) + "…" else it }

    private fun banner(file: VirtualFile, guide: StepGuideService, view: StepGuideService.View): JComponent {
        val cur = view.current
        val step = cur.step
        val head = "Paso ${step.number} (${cur.index + 1} de ${view.steps.size})"
        // Las rutas del plan vienen en NFC («ó» de una pieza); la del archivo puede venir en NFD.
        val here = cur.edits.filter { it.edit.path == nfc(file.path) }
        // Lo que falta según la comprobación gratis: en este archivo primero.
        val gaps = (here + cur.edits.filter { it !in here }).flatMap { ev -> ev.status.todos.filter { it.missing }.map { ev to it } }
        val verdict = cur.verdict
        val verifying = guide.isVerifying(step)
        val status = when {
            cur.complete -> EditorNotificationPanel.Status.Success
            verdict != null && !verdict.ok -> EditorNotificationPanel.Status.Warning
            else -> EditorNotificationPanel.Status.Info
        }
        val panel = EditorNotificationPanel(status)
        panel.toolTipText = tooltip(step, cur)

        when {
            cur.complete -> {
                panel.text = "✓ $head completo" + if (verdict?.ok == true) " · el Mentor lo confirma" else ""
                val next = view.next
                if (next != null) {
                    val where = next.edits.firstOrNull()?.fileName?.let { " · $it" }.orEmpty()
                    panel.createActionLabel("Avanzar al paso ${next.number}$where ›") { guide.advance(view) }
                } else {
                    panel.createActionLabel("Terminaste los pasos de esta guía") {}
                }
                if (verdict == null && cur.edits.isNotEmpty()) addVerify(panel, guide, step)
            }
            cur.edits.isEmpty() -> {
                panel.text = "$head · ${clip(step.title, 60)}" + (step.verify?.let { " · ${clip(it, 80)}" } ?: "")
                panel.createActionLabel("Hecho") { guide.markDone(step) }
            }
            here.isEmpty() -> {
                val target = cur.edits.firstOrNull { !it.status.complete } ?: cur.edits.first()
                panel.text = "$head · este paso sigue en ${target.edit.fileName}"
                panel.createActionLabel("Ir ›") { guide.goTo(target) }
            }
            verifying -> {
                panel.text = "$head · Comprobando con el Mentor…"
            }
            verdict != null && !verdict.ok -> {
                val more = verdict.gaps.size - 1
                panel.text = "$head · El Mentor dice que falta: ${clip(verdict.missing.ifBlank { "algo del paso" }, 80)}" +
                    (if (more > 0) " (y $more más)" else "") + " · marcado en rojo en el código"
                val gapHere = verdict.gaps.firstOrNull { (it.file == null || it.file == file.name) && it.line != null }
                if (gapHere != null) panel.createActionLabel("Ir a lo que falta") { guide.goToLine(file, gapHere.line!! - 1) }
                else gaps.firstOrNull()?.let { (ev, _) -> panel.createActionLabel("Ir a lo que falta") { guide.goTo(ev) } }
                addActions(panel, guide, step, here, file, verifyLabel = "Comprobar de nuevo")
            }
            gaps.isNotEmpty() -> {
                val (ev, t) = gaps.first()
                val where = if (ev.edit.path != file.path) " (en ${ev.edit.fileName})" else ""
                val more = if (gaps.size > 1) " · y ${gaps.size - 1} más" else ""
                panel.text = if (t.state == TodoState.PENDING) {
                    // El comentario sigue ahí: así sabe la comprobación gratis que no lo terminaste. Se dice claro.
                    "$head · ${t.label}$where sigue escrito: ${clip(t.text, 60)} · cuando lo completes, borra el comentario$more"
                } else {
                    "$head · Te falta ${t.label}$where: ${clip(t.text, 80)}$more"
                }
                panel.createActionLabel("Ir a lo que falta") { guide.goTo(ev) }
                addActions(panel, guide, step, here, file)
            }
            else -> {
                // Sin TODO pendientes. Lo que falta puede estar en otro archivo del paso (borrar algo y luego quien lo usa).
                val open = here.firstOrNull { !it.status.complete }
                val elsewhere = cur.edits.firstOrNull { it !in here && !it.status.complete }
                panel.text = if (open == null && elsewhere != null) "$head · aquí ya está · falta en ${elsewhere.edit.fileName}"
                    else "$head · ${clip(step.title, 60)} · no veo TODO pendientes: si compila, pulsa «¿Cumple el paso?»"
                open?.let { ev -> panel.createActionLabel("Ir al código") { guide.goTo(ev) } }
                elsewhere?.let { ev -> panel.createActionLabel("Ir a ${ev.edit.fileName} ›") { guide.goTo(ev) } }
                addActions(panel, guide, step, here, file)
            }
        }
        return panel
    }

    private fun addActions(
        panel: EditorNotificationPanel, guide: StepGuideService, step: GuideStep,
        here: List<StepGuideService.EditView>, file: VirtualFile, verifyLabel: String = "¿Cumple el paso?",
    ) {
        here.firstOrNull { it.status.canPlaceSkeleton }?.let { ev -> panel.createActionLabel("Poner el esqueleto") { guide.placeSkeleton(ev) } }
        addVerify(panel, guide, step, verifyLabel)
        panel.createActionLabel("Explícame este paso") { guide.explain(step, file) }
    }

    private fun addVerify(panel: EditorNotificationPanel, guide: StepGuideService, step: GuideStep, label: String = "¿Cumple el paso?") {
        if (guide.isVerifying(step)) panel.createActionLabel("Comprobando…") {}
        else panel.createActionLabel(label) { guide.verify(step) }
    }

    /** Al pasar el ratón: el paso completo, cada TODO con su estado y cómo verificarlo. */
    private fun tooltip(step: GuideStep, cur: StepGuideService.StepView): String = buildString {
        append("<html><b>").append(StringUtil.escapeXmlEntities(step.label)).append("</b><br>")
        cur.edits.flatMap { ev -> ev.status.todos.map { ev.edit.fileName to it } }.forEach { (f, t) ->
            val mark = when (t.state) {
                TodoState.DONE -> "✓"; TodoState.PENDING -> "✗ sigue escrito"; TodoState.MISSING -> "✗ falta"; TodoState.UNKNOWN -> "?"
            }
            append("$mark ${t.label} ($f): ").append(StringUtil.escapeXmlEntities(clip(t.text, 100))).append("<br>")
        }
        step.verify?.let { append("<br>Cómo verificarlo: ").append(StringUtil.escapeXmlEntities(clip(it, 200))) }
        append("</html>")
    }
}
