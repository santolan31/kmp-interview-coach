package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSessionTest {

    private fun user(text: String, at: String) =
        """{"type":"user","timestamp":"$at","message":{"role":"user","content":${Json.write(text)}}}"""

    private fun assistant(text: String, at: String) =
        """{"type":"assistant","timestamp":"$at","message":{"role":"assistant","content":[{"type":"text","text":${Json.write(text)}},{"type":"tool_use","id":"x","name":"Read","input":{}}]}}"""

    private val toolResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"x","content":"..."}]}}"""

    @Test
    fun projectFolderReplacesEverySymbol() {
        assertEquals("-Users-santi-Documents-maco-application", ClaudeSessions.projectDirName("/Users/santi/Documents/maco_application"))
    }

    @Test
    fun parseKeepsOnlyTextAndStripsIdeTags() {
        val lines = sequenceOf(
            "ea\":\"línea cortada al leer solo el final\"}",
            user("<ide-context>archivo abierto</ide-context>\nDame el esqueleto del paso 5", "t1"),
            toolResult,
            assistant("Aquí va el esqueleto del paso 5.", "t2"),
            """{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"subagente"}]}}""",
        )
        val turns = ClaudeSessions.parse(lines)
        assertEquals(listOf("user", "assistant"), turns.map { it.role })
        assertEquals("Dame el esqueleto del paso 5", turns[0].text)
    }

    @Test
    fun excerptIsTheLastRequestAndWhatFollowed() {
        val turns = ClaudeSessions.parse(sequenceOf(
            user("Arregla el login", "t1"),
            assistant("Listo el login en LoginScreen.", "t2"),
            user("Dame el paso 5", "t3"),
            assistant("Paso 5.1: MacoTopBar acepta un callback nulo", "t4"),
        ))
        val e = ClaudeSessions.excerpt(turns, "MacoTopBar.kt")!!
        assertTrue(e.startsWith("Estudiante: Dame el paso 5"))
        assertTrue(e.contains("callback nulo"))
        assertFalse(e.contains("login"))
        assertEquals("t4", ClaudeSessions.lastMention(turns, "MacoTopBar.kt"))
        assertNull(ClaudeSessions.lastMention(turns, "HomeScreen.kt"))
    }

    @Test
    fun excerptFallsBackToTheExchangeAboutTheOpenFile() {
        val turns = ClaudeSessions.parse(sequenceOf(
            user("Paso 5", "t1"),
            assistant("Cambia MacoTopBar para aceptar null", "t2"),
            user("gracias", "t3"),
            assistant("De nada", "t4"),
        ))
        val e = ClaudeSessions.excerpt(turns, "MacoTopBar.kt")!!
        assertTrue(e.contains("aceptar null"))
        assertFalse(e.contains("De nada"))
    }

    @Test
    fun excerptIsClipped() {
        val turns = ClaudeSessions.parse(sequenceOf(user("hola", "t1"), assistant("x".repeat(20_000), "t2")))
        assertTrue(ClaudeSessions.excerpt(turns, null, maxChars = 1000)!!.length <= 1000)
    }
}
