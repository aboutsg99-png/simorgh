package com.uacspoofer.mobile.engine.wireguard

import java.net.URI
import java.net.URLDecoder
import com.uacspoofer.mobile.profiles.Base64Codec
import java.util.UUID

/**
 * WireGuard tunnels for Simorgh's WireGuard engine. They run on the bundled Xray core's
 * `wireguard` outbound (userspace, no kernel module, no new native library).
 *
 * Sources: the user's own `.conf` / `wireguard://` link, Proton VPN (signed in with the user's own
 * account), Windscribe (configs the user generated in their own account), and plain-WireGuard
 * Amnezia exports.
 */
enum class WgSource(val id: String, val label: String) {
    OWN("own", "WireGuard"),
    PROTON("proton", "Proton VPN"),
    WINDSCRIBE("windscribe", "Windscribe"),
    AMNEZIA("amnezia", "Amnezia");

    companion object {
        fun of(id: String?): WgSource = entries.firstOrNull { it.id == id } ?: OWN
    }
}

data class WgPeer(
    val publicKey: String,
    val presharedKey: String = "",
    val endpointHost: String,
    val endpointPort: Int,
    val allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    val keepAliveSeconds: Int = 0,
) {
    val endpoint: String get() = if (endpointHost.contains(':')) "[$endpointHost]:$endpointPort" else "$endpointHost:$endpointPort"
}

data class WgTunnel(
    val id: String,
    val name: String,
    val privateKey: String,
    /** Interface addresses with prefix, e.g. 10.2.0.2/32, fd00::2/128. */
    val addresses: List<String>,
    val dns: List<String> = emptyList(),
    val mtu: Int = 1280,
    val peers: List<WgPeer>,
    /** Cloudflare-style 3-byte `reserved` (client id), empty when none. */
    val reserved: List<Int> = emptyList(),
    val source: WgSource = WgSource.OWN,
    val countryCode: String? = null,
    /** Provider's server name (e.g. Proton `NL-FREE#12`), shown under the name. */
    val server: String = "",
    val addedAt: Long = 0L,
) {
    val primary: WgPeer get() = peers.first()
    val hasIpv6: Boolean get() = addresses.any { it.contains(':') }

    /** Never print the private key. */
    override fun toString(): String = "WgTunnel($name, ${primary.endpoint}, ${source.id})"
}

class WgParseException(message: String) : IllegalArgumentException(message)

object WgConfigParser {
    private val AMNEZIA_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")

    /** True when [text] looks like one or more WireGuard configs or links. */
    fun looksLikeWireGuard(text: String): Boolean {
        val t = text.trim()
        return t.contains("[Interface]", ignoreCase = true) ||
            t.startsWith("wireguard://", ignoreCase = true) || t.startsWith("wg://", ignoreCase = true) ||
            Regex("(?i)(?<![a-z])(wireguard|wg)://").containsMatchIn(t)
    }

    /** Every tunnel in [text]: INI sections and/or links, one per line. */
    fun parseAll(text: String, source: WgSource = WgSource.OWN, nameHint: String? = null, now: Long = System.currentTimeMillis()): List<WgTunnel> {
        val out = ArrayList<WgTunnel>()
        val links = Regex("(?i)(?<![a-z])(?:wireguard|wg)://[^\\s<>\"']+").findAll(text).map { it.value }.toList()
        links.forEach { out += parseLink(it, source, now) }
        if (text.contains("[Interface]", ignoreCase = true)) {
            val blocks = text.split(Regex("(?im)^(?=\\s*\\[Interface\\])")).filter { it.contains("[Interface]", true) }
            blocks.forEachIndexed { i, b ->
                val name = nameHint?.let { if (blocks.size > 1) "$it ${i + 1}" else it }
                out += parseIni(b, source, name, now)
            }
        }
        if (out.isEmpty()) throw WgParseException("هیچ کانفیگ WireGuard پیدا نشد")
        return out
    }

    fun parseIni(text: String, source: WgSource = WgSource.OWN, name: String? = null, now: Long = System.currentTimeMillis()): WgTunnel {
        var section = ""
        val iface = LinkedHashMap<String, String>()
        val peers = ArrayList<Map<String, String>>()
        var current: LinkedHashMap<String, String>? = null
        var comment: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#")) {
                // "# Name = X" (Proton) or a free-form first comment becomes the name hint.
                val c = line.removePrefix("#").trim()
                if (comment == null && c.isNotBlank()) comment = c.substringAfter("=", c).trim().take(60)
                return@forEach
            }
            if (line.isEmpty() || line.startsWith(";")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                if (section == "peer") current = LinkedHashMap<String, String>().also { peers += it }
                return@forEach
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).substringBefore(" #").trim()
            when (section) {
                "interface" -> iface[key] = if (iface[key] != null && key in setOf("address", "dns")) iface[key] + "," + value else value
                "peer" -> current?.put(key, value)
            }
        }
        rejectAmnezia(iface)
        val privateKey = iface["privatekey"] ?: throw WgParseException("PrivateKey در بخش [Interface] نیست")
        val addresses = split(iface["address"]).ifEmpty { throw WgParseException("Address در بخش [Interface] نیست") }
        if (peers.isEmpty()) throw WgParseException("بخش [Peer] پیدا نشد")
        val parsedPeers = peers.map { p ->
            val (host, port) = splitEndpoint(p["endpoint"] ?: throw WgParseException("Endpoint برای Peer نیست"))
            WgPeer(
                publicKey = WgKeys.requireKey(p["publickey"] ?: throw WgParseException("PublicKey برای Peer نیست"), "PublicKey"),
                presharedKey = p["presharedkey"]?.let { WgKeys.requireKey(it, "PresharedKey") }.orEmpty(),
                endpointHost = host,
                endpointPort = port,
                allowedIps = split(p["allowedips"]).ifEmpty { listOf("0.0.0.0/0", "::/0") },
                keepAliveSeconds = p["persistentkeepalive"]?.toIntOrNull()?.coerceIn(0, 3600) ?: 0,
            )
        }
        val tunnelName = name ?: comment ?: parsedPeers.first().endpointHost
        return WgTunnel(
            id = newId(),
            name = tunnelName.take(60),
            privateKey = WgKeys.requireKey(privateKey, "PrivateKey"),
            addresses = addresses.map(::withPrefix),
            dns = split(iface["dns"]).filter { it.isNotBlank() },
            mtu = iface["mtu"]?.toIntOrNull()?.coerceIn(576, 1500) ?: 1280,
            peers = parsedPeers,
            source = source,
            countryCode = countryFromName(tunnelName),
            addedAt = now,
        )
    }

    /**
     * `wireguard://<privateKey>@host:port?publickey=..&address=..&mtu=..&reserved=1,2,3&presharedkey=..#name`
     * (v2rayN / Hiddify / sing-box share form). `wg://` is accepted as an alias.
     */
    fun parseLink(link: String, source: WgSource = WgSource.OWN, now: Long = System.currentTimeMillis()): WgTunnel {
        val normalized = link.trim().replaceFirst(Regex("(?i)^wg://"), "wireguard://")
        val uri = runCatching { URI(normalized) }.getOrElse { throw WgParseException("لینک WireGuard خراب است") }
        val query = (uri.rawQuery ?: "").split('&').filter { it.contains('=') }.associate {
            decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('='))
        }
        rejectAmnezia(query)
        val secret = decode(uri.rawUserInfo ?: query["privatekey"] ?: query["secretkey"] ?: throw WgParseException("کلید خصوصی در لینک نیست"))
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: throw WgParseException("آدرس سرور در لینک نیست")
        val port = uri.port.takeIf { it in 1..65535 } ?: throw WgParseException("پورت سرور در لینک نیست")
        val name = uri.rawFragment?.let(::decode)?.takeIf { it.isNotBlank() } ?: host
        val reserved = query["reserved"]?.let(::parseReserved).orEmpty()
        return WgTunnel(
            id = newId(),
            name = name.take(60),
            privateKey = WgKeys.requireKey(secret, "PrivateKey"),
            addresses = split(query["address"] ?: query["ip"] ?: throw WgParseException("Address در لینک نیست")).map(::withPrefix),
            dns = split(query["dns"]),
            mtu = query["mtu"]?.toIntOrNull()?.coerceIn(576, 1500) ?: 1280,
            peers = listOf(
                WgPeer(
                    publicKey = WgKeys.requireKey(query["publickey"] ?: query["peerpublickey"] ?: throw WgParseException("PublicKey در لینک نیست"), "PublicKey"),
                    presharedKey = (query["presharedkey"] ?: query["psk"])?.let { WgKeys.requireKey(it, "PresharedKey") }.orEmpty(),
                    endpointHost = host,
                    endpointPort = port,
                    allowedIps = split(query["allowedips"]).ifEmpty { listOf("0.0.0.0/0", "::/0") },
                    keepAliveSeconds = query["keepalive"]?.toIntOrNull()?.coerceIn(0, 3600) ?: 0,
                ),
            ),
            reserved = reserved,
            source = source,
            countryCode = countryFromName(name),
            addedAt = now,
        )
    }

    /** Back to a portable `.conf` (for export / share). */
    fun toIni(t: WgTunnel): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${t.privateKey}")
        appendLine("Address = ${t.addresses.joinToString(", ")}")
        if (t.dns.isNotEmpty()) appendLine("DNS = ${t.dns.joinToString(", ")}")
        appendLine("MTU = ${t.mtu}")
        t.peers.forEach { p ->
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${p.publicKey}")
            if (p.presharedKey.isNotBlank()) appendLine("PresharedKey = ${p.presharedKey}")
            appendLine("AllowedIPs = ${p.allowedIps.joinToString(", ")}")
            appendLine("Endpoint = ${p.endpoint}")
            if (p.keepAliveSeconds > 0) appendLine("PersistentKeepalive = ${p.keepAliveSeconds}")
        }
    }

    /**
     * AmneziaWG adds junk packets and header obfuscation that plain WireGuard (and Xray's
     * outbound) cannot produce. A config with all of them at their neutral values is plain
     * WireGuard and is accepted.
     */
    private fun rejectAmnezia(kv: Map<String, String>) {
        val present = kv.filterKeys { it in AMNEZIA_KEYS }
        if (present.isEmpty()) return
        val neutral = present.all { (k, v) ->
            val n = v.trim().toLongOrNull()
            when (k) {
                "h1" -> n == 1L
                "h2" -> n == 2L
                "h3" -> n == 3L
                "h4" -> n == 4L
                "i1", "i2", "i3", "i4", "i5" -> v.isBlank()
                else -> n == 0L
            }
        }
        if (!neutral) throw WgParseException(
            "این کانفیگ AmneziaWG است (Jc/S1/H1…). هستهٔ Xray فقط WireGuard ساده را اجرا می‌کند؛ " +
                "از پنل Amnezia کانفیگ «WireGuard» یا «Xray» بگیرید.",
        )
    }

    private fun parseReserved(raw: String): List<Int> {
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 3 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) return parts.map { it.toInt() }
        // Base64 client id (Cloudflare WARP "client_id").
        val bytes = runCatching { Base64Codec.decode(raw.trim()) }.getOrNull()
        if (bytes != null && bytes.size == 3) return bytes.map { it.toInt() and 0xFF }
        throw WgParseException("مقدار reserved نامعتبر است")
    }

    internal fun splitEndpoint(raw: String): Pair<String, Int> {
        val s = raw.trim()
        val host: String
        val port: String
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) throw WgParseException("Endpoint نامعتبر: $s")
            host = s.substring(1, close)
            port = s.substring(close + 1).removePrefix(":")
        } else {
            val colon = s.lastIndexOf(':')
            if (colon <= 0 || s.count { it == ':' } > 1) throw WgParseException("Endpoint نامعتبر: $s")
            host = s.substring(0, colon)
            port = s.substring(colon + 1)
        }
        val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw WgParseException("پورت Endpoint نامعتبر است")
        if (host.isBlank() || host.any { it.isWhitespace() }) throw WgParseException("Endpoint نامعتبر: $s")
        return host to p
    }

    private fun withPrefix(addr: String): String {
        val a = addr.trim()
        if (a.contains('/')) return a
        return if (a.contains(':')) "$a/128" else "$a/32"
    }

    private fun split(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** Provider names like "NL-FREE#12", "US-NY#3", "Windscribe Amsterdam" -> ISO code hint. */
    internal fun countryFromName(name: String): String? {
        val m = Regex("^([A-Za-z]{2})[-_ #]").find(name.trim())?.groupValues?.get(1)?.uppercase()
        if (m != null && m in java.util.Locale.getISOCountries()) return m
        return com.uacspoofer.mobile.simorgh.flux.CountryHint.of(name)
    }

    private fun newId() = "wg:" + UUID.randomUUID().toString()
}
