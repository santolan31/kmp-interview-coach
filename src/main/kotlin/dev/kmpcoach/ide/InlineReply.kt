package dev.kmpcoach.ide

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.text.DefaultEditorKit

/**
 * Ventana pequeña pegada al comentario del tutor para responderlo sin salir del editor. Enter envía; Mayús+Enter salta
 * de línea; Esc cierra. La respuesta del tutor aparece bajo el mismo comentario.
 */
object InlineReply {

    fun show(project: Project, editor: Editor, file: VirtualFile, comment: TutorService.LiveComment, at: RelativePoint?) {
        val tutor = TutorService.getInstance(project)
        lateinit var popup: JBPopup

        val area = JBTextArea(3, 44).apply {
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.label()
            border = JBUI.Borders.empty(6)
            emptyText.text = "Tu respuesta (Enter envía · Mayús+Enter salta de línea)"
        }
        fun send() {
            val text = area.text.trim()
            if (text.isEmpty()) return
            popup.cancel()
            tutor.replyInline(file, comment, text)
        }
        area.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "kmpcoach.send")
        area.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction)
        area.actionMap.put("kmpcoach.send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = send()
        })

        val quote = comment.message.replace("`", "").replace(Regex("\\s+"), " ").trim().let { if (it.length > 160) it.take(157) + "…" else it }
        val head = JBLabel("<html><b>${comment.type.label}</b> · ${StringUtil.escapeXmlEntities(quote)}</html>").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(2, 4, 6, 4)
            preferredSize = Dimension(JBUI.scale(420), preferredSize.height.coerceAtLeast(JBUI.scale(18)))
        }
        val sendButton = JButton("Enviar").apply { addActionListener { send() } }
        val buttons = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(6)
            add(ActionLink("Resolver (quitar del editor)") {
                popup.cancel()
                tutor.resolveComment(file, comment)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(sendButton) }, BorderLayout.EAST)
        }
        val content = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
            add(head, BorderLayout.NORTH)
            add(JBScrollPane(area).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, area)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()

        val anchor = at ?: comment.marker.takeIf { it.isValid }?.let { m ->
            val p = editor.offsetToXY(m.startOffset)
            RelativePoint(editor.contentComponent, Point(p.x, p.y + editor.lineHeight))
        } ?: JBPopupFactory.getInstance().guessBestPopupLocation(editor)
        // Un poco más abajo del clic, para no tapar el texto del comentario.
        val below = RelativePoint(anchor.component, Point(anchor.point.x, anchor.point.y + editor.lineHeight / 2))
        popup.show(below)
    }
}
