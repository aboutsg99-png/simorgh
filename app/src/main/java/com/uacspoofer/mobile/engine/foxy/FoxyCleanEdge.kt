package com.uacspoofer.mobile.engine.foxy

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random
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
 * Clean-IP search for Mozilla's Fastly MASQUE edge. Every address of the edge block serves
 * every `*.m1.fastly-masque.net` name on the edge's own port (2499 in today's directory; 443
 * there serves Fastly's default certificate, not the proxy), so when the resolved address is
 * blocked or poisoned, another address of the same block carries the same server by SNI.
 * A probe counts only when TLS completes with the real SNI and the certificate is valid for it.
 */
internal object FoxyCleanEdgeRanges {
    /** The /24 every edge in the 2026 directory resolved into. */
    val SEED_V4 = listOf("23.235.42.0/24")

    /** The /24 of each resolved IPv4 edge address, after the seed, without duplicates. */
    fun blocks(resolved: List<String?>): List<String> =
        (SEED_V4 + resolved.mapNotNull { ip ->
            ip?.takeIf { FoxyHosts.isValidIpAddress(it) && !it.contains(':') }
                ?.split('.')?.take(3)?.joinToString(".", postfix = ".0/24")
        }).distinct()

    /**
     * Up to [count] host addresses: [known] first (cached clean IPs, in order), then hosts
     * 1..254 of each /24 in [blocks], shuffled and interleaved so every block is sampled.
     */
    fun sample(blocks: List<String>, known: List<String>, count: Int, random: Random = Random.Default): List<String> {
        val out = LinkedHashSet<String>()
        known.filter(FoxyHosts::isValidIpAddress).forEach { if (out.size < count) out += it }
        val pools = blocks.mapNotNull { cidr ->
            val parts = cidr.split('/')
            if (parts.size != 2 || parts[1] != "24") return@mapNotNull null
            val prefix = parts[0].split('.').take(3)
            if (prefix.size != 3 || prefix.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return@mapNotNull null
            (1..254).shuffled(random).map { "${prefix.joinToString(".")}.$it" }.iterator()
        }
        while (out.size < count && pools.any { it.hasNext() }) {
            pools.forEach { if (it.hasNext() && out.size < count) out += it.next() }
        }
        return out.toList()
    }
}

data class CleanEdgeHit(val ip: String, val tlsMs: Int)

internal object FoxyCleanEdgeScanner {
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val TLS_TIMEOUT_MS = 2_500

    /**
     * Probes [ips] (best guesses first) for [sni]:[port], [parallel] at a time, and returns the
     * ones that completed TLS, fastest first. Stops after [want] hits or [budgetMs].
     */
    suspend fun scan(sni: String, port: Int, ips: List<String>, want: Int = 4, parallel: Int = 16, budgetMs: Long = 10_000L): List<CleanEdgeHit> =
        withContext(Dispatchers.IO) {
            val hits = java.util.concurrent.ConcurrentLinkedQueue<CleanEdgeHit>()
            withTimeoutOrNull(budgetMs) {
                coroutineScope {
                    val gate = Semaphore(parallel.coerceIn(1, 32))
                    ips.map { ip ->
                        async {
                            gate.withPermit {
                                if (hits.size >= want) return@withPermit
                                probe(ip, sni, port)?.let { hits += CleanEdgeHit(ip, it) }
                            }
                        }
                    }.awaitAll()
                }
            }
            hits.toList().sortedBy { it.tlsMs }.take(want)
        }

    /** TLS handshake time in ms when [ip] serves a valid certificate for [sni]; null otherwise. */
    fun probe(ip: String, sni: String, port: Int): Int? {
        val address = runCatching { InetAddress.getByName(ip) }.getOrNull() as? Inet4Address ?: return null
        if (FoxyPing.isBogus(address)) return null
        var raw: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            val socket = Socket(Proxy.NO_PROXY)
            raw = socket
            socket.tcpNoDelay = true
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = TLS_TIMEOUT_MS
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, sni, port, true) as SSLSocket
            ssl = tls
            runCatching {
                val params = tls.sslParameters
                params.serverNames = listOf(SNIHostName(sni))
                tls.sslParameters = params
            }
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, tls.session)) return null
            ((System.nanoTime() - start) / 1_000_000L).toInt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw?.close() }
        }
    }
}
