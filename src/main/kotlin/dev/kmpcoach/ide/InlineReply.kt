package dev.kmpcoach.ide

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.kmpcoach.core.CommentType
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultEditorKit

/**
 * Ventana pequeña pegada al comentario del Mentor para responderlo sin salir del editor:
 *
 *   ● Error · línea 113                       (color del tipo de comentario)
 *   ┃ Esta barra debería ser el caso…         (el comentario citado, con la barra de su color)
 *   ╭──────────────────────────────╮
 *   │ Tu respuesta…                │
 *   ╰──────────────────────────────╯
 *   [✓ Resolver]   Enter envía · Esc cierra   [Enviar]
 *
 * Enter envía; Mayús+Enter salta de línea; Esc cierra. La respuesta del Mentor aparece bajo el mismo comentario.
 */
object InlineReply {

    private const val WIDTH = 440

    fun show(project: Project, editor: Editor, file: VirtualFile, comment: TutorService.LiveComment, at: RelativePoint?) {
        val tutor = TutorService.getInstance(project)
        val accent = TutorInlayRenderer.colorOf(comment.type)
        lateinit var popup: JBPopup

        val area = JBTextArea(3, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            font = JBFont.label()
            border = JBUI.Borders.empty(6, 8)
            emptyText.text = if (comment.thread.isEmpty()) "Tu respuesta o tu pregunta…" else "Sigue la conversación…"
        }
        val sendButton = JButton("Enviar").apply {
            isEnabled = false
            // Estilo de botón principal del IDE (azul), como el «OK» de un diálogo.
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        }
        fun send() {
            val text = area.text.trim()
            if (text.isEmpty()) return
            popup.cancel()
            tutor.replyInline(file, comment, text)
        }
        sendButton.addActionListener { send() }
        area.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                sendButton.isEnabled = area.text.isNotBlank()
            }
        })
        area.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "kmpcoach.send")
        area.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction)
        area.actionMap.put("kmpcoach.send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = send()
        })

        val content = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10, 12)
            add(header(comment, accent), BorderLayout.NORTH)
            add(JBScrollPane(area).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = RoundedLineBorder(JBColor.namedColor("Component.borderColor", JBColor.border()), JBUI.scale(8), 1)
                viewportBorder = null
            }, BorderLayout.CENTER)
            add(footer(sendButton, comment) {
                popup.cancel()
                tutor.resolveComment(file, comment)
            }, BorderLayout.SOUTH)
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

    /** «● Error · línea 113» y debajo el comentario citado con una barra de su color, como en el editor. */
    private fun header(comment: TutorService.LiveComment, accent: Color): JComponent {
        val line = comment.marker.takeIf { it.isValid }?.let { it.document.getLineNumber(it.startOffset) + 1 }
        val title = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JBLabel("● ${comment.type.label}").apply {
                foreground = accent
                font = JBFont.label().asBold()
            })
            add(JBLabel(listOfNotNull("Mentor", line?.let { "línea $it" }).joinToString(" · ", prefix = "  ·  ")).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBFont.small()
            })
        }
        val text = comment.message.replace("`", "").replace(Regex("\\s+"), " ").trim().let { if (it.length > 220) it.take(217) + "…" else it }
        val quote = JBLabel("<html><div style='width:${WIDTH - 40}px'>${StringUtil.escapeXmlEntities(text)}</div></html>").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(accent, 0, 3, 0, 0), JBUI.Borders.empty(2, 8, 2, 4))
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            title.alignmentX = 0f
            quote.alignmentX = 0f
            add(title)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            add(quote)
            preferredSize = Dimension(JBUI.scale(WIDTH), preferredSize.height)
        }
    }

    /** Resolver a la izquierda (quita el comentario del editor), los atajos en gris y Enviar como botón principal. */
    private fun footer(sendButton: JButton, comment: TutorService.LiveComment, onResolve: () -> Unit): JComponent {
        val resolve = JButton("Resolver", AllIcons.Actions.Checked).apply {
            toolTipText = "Lo das por resuelto: sale del editor y queda marcado en el panel"
            isVisible = comment.type != CommentType.BIEN
            addActionListener { onResolve() }
        }
        val keys = JBLabel("Enter envía · Mayús+Enter salto · Esc cierra").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBFont.small()
            horizontalAlignment = JBLabel.CENTER
        }
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(resolve, BorderLayout.WEST)
            add(keys, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
    }
}
