package com.uacspoofer.mobile.engine.foxy

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
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
import org.json.JSONObject

/**
 * One real measurement of a Mozilla edge server from this phone.
 *
 * [tcpMs] is the TCP connect time (one network round trip). [tlsMs] is the full TLS
 * handshake with the server's real SNI on top of that: on filtered networks TCP often
 * succeeds while TLS is reset. [udpMs] is a QUIC version-negotiation round trip, measured
 * only when TCP failed (HTTP/3 to the edge can work where TCP is blocked).
 * Nothing is estimated; a failed step is reported as null.
 */
data class FoxyPingResult(
    val host: String,
    val port: Int,
    val address: String?,
    val tcpMs: Int?,
    val tlsMs: Int?,
    val error: String = "",
    val udpMs: Int? = null,
) {
    /** TLS over TCP completed: HTTP/1.1 and HTTP/2 can use this edge. */
    val reachable: Boolean get() = tlsMs != null

    /** Some path answered (TCP, TLS or QUIC): the edge is alive from this network. */
    val answered: Boolean get() = tcpMs != null || udpMs != null

    /** One network round trip to the edge, the number shown as "ping". */
    val pingMs: Int? get() = tcpMs ?: udpMs
}

object FoxyPing {
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val TLS_TIMEOUT_MS = 3_500
    private const val QUIC_TIMEOUT_MS = 1_500
    private const val DNS_TIMEOUT_MS = 3_000L
    private const val DNS_CACHE_TTL_MS = 10 * 60_000L

    private val dnsPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "foxy-dns").apply { isDaemon = true }
    }
    private val dnsCache = ConcurrentHashMap<String, Pair<Long, InetAddress>>()

    /**
     * Resolver answers Iran's filter injects instead of a real address (10.10.34.0/24 and
     * other private/reserved ranges). A host that resolves to one of these is treated as
     * unresolved so the DoH fallback runs, instead of pinging a sinkhole that never answers.
     */
    internal fun isBogus(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            (address is Inet4Address && address.address[0].toInt() == 0)

    /**
     * System DNS, then DoH to IP-literal resolvers (no DNS needed to reach them), with a
     * per-host 10 min cache. The app is excluded from its own VPN, so this measures the
     * phone's real network path whether or not a tunnel is up.
     */
    suspend fun resolve(host: String): InetAddress? = withContext(Dispatchers.IO) {
        val key = host.lowercase(java.util.Locale.ROOT)
        if (FoxyHosts.isValidIpAddress(host)) {
            return@withContext runCatching { InetAddress.getByName(host.removeSurrounding("[", "]")) }.getOrNull()
        }
        dnsCache[key]?.let { (at, address) ->
            if (System.currentTimeMillis() - at < DNS_CACHE_TTL_MS) return@withContext address
        }
        val resolved = systemResolve(host)?.takeUnless(::isBogus) ?: dohResolve(host)
        if (resolved != null) dnsCache[key] = System.currentTimeMillis() to resolved
        resolved
    }

    private fun systemResolve(host: String): InetAddress? {
        val future = dnsPool.submit<InetAddress?> {
            val all = InetAddress.getAllByName(host).toList().filterNot(::isBogus)
            all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
        }
        return try {
            future.get(DNS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            future.cancel(true)
            null
        }
    }

    private val DOH_JSON = listOf(
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
        "https://9.9.9.9:5053/dns-query?type=A&name=",
    )

    private fun dohResolve(host: String): InetAddress? {
        for (base in DOH_JSON) {
            val ip = runCatching {
                val connection = URL(base + java.net.URLEncoder.encode(host, "UTF-8"))
                    .openConnection(Proxy.NO_PROXY) as HttpURLConnection
                try {
                    connection.connectTimeout = 2_500
                    connection.readTimeout = 2_500
                    connection.setRequestProperty("Accept", "application/dns-json")
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val body = connection.inputStream.use { it.readBoundedUtf8(64 * 1024) }
                    parseDohA(body)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            if (ip != null) return runCatching { InetAddress.getByName(ip) }.getOrNull()?.takeUnless(::isBogus)
        }
        return null
    }

    /** First A record of a DoH JSON answer (RFC 8427-style, as served by 1.1.1.1 and 8.8.8.8). */
    internal fun parseDohA(body: String): String? {
        val answers = JSONObject(body).optJSONArray("Answer") ?: return null
        for (i in 0 until answers.length()) {
            val a = answers.optJSONObject(i) ?: continue
            if (a.optInt("type") != 1) continue
            val data = a.optString("data").trim()
            if (FoxyHosts.isValidIpAddress(data)) return data
        }
        return null
    }

    suspend fun measure(host: String, port: Int): FoxyPingResult = withContext(Dispatchers.IO) {
        val address = resolve(host)
            ?: return@withContext FoxyPingResult(host, port, null, null, null, "dns")
        var socket: Socket? = null
        var tls: SSLSocket? = null
        var tcp: Int? = null
        try {
            val raw = Socket(Proxy.NO_PROXY)
            socket = raw
            raw.tcpNoDelay = true
            val start = System.nanoTime()
            raw.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            tcp = elapsedMs(start)
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
            val reason = when {
                tcp != null -> "tls"
                error is java.net.SocketTimeoutException -> "timeout"
                else -> "tcp"
            }
            // TCP itself failed: HTTP/3 (QUIC over UDP) may still reach the edge.
            val udp = if (tcp == null) quicRttMs(address, port) else null
            FoxyPingResult(host, port, address.hostAddress, tcp, null, reason, udpMs = udp)
        } finally {
            runCatching { tls?.close() }
            runCatching { socket?.close() }
        }
    }

    /**
     * Real UDP round trip to a QUIC server: a long-header packet with an unknown version
     * makes any QUIC server answer with a Version Negotiation packet (RFC 9000 §6), without
     * a handshake or keys. Padded to 1200 bytes as RFC 9000 §14.1 requires.
     */
    internal fun quicRttMs(address: InetAddress, port: Int, timeoutMs: Int = QUIC_TIMEOUT_MS): Int? {
        val dcid = Random.nextBytes(8)
        val scid = Random.nextBytes(8)
        val probe = quicVersionProbe(dcid, scid)
        return runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val reply = ByteArray(1_500)
                val start = System.nanoTime()
                socket.send(DatagramPacket(probe, probe.size, InetSocketAddress(address, port)))
                val deadline = start + timeoutMs * 1_000_000L
                while (System.nanoTime() < deadline) {
                    val packet = DatagramPacket(reply, reply.size)
                    socket.receive(packet)
                    if (isVersionNegotiation(reply, packet.length, scid)) return@use elapsedMs(start)
                }
                null
            }
        }.getOrNull()
    }

    internal fun quicVersionProbe(dcid: ByteArray, scid: ByteArray): ByteArray {
        val out = ByteArray(1_200)
        var i = 0
        out[i++] = 0xC0.toByte() // long header, fixed bit
        // Reserved "force negotiation" version 0x?a?a?a?a (RFC 9000 §15).
        out[i++] = 0x1A; out[i++] = 0x2A; out[i++] = 0x3A; out[i++] = 0x4A
        out[i++] = dcid.size.toByte(); dcid.copyInto(out, i); i += dcid.size
        out[i++] = scid.size.toByte(); scid.copyInto(out, i)
        return out
    }

    /** A Version Negotiation packet: long header, version 0, echoing our SCID as its DCID. */
    internal fun isVersionNegotiation(packet: ByteArray, length: Int, ourScid: ByteArray): Boolean {
        if (length < 7 + ourScid.size) return false
        if (packet[0].toInt() and 0x80 == 0) return false
        if (packet[1].toInt() or packet[2].toInt() or packet[3].toInt() or packet[4].toInt() != 0) return false
        val dcidLen = packet[5].toInt() and 0xff
        if (dcidLen != ourScid.size || 6 + dcidLen > length) return false
        return packet.copyOfRange(6, 6 + dcidLen).contentEquals(ourScid)
    }

    /** Ordering used everywhere: TLS ok first, then any answer, then dead; fastest first. */
    val BEST_FIRST: Comparator<FoxyPingResult> = compareBy(
        { if (it.reachable) 0 else if (it.answered) 1 else 2 },
        { it.pingMs ?: Int.MAX_VALUE },
    )

    /** Measures candidates in parallel (bounded) and returns them best first ([BEST_FIRST]). */
    suspend fun rank(candidates: List<ProxyCandidate>, parallel: Int = 8): List<Pair<ProxyCandidate, FoxyPingResult>> =
        coroutineScope {
            val gate = Semaphore(parallel.coerceIn(1, 16))
            candidates.map { candidate ->
                async {
                    gate.withPermit {
                        candidate to (withTimeoutOrNull(9_000L) { measure(candidate.host, candidate.port) }
                            ?: FoxyPingResult(candidate.host, candidate.port, null, null, null, "timeout"))
                    }
                }
            }.awaitAll().sortedWith(compareBy(BEST_FIRST) { it.second })
        }

    private fun elapsedMs(start: Long): Int =
        ((System.nanoTime() - start) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
}
