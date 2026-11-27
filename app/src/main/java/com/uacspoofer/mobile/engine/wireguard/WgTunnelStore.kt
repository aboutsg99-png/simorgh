package com.uacspoofer.mobile.engine.wireguard

import android.content.Context
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Last real measurement of a tunnel: through the tunnel, never a plain ICMP ping. */
@Immutable
data class WgResult(val ok: Boolean, val latencyMs: Long?, val atMs: Long, val error: String = "")

@Immutable
data class WgLibrary(
    val tunnels: List<WgTunnel> = emptyList(),
    /** Selected tunnel id, or null for «خودکار» (Flux picks the best). */
    val selectedId: String? = null,
    val results: Map<String, WgResult> = emptyMap(),
    val storageError: Boolean = false,
) {
    val selected: WgTunnel? get() = tunnels.firstOrNull { it.id == selectedId }
}

/** WireGuard tunnels, encrypted at rest (they carry private keys). */
class WgTunnelStore private constructor(context: Context) {
    private val secret = SecretFile(context, "wireguard-tunnels.bin", KEY_ALIAS)
    private val mutable = MutableStateFlow(load())
    val library: StateFlow<WgLibrary> = mutable.asStateFlow()

    fun snapshot(): WgLibrary = mutable.value

    fun importText(text: String, source: WgSource = WgSource.OWN, nameHint: String? = null): List<WgTunnel> {
        val parsed = WgConfigParser.parseAll(text, source, nameHint)
        add(parsed)
        return parsed
    }

    @Synchronized
    fun add(items: List<WgTunnel>) = change { lib ->
        // The same server+key is one tunnel: keep the old id (and its measurements).
        val byKey = lib.tunnels.associateBy { identity(it) }
        val merged = lib.tunnels.toMutableList()
        items.forEach { t ->
            val old = byKey[identity(t)]
            if (old != null) merged[merged.indexOf(old)] = t.copy(id = old.id, addedAt = old.addedAt) else merged += t
        }
        lib.copy(tunnels = merged.take(MAX_TUNNELS))
    }

    /** Replace every tunnel of [source] (a provider refresh), keeping ids where the server is the same. */
    @Synchronized
    fun replaceSource(source: WgSource, items: List<WgTunnel>) = change { lib ->
        val old = lib.tunnels.filter { it.source == source }.associateBy { it.primary.endpoint }
        val fresh = items.map { t -> old[t.primary.endpoint]?.let { t.copy(id = it.id) } ?: t }
        val others = lib.tunnels.filter { it.source != source }
        val ids = (others + fresh).map { it.id }.toSet()
        lib.copy(
            tunnels = (others + fresh).take(MAX_TUNNELS),
            selectedId = lib.selectedId?.takeIf { it in ids },
            results = lib.results.filterKeys { it in ids },
        )
    }

    @Synchronized
    fun delete(ids: Set<String>) = change { lib ->
        lib.copy(
            tunnels = lib.tunnels.filterNot { it.id in ids },
            selectedId = lib.selectedId?.takeUnless { it in ids },
            results = lib.results - ids,
        )
    }

    @Synchronized
    fun select(id: String?) = change { lib -> lib.copy(selectedId = id?.takeIf { i -> lib.tunnels.any { it.id == i } }) }

    @Synchronized
    fun rename(id: String, name: String) = change { lib ->
        lib.copy(tunnels = lib.tunnels.map { if (it.id == id) it.copy(name = name.trim().take(60).ifBlank { it.name }) else it })
    }

    @Synchronized
    fun recordResult(id: String, result: WgResult) {
        // Results are not secret, but keeping one file keeps the store simple; writes are rare.
        change { lib -> lib.copy(results = lib.results + (id to result)) }
    }

    private fun identity(t: WgTunnel) = "${t.primary.endpoint}|${t.primary.publicKey}|${WgKeys.publicKey(t.privateKey)}"

    private inline fun change(block: (WgLibrary) -> WgLibrary) {
        val current = mutable.value
        if (current.storageError) throw IllegalStateException("فضای امن کانفیگ‌های WireGuard در دسترس نیست")
        val next = block(current)
        secret.write(encode(next))
        mutable.value = next
    }

    private fun load(): WgLibrary = runCatching {
        decode(secret.read() ?: return WgLibrary())
    }.getOrElse {
        AppLogRepository.warning(LogSource.SERVICE, "WireGuard store unreadable", it)
        WgLibrary(storageError = true)
    }

    /** Forget everything (also clears the storage error after a Keystore reset). */
    @Synchronized
    fun wipe() {
        secret.delete()
        mutable.value = WgLibrary()
    }

    companion object {
        private const val KEY_ALIAS = "simorgh_wireguard_v1"
        const val MAX_TUNNELS = 400

        @Volatile private var instance: WgTunnelStore? = null

        fun get(context: Context): WgTunnelStore = instance ?: synchronized(this) {
            instance ?: WgTunnelStore(context.applicationContext).also { instance = it }
        }

        internal fun encode(lib: WgLibrary): String {
            val arr = JSONArray()
            lib.tunnels.forEach { t ->
                val peers = JSONArray()
                t.peers.forEach { p ->
                    peers.put(
                        JSONObject().put("pub", p.publicKey).put("psk", p.presharedKey).put("host", p.endpointHost)
                            .put("port", p.endpointPort).put("allowed", JSONArray(p.allowedIps)).put("ka", p.keepAliveSeconds),
                    )
                }
                arr.put(
                    JSONObject().put("id", t.id).put("name", t.name).put("priv", t.privateKey)
                        .put("addr", JSONArray(t.addresses)).put("dns", JSONArray(t.dns)).put("mtu", t.mtu)
                        .put("peers", peers).put("res", JSONArray(t.reserved)).put("src", t.source.id)
                        .put("cc", t.countryCode ?: "").put("server", t.server).put("at", t.addedAt),
                )
            }
            val res = JSONObject()
            lib.results.forEach { (k, r) ->
                res.put(k, JSONObject().put("ok", r.ok).put("ms", r.latencyMs ?: -1).put("at", r.atMs).put("err", r.error))
            }
            return JSONObject().put("v", 1).put("tunnels", arr).put("selected", lib.selectedId ?: "").put("results", res).toString()
        }

        internal fun decode(raw: String): WgLibrary {
            val root = JSONObject(raw)
            fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
            val tunnels = root.optJSONArray("tunnels")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val peersJ = o.optJSONArray("peers") ?: JSONArray()
                    val peers = (0 until peersJ.length()).mapNotNull { j ->
                        val p = peersJ.optJSONObject(j) ?: return@mapNotNull null
                        WgPeer(p.optString("pub"), p.optString("psk"), p.optString("host"), p.optInt("port"),
                            strings(p.optJSONArray("allowed")), p.optInt("ka"))
                    }
                    if (peers.isEmpty()) return@mapNotNull null
                    val resJ = o.optJSONArray("res")
                    WgTunnel(
                        id = o.optString("id"), name = o.optString("name"), privateKey = o.optString("priv"),
                        addresses = strings(o.optJSONArray("addr")), dns = strings(o.optJSONArray("dns")),
                        mtu = o.optInt("mtu", 1280), peers = peers,
                        reserved = resJ?.let { r -> (0 until r.length()).map { r.optInt(it) } }.orEmpty(),
                        source = WgSource.of(o.optString("src")),
                        countryCode = o.optString("cc").takeIf { it.length == 2 },
                        server = o.optString("server"), addedAt = o.optLong("at"),
                    )
                }
            }.orEmpty()
            val results = buildMap {
                val r = root.optJSONObject("results") ?: JSONObject()
                r.keys().forEach { k ->
                    val o = r.optJSONObject(k) ?: return@forEach
                    put(k, WgResult(o.optBoolean("ok"), o.optLong("ms", -1).takeIf { it >= 0 }, o.optLong("at"), o.optString("err")))
                }
            }
            return WgLibrary(tunnels, root.optString("selected").takeIf { id -> tunnels.any { it.id == id } }, results)
        }
    }
}
