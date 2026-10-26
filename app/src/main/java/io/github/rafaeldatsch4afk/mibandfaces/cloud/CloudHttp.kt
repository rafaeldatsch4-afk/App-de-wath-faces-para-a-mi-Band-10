package io.github.rafaeldatsch4afk.mibandfaces.cloud

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Resposta HTTP. [setCookies] reúne os Set-Cookie de todos os saltos de redirecionamento seguidos. */
class HttpResponse(val status: Int, val body: String, val setCookies: List<String>, val location: String?) {
    // Nunca expõe o corpo: ele pode ter tokens.
    override fun toString() = "HttpResponse(status=$status, bodyBytes=${body.length})"
}

/** Tempo esgotado na leitura (usado na espera longa do login por QR). */
class HttpTimeoutException(message: String) : IOException(message)

/** Acesso HTTP da nuvem da Xiaomi. Trocável nos testes. */
interface CloudHttp {
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        followRedirects: Boolean = false,
        readTimeoutMs: Int = 20_000,
    ): HttpResponse

    suspend fun bytes(url: String): ByteArray
}

/** Implementação com HttpURLConnection (sem dependências extras). Só aceita https. */
class UrlConnectionHttp : CloudHttp {

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        followRedirects: Boolean,
        readTimeoutMs: Int,
    ): HttpResponse = blocking { current ->
        var target = url
        val cookies = mutableListOf<String>()
        var hops = 0
        while (true) {
            val conn = open(target, if (hops == 0) method else "GET", headers, if (hops == 0) body else null, readTimeoutMs)
            current(conn)
            val status = conn.responseCode
            cookies += conn.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten()
            val location = conn.getHeaderField("Location")
            if (followRedirects && status in 300..399 && location != null && hops < MAX_REDIRECTS) {
                conn.disconnect()
                target = URI(target).resolve(location).toString()
                hops++
                continue
            }
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = if (stream == null) "" else String(readLimited(stream), Charsets.UTF_8)
            conn.disconnect()
            return@blocking HttpResponse(status, text, cookies, location)
        }
        @Suppress("UNREACHABLE_CODE")
        error("inalcançável")
    }

    override suspend fun bytes(url: String): ByteArray = blocking { current ->
        val conn = open(url, "GET", emptyMap(), null, 20_000)
        current(conn)
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        val data = readLimited(conn.inputStream)
        conn.disconnect()
        data
    }

    private fun open(url: String, method: String, headers: Map<String, String>, body: String?, readTimeoutMs: Int): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https") { "só https é permitido" }
        val conn = parsed.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = readTimeoutMs
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (method == "POST") {
            conn.doOutput = true
            val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    private fun readLimited(input: InputStream): ByteArray = input.use {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BODY) throw IOException("resposta grande demais")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    /** Roda o bloqueio numa thread própria; cancelar a corrotina fecha a conexão aberta. */
    private suspend fun <T> blocking(block: (register: (HttpURLConnection) -> Unit) -> T): T =
        suspendCancellableCoroutine { cont ->
            val current = AtomicReference<HttpURLConnection?>(null)
            cont.invokeOnCancellation { current.get()?.disconnect() }
            thread(name = "xiaomi-cloud-http", isDaemon = true) {
                try {
                    val result = block { current.set(it) }
                    cont.resume(result)
                } catch (e: SocketTimeoutException) {
                    cont.resumeWithException(HttpTimeoutException("tempo esgotado"))
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            }
        }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_BODY = 4 * 1024 * 1024
    }
}
