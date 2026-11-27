package com.uacspoofer.mobile.engine.wireguard

import android.content.Context
import com.uacspoofer.mobile.vpn.LivePing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Real delay of a WireGuard tunnel: a temporary Xray core with the tunnel, then an HTTP request
 * through it. WireGuard is UDP and has no "connect": only a request that comes back proves the
 * handshake worked.
 */
object WgProbe {
    data class Outcome(val tunnelId: String, val latencyMs: Long?, val error: String = "")

    suspend fun measure(context: Context, tunnel: WgTunnel, timeoutMs: Int = LivePing.TIMEOUT_MS): Outcome =
        withContext(Dispatchers.IO) {
            val port = freePort()
            val core = XrayRawCore(context, "wg-probe")
            try {
                val live = runCatching { WgEndpoints.resolved(tunnel) }.getOrDefault(tunnel)
                core.start(WgXrayConfig.build(live, WgXrayConfig.Options(socksPort = port, udp = false, logLevel = "error")), port)
                // The first request pays the handshake; take the better of two.
                val first = LivePing.measure("127.0.0.1", port, timeoutMs)
                val second = first?.let { LivePing.measure("127.0.0.1", port, timeoutMs) }
                val best = listOfNotNull(first, second).minOrNull()
                Outcome(tunnel.id, best, if (best == null) "no answer through the tunnel" else "")
            } catch (error: Throwable) {
                Outcome(tunnel.id, null, error.message.orEmpty().take(160))
            } finally {
                runCatching { core.stop() }
            }
        }

    suspend fun measureAll(context: Context, tunnels: List<WgTunnel>, parallel: Int = 3, onEach: (Outcome) -> Unit = {}): List<Outcome> =
        coroutineScope {
            val gate = Semaphore(parallel.coerceIn(1, 6))
            tunnels.map { t -> async { gate.withPermit { measure(context, t).also(onEach) } } }.awaitAll()
        }

    internal fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
