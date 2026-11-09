package dev.kmpcoach.core

import java.net.ConnectException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

enum class ProviderKind { ANTHROPIC, OPENAI_COMPATIBLE }

/** Proveedores listos para usar. Todos los compatibles con OpenAI comparten el mismo cliente. */
enum class ProviderPreset(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
) {
    ANTHROPIC("anthropic", "Anthropic (Claude)", ProviderKind.ANTHROPIC, "https://api.anthropic.com", "claude-haiku-5-5", true),
    OPENAI("openai", "OpenAI", ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-6-luna", true),
    GEMINI("gemini", "Google Gemini", ProviderKind.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash", true),
    GROQ("groq", "Groq", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "", true),
    OPENROUTER("openrouter", "OpenRouter", ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "", true),
    OLLAMA("ollama", "Ollama (local, sin clave)", ProviderKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1", "", false),
    CUSTOM("custom", "Otro compatible con OpenAI", ProviderKind.OPENAI_COMPATIBLE, "", "", false);

    override fun toString() = label

    companion object {
        fun of(id: String?): ProviderPreset = entries.firstOrNull { it.id == id } ?: ANTHROPIC
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

data class AiReply(val text: String, val usage: AiUsage?, val limits: RateLimits?)

interface AiClient {
    /** Pide una respuesta. [system] son las instrucciones; [messages] alterna user/assistant y termina en user. */
    fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): CompletableFuture<AiReply>

    /** Modelos disponibles para la clave configurada. */
    fun listModels(): CompletableFuture<List<String>>

    /** Saldo restante, o null si el proveedor no lo expone (Anthropic, OpenAI, Gemini y Groq no lo hacen con una clave de API normal). */
    fun balance(): CompletableFuture<AccountBalance?> = CompletableFuture.completedFuture(null)
}

object AiClients {

    /** Devuelve un mensaje si la configuración está incompleta, o null si se puede usar. */
    fun validate(cfg: AiConfig): String? = when {
        cfg.baseUrl.isBlank() -> "Falta la URL base del proveedor. Configúrala en Ajustes › Herramientas › KMP Interview Coach."
        cfg.preset.needsKey && cfg.apiKey.isNullOrBlank() -> "Falta la clave de API de ${cfg.preset.label}. Agrégala en Ajustes › Herramientas › KMP Interview Coach."
        cfg.model.isBlank() -> "Elige un modelo en Ajustes › Herramientas › KMP Interview Coach (el botón «Cargar modelos» te muestra los disponibles)."
        else -> null
    }

    fun create(cfg: AiConfig, http: HttpClient = defaultHttp()): AiClient = when (cfg.preset.kind) {
        ProviderKind.ANTHROPIC -> AnthropicClient(cfg, http)
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(cfg, http)
    }

    fun defaultHttp(): HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .proxy(ProxySelector.getDefault())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Mensaje legible para cualquier fallo de red o de proveedor. */
    fun friendly(t: Throwable, cfg: AiConfig? = null): String {
        var e: Throwable = t
        while ((e is CompletionException || e is ExecutionException) && e.cause != null) e = e.cause!!
        return when (e) {
            is AiException -> e.message ?: "Error del proveedor."
            is HttpTimeoutException -> "El proveedor tardó demasiado en responder. Prueba con un modelo más rápido o vuelve a intentarlo."
            is ConnectException -> "No se pudo conectar con ${cfg?.baseUrl ?: "el proveedor"}." +
                if (cfg?.preset == ProviderPreset.OLLAMA) " ¿Está corriendo Ollama (`ollama serve`)?" else " Revisa tu conexión o la URL base."
            is JsonException -> "La respuesta del modelo no tenía el formato esperado. Vuelve a intentarlo o usa un modelo más capaz."
            else -> e.message ?: e.javaClass.simpleName
        }
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

    internal fun check(resp: HttpResponse<String>, preset: ProviderPreset): String {
        val code = resp.statusCode()
        if (code in 200..299) return resp.body()
        val detail = runCatching {
            val root = Json.parse(resp.body()).asMap()
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

private class AnthropicClient(private val cfg: AiConfig, private val http: HttpClient) : AiClient {
    private fun req(path: String) = HttpRequest.newBuilder(URI.create(AiClients.join(cfg.baseUrl, path)))
        .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
        .header("x-api-key", cfg.apiKey.orEmpty())
        .header("anthropic-version", "2023-06-01")
        .header("content-type", "application/json")

    override fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): CompletableFuture<AiReply> {
        val body = Json.write(mapOf(
            "model" to cfg.model,
            "max_tokens" to maxTokens,
            "system" to system,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
        ))
        return http.sendAsync(req("/v1/messages").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            .thenApply { resp ->
                val root = Json.parse(AiClients.check(resp, cfg.preset)).asMap()
                val text = root["content"].asList().mapNotNull { it.asMap().takeIf { b -> b["type"] == "text" }?.str("text") }.joinToString("")
                if (text.isBlank()) throw AiException("${cfg.preset.label} devolvió una respuesta vacía.")
                AiReply(text, AiClients.anthropicUsage(root), AiClients.rateLimits(resp.headers().map()))
            }
    }

    override fun listModels(): CompletableFuture<List<String>> =
        http.sendAsync(req("/v1/models?limit=100").GET().build(), HttpResponse.BodyHandlers.ofString()).thenApply { resp ->
            Json.parse(AiClients.check(resp, cfg.preset)).asMap()["data"].asList().mapNotNull { it.asMap().str("id") }
        }
}

private class OpenAiCompatibleClient(private val cfg: AiConfig, private val http: HttpClient) : AiClient {
    private fun req(path: String): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(URI.create(AiClients.join(cfg.baseUrl, path)))
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
            .header("content-type", "application/json")
        if (!cfg.apiKey.isNullOrBlank()) b.header("authorization", "Bearer ${cfg.apiKey}")
        if (cfg.preset == ProviderPreset.OPENROUTER) b.header("X-Title", "KMP Interview Coach")
        return b
    }

    override fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): CompletableFuture<AiReply> {
        // Sin max_tokens ni temperature: cada proveedor compatible acepta nombres distintos y algunos modelos rechazan valores no por defecto.
        val body = Json.write(mapOf(
            "model" to cfg.model,
            "messages" to listOf(mapOf("role" to "system", "content" to system)) + messages.map { mapOf("role" to it.role, "content" to it.content) },
        ))
        return http.sendAsync(req("/chat/completions").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            .thenApply { resp ->
                val root = Json.parse(AiClients.check(resp, cfg.preset)).asMap()
                val msg = root["choices"].asList().firstOrNull().asMap()["message"].asMap()
                val text = when (val c = msg["content"]) {
                    is String -> c
                    is List<*> -> c.mapNotNull { it.asMap().str("text") }.joinToString("")
                    else -> ""
                }
                if (text.isBlank()) throw AiException("${cfg.preset.label} devolvió una respuesta vacía.")
                AiReply(text, AiClients.openAiUsage(root), AiClients.rateLimits(resp.headers().map()))
            }
    }

    override fun listModels(): CompletableFuture<List<String>> =
        http.sendAsync(req("/models").GET().build(), HttpResponse.BodyHandlers.ofString()).thenApply { resp ->
            Json.parse(AiClients.check(resp, cfg.preset)).asMap()["data"].asList()
                .mapNotNull { it.asMap().str("id")?.removePrefix("models/") }
                .sorted()
        }

    override fun balance(): CompletableFuture<AccountBalance?> {
        if (cfg.preset != ProviderPreset.OPENROUTER) return CompletableFuture.completedFuture(null)
        // /key funciona con cualquier clave: límite que le pusiste a la clave y lo que lleva gastado.
        // /credits da el saldo de la cuenta, pero OpenRouter puede exigir una clave de administración: si falla, nos quedamos con /key.
        val key = http.sendAsync(req("/key").GET().build(), HttpResponse.BodyHandlers.ofString()).thenApply { resp ->
            val d = Json.parse(AiClients.check(resp, cfg.preset)).asMap()["data"].asMap()
            AccountBalance((d["limit_remaining"] as? Number)?.toDouble(), (d["usage"] as? Number)?.toDouble(), (d["limit"] as? Number)?.toDouble())
        }
        val credits = http.sendAsync(req("/credits").GET().build(), HttpResponse.BodyHandlers.ofString()).handle { resp, err ->
            if (err != null || resp.statusCode() !in 200..299) return@handle null
            val d = runCatching { Json.parse(resp.body()).asMap()["data"].asMap() }.getOrNull() ?: return@handle null
            val total = (d["total_credits"] as? Number)?.toDouble() ?: return@handle null
            val used = (d["total_usage"] as? Number)?.toDouble() ?: 0.0
            AccountBalance(total - used, used, total)
        }
        return key.handle { k, err -> k to err }.thenCombine(credits) { (k, err), c ->
            c ?: k ?: throw CompletionException(err ?: AiException("OpenRouter no devolvió el saldo."))
        }
    }
}
