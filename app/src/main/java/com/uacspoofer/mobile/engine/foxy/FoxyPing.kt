package com.uacspoofer.mobile.engine.foxy

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One real measurement of a Mozilla edge server from this phone.
 *
 * [tcpMs] is the TCP connect time (one network round trip). [tlsMs] is the full TLS
 * handshake with the server's real SNI on top of that: on filtered networks TCP often
 * succeeds while TLS is reset, so a server only counts as reachable when TLS completes.
 * Nothing is estimated; a failed step is reported as null.
 */
data class FoxyPingResult(
    val host: String,
    val port: Int,
    val address: String?,
    val tcpMs: Int?,
    val tlsMs: Int?,
    val error: String = "",
) {
    val reachable: Boolean get() = tlsMs != null
}

object FoxyPing {
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val TLS_TIMEOUT_MS = 3_000

    private val dnsPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "foxy-dns").apply { isDaemon = true }
    }

    /** System DNS with a hard 4 s cap (getAllByName itself cannot be cancelled). */
    suspend fun resolve(host: String): InetAddress? = withContext(Dispatchers.IO) {
        val future = dnsPool.submit<InetAddress?> {
            val all = InetAddress.getAllByName(host).toList()
            all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
        }
        try {
            future.get(4, java.util.concurrent.TimeUnit.SECONDS)
        } catch (error: Throwable) {
            future.cancel(true)
            null
        }
    }

    suspend fun measure(host: String, port: Int): FoxyPingResult = withContext(Dispatchers.IO) {
        val address = resolve(host)
            ?: return@withContext FoxyPingResult(host, port, null, null, null, "dns")
        var socket: Socket? = null
        var tls: SSLSocket? = null
        try {
            val raw = Socket(Proxy.NO_PROXY)
            socket = raw
            val start = System.nanoTime()
            raw.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            val tcp = elapsedMs(start)
            raw.soTimeout = TLS_TIMEOUT_MS
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
            tls = ssl
            runCatching {
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(host))
                ssl.sslParameters = params
            }
            ssl.startHandshake()
            FoxyPingResult(host, port, address.hostAddress, tcp, elapsedMs(start))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val reason = when (error) {
                is java.net.SocketTimeoutException -> "timeout"
                is javax.net.ssl.SSLException -> "tls"
                else -> "tcp"
            }
            FoxyPingResult(host, port, address.hostAddress, null, null, reason)
        } finally {
            runCatching { tls?.close() }
            runCatching { socket?.close() }
        }
    }

    /** Measures candidates in parallel (bounded) and returns them fastest first. */
    suspend fun rank(candidates: List<ProxyCandidate>, parallel: Int = 6): List<Pair<ProxyCandidate, FoxyPingResult>> =
        coroutineScope {
            val gate = Semaphore(parallel.coerceIn(1, 12))
            candidates.map { candidate ->
                async {
                    gate.withPermit {
                        candidate to (withTimeoutOrNull(8_000L) { measure(candidate.host, candidate.port) }
                            ?: FoxyPingResult(candidate.host, candidate.port, null, null, null, "timeout"))
                    }
                }
            }.awaitAll().sortedWith(compareBy({ it.second.tlsMs == null }, { it.second.tlsMs ?: Int.MAX_VALUE }))
        }

    private fun elapsedMs(start: Long): Int =
        ((System.nanoTime() - start) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}
