package dev.kmpcoach.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** El proveedor por suscripción, con respuestas reales capturadas de `agy` y `claude` (12 nov 2026). */
class CliClientTest {

    /** Ejecutor falso: guarda lo que se le pidió y devuelve una salida fija. */
    private class FakeRunner(private val result: ProcessResult, private val path: String? = "/bin/tool") : ProcessRunner {
        var command: List<String> = emptyList()
        var stdin = ""
        var files: Map<String, String> = emptyMap()
        override suspend fun run(command: List<String>, stdin: String, files: Map<String, String>, timeoutSeconds: Long): ProcessResult {
            this.command = command; this.stdin = stdin; this.files = files
            return result
        }
        override fun find(tool: CliTool, configuredPath: String?) = path
    }

    private fun cfg(p: ProviderPreset, model: String = "") = AiConfig(p, "", model, null)

    private val claudeOk = """{"type":"result","subtype":"success","is_error":false,"stop_reason":"end_turn","result":"PIÑA","usage":{"input_tokens":2,"cache_creation_input_tokens":16670,"cache_read_input_tokens":7016,"output_tokens":4}}"""

    @Test
    fun antigravitySubscriptionWasRemovedAndMovesToGeminiWithAKey() {
        // Sus términos prohíben envolver `agy` en otras aplicaciones: quien lo tenía guardado pasa a Gemini con clave de API.
        assertTrue(ProviderPreset.entries.none { it.id == "antigravity-cli" })
        assertEquals(ProviderPreset.GEMINI, ProviderPreset.of("antigravity-cli"))
        assertEquals(ProviderPreset.ANTHROPIC, ProviderPreset.of("desconocido"))
    }

    @Test
    fun claudeGetsTheInstructionsInAFileAndNoTools() = runTest {
        val runner = FakeRunner(ProcessResult(0, claudeOk, "Warning: no stdin data received"))
        val reply = AiClients.create(cfg(ProviderPreset.CLAUDE_CLI, "haiku"), processes = runner)
            .complete("Eres un mentor.", listOf(ChatMessage("user", "Hola")), 500)
        assertEquals("PIÑA", reply.text)
        assertEquals(AiUsage(2 + 16670 + 7016, 4), reply.usage)
        assertFalse(reply.truncated)
        assertEquals("Eres un mentor.", runner.files[CliProtocol.SYSTEM_FILE])
        assertTrue("--system-prompt-file" in runner.command && "--no-session-persistence" in runner.command)
        val disallowed = runner.command[runner.command.indexOf("--disallowed-tools") + 1]
        assertTrue(listOf("Bash", "Edit", "Write", "Read").all { it in disallowed }, "sin herramientas: solo conversa")
        assertEquals("Hola", runner.stdin)
    }

    @Test
    fun aConversationBecomesOneMessageWithRoles() {
        val t = CliProtocol.transcript(listOf(ChatMessage("user", "¿Por qué?"), ChatMessage("assistant", "Piensa en null."), ChatMessage("user", "Ah, ya")))
        assertTrue(t.indexOf("ESTUDIANTE:") < t.indexOf("MENTOR (tú):") && t.trim().endsWith("Ah, ya"))
    }

    @Test
    fun notLoggedInSaysWhatToRun() = runTest {
        val runner = FakeRunner(ProcessResult(1, "", "Error: authentication required. Please log in."))
        val e = assertFailsWith<AiException> {
            AiClients.create(cfg(ProviderPreset.CLAUDE_CLI), processes = runner).complete("s", listOf(ChatMessage("user", "u")), 10)
        }
        assertTrue("ejecuta `claude` una vez" in e.message!!, e.message)
    }

    @Test
    fun anErrorStatusIsReportedWithItsMessage() = runTest {
        val out = """{"type":"result","subtype":"error","is_error":true,"result":"quota exceeded for today"}"""
        val e = assertFailsWith<AiException> {
            AiClients.create(cfg(ProviderPreset.CLAUDE_CLI), processes = FakeRunner(ProcessResult(1, out, ""))).complete("s", listOf(ChatMessage("user", "u")), 10)
        }
        assertTrue("quota exceeded" in e.message!!)
    }

    @Test
    fun missingProgramExplainsHowToInstall() = runTest {
        val e = assertFailsWith<AiException> {
            AiClients.create(cfg(ProviderPreset.CLAUDE_CLI), processes = FakeRunner(ProcessResult(0, "", ""), path = null))
                .complete("s", listOf(ChatMessage("user", "u")), 10)
        }
        assertTrue("No encontré `claude`" in e.message!!)
    }

    @Test
    fun claudeOffersItsModelAliases() {
        assertEquals(listOf("haiku", "sonnet", "opus"), CliProtocol.models(CliTool.CLAUDE))
    }

    @Test
    fun subscriptionsNeedNoKeyUrlOrModel() {
        listOf(ProviderPreset.CLAUDE_CLI, ProviderPreset.CURSOR_CLI).forEach { p ->
            assertEquals(null, AiClients.validate(cfg(p)))
            assertTrue(p.isSubscription)
        }
        assertFalse(ProviderPreset.ANTHROPIC.isSubscription)
    }

    @Test
    fun copilotIsASubscriptionThatOnlyTheEditorCalls() = runTest {
        val p = ProviderPreset.COPILOT_EDITOR
        assertTrue(p.isSubscription && p.isEditorOnly && !p.needsKey)
        assertEquals(null, AiClients.validate(cfg(p)))
        assertEquals(Brand.COPILOT, Brand.ofProvider(p.id))
        assertEquals(p, ProviderPreset.of("copilot-vscode"))
        assertTrue(ProviderPreset.entries.filter { it.isEditorOnly } == listOf(p), "Android Studio oculta solo este")
        // Si por error llega al core (Android Studio), se explica en vez de intentar una llamada que no puede hacer.
        val e = assertFailsWith<AiException> { AiClients.create(cfg(p)).complete("s", listOf(ChatMessage("user", "u")), 10) }
        assertTrue("solo funciona dentro de VS Code" in e.message!!, e.message)
    }
}
