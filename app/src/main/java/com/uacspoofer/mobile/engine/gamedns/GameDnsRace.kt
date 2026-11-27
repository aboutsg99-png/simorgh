package com.uacspoofer.mobile.engine.gamedns

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Races resolvers with real UDP queries for the game's domains, straight from the phone's line. */
object GameDnsRace {
    suspend fun race(servers: List<DnsServer>, domains: List<String>, timeoutMs: Int = 1_500, onEach: (DnsRaceResult) -> Unit = {}): List<DnsRaceResult> =
        coroutineScope {
            val gate = Semaphore(8)
            servers.map { s -> async(Dispatchers.IO) { gate.withPermit { measure(s, domains, timeoutMs).also(onEach) } } }.awaitAll()
        }

    suspend fun measure(server: DnsServer, domains: List<String>, timeoutMs: Int): DnsRaceResult = withContext(Dispatchers.IO) {
        val samples = domains.take(6).map { d -> once(server.ip, d, timeoutMs) }
        DnsRaceResult(server, samples)
    }

    fun once(ip: String, domain: String, timeoutMs: Int): Long? = runCatching {
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val id = GameDns.newId()
            val q = GameDns.query(id, domain)
            val started = SystemClock.elapsedRealtime()
            sock.send(DatagramPacket(q, q.size, InetSocketAddress(InetAddress.getByName(ip), 53)))
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            if (!GameDns.isAnswer(buf, p.length, id)) return null
            SystemClock.elapsedRealtime() - started
        }
    }.getOrNull()
}
