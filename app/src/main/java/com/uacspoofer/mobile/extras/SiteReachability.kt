package com.uacspoofer.mobile.extras

import android.content.Context
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.vpn.LivePing
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One-tap reachability check of a few popular sites through the active tunnel.
 *
 * Each site gets exactly one HTTPS HEAD request through the tunnel's local
 * SOCKS endpoint (the same endpoint [LivePing] uses), with a ~5 s timeout and
 * no body read. At most [CONCURRENCY] requests run at once. The latency shown
 * is the measured wall time until response headers arrived (TCP + TLS + first
 * response through the tunnel); nothing is estimated.
 */
internal object SiteReachability {
    const val CONCURRENCY = 3
    private const val TIMEOUT_MS = 5_000
    private const val TOR_TIMEOUT_MS = 10_000

    enum class SiteId { WEB, GEMINI, CHATGPT, INSTAGRAM, TELEGRAM, YOUTUBE, GITHUB }

    data class Site(val id: SiteId, val label: String, val url: String)

    val SITES = listOf(
        Site(SiteId.WEB, "وب‌گردی", "https://www.google.com/generate_204"),
        Site(SiteId.GEMINI, "Gemini", "https://gemini.google.com/"),
        Site(SiteId.CHATGPT, "ChatGPT", "https://chatgpt.com/"),
        Site(SiteId.INSTAGRAM, "Instagram", "https://www.instagram.com/"),
        Site(SiteId.TELEGRAM, "Telegram", "https://web.telegram.org/"),
        Site(SiteId.YOUTUBE, "YouTube", "https://www.youtube.com/"),
        Site(SiteId.GITHUB, "GitHub", "https://github.com/"),
    )

    sealed interface Result {
        data object Pending : Result
        data object Testing : Result

        /** The site answered through the tunnel. */
        data class Reachable(val latencyMs: Long, val httpCode: Int) : Result

        /** The site answered, but refused access (e.g. 403/451 region block). */
        data class Restricted(val latencyMs: Long, val httpCode: Int) : Result

        /** No answer within the timeout, or the connection failed. */
        data class Blocked(val reason: String) : Result
    }

    fun tunnelUp(): Boolean = ConnectionStateStore.state.value == ConnectionState.CONNECTED

    /** Runs the full check; [onUpdate] is called on the caller's dispatcher as each site starts/finishes. */
    suspend fun runAll(context: Context, onUpdate: (SiteId, Result) -> Unit) {
        val app = context.applicationContext
        val (host, port) = LivePing.socksEndpoint(app)
        val engine = EngineModeStore.get(app).snapshot()
        val timeout = if (engine.isTor) TOR_TIMEOUT_MS else TIMEOUT_MS
        val gate = Semaphore(CONCURRENCY)
        coroutineScope {
            SITES.map { site ->
                async {
                    gate.withPermit {
                        onUpdate(site.id, Result.Testing)
                        val result = withContext(Dispatchers.IO) { probe(site.url, host, port, timeout) }
                        onUpdate(site.id, result)
                    }
                }
            }.awaitAll()
        }
    }

    private fun probe(url: String, socksHost: String, socksPort: Int, timeoutMs: Int): Result {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection(
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort)),
            ) as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Blackdey-Reachability")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val code = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
            when {
                code == 403 || code == 451 -> Result.Restricted(elapsed, code)
                code in 100..599 -> Result.Reachable(elapsed, code)
                else -> Result.Blocked("HTTP $code")
            }
        } catch (error: Throwable) {
            Result.Blocked(error.javaClass.simpleName)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
