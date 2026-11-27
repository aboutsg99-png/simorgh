package com.uacspoofer.mobile.engine.foxy

import com.uacspoofer.mobile.engine.wireguard.WgPeer
import com.uacspoofer.mobile.engine.wireguard.WgSource
import com.uacspoofer.mobile.engine.wireguard.WgTunnel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * How the Firefox VPN engine reaches Mozilla.
 *
 * [MASQUE] is the original path: HTTP/1.1, HTTP/2 or HTTP/3 to Mozilla's Fastly edge with the
 * free Firefox account (sing-box). It is never removed and stays the last fallback of every
 * other choice.
 *
 * The WireGuard choices use the Mozilla VPN subscription (its own sign-in on vpn.mozilla.org):
 * Mozilla VPN servers are WireGuard relays run by Mullvad, so the bundled Xray WireGuard
 * outbound talks to them directly, without HTTP:
 *  - [WIREGUARD]: plain WireGuard over UDP (51820, then 53 and other ports the relay listens on);
 *  - [WG_TCP]: the same WireGuard packets framed over one TCP connection to the relay's
 *    UDP-over-TCP service (ports 443, 80, 5001), for networks that drop or throttle UDP;
 *  - [WG_SS]: WireGuard carried inside Shadowsocks to the relay's Shadowsocks service.
 * [AUTO] tries WireGuard, then UDP-over-TCP, then Shadowsocks, then MASQUE.
 */
enum class FoxyTransport(val id: String, val fa: String) {
    AUTO("auto", "خودکار (WireGuard ← TCP ← Shadowsocks ← HTTPS)"),
    MASQUE("masque", "HTTPS (MASQUE، HTTP/1 · 2 · 3)"),
    WIREGUARD("wg", "WireGuard (UDP)"),
    WG_TCP("wg_tcp", "WireGuard روی TCP (پورت ۴۴۳/۸۰/۵۰۰۱)"),
    WG_SS("wg_ss", "WireGuard داخل Shadowsocks");

    /** WireGuard variants to try, in order, before MASQUE. */
    val wireGuardOrder: List<FoxyTransport>
        get() = when (this) {
            AUTO -> listOf(WIREGUARD, WG_TCP, WG_SS)
            MASQUE -> emptyList()
            else -> listOf(this)
        }

    val isWireGuard: Boolean get() = this == WIREGUARD || this == WG_TCP || this == WG_SS

    companion object {
        fun from(raw: String?): FoxyTransport = entries.firstOrNull { it.id == raw } ?: MASQUE
    }
}

/** One Mozilla VPN (Mullvad) WireGuard relay from `/api/v1/vpn/servers`. */
data class MozillaWgServer(
    val hostname: String,
    val countryCode: String,
    val countryName: String,
    val cityName: String,
    val ipv4: String,
    val ipv6: String,
    val publicKey: String,
    val weight: Int,
    /** UDP port ranges the relay's WireGuard listens on, inclusive. */
    val portRanges: List<IntRange>,
    /** Relay-side resolver inside the tunnel (Mullvad: 10.64.0.1). */
    val ipv4Gateway: String,
)

/** The addresses Mozilla assigned to this phone's WireGuard key. */
data class MozillaWgDevice(val publicKey: String, val ipv4: String, val ipv6: String)

/** Where the WireGuard packets of one attempt go. */
data class MozillaWgRoute(
    val transport: FoxyTransport,
    val server: MozillaWgServer,
    /** Remote host and port the phone dials (UDP for WIREGUARD, TCP for WG_TCP, SS for WG_SS). */
    val host: String,
    val port: Int,
)

object MozillaWg {
    const val GUARDIAN = "https://vpn.mozilla.org"
    const val LOGIN_SUCCESS_PATH = "/vpn/client/login/success"

    /** UDP-over-TCP service ports on Mullvad relays. */
    val UDP2TCP_PORTS = listOf(443, 80, 5001)

    /** Shadowsocks on Mullvad relays: fixed public parameters, ports from this range. */
    const val SS_METHOD = "aes-256-gcm"
    const val SS_PASSWORD = "mullvad"
    val SS_PORTS = 51900..51949

    /** Shadowsocks forwards to the relay's own WireGuard port. */
    const val RELAY_WG_PORT = 51820

    // PKCE (RFC 7636) for the Mozilla VPN sign-in.
    fun newVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(48).also(random::nextBytes)
        return b64url(bytes)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return b64url(digest)
    }

    // java.util.Base64 needs API 26 (minSdk is 24).
    private fun b64url(bytes: ByteArray): String =
        com.uacspoofer.mobile.profiles.Base64Codec.encode(bytes).trimEnd('=').replace('+', '-').replace('/', '_')

    fun loginUrl(verifier: String): String =
        "$GUARDIAN/api/v2/vpn/login/android?code_challenge=${challenge(verifier)}&code_challenge_method=S256"

    /** The one-time code from the page Guardian redirects to after sign-in, or null. */
    fun codeFromRedirect(url: String?): String? {
        val u = runCatching { java.net.URI(url ?: return null) }.getOrNull() ?: return null
        val host = u.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "vpn.mozilla.org" && !host.endsWith(".vpn.mozilla.org")) return null
        if (u.path?.trimEnd('/') != LOGIN_SUCCESS_PATH) return null
        return u.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "code") java.net.URLDecoder.decode(kv[1], "UTF-8").takeIf { it.isNotBlank() } else null
        }
    }

    fun parseServers(body: String): List<MozillaWgServer> {
        val countries = JSONObject(body).optJSONArray("countries") ?: return emptyList()
        val out = ArrayList<MozillaWgServer>()
        for (i in 0 until countries.length()) {
            val c = countries.optJSONObject(i) ?: continue
            val code = c.optString("code").uppercase(Locale.ROOT)
            val cities = c.optJSONArray("cities") ?: continue
            for (j in 0 until cities.length()) {
                val city = cities.optJSONObject(j) ?: continue
                val servers = city.optJSONArray("servers") ?: continue
                for (k in 0 until servers.length()) {
                    val s = servers.optJSONObject(k) ?: continue
                    val key = s.optString("public_key")
                    val v4 = s.optString("ipv4_addr_in")
                    if (key.length != 44 || v4.isBlank()) continue
                    out += MozillaWgServer(
                        hostname = s.optString("hostname"),
                        countryCode = code,
                        countryName = c.optString("name"),
                        cityName = city.optString("name"),
                        ipv4 = v4,
                        ipv6 = s.optString("ipv6_addr_in"),
                        publicKey = key,
                        weight = s.optInt("weight", 100),
                        portRanges = ranges(s.optJSONArray("port_ranges")),
                        ipv4Gateway = s.optString("ipv4_gateway").ifBlank { "10.64.0.1" },
                    )
                }
            }
        }
        return out
    }

    private fun ranges(array: JSONArray?): List<IntRange> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val r = array.optJSONArray(i) ?: return@mapNotNull null
            val a = r.optInt(0, -1)
            val b = r.optInt(1, -1)
            if (a in 1..65_535 && b in a..65_535) a..b else null
        }
    }

    fun parseDevice(body: String): MozillaWgDevice {
        val o = JSONObject(body)
        val v4 = o.optString("ipv4_address")
        require(v4.isNotBlank()) { "Mozilla did not assign an address to this device" }
        return MozillaWgDevice(o.optString("pubkey"), v4, o.optString("ipv6_address"))
    }

    /** Device JSON of an existing key in `/api/v1/vpn/account` (`devices` array), or null. */
    fun deviceFromAccount(body: String, publicKey: String): MozillaWgDevice? {
        val devices = runCatching { JSONObject(body).optJSONArray("devices") }.getOrNull() ?: return null
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            if (d.optString("pubkey") == publicKey && d.optString("ipv4_address").isNotBlank()) {
                return MozillaWgDevice(publicKey, d.optString("ipv4_address"), d.optString("ipv6_address"))
            }
        }
        return null
    }

    /**
     * Servers for [country] ("" = any), weighted-random within the country so load spreads,
     * at most [max]. When [country] has none, the whole list.
     */
    fun pickServers(all: List<MozillaWgServer>, country: String, max: Int, random: Random = Random.Default): List<MozillaWgServer> {
        val inCountry = if (country.isBlank()) all else all.filter { it.countryCode.equals(country, true) }
        val pool = inCountry.ifEmpty { all }.toMutableList()
        val out = ArrayList<MozillaWgServer>()
        while (pool.isNotEmpty() && out.size < max) {
            val total = pool.sumOf { it.weight.coerceAtLeast(1) }
            var roll = random.nextInt(total)
            val idx = pool.indexOfFirst { roll -= it.weight.coerceAtLeast(1); roll < 0 }.coerceAtLeast(0)
            out += pool.removeAt(idx)
        }
        return out
    }

    /** UDP ports to try for plain WireGuard: 51820, then 53 (DNS-looking), then one random listened port. */
    fun udpPorts(server: MozillaWgServer, random: Random = Random.Default): List<Int> {
        val ranges = server.portRanges
        fun listens(p: Int) = ranges.isEmpty() || ranges.any { p in it }
        val out = mutableListOf<Int>()
        if (listens(RELAY_WG_PORT)) out += RELAY_WG_PORT
        if (ranges.any { 53 in it }) out += 53
        ranges.filter { it.last - it.first > 100 }.randomOrNull(random)?.let { r -> out += random.nextInt(r.first, r.last + 1) }
        return out.distinct().ifEmpty { listOf(RELAY_WG_PORT) }
    }

    /** Every attempt of [transport] on [server], in order. */
    fun routes(transport: FoxyTransport, server: MozillaWgServer, random: Random = Random.Default): List<MozillaWgRoute> = when (transport) {
        FoxyTransport.WIREGUARD -> udpPorts(server, random).take(2).map { MozillaWgRoute(transport, server, server.ipv4, it) }
        FoxyTransport.WG_TCP -> UDP2TCP_PORTS.take(2).map { MozillaWgRoute(transport, server, server.ipv4, it) }
        FoxyTransport.WG_SS -> listOf(MozillaWgRoute(transport, server, server.ipv4, random.nextInt(SS_PORTS.first, SS_PORTS.last + 1)))
        else -> emptyList()
    }

    /**
     * The tunnel for one route. [localUdpPort] is the loopback UDP port of the UDP-over-TCP
     * relay (WG_TCP only). Shadowsocks routes keep the relay's own WireGuard endpoint; the
     * Xray config carries it inside Shadowsocks.
     */
    fun tunnel(route: MozillaWgRoute, privateKey: String, device: MozillaWgDevice, localUdpPort: Int = 0, now: Long = System.currentTimeMillis()): WgTunnel {
        val (host, port) = when (route.transport) {
            FoxyTransport.WG_TCP -> "127.0.0.1" to localUdpPort
            FoxyTransport.WG_SS -> route.server.ipv4 to RELAY_WG_PORT
            else -> route.host to route.port
        }
        val addresses = listOfNotNull(
            device.ipv4.let { if ('/' in it) it else "$it/32" },
            device.ipv6.takeIf { it.isNotBlank() }?.let { if ('/' in it) it else "$it/128" },
        )
        return WgTunnel(
            id = "mozilla:${route.server.hostname}:${route.transport.id}:${route.port}",
            name = "${route.server.cityName.ifBlank { route.server.countryCode }} · ${route.server.hostname}",
            privateKey = privateKey,
            addresses = addresses,
            dns = listOf(route.server.ipv4Gateway),
            // Every variant re-encapsulates (UDP, TCP framing, Shadowsocks): 1280 fits mobile paths.
            mtu = 1280,
            peers = listOf(WgPeer(publicKey = route.server.publicKey, endpointHost = host, endpointPort = port, keepAliveSeconds = 25)),
            source = WgSource.MOZILLA,
            countryCode = route.server.countryCode.takeIf { it.length == 2 },
            server = route.server.hostname,
            addedAt = now,
        )
    }
}
