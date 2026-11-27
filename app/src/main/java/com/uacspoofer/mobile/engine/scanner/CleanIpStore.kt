package com.uacspoofer.mobile.engine.scanner

import android.content.Context
import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.ProfileNetworks
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.vpn.ConnectEdgePoolStore
import com.uacspoofer.mobile.vpn.NetworkFingerprintResolver
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scanner results, per network, and «use for my configs»: the clean IPs become the connect pool
 * Parvaz already dials for Cloudflare-fronted configs on this network (the same pool its own
 * rescue scan fills), so the anti-filter fragmenting Parvaz applies runs over the clean IP.
 * REALITY and ECH configs are left alone: they must dial their own address.
 */
class CleanIpStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("simorgh_clean_ip_v1", Context.MODE_PRIVATE)

    data class Saved(val ips: List<String>, val sni: String, val atMs: Long)

    fun last(): Saved? = runCatching {
        val o = JSONObject(prefs.getString("last", null) ?: return null)
        val a = o.getJSONArray("ips")
        Saved((0 until a.length()).map { a.getString(it) }, o.optString("sni"), o.optLong("at"))
    }.getOrNull()

    fun save(ips: List<String>, sni: String) {
        prefs.edit().putString("last", JSONObject().put("ips", JSONArray(ips.take(50))).put("sni", sni).put("at", System.currentTimeMillis()).toString()).apply()
    }

    fun userRanges(): String = prefs.getString("ranges", "").orEmpty()
    fun saveUserRanges(text: String) = prefs.edit().putString("ranges", text.take(20_000)).apply()

    /** Configs that can be carried by any Cloudflare edge (HTTP-shaped transport, TLS, own SNI/Host). */
    fun frontableProfiles(): List<ProxyProfile> = ProfileStore(app).snapshot().customProfiles.filter { p ->
        val id = DirectCompatProfileParser.parseIdentity(p) ?: return@filter false
        id.security == "tls" && !id.pinnedToOwnAddress &&
            (id.network == "ws" || id.network == "grpc" || id.network == "httpupgrade" || ProfileNetworks.isXhttp(id.network)) &&
            (id.sni.isNotBlank() || id.host.isNotBlank())
    }

    /** Returns how many configs got the pool. Each config keeps its own port (443, 2053, 8443…). */
    fun applyToConfigs(ips: List<String>): Int {
        val network = runCatching { NetworkFingerprintResolver(app).capture() }.getOrNull() ?: return 0
        if (ips.isEmpty()) return 0
        val pool = ConnectEdgePoolStore(app)
        val profiles = frontableProfiles()
        profiles.forEach { p ->
            val port = DirectCompatProfileParser.parse(p)?.port ?: 443
            pool.save(network, p.id, ips.take(8).mapIndexed { i, ip -> MciEdge(ip, port, "clean-$i") })
        }
        return profiles.size
    }
}
