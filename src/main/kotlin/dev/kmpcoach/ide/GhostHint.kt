package dev.kmpcoach.ide

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/**
 * Pista en gris al final de la línea, como la sugerencia de un autocompletado; pero en vez de código, es una pregunta
 * o una pista. Tab pide la siguiente y Esc la oculta.
 */
class GhostHintRenderer(text: String, private val keys: String) : EditorCustomElementRenderer {
    val text: String = text.replace("`", "").replace(Regex("\\s+"), " ").trim().let { if (it.length > MAX) it.take(MAX - 1) + "…" else it }

    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
        return fm.stringWidth("    💭 $text   $keys") + 4
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, r: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAntialiasing(g2)
            g2.font = font(editor)
            val fm = g2.fontMetrics
            val y = r.y + editor.ascent
            val main = "    💭 $text"
            g2.color = HINT
            g2.drawString(main, r.x, y)
            g2.color = KEYS
            g2.drawString("   $keys", r.x + fm.stringWidth(main), y)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        const val MAX = 110
        private val HINT = JBColor(Color(0x6E7781), Color(0x8B949E))
        private val KEYS = JBColor(Color(0xA0A7AF), Color(0x6E7681))
    }
}

private fun tutorFor(editor: Editor): Pair<TutorService, com.intellij.openapi.vfs.VirtualFile>? {
    val project = editor.project ?: return null
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
    return TutorService.getInstance(project) to file
}

/** Tab con la pista en gris visible y el cursor al final de esa línea: pide la siguiente pista. Si no, Tab normal. */
class GhostTabHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val (tutor, file) = tutorFor(editor) ?: return original.execute(editor, caret, dataContext)
        if (tutor.ghostAcceptsTab(editor, file)) tutor.requestHint(file) else original.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        tutorFor(editor)?.let { (t, f) -> t.ghostAcceptsTab(editor, f) } == true || original.isEnabled(editor, caret, dataContext)
}

/** Esc con la pista en gris visible: la oculta. Si no, Esc normal. */
class GhostEscapeHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val (tutor, file) = tutorFor(editor) ?: return original.execute(editor, caret, dataContext)
        if (tutor.isGhostVisible(file)) tutor.hideHintInEditor(file) else original.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        tutorFor(editor)?.let { (t, f) -> t.isGhostVisible(f) } == true || original.isEnabled(editor, caret, dataContext)
}
