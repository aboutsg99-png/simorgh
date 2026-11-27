package com.uacspoofer.mobile.engine.wireguard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Xray JSON for one WireGuard tunnel: a SOCKS inbound on loopback (the TUN relay feeds it) and
 * Xray's userspace `wireguard` outbound. DNS is asked through the tunnel itself (the tunnel's own
 * DNS server, else Cloudflare/Quad9), never on the local network.
 */
object WgXrayConfig {
    data class Options(
        val socksPort: Int,
        val udp: Boolean = true,
        /** Private / LAN ranges stay direct; when true Iranian domains/IPs too (Xray geo assets). */
        val iranDirect: Boolean = false,
        val directDomains: List<String> = emptyList(),
        val paused: Boolean = false,
        val logLevel: String = "warning",
        /** Override endpoint (a probe of an alternate address of the same server). */
        val endpointOverride: String? = null,
        /** Carry the WireGuard UDP inside Shadowsocks to this server (Xray dialerProxy chain). */
        val shadowsocks: SsBridge? = null,
    )

    data class SsBridge(val host: String, val port: Int, val method: String, val password: String)

    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "fc00::/7", "fe80::/10", "::1/128",
    )

    fun build(t: WgTunnel, o: Options): String {
        require(t.peers.isNotEmpty()) { "WireGuard tunnel has no peer" }
        val v6 = t.hasIpv6
        val peers = JSONArray()
        t.peers.forEachIndexed { i, p ->
            val peer = JSONObject()
                .put("publicKey", p.publicKey)
                .put("endpoint", if (i == 0 && o.endpointOverride != null) o.endpointOverride else p.endpoint)
                .put("allowedIPs", JSONArray(p.allowedIps))
            if (p.presharedKey.isNotBlank()) peer.put("preSharedKey", p.presharedKey)
            if (p.keepAliveSeconds > 0) peer.put("keepAlive", p.keepAliveSeconds)
            peers.put(peer)
        }
        val wgSettings = JSONObject()
            .put("secretKey", t.privateKey)
            .put("address", JSONArray(t.addresses))
            .put("peers", peers)
            .put("mtu", t.mtu)
            // Userspace stack: Blackdey has no kernel WireGuard and must never need root.
            .put("noKernelTun", true)
            .put("domainStrategy", if (v6) "ForceIP" else "ForceIPv4")
        if (t.reserved.size == 3) wgSettings.put("reserved", JSONArray(t.reserved))

        val wgOut = JSONObject().put("tag", "proxy").put("protocol", "wireguard").put("settings", wgSettings)
        o.shadowsocks?.let { wgOut.put("streamSettings", JSONObject().put("sockopt", JSONObject().put("dialerProxy", "ss-bridge"))) }
        val outbounds = JSONArray().put(wgOut)
        o.shadowsocks?.let { ss ->
            outbounds.put(
                JSONObject().put("tag", "ss-bridge").put("protocol", "shadowsocks").put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject().put("address", ss.host).put("port", ss.port)
                                .put("method", ss.method).put("password", ss.password),
                        ),
                    ),
                ),
            )
        }
        outbounds
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject().put("domainStrategy", "AsIs")))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole").put("settings", JSONObject()))
            .put(JSONObject().put("tag", "dns-out").put("protocol", "dns").put("settings", JSONObject()))

        val dnsIps = t.dns.filter { com.uacspoofer.mobile.simorgh.flux.Family.ofLiteral(it) != null }
            .ifEmpty { listOf("1.1.1.1", "9.9.9.9") }
        val dns = JSONObject()
            .put("tag", "dns-query")
            .put("servers", JSONArray(dnsIps))
            .put("queryStrategy", if (v6) "UseIP" else "UseIPv4")

        val tunnelOut = if (o.paused) "direct" else "proxy"
        val rules = JSONArray()
        rules.put(rule().put("inboundTag", JSONArray().put("socks-in")).put("port", "53").put("outboundTag", "dns-out"))
        // Xray's own DNS queries ride the tunnel; the tunnel's DNS (often a private 10.x address
        // inside the provider's network) must not fall into the LAN rule below.
        rules.put(rule().put("inboundTag", JSONArray().put("dns-query")).put("outboundTag", tunnelOut))
        rules.put(rule().put("ip", JSONArray(dnsIps)).put("outboundTag", tunnelOut))
        rules.put(rule().put("ip", JSONArray(PRIVATE_CIDRS)).put("outboundTag", "direct"))
        if (o.paused) {
            rules.put(rule().put("network", "tcp,udp").put("outboundTag", "direct"))
        } else {
            val domains = o.directDomains.map { it.trim().trimStart('.').lowercase() }
                .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
                .distinct().take(500)
            if (domains.isNotEmpty()) rules.put(rule().put("domain", JSONArray(domains.map { "domain:$it" })).put("outboundTag", "direct"))
            if (o.iranDirect) {
                // Same geosite/geoip tags as Parvaz (GeoAssets must be ensured before the core starts).
                rules.put(rule().put("domain", JSONArray(com.uacspoofer.mobile.mci.IranDirectRules.DOMAINS)).put("outboundTag", "direct"))
                rules.put(rule().put("ip", JSONArray(com.uacspoofer.mobile.mci.IranDirectRules.IR_IPS)).put("outboundTag", "direct"))
            }
            if (!v6) rules.put(rule().put("ip", JSONArray().put("::/0")).put("outboundTag", "block"))
            rules.put(rule().put("network", "tcp,udp").put("outboundTag", "proxy"))
        }

        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", o.socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", o.udp).put("ip", "127.0.0.1"))
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")).put("routeOnly", true))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", o.logLevel))
            .put("dns", dns)
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch").put("rules", rules))
            .toString()
    }

    private fun rule() = JSONObject().put("type", "field")
}
