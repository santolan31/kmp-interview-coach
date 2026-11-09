package dev.kmpcoach.ide

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import dev.kmpcoach.core.CommentType
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/** Dibuja un comentario del tutor como un bloque bajo la línea, con el color de su tipo. */
class TutorInlayRenderer(
    editor: Editor,
    line: Int,
    private val type: CommentType,
    message: String,
    labelText: String? = null,
    private val isStale: () -> Boolean,
) : EditorCustomElementRenderer {

    private val label = labelText ?: type.label
    private val text = message.replace("`", "").replace(Regex("\\s+"), " ").trim()
    private val indentPx: Int = run {
        val doc = editor.document
        val start = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        val chars = doc.charsSequence
        var k = start
        while (k < end && chars[k].isWhitespace()) k++
        editor.offsetToXY(k).x
    }

    // Ancho fijado al crear el comentario: si dependiera del tamaño de la ventana, la altura del bloque quedaría desfasada.
    private val maxWidth: Int = run {
        val visible = (editor as? EditorEx)?.scrollingModel?.visibleArea?.width?.takeIf { it > 0 } ?: 900
        (visible - indentPx - JBUI.scale(40)).coerceIn(JBUI.scale(260), JBUI.scale(820))
    }

    private class Layout(val font: Font, val bold: Font, val lines: List<String>, val width: Int, val height: Int, val fm: FontMetrics, val labelWidth: Int)

    private var cached: Layout? = null
    private var cachedKey = ""

    private fun layout(editor: Editor): Layout {
        val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val font = JBUI.Fonts.label().deriveFont(base.size2D * 0.95f)
        val bold = font.deriveFont(Font.BOLD)
        val key = "${font.size2D}"
        cached?.takeIf { cachedKey == key }?.let { return it }

        val fm = editor.contentComponent.getFontMetrics(font)
        val bfm = editor.contentComponent.getFontMetrics(bold)
        val labelWidth = bfm.stringWidth("$label  ")
        val inner = maxWidth - PAD_X * 2 - BAR
        val lines = ArrayList<String>()
        var current = StringBuilder()
        var limit = inner - labelWidth
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (fm.stringWidth(candidate) <= limit || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
                limit = inner
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        val widest = lines.mapIndexed { i, l -> fm.stringWidth(l) + if (i == 0) labelWidth else 0 }.maxOrNull() ?: 0
        val width = widest + PAD_X * 2 + BAR
        val height = lines.size * fm.height + PAD_Y * 2 + MARGIN * 2
        return Layout(font, bold, lines, width, height, fm, labelWidth).also { cached = it; cachedKey = key }
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = indentPx + layout(inlay.editor).width

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = layout(inlay.editor).height

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val l = layout(editor)
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAntialiasing(g2)
            if (isStale()) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
            val x = targetRegion.x + indentPx
            val y = targetRegion.y + MARGIN
            val h = targetRegion.height - MARGIN * 2
            g2.color = BACKGROUND
            g2.fillRoundRect(x, y, l.width, h, ARC, ARC)
            g2.color = accent()
            g2.fillRect(x, y, BAR, h)
            var ty = y + PAD_Y + l.fm.ascent
            val tx = x + BAR + PAD_X
            l.lines.forEachIndexed { i, line ->
                var lx = tx
                if (i == 0) {
                    g2.font = l.bold; g2.color = accent()
                    g2.drawString(label, lx, ty)
                    lx += l.labelWidth
                }
                g2.font = l.font
                g2.color = editor.colorsScheme.defaultForeground
                g2.drawString(line, lx, ty)
                ty += l.fm.height
            }
        } finally {
            g2.dispose()
        }
    }

    private fun accent(): Color = when (type) {
        CommentType.ERROR -> ERROR_COLOR
        CommentType.MEJORA -> MEJORA_COLOR
        CommentType.PREGUNTA -> PREGUNTA_COLOR
        CommentType.BIEN -> BIEN_COLOR
    }

    companion object {
        private val PAD_X get() = JBUI.scale(8)
        private val PAD_Y get() = JBUI.scale(4)
        private val MARGIN get() = JBUI.scale(2)
        private val BAR get() = JBUI.scale(3)
        private val ARC get() = JBUI.scale(8)
        private val BACKGROUND = JBColor(Color(0xEEF2EC), Color(0x2A302C))
        private val ERROR_COLOR = JBColor(Color(0xB0382A), Color(0xEC7C69))
        private val MEJORA_COLOR = JBColor(Color(0x9A6408), Color(0xE3A443))
        private val PREGUNTA_COLOR = JBColor(Color(0x2F6AAE), Color(0x8DB8F2))
        private val BIEN_COLOR = JBColor(Color(0x1C7A49), Color(0x5DBE8A))
    }
}
