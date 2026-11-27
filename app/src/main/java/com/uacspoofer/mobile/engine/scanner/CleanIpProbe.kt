package com.uacspoofer.mobile.engine.scanner

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Measures one address: TCP connect to :443, a TLS handshake carrying [sni] (the name the user's
 * config will send, so a clean IP that the filter kills for that name is not reported clean), then
 * `GET /cdn-cgi/trace` - a real answer from Cloudflare, which also names the data center.
 * Direct from the phone (the app is outside its own VPN): measuring the line is the point.
 */
object CleanIpProbe {
    data class Options(val sni: String = "speed.cloudflare.com", val rounds: Int = 3, val timeoutMs: Int = 2_500, val port: Int = 443)

    suspend fun measure(ip: String, o: Options): CleanIpSample = withContext(Dispatchers.IO) {
        val tcp = ArrayList<Long?>()
        val tls = ArrayList<Long?>()
        val http = ArrayList<Long?>()
        var colo: String? = null
        repeat(o.rounds) {
            currentCoroutineContext().ensureActive()
            val r = once(ip, o)
            tcp += r.tcp; tls += r.tls; http += r.http
            if (r.colo != null) colo = r.colo
            // Not even TCP: further rounds will not change the verdict.
            if (r.tcp == null && it == 0) return@withContext CleanIpSample(ip, tcp, tls, http, null)
        }
        CleanIpSample(ip, tcp, tls, http, colo)
    }

    suspend fun measureAll(ips: List<String>, o: Options, parallel: Int, onEach: (CleanIpSample) -> Unit): List<CleanIpSample> = coroutineScope {
        val gate = Semaphore(parallel.coerceIn(1, 32))
        ips.map { ip -> async { gate.withPermit { measure(ip, o).also(onEach) } } }.awaitAll()
    }

    private data class Once(val tcp: Long?, val tls: Long?, val http: Long?, val colo: String?)

    private fun once(ip: String, o: Options): Once {
        val started = SystemClock.elapsedRealtime()
        val raw = Socket(Proxy.NO_PROXY)
        return try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(InetAddress.getByName(ip), o.port), o.timeoutMs)
            val tcpMs = SystemClock.elapsedRealtime() - started
            raw.soTimeout = o.timeoutMs
            val tlsStart = SystemClock.elapsedRealtime()
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, o.sni, o.port, true) as SSLSocket
            runCatching {
                val p = ssl.sslParameters
                p.serverNames = listOf(SNIHostName(o.sni))
                ssl.sslParameters = p
            }
            val tlsMs = runCatching { ssl.startHandshake(); SystemClock.elapsedRealtime() - tlsStart }.getOrNull()
                ?: return Once(tcpMs, null, null, null)
            val httpStart = SystemClock.elapsedRealtime()
            val answer = runCatching {
                ssl.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: ${o.sni}\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray())
                ssl.outputStream.flush()
                val text = BufferedReader(InputStreamReader(ssl.inputStream)).readText().take(4096)
                text
            }.getOrNull()
            val httpMs = SystemClock.elapsedRealtime() - httpStart
            val (isCf, colo) = answer?.let { CleanIpScoring.parseTrace(it.substringAfter("\r\n\r\n")) } ?: (false to null)
            // A real HTTP status line from Cloudflare counts even when /cdn-cgi/trace is hidden
            // behind the config's own origin (403/404 still proves bytes moved both ways).
            val answered = isCf || answer?.startsWith("HTTP/1.1 ") == true
            runCatching { ssl.close() }
            Once(tcpMs, tlsMs, if (answered) httpMs else null, colo)
        } catch (_: Throwable) {
            Once(null, null, null, null)
        } finally {
            runCatching { raw.close() }
        }
    }
}
