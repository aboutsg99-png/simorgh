package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import com.uacspoofer.mobile.engine.wireguard.WgXrayConfig
import com.uacspoofer.mobile.engine.wireguard.WireGuardConnectionCoordinator

/** Turns the Firefox VPN transport choice into concrete Mozilla VPN WireGuard attempts. */
internal object MozillaWgPlanner {
    /** Relays tried per connect: enough to skip one dead relay, few enough to fit the budget. */
    private const val SERVERS = 2

    suspend fun attempts(context: Context, transports: List<FoxyTransport>, country: String): List<WireGuardConnectionCoordinator.Attempt> {
        if (transports.isEmpty()) return emptyList()
        val account = MozillaVpnAccount.get(context)
        val (privateKey, device) = account.device()
        val servers = MozillaWg.pickServers(account.servers(), country, SERVERS)
        if (servers.isEmpty()) throw FoxyUnusableException("Mozilla VPN returned no WireGuard server")
        return transports.flatMap { transport ->
            servers.flatMap { server -> MozillaWg.routes(transport, server) }
        }.map { route ->
            val label = "Mozilla ${route.transport.id} ${route.server.hostname}:${route.port}"
            when (route.transport) {
                FoxyTransport.WG_TCP -> WireGuardConnectionCoordinator.Attempt(label, udp2tcp = route.host to route.port) { local ->
                    MozillaWg.tunnel(route, privateKey, device, localUdpPort = local)
                }
                FoxyTransport.WG_SS -> WireGuardConnectionCoordinator.Attempt(
                    label,
                    shadowsocks = WgXrayConfig.SsBridge(route.host, route.port, MozillaWg.SS_METHOD, MozillaWg.SS_PASSWORD),
                ) { MozillaWg.tunnel(route, privateKey, device) }
                else -> WireGuardConnectionCoordinator.Attempt(label) { MozillaWg.tunnel(route, privateKey, device) }
            }
        }
    }
}
