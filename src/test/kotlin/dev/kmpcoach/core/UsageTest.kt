package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    @Test
    fun anthropicUsageCountsCacheAsInput() {
        val root = Json.parse("""{"usage":{"input_tokens":1200,"cache_read_input_tokens":300,"output_tokens":410}}""").asMap()
        assertEquals(AiUsage(1500, 410), AiClients.anthropicUsage(root))
    }

    @Test
    fun openAiUsageReadsOpenRouterCost() {
        val root = Json.parse("""{"usage":{"prompt_tokens":900,"completion_tokens":200,"cost":0.00042}}""").asMap()
        assertEquals(AiUsage(900, 200, 0.00042), AiClients.openAiUsage(root))
        assertNull(AiClients.openAiUsage(emptyMap()))
    }

    @Test
    fun rateLimitHeaders() {
        val anthropic = AiClients.rateLimits(mapOf(
            "anthropic-ratelimit-requests-remaining" to listOf("49"),
            "anthropic-ratelimit-requests-limit" to listOf("50"),
            "Anthropic-Ratelimit-Tokens-Remaining" to listOf("39000"),
            "anthropic-ratelimit-tokens-limit" to listOf("40000"),
        ))
        assertEquals(RateLimits(49, 50, 39000, 40000, null), anthropic)
        val groq = AiClients.rateLimits(mapOf("x-ratelimit-remaining-tokens" to listOf("5900"), "x-ratelimit-reset-tokens" to listOf("1.2s")))
        assertEquals(RateLimits(tokensRemaining = 5900, tokensReset = "1.2s"), groq)
        assertNull(AiClients.rateLimits(mapOf("content-type" to listOf("application/json"))))
    }

    @Test
    fun priceAndStats() {
        val price = ModelPrice.parse("1", "5,0")!!
        assertEquals(0.003, price.cost(1000, 400), 1e-9)
        assertNull(ModelPrice.parse("", "5"))
        val s = UsageStats.of(AiUsage(1000, 400), 0.003) + UsageStats.of(AiUsage(3000, 600), null)
        assertEquals(2, s.calls)
        assertEquals(1, s.pricedCalls)
        assertEquals(2000L, s.avgInput)
        assertEquals(s, UsageStats.fromMap(Json.parse(Json.write(s.toMap())).asMap()))
        assertEquals("$0.0030", UsageMath.usd(0.003))
        assertEquals("2.5k", UsageMath.tokens(2500))
    }
}
