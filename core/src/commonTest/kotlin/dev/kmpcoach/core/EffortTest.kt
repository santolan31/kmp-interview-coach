package dev.kmpcoach.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffortTest {

    /** Transporte falso: guarda los cuerpos enviados y responde con lo que diga [reply] según el cuerpo. */
    private class FakeHttp(private val reply: (String?) -> HttpResult) : HttpTransport {
        val bodies = mutableListOf<String?>()
        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?, timeoutSeconds: Long): HttpResult {
            bodies += body
            return reply(body)
        }
    }

    private val anthropicOk = HttpResult(200, """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"Hola"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":40}}""", emptyMap())
    private val openAiOk = HttpResult(200, """{"choices":[{"message":{"content":"Hola"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4}}""", emptyMap())

    private fun cfg(p: ProviderPreset, model: String, effort: String = "") = AiConfig(p, p.baseUrl, model, "k", effort = effort)
    private fun body(http: FakeHttp, i: Int = 0) = Json.parse(http.bodies[i]!!).asMap()
    private val ask = listOf(ChatMessage("user", "u"))

    @Test
    fun onlyKnownLevelsAreKept() {
        assertEquals("high", Effort.normalize(" HIGH "))
        assertEquals("", Effort.normalize("turbo"))
        assertEquals("", Effort.normalize(null))
    }

    @Test
    fun everyProviderThatHasEffortCanUseIt() {
        listOf(ProviderPreset.ANTHROPIC, ProviderPreset.CLAUDE_CLI, ProviderPreset.OPENAI, ProviderPreset.GEMINI,
            ProviderPreset.GROQ, ProviderPreset.OPENROUTER, ProviderPreset.OLLAMA, ProviderPreset.CUSTOM)
            .forEach { assertTrue(Effort.supported(it), "${it.id} acepta esfuerzo") }
        // Cursor no tiene la opción y a Copilot lo llama VS Code.
        assertFalse(Effort.supported(ProviderPreset.CURSOR_CLI))
        assertFalse(Effort.supported(ProviderPreset.COPILOT_EDITOR))
    }

    @Test
    fun aLevelThisProviderDoesNotHaveIsNotSent() {
        // «minimal» existe en OpenAI y Gemini, no en Anthropic: si lo elegiste con otro proveedor, aquí no se envía.
        assertEquals("", Effort.forPreset(ProviderPreset.ANTHROPIC, "minimal"))
        assertEquals("minimal", Effort.forPreset(ProviderPreset.GEMINI, "minimal"))
        assertEquals("", Effort.forPreset(ProviderPreset.GEMINI, "max"))
        assertEquals("none", Effort.forPreset(ProviderPreset.OPENAI, "none"))
    }

    @Test
    fun opus55DefaultsToMediumAndOthersAreUnknown() {
        assertEquals("medium", Effort.defaultFor(ProviderPreset.ANTHROPIC, "claude-opus-5-5"))
        assertEquals("medium", Effort.defaultFor(ProviderPreset.ANTHROPIC, "claude-haiku-5-5"))
        assertNull(Effort.defaultFor(ProviderPreset.ANTHROPIC, "claude-sonnet-5-5"))
        assertNull(Effort.defaultFor(ProviderPreset.CLAUDE_CLI, "opus"))
        assertNull(Effort.defaultFor(ProviderPreset.OPENAI, "gpt-6-luna"))
    }

    @Test
    fun labelSaysWhatIsUsed() {
        assertEquals("esfuerzo: high", Effort.label("high", "medium"))
        assertEquals("esfuerzo: medium (predeterminado)", Effort.label("", "medium"))
        assertEquals("esfuerzo: predeterminado", Effort.label("", null))
        assertEquals("esfuerzo: none no aplica en este modelo", Effort.label("none", null, rejected = true))
    }

    @Test
    fun moreEffortLeavesMoreRoomToThink() {
        val rooms = Effort.ALL.map(Effort::thinkingRoom)
        assertEquals(rooms.sorted(), rooms)
        assertEquals(Effort.thinkingRoom("medium"), Effort.thinkingRoom(""))
    }

    @Test
    fun anthropicWithoutAChoiceSendsNoEffortButRoomToThink() = runTest {
        val http = FakeHttp { anthropicOk }
        val reply = AiClients.create(cfg(ProviderPreset.ANTHROPIC, "claude-opus-5-5"), http).complete("s", ask, 500)
        assertEquals("Hola", reply.text, "el bloque de razonamiento no se mezcla con la respuesta")
        assertNull(body(http)["output_config"])
        assertEquals(500 + Effort.thinkingRoom(""), body(http).int("max_tokens"))
    }

    @Test
    fun anthropicGetsOutputConfig() = runTest {
        val http = FakeHttp { anthropicOk }
        AiClients.create(cfg(ProviderPreset.ANTHROPIC, "claude-opus-5-5", "high"), http).complete("s", ask, 500)
        assertEquals("high", body(http)["output_config"].asMap().str("effort"))
        assertEquals(500 + 8_000, body(http).int("max_tokens"))
    }

    @Test
    fun openAiGeminiAndGroqGetReasoningEffort() = runTest {
        for (p in listOf(ProviderPreset.OPENAI, ProviderPreset.GEMINI, ProviderPreset.GROQ, ProviderPreset.OLLAMA)) {
            val http = FakeHttp { openAiOk }
            AiClients.create(cfg(p, "m", "low"), http).complete("s", ask, 500)
            assertEquals("low", body(http).str("reasoning_effort"), p.id)
            assertNull(body(http)["reasoning"], p.id)
        }
    }

    @Test
    fun openRouterGetsReasoningObject() = runTest {
        val http = FakeHttp { openAiOk }
        AiClients.create(cfg(ProviderPreset.OPENROUTER, "x/y", "xhigh"), http).complete("s", ask, 500)
        assertEquals("xhigh", body(http)["reasoning"].asMap().str("effort"))
        assertNull(body(http)["reasoning_effort"])
    }

    @Test
    fun withoutAChoiceNothingIsSentToOpenAiCompatibles() = runTest {
        val http = FakeHttp { openAiOk }
        AiClients.create(cfg(ProviderPreset.OPENAI, "m"), http).complete("s", ask, 500)
        assertNull(body(http)["reasoning_effort"])
    }

    @Test
    fun aModelThatRejectsTheEffortStillAnswersAndIsRemembered() = runTest {
        val rejects = HttpResult(400, """{"error":{"message":"Unsupported value: 'reasoning_effort' does not support 'none' with this model."}}""", emptyMap())
        val http = FakeHttp { b -> if (b!!.contains("reasoning_effort")) rejects else openAiOk }
        val reply = AiClients.create(cfg(ProviderPreset.OPENAI, "gpt-rechaza", "none"), http).complete("s", ask, 500)
        assertEquals("Hola", reply.text)
        assertEquals(2, http.bodies.size, "repite la pregunta una vez, sin esfuerzo")
        assertNull(body(http, 1)["reasoning_effort"])
        assertTrue(Effort.isRejected(ProviderPreset.OPENAI, "gpt-rechaza", "none"))
        Effort.clearRejected(ProviderPreset.OPENAI, "gpt-rechaza")
        assertFalse(Effort.isRejected(ProviderPreset.OPENAI, "gpt-rechaza", "none"))
    }

    @Test
    fun otherErrorsAreNotTreatedAsARejectedEffort() = runTest {
        val badKey = HttpResult(401, """{"error":{"message":"invalid x-api-key"}}""", emptyMap())
        val http = FakeHttp { badKey }
        val error = runCatching { AiClients.create(cfg(ProviderPreset.ANTHROPIC, "claude-opus-5-5", "high"), http).complete("s", ask, 500) }
        assertTrue(error.exceptionOrNull() is AiException)
        assertEquals(1, http.bodies.size, "no repite: el problema es la clave, no el esfuerzo")
    }

    @Test
    fun openRouterSaysWhichLevelsEachModelAccepts() {
        val models = listOf(
            mapOf("id" to "a/razona", "reasoning" to mapOf("supported_efforts" to listOf("high", "medium", "low"))),
            mapOf("id" to "b/todos", "reasoning" to mapOf("supported_efforts" to null)),
            mapOf("id" to "c/parametro", "supported_parameters" to listOf("tools", "reasoning")),
            mapOf("id" to "d/no-razona", "supported_parameters" to listOf("tools")),
        )
        assertEquals(listOf("low", "medium", "high"), Effort.openRouterLevels(models, "a/razona"))
        assertEquals(Effort.ALL, Effort.openRouterLevels(models, "b/todos"))
        assertEquals(Effort.ALL, Effort.openRouterLevels(models, "c/parametro"))
        assertEquals(emptyList(), Effort.openRouterLevels(models, "d/no-razona"))
        assertNull(Effort.openRouterLevels(models, "e/desconocido"))
    }

    @Test
    fun claudeCodeGetsTheFlagAndCursorDoesNot() {
        val with = CliProtocol.call(CliTool.CLAUDE, "claude", "opus", "s", ask, "xhigh").args
        assertEquals("xhigh", with[with.indexOf("--effort") + 1])
        assertFalse("--effort" in CliProtocol.call(CliTool.CLAUDE, "claude", "opus", "s", ask).args)
        assertFalse("--effort" in CliProtocol.call(CliTool.CURSOR, "cursor-agent", "", "s", ask, "high").args)
    }
}
