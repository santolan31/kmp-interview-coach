package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Respuestas cortadas por el máximo de tokens: lo que hacía fallar «¿Cumple el paso?» con modelos que escriben más. */
class TruncationTest {

    /** Cliente falso: la primera respuesta llega cortada y la segunda completa. Guarda el máximo pedido en cada llamada. */
    private class FakeClient(private val replies: List<AiReply>) : AiClient {
        val asked = ArrayList<Int>()
        override suspend fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): AiReply {
            asked += maxTokens
            return replies[asked.size - 1]
        }
        override suspend fun listModels(): List<String> = emptyList()
    }

    @Test
    fun aCutReplyIsAskedAgainWithMoreRoom() = runTest {
        val cut = AiReply("{\"cumple\": false, \"faltan\": [{\"que\": \"la dirección lar", AiUsage(100, 1000, 0.01), null, truncated = true)
        val full = AiReply("{\"cumple\": true, \"faltan\": []}", AiUsage(100, 20, 0.002), null)
        val client = FakeClient(listOf(cut, full))
        val reply = client.completeFull("s", listOf(ChatMessage("user", "u")), 1000)
        assertEquals(listOf(1000, 2000), client.asked)
        assertEquals(full.text, reply.text)
        assertEquals("el consumo suma las dos llamadas", AiUsage(200, 1020, 0.012), reply.usage)
    }

    @Test
    fun aCompleteReplyIsNotAskedTwice() = runTest {
        val client = FakeClient(listOf(AiReply("ok", null, null)))
        client.completeFull("s", listOf(ChatMessage("user", "u")), 500)
        assertEquals(listOf(500), client.asked)
    }

    @Test
    fun aCutVerdictKeepsWhatArrivedComplete() {
        val text = """{"cumple": false, "faltan": [{"archivo": "MacoTopBar.kt", "linea": 110, "que": "una dirección larga en una de las dos barras"}, {"archivo": "MacoTopBar.kt", "linea": 1"""
        val v = StepPrompts.parseVerdict(text)
        assertFalse(v.ok)
        assertEquals(listOf(StepGap("MacoTopBar.kt", 110, "una dirección larga en una de las dos barras")), v.gaps)
    }

    @Test
    fun withoutCumpleItStillFails() {
        val failed = runCatching { StepPrompts.parseVerdict("{\"faltan\": [{\"que\": \"algo") }
        assertTrue(failed.exceptionOrNull() is JsonException)
    }

    @Test
    fun aCodeFragmentThatChangedResolvesTheComment() {
        // Tu caso: el comentario decía que la barra «recibe {}» y ya le pasaste null.
        val msg = "Esta barra debería ser el caso deshabilitado, pero recibe {} igual que la primera."
        val before = listOf("            onAddressClick = {},", "            onNotificationClick = {},", "            modifier = Modifier")
        val after = listOf(before[0], "            onNotificationClick = null,", before[2])
        assertTrue(StaleComments.outdated(msg, before, after))
        assertFalse(StaleComments.outdated(msg, before, before))
    }
}
