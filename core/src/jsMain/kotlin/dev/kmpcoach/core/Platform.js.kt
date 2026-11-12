package dev.kmpcoach.core

import kotlinx.coroutines.await
import kotlin.js.Promise

private external fun fetch(url: String, init: dynamic): Promise<dynamic>
private external fun setTimeout(handler: () -> Unit, ms: Int): dynamic
private external fun clearTimeout(id: dynamic)
private external class AbortController {
    val signal: dynamic
    fun abort()
}

actual fun defaultTransport(): HttpTransport = FetchTransport()

/** HTTP con `fetch` de Node (incluido desde Node 18; VS Code trae uno más nuevo). */
class FetchTransport : HttpTransport {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?, timeoutSeconds: Long): HttpResult {
        val controller = AbortController()
        var timedOut = false
        val timer = setTimeout({ timedOut = true; controller.abort() }, (timeoutSeconds * 1000).toInt())
        val init: dynamic = js("({})")
        init.method = method
        val h: dynamic = js("({})")
        headers.forEach { (k, v) -> h[k] = v }
        init.headers = h
        if (body != null) init.body = body
        init.signal = controller.signal
        try {
            val resp: dynamic = fetch(url, init).await()
            val text: String = (resp.text() as Promise<String>).await()
            val out = HashMap<String, List<String>>()
            resp.headers.forEach { value: String, key: String -> out[key.lowercase()] = listOf(value) }
            return HttpResult(resp.status as Int, text, out)
        } catch (e: Throwable) {
            if (e is AiException) throw e
            val kind = if (timedOut) AiNetworkException.Kind.TIMEOUT else AiNetworkException.Kind.CONNECT
            throw AiNetworkException(kind, e.message ?: "fetch failed", e)
        } finally {
            clearTimeout(timer)
        }
    }
}

actual fun todayIso(): String {
    // Fecha local (no UTC): el repaso «toca hoy» según el reloj de la persona.
    val d: dynamic = js("new Date()")
    val y = (d.getFullYear() as Int).toString().padStart(4, '0')
    val m = ((d.getMonth() as Int) + 1).toString().padStart(2, '0')
    val day = (d.getDate() as Int).toString().padStart(2, '0')
    return "$y-$m-$day"
}
