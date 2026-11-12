package dev.kmpcoach.core

/**
 * [CLI]: la suscripción a través del programa de terminal del servicio (ver [CliClient]).
 * [EDITOR]: la suscripción la pone el propio editor (la API de modelos de VS Code con GitHub Copilot). El core no la
 * llama: arma los prompts y lee las respuestas, pero el envío lo hace la extensión.
 */
enum class ProviderKind { ANTHROPIC, OPENAI_COMPATIBLE, CLI, EDITOR }

/** Proveedores listos para usar. Todos los compatibles con OpenAI comparten el mismo cliente. */
enum class ProviderPreset(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
    /** Solo en los de suscripción: qué programa usan. Su [baseUrl] es la ruta del programa (vacía = buscarlo solo). */
    val cli: CliTool? = null,
) {
    ANTHROPIC("anthropic", "Anthropic (Claude)", ProviderKind.ANTHROPIC, "https://api.anthropic.com", "claude-haiku-5-5", true),
    OPENAI("openai", "OpenAI", ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-6-luna", true),
    GEMINI("gemini", "Google Gemini (clave de Google AI Studio)", ProviderKind.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash", true),
    GROQ("groq", "Groq", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "", true),
    OPENROUTER("openrouter", "OpenRouter", ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "", true),
    OLLAMA("ollama", "Ollama (local, sin clave)", ProviderKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1", "", false),
    CUSTOM("custom", "Otro compatible con OpenAI", ProviderKind.OPENAI_COMPATIBLE, "", "", false),
    CLAUDE_CLI("claude-cli", CliTool.CLAUDE.label, ProviderKind.CLI, "", "", false, CliTool.CLAUDE),
    CURSOR_CLI("cursor-cli", CliTool.CURSOR.label, ProviderKind.CLI, "", "", false, CliTool.CURSOR),
    COPILOT_EDITOR("copilot-vscode", "GitHub Copilot (suscripción · solo VS Code)", ProviderKind.EDITOR, "", "", false);

    val isSubscription: Boolean get() = kind == ProviderKind.CLI || kind == ProviderKind.EDITOR

    /** Solo lo puede usar un editor que lo llame por su cuenta (VS Code). Android Studio no lo ofrece. */
    val isEditorOnly: Boolean get() = kind == ProviderKind.EDITOR

    override fun toString() = label

    companion object {
        fun of(id: String?): ProviderPreset = entries.firstOrNull { it.id == id }
            // La suscripción de Antigravity se retiró (sus términos lo prohíben): quien la tenía pasa a Gemini con clave de API.
            ?: if (id == "antigravity-cli") GEMINI else ANTHROPIC
    }
}

data class AiConfig(
    val preset: ProviderPreset,
    val baseUrl: String,
    val model: String,
    val apiKey: String?,
    val timeoutSeconds: Long = 90,
)

data class ChatMessage(val role: String, val content: String)

class AiException(message: String, val status: Int = 0) : RuntimeException(message)

/** Fallo de red: no se pudo conectar ([Kind.CONNECT]) o el proveedor tardó demasiado ([Kind.TIMEOUT]). */
class AiNetworkException(val kind: Kind, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    enum class Kind { CONNECT, TIMEOUT }
}

/** Tokens que cobró el proveedor por una llamada. [costUsd] solo llega cuando el proveedor lo informa (OpenRouter). */
data class AiUsage(val inputTokens: Int, val outputTokens: Int, val costUsd: Double? = null)

/** Cupo de la ventana de límites actual, según las cabeceras de la última respuesta. Cada campo es null si el proveedor no lo envía. */
data class RateLimits(
    val requestsRemaining: Long? = null,
    val requestsLimit: Long? = null,
    val tokensRemaining: Long? = null,
    val tokensLimit: Long? = null,
    val tokensReset: String? = null,
) {
    val isEmpty get() = requestsRemaining == null && tokensRemaining == null
}

/** Saldo de la cuenta en USD. Solo algunos proveedores lo exponen con una clave normal. */
data class AccountBalance(val remainingUsd: Double?, val usedUsd: Double?, val limitUsd: Double?)

/**
 * [truncated]: el proveedor cortó la respuesta por llegar al máximo de tokens (Anthropic: `stop_reason` "max_tokens";
 * OpenAI y compatibles: `finish_reason` "length"). Un JSON cortado no se puede leer.
 */
data class AiReply(val text: String, val usage: AiUsage?, val limits: RateLimits?, val truncated: Boolean = false)

/**
 * Como [AiClient.complete], pero si la respuesta llega cortada la pide una vez más con el doble de espacio. El consumo
 * devuelto suma las dos llamadas, para que el costo que ves sea el real.
 */
suspend fun AiClient.completeFull(system: String, messages: List<ChatMessage>, maxTokens: Int): AiReply {
    val first = complete(system, messages, maxTokens)
    if (!first.truncated) return first
    val second = complete(system, messages, maxTokens * 2)
    return second.copy(usage = sum(first.usage, second.usage))
}

private fun sum(a: AiUsage?, b: AiUsage?): AiUsage? = when {
    a == null -> b
    b == null -> a
    else -> AiUsage(a.inputTokens + b.inputTokens, a.outputTokens + b.outputTokens,
        if (a.costUsd != null && b.costUsd != null) a.costUsd + b.costUsd else null)
}

interface AiClient {
    /** Pide una respuesta. [system] son las instrucciones; [messages] alterna user/assistant y termina en user. */
    suspend fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): AiReply

    /** Modelos disponibles para la clave configurada. */
    suspend fun listModels(): List<String>

    /** Saldo restante, o null si el proveedor no lo expone (Anthropic, OpenAI, Gemini y Groq no lo hacen con una clave de API normal). */
    suspend fun balance(): AccountBalance? = null
}

/** Respuesta HTTP mínima que necesitan los clientes de IA. Las cabeceras van con el nombre en minúsculas. */
class HttpResult(val status: Int, val body: String, val headers: Map<String, List<String>>)

/**
 * Envío HTTP de cada plataforma: `java.net.http` en el IDE (jvmMain) y `fetch` en Node para VS Code (jsMain).
 * Los fallos de red se lanzan como [AiNetworkException].
 */
interface HttpTransport {
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?, timeoutSeconds: Long): HttpResult
}

/** El transporte HTTP por defecto de la plataforma. */
expect fun defaultTransport(): HttpTransport

object AiClients {

    /** Devuelve un mensaje si la configuración está incompleta, o null si se puede usar. */
    fun validate(cfg: AiConfig): String? = when {
        // Con suscripción no hace falta URL, clave ni modelo: el programa usa los suyos.
        cfg.preset.isSubscription -> null
        cfg.baseUrl.isBlank() -> "Falta la URL base del proveedor. Configúrala en Ajustes › Herramientas › Mentor."
        cfg.preset.needsKey && cfg.apiKey.isNullOrBlank() -> "Falta la clave de API de ${cfg.preset.label}. Agrégala en Ajustes › Herramientas › Mentor."
        cfg.model.isBlank() -> "Elige un modelo en Ajustes › Herramientas › Mentor, o desde el panel del Mentor (el botón «Cargar modelos» te muestra los disponibles)."
        else -> null
    }

    fun create(
        cfg: AiConfig, http: HttpTransport = defaultTransport(), processes: ProcessRunner = defaultProcessRunner(),
    ): AiClient = when (cfg.preset.kind) {
        ProviderKind.ANTHROPIC -> AnthropicClient(cfg, http)
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(cfg, http)
        ProviderKind.CLI -> CliClient(cfg.preset.cli!!, cfg, processes)
        ProviderKind.EDITOR -> EditorOnlyClient(cfg.preset)
    }

    /** Mensaje legible para cualquier fallo de red o de proveedor. */
    fun friendly(t: Throwable, cfg: AiConfig? = null): String = when (t) {
        is AiException -> t.message ?: "Error del proveedor."
        is AiNetworkException -> when (t.kind) {
            AiNetworkException.Kind.TIMEOUT -> "El proveedor tardó demasiado en responder. Prueba con un modelo más rápido o vuelve a intentarlo."
            AiNetworkException.Kind.CONNECT -> "No se pudo conectar con ${cfg?.baseUrl ?: "el proveedor"}." +
                if (cfg?.preset == ProviderPreset.OLLAMA) " ¿Está corriendo Ollama (`ollama serve`)?" else " Revisa tu conexión o la URL base."
        }
        is JsonException -> "La respuesta del modelo llegó incompleta o con otro formato. Vuelve a intentarlo."
        else -> t.message ?: t::class.simpleName ?: "Error desconocido"
    }

    internal fun join(base: String, path: String) = base.trimEnd('/') + path

    /** Página donde el usuario ve su saldo y consumo real. */
    fun billingUrl(preset: ProviderPreset): String? = when (preset) {
        ProviderPreset.ANTHROPIC -> "https://console.anthropic.com/settings/billing"
        ProviderPreset.OPENAI -> "https://platform.openai.com/usage"
        ProviderPreset.GEMINI -> "https://aistudio.google.com/"
        ProviderPreset.GROQ -> "https://console.groq.com/"
        ProviderPreset.OPENROUTER -> "https://openrouter.ai/settings/credits"
        ProviderPreset.OLLAMA, ProviderPreset.CUSTOM -> null
        ProviderPreset.CLAUDE_CLI -> "https://claude.ai/settings/usage"
        ProviderPreset.CURSOR_CLI -> "https://cursor.com/dashboard"
        ProviderPreset.COPILOT_EDITOR -> "https://github.com/settings/copilot"
    }

    /** `usage` de la API de Anthropic. Los tokens leídos o escritos en caché también cuentan como entrada. */
    fun anthropicUsage(root: Map<String, Any?>): AiUsage? {
        val u = root["usage"] as? Map<*, *> ?: return null
        val m = u.asMap()
        val input = (m.int("input_tokens") ?: 0) + (m.int("cache_creation_input_tokens") ?: 0) + (m.int("cache_read_input_tokens") ?: 0)
        return AiUsage(input, m.int("output_tokens") ?: 0)
    }

    /** `usage` de las APIs compatibles con OpenAI. OpenRouter agrega `cost` en USD. */
    fun openAiUsage(root: Map<String, Any?>): AiUsage? {
        val u = root["usage"] as? Map<*, *> ?: return null
        val m = u.asMap()
        return AiUsage(m.int("prompt_tokens") ?: 0, m.int("completion_tokens") ?: 0, (m["cost"] as? Number)?.toDouble())
    }

    /** Lee las cabeceras de límites de Anthropic (`anthropic-ratelimit-*`) y de OpenAI/Groq (`x-ratelimit-*`). */
    fun rateLimits(headers: Map<String, List<String>>): RateLimits? {
        val h = headers.entries.associate { it.key.lowercase() to it.value.firstOrNull().orEmpty() }
        fun num(vararg keys: String) = keys.firstNotNullOfOrNull { h[it]?.trim()?.toLongOrNull() }
        fun str(vararg keys: String) = keys.firstNotNullOfOrNull { h[it]?.takeIf(String::isNotBlank) }
        val r = RateLimits(
            requestsRemaining = num("anthropic-ratelimit-requests-remaining", "x-ratelimit-remaining-requests"),
            requestsLimit = num("anthropic-ratelimit-requests-limit", "x-ratelimit-limit-requests"),
            tokensRemaining = num("anthropic-ratelimit-tokens-remaining", "anthropic-ratelimit-input-tokens-remaining", "x-ratelimit-remaining-tokens"),
            tokensLimit = num("anthropic-ratelimit-tokens-limit", "anthropic-ratelimit-input-tokens-limit", "x-ratelimit-limit-tokens"),
            tokensReset = str("anthropic-ratelimit-tokens-reset", "anthropic-ratelimit-input-tokens-reset", "x-ratelimit-reset-tokens"),
        )
        return r.takeUnless { it.isEmpty }
    }

    internal fun check(resp: HttpResult, preset: ProviderPreset): String {
        val code = resp.status
        if (code in 200..299) return resp.body
        val detail = runCatching {
            val root = Json.parse(resp.body).asMap()
            val err = root["error"]
            (err as? Map<*, *>)?.get("message")?.toString() ?: err as? String ?: root.str("message")
        }.getOrNull()?.take(300)
        val base = when (code) {
            401, 403 -> "La clave de ${preset.label} no es válida o no tiene permiso para este modelo"
            404 -> "No se encontró el modelo o la ruta. Revisa la URL base y el nombre del modelo"
            429 -> "Llegaste al límite de uso de ${preset.label}. Espera un momento"
            in 500..599 -> "${preset.label} tuvo un error interno"
            else -> "${preset.label} rechazó la solicitud"
        }
        throw AiException("$base (HTTP $code)" + (detail?.let { ": $it" } ?: "."), code)
    }
}

/** Un proveedor que solo el editor sabe llamar: si llega hasta el core, se explica en vez de fallar a ciegas. */
private class EditorOnlyClient(private val preset: ProviderPreset) : AiClient {
    private fun fail(): Nothing =
        throw AiException("${preset.label} solo funciona dentro de VS Code, que lo llama con tu sesión de Copilot. Elige otro proveedor.")

    override suspend fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): AiReply = fail()
    override suspend fun listModels(): List<String> = fail()
}

private class AnthropicClient(private val cfg: AiConfig, private val http: HttpTransport) : AiClient {
    private val headers = mapOf(
        "x-api-key" to cfg.apiKey.orEmpty(),
        "anthropic-version" to "2023-06-01",
        "content-type" to "application/json",
    )

    private suspend fun send(method: String, path: String, body: String? = null) =
        http.send(method, AiClients.join(cfg.baseUrl, path), headers, body, cfg.timeoutSeconds)

    override suspend fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): AiReply {
        val body = Json.write(mapOf(
            "model" to cfg.model,
            "max_tokens" to maxTokens,
            "system" to system,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
        ))
        val resp = send("POST", "/v1/messages", body)
        val root = Json.parse(AiClients.check(resp, cfg.preset)).asMap()
        val text = root["content"].asList().mapNotNull { it.asMap().takeIf { b -> b["type"] == "text" }?.str("text") }.joinToString("")
        if (text.isBlank()) throw AiException("${cfg.preset.label} devolvió una respuesta vacía.")
        return AiReply(text, AiClients.anthropicUsage(root), AiClients.rateLimits(resp.headers), root.str("stop_reason") == "max_tokens")
    }

    override suspend fun listModels(): List<String> {
        val resp = send("GET", "/v1/models?limit=100")
        return Json.parse(AiClients.check(resp, cfg.preset)).asMap()["data"].asList().mapNotNull { it.asMap().str("id") }
    }
}

private class OpenAiCompatibleClient(private val cfg: AiConfig, private val http: HttpTransport) : AiClient {
    private val headers = buildMap {
        put("content-type", "application/json")
        if (!cfg.apiKey.isNullOrBlank()) put("authorization", "Bearer ${cfg.apiKey}")
        if (cfg.preset == ProviderPreset.OPENROUTER) put("X-Title", "Mentor")
    }

    private suspend fun send(method: String, path: String, body: String? = null) =
        http.send(method, AiClients.join(cfg.baseUrl, path), headers, body, cfg.timeoutSeconds)

    override suspend fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): AiReply {
        // Sin max_tokens ni temperature: cada proveedor compatible acepta nombres distintos y algunos modelos rechazan valores no por defecto.
        val body = Json.write(mapOf(
            "model" to cfg.model,
            "messages" to listOf(mapOf("role" to "system", "content" to system)) + messages.map { mapOf("role" to it.role, "content" to it.content) },
        ))
        val resp = send("POST", "/chat/completions", body)
        val root = Json.parse(AiClients.check(resp, cfg.preset)).asMap()
        val choice = root["choices"].asList().firstOrNull().asMap()
        val msg = choice["message"].asMap()
        val text = when (val c = msg["content"]) {
            is String -> c
            is List<*> -> c.mapNotNull { it.asMap().str("text") }.joinToString("")
            else -> ""
        }
        if (text.isBlank()) throw AiException("${cfg.preset.label} devolvió una respuesta vacía.")
        return AiReply(text, AiClients.openAiUsage(root), AiClients.rateLimits(resp.headers), choice.str("finish_reason") == "length")
    }

    override suspend fun listModels(): List<String> {
        val resp = send("GET", "/models")
        return Json.parse(AiClients.check(resp, cfg.preset)).asMap()["data"].asList()
            .mapNotNull { it.asMap().str("id")?.removePrefix("models/") }
            .sorted()
    }

    override suspend fun balance(): AccountBalance? {
        if (cfg.preset != ProviderPreset.OPENROUTER) return null
        // /key funciona con cualquier clave: límite que le pusiste a la clave y lo que lleva gastado.
        // /credits da el saldo de la cuenta, pero OpenRouter puede exigir una clave de administración: si falla, nos quedamos con /key.
        val key = runCatching {
            val d = Json.parse(AiClients.check(send("GET", "/key"), cfg.preset)).asMap()["data"].asMap()
            AccountBalance((d["limit_remaining"] as? Number)?.toDouble(), (d["usage"] as? Number)?.toDouble(), (d["limit"] as? Number)?.toDouble())
        }
        val credits = runCatching {
            val resp = send("GET", "/credits")
            if (resp.status !in 200..299) return@runCatching null
            val d = Json.parse(resp.body).asMap()["data"].asMap()
            val total = (d["total_credits"] as? Number)?.toDouble() ?: return@runCatching null
            val used = (d["total_usage"] as? Number)?.toDouble() ?: 0.0
            AccountBalance(total - used, used, total)
        }.getOrNull()
        return credits ?: key.getOrElse { throw it }
    }
}
