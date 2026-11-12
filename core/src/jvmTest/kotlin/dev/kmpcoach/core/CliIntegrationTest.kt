package dev.kmpcoach.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Llama de verdad a los programas instalados (gasta un poco de la cuota de la suscripción). Solo corre con
 * `MENTOR_CLI_IT=1`; si un programa no está instalado, lo salta.
 */
class CliIntegrationTest {
    private val enabled = System.getenv("MENTOR_CLI_IT") == "1"

    private fun check(preset: ProviderPreset, model: String) = runTest(timeout = 4.minutes) {
        if (!enabled) return@runTest
        if (JvmProcessRunner.find(preset.cli!!, null) == null) return@runTest println("${preset.cli} no está instalado: salto")
        val client = AiClients.create(AiConfig(preset, "", model, null))
        val reply = client.complete(
            "Eres un mentor de programación. Responde solo con JSON.",
            listOf(ChatMessage("user", """Responde exactamente: {"ok": true, "palabra": "piña"}""")),
            200,
        )
        println("${preset.id}: ${reply.text} · ${reply.usage}")
        assertTrue(Json.extract(reply.text).asMap()["ok"] == true, reply.text)
    }

    @Test fun claude() = check(ProviderPreset.CLAUDE_CLI, "haiku")
}
