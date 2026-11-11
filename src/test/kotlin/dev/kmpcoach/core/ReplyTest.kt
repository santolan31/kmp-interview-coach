package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTest {
    @Test
    fun replyQuotesTheCommentAndItsLine() {
        val msg = Prompts.replyToComment(12, CommentType.PREGUNTA, "¿Por qué usas `var`  aquí?", "  Porque cambia en el bucle. ")
        assertEquals("Respondo a tu comentario de la línea 12 (Pregunta: «¿Por qué usas var aquí?»):\nPorque cambia en el bucle.", msg)
    }

    @Test
    fun replyWithoutLineStillNamesTheReview() {
        val msg = Prompts.replyToComment(null, CommentType.MEJORA, "Extrae esta función", "No veo por qué")
        assertTrue(msg.startsWith("Respondo a tu comentario de la revisión (Mejora: «Extrae esta función»):"))
    }

    @Test
    fun chatPromptKnowsHowToEvaluateAReply() {
        val system = Prompts.chatSystem(PromptOptions(Level.JUNIOR, "es", 5), null, "", null, null)
        assertTrue(system.contains("Respondo a tu comentario"))
    }
}
