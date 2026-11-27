package com.uacspoofer.mobile.engine.wireguard

import com.uacspoofer.mobile.profiles.Base64Codec
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.zip.Inflater
import org.json.JSONObject

/**
 * Amnezia VPN share keys (`vpn://…`, what «Share» in the Amnezia app or a self-hosted Amnezia
 * server hands out): base64url of Qt's qCompress (4-byte big-endian length + zlib) of a JSON
 * document with one entry per protocol container.
 *
 * What Simorgh can run without new native code:
 *  - `amnezia-wireguard`, and `amnezia-awg` when its obfuscation values are neutral -> WireGuard
 *    engine (a real AmneziaWG config with junk packets needs the amneziawg-go core, which Simorgh
 *    does not ship; [WgConfigParser] refuses it with a clear message);
 *  - `amnezia-xray` (VLESS/REALITY) -> a `vless://` link for Parvaz.
 * OpenVPN, Cloak, IKEv2 and SOCKS containers are reported as unsupported.
 */
object AmneziaImport {
    data class Expanded(val wireGuardConfigs: List<String>, val xrayLinks: List<String>, val unsupported: List<String>, val name: String)

    fun isAmneziaKey(text: String): Boolean = text.trim().startsWith("vpn://", ignoreCase = true)

    fun expand(text: String): Expanded? {
        val t = text.trim()
        if (!isAmneziaKey(t)) return null
        val json = decodeDocument(t.substringAfter("://")) ?: throw WgParseException("کلید vpn:// امنزیا خوانده نشد")
        return parseDocument(json)
    }

    internal fun decodeDocument(payload: String): JSONObject? = runCatching {
        val bytes = Base64Codec.decode(payload.trim())
        val body = if (bytes.size > 4 && bytes[4].toInt() and 0xFF == 0x78) inflate(bytes.copyOfRange(4, bytes.size)) else bytes
        JSONObject(String(body, Charsets.UTF_8))
    }.getOrNull()

    internal fun parseDocument(doc: JSONObject): Expanded {
        val wg = ArrayList<String>()
        val links = ArrayList<String>()
        val unsupported = ArrayList<String>()
        val name = doc.optString("description").ifBlank { doc.optString("hostName") }.ifBlank { "Amnezia" }
        val containers = doc.optJSONArray("containers")
        for (i in 0 until (containers?.length() ?: 0)) {
            val c = containers!!.optJSONObject(i) ?: continue
            val kind = c.optString("container")
            when (kind) {
                "amnezia-wireguard", "amnezia-awg" -> {
                    val inner = c.optJSONObject(if (kind == "amnezia-awg") "awg" else "wireguard")
                    val cfg = inner?.optString("last_config")?.let { lc -> runCatching { JSONObject(lc).optString("config") }.getOrNull() }
                    if (!cfg.isNullOrBlank()) wg += fill(cfg, doc) else unsupported += kind
                }
                "amnezia-xray" -> {
                    val lc = c.optJSONObject("xray")?.optString("last_config")
                    val link = lc?.let { runCatching { vlessFromXray(JSONObject(it), name) }.getOrNull() }
                    if (link != null) links += link else unsupported += kind
                }
                else -> unsupported += kind.ifBlank { "unknown" }
            }
        }
        if (wg.isEmpty() && links.isEmpty()) {
            throw WgParseException(
                "این کلید امنزیا پروتکلی ندارد که سیمرغ اجرا کند (${unsupported.joinToString()}). " +
                    "در سرور امنزیا پروتکل WireGuard یا XRay را فعال کنید.",
            )
        }
        return Expanded(wg, links, unsupported, name)
    }

    /** Amnezia leaves `$PRIMARY_DNS` style placeholders in some exports. */
    private fun fill(cfg: String, doc: JSONObject): String = cfg
        .replace("\$PRIMARY_DNS", doc.optString("dns1").ifBlank { "1.1.1.1" })
        .replace("\$SECONDARY_DNS", doc.optString("dns2").ifBlank { "1.0.0.1" })

    /** The first VLESS outbound of an Xray JSON as a share link (TCP/REALITY or TLS). */
    internal fun vlessFromXray(cfg: JSONObject, name: String): String? {
        val outs = cfg.optJSONArray("outbounds") ?: return null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("protocol") != "vless") continue
            val vnext = o.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0) ?: continue
            val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: continue
            val stream = o.optJSONObject("streamSettings") ?: JSONObject()
            val security = stream.optString("security", "none")
            val q = linkedMapOf("type" to stream.optString("network", "tcp"), "security" to security, "encryption" to "none")
            user.optString("flow").takeIf { it.isNotBlank() }?.let { q["flow"] = it }
            if (security == "reality") {
                val r = stream.optJSONObject("realitySettings") ?: JSONObject()
                q["sni"] = r.optString("serverName")
                q["pbk"] = r.optString("publicKey")
                q["sid"] = r.optString("shortId")
                q["fp"] = r.optString("fingerprint", "chrome")
                r.optString("spiderX").takeIf { it.isNotBlank() }?.let { q["spx"] = it }
            } else if (security == "tls") {
                val t = stream.optJSONObject("tlsSettings") ?: JSONObject()
                q["sni"] = t.optString("serverName")
                t.optString("fingerprint").takeIf { it.isNotBlank() }?.let { q["fp"] = it }
            }
            val host = vnext.optString("address").let { if (it.contains(':')) "[$it]" else it }
            val query = q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
            return "vless://${enc(user.optString("id"))}@$host:${vnext.optInt("port", 443)}?$query#${enc(name)}"
        }
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 2 * 1024 * 1024) break
        }
        inf.end()
        return out.toByteArray()
    }
}
