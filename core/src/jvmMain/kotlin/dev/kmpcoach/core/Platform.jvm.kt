package dev.kmpcoach.core

import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.ConnectException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Un solo cliente HTTP para todo el IDE: reutiliza conexiones y respeta el proxy del sistema. */
private val sharedHttp: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .proxy(ProxySelector.getDefault())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

actual fun defaultTransport(): HttpTransport = JvmHttpTransport(sharedHttp)

/** HTTP con `java.net.http`. Si la corrutina se cancela (por ejemplo, «Detener» la revisión), se cancela la petición. */
class JvmHttpTransport(private val http: HttpClient) : HttpTransport {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?, timeoutSeconds: Long): HttpResult {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
        headers.forEach { (k, v) -> b.header(k, v) }
        if (method == "POST") b.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty())) else b.GET()
        val resp = try {
            http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: HttpTimeoutException) {
            throw AiNetworkException(AiNetworkException.Kind.TIMEOUT, e.message ?: "timeout", e)
        } catch (e: ConnectException) {
            throw AiNetworkException(AiNetworkException.Kind.CONNECT, e.message ?: "connect", e)
        } catch (e: IOException) {
            if (e.cause is ConnectException) throw AiNetworkException(AiNetworkException.Kind.CONNECT, e.message ?: "connect", e)
            throw e
        }
        val h = resp.headers().map().entries.associate { it.key.lowercase() to it.value }
        return HttpResult(resp.statusCode(), resp.body(), h)
    }
}

actual fun todayIso(): String = LocalDate.now().toString()

/**
 * Corre una función `suspend` del core y devuelve un `CompletableFuture`, para el código de Swing que no vive en
 * una corrutina (por ejemplo, los botones de Ajustes). Solo usa la biblioteca estándar.
 */
fun <T> futureOf(block: suspend () -> T): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
        result.fold(future::complete, future::completeExceptionally)
    })
    return future
}
