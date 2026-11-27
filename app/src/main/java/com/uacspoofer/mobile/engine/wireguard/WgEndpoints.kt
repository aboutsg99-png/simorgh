package com.uacspoofer.mobile.engine.wireguard

import com.uacspoofer.mobile.engine.foxy.FoxyPing
import com.uacspoofer.mobile.simorgh.flux.Family

/**
 * Peer endpoints as IP literals before the core starts.
 *
 * Xray resolves a WireGuard endpoint host name with its own DNS client, and that DNS is routed
 * through the tunnel (the `dns-query` rule): the handshake waits for a lookup that waits for the
 * handshake, so a config with `Endpoint = host.name:port` never came up. The phone resolves it
 * here instead (system DNS, filtered sinkhole answers dropped, DoH to IP-literal resolvers as a
 * fallback); a host that cannot be resolved is left as it was.
 */
internal object WgEndpoints {
    suspend fun resolved(tunnel: WgTunnel): WgTunnel {
        if (tunnel.peers.all { isLiteral(it.endpointHost) }) return tunnel
        val peers = tunnel.peers.map { p ->
            if (isLiteral(p.endpointHost)) p
            else FoxyPing.resolve(p.endpointHost)?.hostAddress?.let { p.copy(endpointHost = it) } ?: p
        }
        return tunnel.copy(peers = peers)
    }

    fun isLiteral(host: String): Boolean = Family.ofLiteral(host.removeSurrounding("[", "]")) != null

    /**
     * TUN MTU for a WireGuard tunnel: not above the tunnel's own MTU (a UDP datagram that does not
     * fit the inner MTU is dropped or fragmented inside the userspace stack), never below 1280.
     */
    fun tunMtu(tunnel: WgTunnel): Int = tunnel.mtu.coerceIn(1280, 1420)
}
