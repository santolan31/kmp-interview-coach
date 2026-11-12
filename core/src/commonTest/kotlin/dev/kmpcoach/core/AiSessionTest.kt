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

    // El chat que dio el plan es la fuente de verdad: el plan entero va primero, con lo que lo rodea.
    private val planTurns = ClaudeSessions.parse(sequenceOf(
        user("¿Cómo está organizado el proyecto?", "t1"),
        assistant("El tema vive en core/designsystem; MacoTopBar usa colores de extended.", "t2"),
        user("Arregla el login", "t3"),
        assistant("Listo el login en LoginScreen.", "t4"),
        user("Dame el plan de la campana", "t5"),
        assistant("## Paso 5.1: MacoTopBar acepta null\n[MacoTopBar.kt:1-6](file:///p/MacoTopBar.kt#L1-L6)\n```diff\n-a\n+// TODO 1: b\n```\n## Paso 5.2: el preview", "t6"),
        user("¿Y el paso 5.2 en qué archivo va?", "t7"),
        assistant("También en MacoTopBar, en el preview.", "t8"),
    ))

    @Test
    fun excerptCentersOnThePlanAndKeepsWhatSurroundsIt() {
        val e = ClaudeSessions.excerpt(planTurns, "core/designsystem/src/commonMain/kotlin/MacoTopBar.kt")!!
        assertTrue("el plan completo", e.contains("IA (el plan con los pasos): ## Paso 5.1") && e.contains("## Paso 5.2"))
        assertTrue("el pedido que lo originó", e.contains("Estudiante (pidió el plan): Dame el plan de la campana"))
        assertTrue("lo que se habló después", e.contains("en el preview"))
        assertTrue("lo anterior que menciona el archivo", e.contains("core/designsystem"))
        assertFalse("lo anterior que no tiene que ver", e.contains("login"))
        assertTrue("en orden", e.indexOf("core/designsystem") < e.indexOf("Dame el plan") && e.indexOf("## Paso 5.1") < e.indexOf("en el preview"))
    }

    @Test
    fun excerptWithAPlanStaysWithinTheBudget() {
        val long = planTurns + SessionTurn("assistant", "z".repeat(30_000), "t9")
        val e = ClaudeSessions.excerpt(long, "MacoTopBar.kt", maxChars = 3000)!!
        assertTrue(e.length <= 3000)
        assertTrue("el plan nunca se queda fuera", e.contains("## Paso 5.1"))
    }

    // ---------- el lenguaje del archivo ----------

    @Test
    fun theMentorPresentsItselfForTheFileLanguage() {
        val o = PromptOptions(Level.JUNIOR, "es", 5)
        assertTrue("en Kotlin, como siempre", Prompts.reviewSystem(o.forFile("src/commonMain/kotlin/A.kt")).startsWith("Eres un entrevistador técnico de Android y Kotlin Multiplatform"))
        assertTrue(Prompts.reviewSystem(o.forFile("web/src/app.tsx")).startsWith("Eres un entrevistador técnico de programación en TypeScript"))
        assertTrue(HintPrompts.giveUpSystem(o.forFile("main.py")).contains("```python"))
        assertEquals(CodeLanguage.OTHER, CodeLanguage.of("notas.txt"))
        assertEquals(CodeLanguage.KOTLIN, CodeLanguage.of(null))
    }

    @Test
    fun theFileLineCarriesThePathButForbidsNothing() {
        val line = Prompts.fileLine("core/designsystem/src/commonMain/kotlin/MacoTopBar.kt")
        assertTrue(line.contains("commonMain") && line.contains("(Kotlin)"))
        assertFalse("la ruta es un dato, no una prohibición", line.contains("no uses") || line.contains("Android"))
        assertTrue("el plan manda sobre las APIs", Prompts.sessionContext("plan").contains("librerías de AndroidX que funcionan en código común"))
    }
}
