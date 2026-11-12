package dev.kmpcoach.core

import kotlin.math.ceil

/** Para qué se usó cada llamada a la IA. */
enum class UsageKind(val id: String, val label: String) {
    REVIEW("review", "Revisar mi código"),
    LIVE("live", "Tutor en vivo"),
    CHAT("chat", "Chat"),
    HINT("hint", "Pistas"),
    GIVE_UP("giveup", "Me rindo"),
    EXPLAIN("explain", "Explícame esto"),
    COMPREHENSION("comprehension", "Comprensión de código nuevo"),
    SPACED("spaced", "Repaso"),
    STEP("step", "Comprobar paso");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** Precio del modelo en USD por millón de tokens. */
data class ModelPrice(val inputPerM: Double, val outputPerM: Double) {
    fun cost(inputTokens: Int, outputTokens: Int) = inputTokens * inputPerM / 1e6 + outputTokens * outputPerM / 1e6

    companion object {
        /** Lee los dos precios escritos en Ajustes; null si falta alguno o no es un número. */
        fun parse(input: String?, output: String?): ModelPrice? {
            fun num(s: String?) = s?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it >= 0 }
            return ModelPrice(num(input) ?: return null, num(output) ?: return null)
        }
    }
}

/** Acumulado de llamadas y tokens. [costUsd] suma solo las llamadas cuyo costo se conoce (informado por el proveedor o calculado con tu precio). */
data class UsageStats(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    val pricedCalls: Int = 0,
) {
    operator fun plus(o: UsageStats) = UsageStats(
        calls + o.calls, inputTokens + o.inputTokens, outputTokens + o.outputTokens, costUsd + o.costUsd, pricedCalls + o.pricedCalls,
    )

    val avgInput get() = if (calls == 0) 0L else inputTokens / calls
    val avgOutput get() = if (calls == 0) 0L else outputTokens / calls
    val avgCost get() = if (pricedCalls == 0) null else costUsd / pricedCalls

    fun toMap() = mapOf("calls" to calls, "in" to inputTokens, "out" to outputTokens, "cost" to costUsd, "priced" to pricedCalls)

    companion object {
        fun of(usage: AiUsage, costUsd: Double?) =
            UsageStats(1, usage.inputTokens.toLong(), usage.outputTokens.toLong(), costUsd ?: 0.0, if (costUsd != null) 1 else 0)

        fun fromMap(m: Map<String, Any?>) = UsageStats(
            (m["calls"] as? Number)?.toInt() ?: 0,
            (m["in"] as? Number)?.toLong() ?: 0,
            (m["out"] as? Number)?.toLong() ?: 0,
            (m["cost"] as? Number)?.toDouble() ?: 0.0,
            (m["priced"] as? Number)?.toInt() ?: 0,
        )
    }
}

object UsageMath {
    /**
     * Estimación de tokens antes de llamar. El código Kotlin con sangría ronda los 3,5 caracteres por token;
     * es una aproximación para decidir, el número exacto lo devuelve el proveedor después.
     */
    fun estimateTokens(text: String): Int = ceil(text.length / 3.5).toInt()

    /** Tokens de salida típicos de una revisión (JSON con 3–5 comentarios) y de una respuesta de chat. */
    const val TYPICAL_REVIEW_OUTPUT = 450
    const val TYPICAL_CHAT_OUTPUT = 220

    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> fixed(n / 1e6, 1) + "M"
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> fixed(n / 1e3, 1) + "k"
        else -> n.toString()
    }

    fun usd(v: Double): String = when {
        v == 0.0 -> "$0"
        v < 0.0001 -> "< $0.0001"
        v < 0.01 -> "$" + fixed(v, 4)
        v < 1 -> "$" + fixed(v, 3)
        else -> "$" + fixed(v, 2)
    }
}
