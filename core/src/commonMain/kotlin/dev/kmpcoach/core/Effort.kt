package dev.kmpcoach.core

import kotlin.concurrent.Volatile

/**
 * Esfuerzo: cuánto razona el modelo antes de responder. Más esfuerzo da mejores respuestas en problemas difíciles, pero
 * tarda más y cuesta más (el razonamiento se cobra como tokens de salida).
 *
 * Cada proveedor lo recibe a su manera ([wireOf]) y acepta niveles distintos ([levels]). Vacío = no se envía y el modelo
 * usa su valor por defecto. Solo OpenRouter dice de antemano qué niveles acepta cada modelo; con los demás, si el modelo
 * lo rechaza, el cliente repite la pregunta sin esfuerzo y lo anota ([markRejected]) para avisarte en el panel.
 */
object Effort {

    /** Todos los niveles que existen en algún proveedor, del más rápido y barato al más lento y caro. */
    val ALL = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    /** Cómo viaja el esfuerzo en la petición. */
    enum class Wire {
        /** API de Anthropic: `output_config: {effort}`. */
        ANTHROPIC,
        /** OpenAI, Gemini, Groq, Ollama y compatibles: `reasoning_effort` arriba del todo. */
        REASONING_EFFORT,
        /** OpenRouter: `reasoning: {effort}`. */
        OPENROUTER,
        /** Claude Code: `claude --effort`. */
        CLI_FLAG,
    }

    /** Null si el proveedor no permite elegir esfuerzo (Cursor no tiene la opción; Copilot lo decide VS Code). */
    fun wireOf(preset: ProviderPreset): Wire? = when (preset) {
        ProviderPreset.ANTHROPIC -> Wire.ANTHROPIC
        ProviderPreset.CLAUDE_CLI -> Wire.CLI_FLAG
        ProviderPreset.OPENROUTER -> Wire.OPENROUTER
        ProviderPreset.OPENAI, ProviderPreset.GEMINI, ProviderPreset.GROQ, ProviderPreset.OLLAMA, ProviderPreset.CUSTOM -> Wire.REASONING_EFFORT
        ProviderPreset.CURSOR_CLI, ProviderPreset.COPILOT_EDITOR -> null
    }

    fun supported(preset: ProviderPreset): Boolean = wireOf(preset) != null

    /**
     * Niveles que documenta cada proveedor. Cada modelo puede aceptar solo una parte: por ejemplo, en OpenAI algunos no
     * aceptan `none`; en Gemini `xhigh` solo existe en algunos modelos; en Ollama depende del modelo (low/medium/high).
     */
    fun levels(preset: ProviderPreset): List<String> = when (preset) {
        ProviderPreset.ANTHROPIC, ProviderPreset.CLAUDE_CLI -> listOf("low", "medium", "high", "xhigh", "max")
        ProviderPreset.GEMINI -> listOf("minimal", "low", "medium", "high", "xhigh")
        ProviderPreset.OLLAMA -> listOf("low", "medium", "high")
        ProviderPreset.OPENAI, ProviderPreset.GROQ, ProviderPreset.OPENROUTER, ProviderPreset.CUSTOM -> ALL
        ProviderPreset.CURSOR_CLI, ProviderPreset.COPILOT_EDITOR -> emptyList()
    }

    /** Un nivel válido en minúsculas, o vacío si no lo es (vacío = el del modelo). */
    fun normalize(raw: String?): String = raw?.trim()?.lowercase()?.takeIf { it in ALL }.orEmpty()

    /** El nivel guardado, si este proveedor lo acepta; si no (lo elegiste con otro proveedor), vacío. */
    fun forPreset(preset: ProviderPreset, raw: String?): String = normalize(raw).takeIf { it in levels(preset) }.orEmpty()

    /**
     * Nivel que usa el modelo cuando no se envía, si se conoce: medium en Claude Opus 5.5 y Claude Haiku 5.5.
     * Null en los demás: cambia de un modelo a otro, y en Claude Code depende de su propia configuración.
     */
    fun defaultFor(preset: ProviderPreset, model: String): String? {
        if (preset != ProviderPreset.ANTHROPIC) return null
        val m = model.trim().lowercase()
        return if (m.startsWith("claude-opus-5-5") || m.startsWith("claude-haiku-5-5")) "medium" else null
    }

    /**
     * Tokens que se suman a `max_tokens` en la API de Anthropic: allí el razonamiento cuenta dentro de ese tope, y sin este
     * margen el modelo se queda sin espacio para responder. Es solo un tope: se paga lo que el modelo genera, no el margen.
     * Vacío cuenta como medium, el valor por defecto de Claude Opus 5.5.
     */
    fun thinkingRoom(effort: String): Int = when (normalize(effort)) {
        "none", "minimal", "low" -> 2_000
        "", "medium" -> 4_000
        "high" -> 8_000
        else -> 16_000
    }

    /** Texto corto para el panel: «esfuerzo: high», «esfuerzo: medium (predeterminado)» o el aviso de que no aplicó. */
    fun label(effort: String, default: String?, rejected: Boolean = false): String {
        val e = normalize(effort)
        return when {
            e.isNotEmpty() && rejected -> "esfuerzo: $e no aplica en este modelo"
            e.isNotEmpty() -> "esfuerzo: $e"
            default != null -> "esfuerzo: $default (predeterminado)"
            else -> "esfuerzo: predeterminado"
        }
    }

    /** Para qué sirve cada nivel, en la lista para elegirlo. */
    fun describe(level: String): String = when (level) {
        "none" -> "sin razonar; lo más rápido"
        "minimal" -> "razona lo mínimo"
        "low" -> "rápido y barato; para dudas simples"
        "medium" -> "equilibrado"
        "high" -> "razona más; para revisar código difícil"
        "xhigh" -> "razona mucho más; más lento"
        "max" -> "el más lento y caro; rara vez hace falta"
        else -> ""
    }

    /**
     * Niveles que OpenRouter dice que acepta [model], leyendo un elemento de su `GET /models`:
     * - `reasoning.supported_efforts` con una lista: esos niveles (los que el Mentor conoce).
     * - `reasoning` sin `supported_efforts` (null): todos.
     * - Sin `reasoning` pero con "reasoning" en `supported_parameters`: todos.
     * - Nada de eso: lista vacía (el modelo no razona). Null si el modelo no está en la lista (no se sabe).
     */
    fun openRouterLevels(models: List<Map<String, Any?>>, model: String): List<String>? {
        val entry = models.firstOrNull { it["id"] == model } ?: return null
        val reasoning = entry["reasoning"] as? Map<*, *>
        if (reasoning != null) {
            val efforts = reasoning["supported_efforts"] as? List<*> ?: return ALL
            return ALL.filter { level -> efforts.any { it.toString().lowercase() == level } }
        }
        val params = (entry["supported_parameters"] as? List<*>).orEmpty().map { it.toString() }
        return if ("reasoning" in params || "reasoning_effort" in params) ALL else emptyList()
    }

    // ---------- rechazos ----------

    /** Modelos que rechazaron un nivel en esta sesión del IDE: «proveedor/modelo» → nivel rechazado. */
    @Volatile private var rejected: Map<String, String> = emptyMap()

    private fun key(preset: ProviderPreset, model: String) = "${preset.id}/${model.trim()}"

    fun markRejected(preset: ProviderPreset, model: String, level: String) {
        rejected = rejected + (key(preset, model) to level)
    }

    /** Al elegir otro nivel se vuelve a intentar con él. */
    fun clearRejected(preset: ProviderPreset, model: String) {
        rejected = rejected - key(preset, model)
    }

    fun isRejected(preset: ProviderPreset, model: String, level: String): Boolean =
        level.isNotEmpty() && rejected[key(preset, model)] == level

    /** Un 400 que habla del esfuerzo o del razonamiento: el modelo no acepta ese nivel (o ninguno). */
    fun looksRejected(e: AiException): Boolean {
        if (e.status != 400) return false
        val m = e.message.orEmpty().lowercase()
        return listOf("effort", "reasoning", "thinking").any { it in m }
    }
}
