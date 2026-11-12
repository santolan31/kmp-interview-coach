package dev.kmpcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conversaciones de Antigravity y de GitHub Copilot, con el formato real de sus archivos (noviembre de 2026). */
class ConversationsTest {

    private val antigravity = listOf(
        """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-05-21T16:22:22Z","content":"<USER_REQUEST>\nDame el plan del carrusel de promociones\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nActive Document: /Users/s/Documents/maco/home/src/PromoSeeder.kt (LANGUAGE_KOTLIN)\n</ADDITIONAL_METADATA>"}""",
        """{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-05-21T16:22:23Z","thinking":"pienso…","tool_calls":[{"name":"view_file","args":{}}]}""",
        """{"step_index":2,"source":"MODEL","type":"VIEW_FILE","status":"DONE","created_at":"2026-05-21T16:22:24Z","content":"contenido de un archivo"}""",
        """{"step_index":3,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-05-21T16:22:30Z","content":"## Paso 1: el carrusel lee las promociones\nUsa PromoRepository."}""",
        """{"step_index":4,"source":"SYSTEM","type":"EPHEMERAL_MESSAGE","status":"DONE","created_at":"2026-05-21T16:22:31Z","content":"aviso interno"}""",
        "línea cortada {\"type\":",
    )

    @Test
    fun antigravityKeepsYourRequestAndGeminisAnswersOnly() {
        val turns = AntigravityTranscripts.parse(antigravity.asSequence())
        assertEquals(listOf("user", "assistant"), turns.map { it.role })
        assertEquals("Dame el plan del carrusel de promociones", turns[0].text)
        assertTrue(turns[1].text.startsWith("## Paso 1"))
        assertEquals("2026-05-21T16:22:30Z", turns[1].at)
        assertTrue(StepParser.hasSteps(turns[1].text), "la guía de pasos lo entiende igual que a Claude")
    }

    @Test
    fun antigravityBelongsToTheProjectItMentions() {
        val text = antigravity.joinToString("\n")
        assertTrue(AntigravityTranscripts.belongsTo(text, "/Users/s/Documents/maco"))
        assertFalse(AntigravityTranscripts.belongsTo(text, "/Users/s/Documents/maco_application"), "no confunde maco con maco_application")
    }

    private val copilot = """
        {"version":3,"requesterUsername":"santolan31","responderUsername":"GitHub Copilot","requests":[
          {"requestId":"r1","message":{"text":"Dame los pasos para el login"},"timestamp":1785876946664,
           "response":[{"value":"## Paso 1: el formulario\n"},{"kind":"codeblockUri","uri":{"path":"/x"}},{"value":"Valida el correo."}]},
          {"requestId":"r2","message":{"text":"gracias"},"timestamp":1785876950000,"response":[]}
        ]}
    """.trimIndent()

    @Test
    fun copilotKeepsTheTextOfEachRequestAndAnswer() {
        val turns = CopilotSessions.parse(copilot)
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.role })
        assertEquals("## Paso 1: el formulario\nValida el correo.", turns[1].text)
        assertEquals("2026-08-04T20:55:46.664Z", turns[0].at)
        assertEquals(emptyList(), CopilotSessions.parse("no es json"))
    }

    @Test
    fun copilotFolderComesFromWorkspaceJson() {
        assertEquals("/Users/s/Mis Proyectos/Fit Buddy", CopilotSessions.folderOf("""{"folder":"file:///Users/s/Mis%20Proyectos/Fit%20Buddy/"}"""))
        assertNull(CopilotSessions.folderOf("""{"workspace":"file:///x.code-workspace"}"""))
    }

    @Test
    fun eachSourceHasItsOwnIcon() {
        assertEquals(SourceKind.ANTIGRAVITY, SourceKind.ofPath("/Users/s/.gemini/antigravity-ide/brain/1/.system_generated/logs/transcript.jsonl"))
        assertEquals(SourceKind.COPILOT, SourceKind.ofPath("/Users/s/Library/Application Support/Code/User/workspaceStorage/ab/chatSessions/1.json"))
        assertEquals(SourceKind.CLAUDE_CODE, SourceKind.ofPath("/Users/s/.claude/projects/p/s.jsonl"))
        assertEquals(SourceKind.PASTED, SourceKind.ofPath(SourceKind.PASTED_PATH))
        assertEquals(Brand.COPILOT, Brand.ofPlanFile("/x/chatSessions/1.json"))
    }

    @Test
    fun withoutAPlanTheMentorSaysExactlyWhatToDo() {
        val o = PromptOptions(Level.JUNIOR, "es", 5)
        val with = Prompts.chatSystem(o, null, "fun a() {}", null, "A.kt", null, null, noPlan = true)
        assertTrue("«Pegar el plan»" in with && "«Elegir conversación»" in with && "GitHub Copilot" in with)
        val withSession = Prompts.chatSystem(o, null, "fun a() {}", null, "A.kt", "el plan", null, noPlan = true)
        assertFalse("«Pegar el plan»" in withSession, "si hay plan, no se menciona")
    }
}
