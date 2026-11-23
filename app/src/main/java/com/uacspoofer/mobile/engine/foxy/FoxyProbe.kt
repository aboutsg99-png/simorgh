package com.uacspoofer.mobile.engine.foxy

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Real measurements through the local sing-box SOCKS port, i.e. through Mozilla's edge.
 * Nothing here is estimated: latency is a timed HTTPS request, throughput is bytes
 * actually received in a fixed window.
 */
internal object FoxyProbe {
    private const val LATENCY_URL = "https://www.gstatic.com/generate_204"
    private const val LATENCY_FALLBACK_URL = "https://cp.cloudflare.com/generate_204"
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=4000000"
    private const val DOWNLOAD_WINDOW_MS = 4_000L

    /** Below this the path is treated as weak and the next HTTP version is tried. */
    const val GOOD_BYTES_PER_SECOND = 300L * 1024
    const val GOOD_LATENCY_MS = 1_500

    /** Below this a path is not used at all (engine fallback instead). */
    const val MIN_USABLE_BYTES_PER_SECOND = 48L * 1024

    private fun client(port: Int, timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs + 1_000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun OkHttpClient.release() {
        runCatching { dispatcher.executorService.shutdown() }
        runCatching { connectionPool.evictAll() }
    }

    /** Time of one HTTPS request on an already-warm connection, in ms; null on failure. */
    suspend fun latencyMs(port: Int, timeoutMs: Long = 6_000L): Int? = withContext(Dispatchers.IO) {
        val http = client(port, timeoutMs)
        try {
            withTimeoutOrNull(timeoutMs * 2 + 2_000) {
                for (url in listOf(LATENCY_URL, LATENCY_FALLBACK_URL)) {
                    val result = runCatching {
                        // First request pays for CONNECT setup; the second is the real round trip.
                        timed(http, url)
                        timed(http, url)
                    }.getOrNull()
                    if (result != null) return@withTimeoutOrNull result
                }
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            http.release()
        }
    }

    private suspend fun timed(http: OkHttpClient, url: String): Int {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        val start = System.nanoTime()
        http.newCall(request).awaitResponse().use { response ->
            check(response.code in 200..399) { "HTTP ${response.code}" }
        }
        return ((System.nanoTime() - start) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Bytes per second received during a fixed window; null when nothing arrived. */
    suspend fun throughput(port: Int): Long? = withContext(Dispatchers.IO) {
        val http = client(port, 6_000L)
        val call = http.newCall(Request.Builder().url(DOWNLOAD_URL).header("Cache-Control", "no-cache").build())
        try {
            withTimeoutOrNull(DOWNLOAD_WINDOW_MS + 8_000L) {
                call.awaitResponse().use { response ->
                    if (!response.isSuccessful) return@withTimeoutOrNull null
                    val source = response.body?.source() ?: return@withTimeoutOrNull null
                    val buffer = ByteArray(32 * 1024)
                    val stream = source.inputStream()
                    val start = System.nanoTime()
                    val deadline = start + DOWNLOAD_WINDOW_MS * 1_000_000L
                    var total = 0L
                    while (System.nanoTime() < deadline) {
                        val read = runCatching { stream.read(buffer) }.getOrDefault(-1)
                        if (read < 0) break
                        total += read
                    }
                    val seconds = (System.nanoTime() - start) / 1e9
                    if (total <= 0L || seconds <= 0.0) null else (total / seconds).toLong()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { call.cancel() }
            http.release()
        }
    }
}
