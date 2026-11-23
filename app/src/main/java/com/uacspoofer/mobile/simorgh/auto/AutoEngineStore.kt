package com.uacspoofer.mobile.simorgh.auto

import android.content.Context
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.engine.EngineMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Last real outcome of an engine on this device: a live tunnel ping or a failure. */
@Immutable
data class EngineResult(val ok: Boolean, val latencyMs: Long?, val atMs: Long)

@Immutable
data class AutoEngineSettings(
    val enabled: Boolean = false,
    /** Fallback order, first tried first. Always holds every shipped engine exactly once. */
    val order: List<EngineMode> = DEFAULT_ORDER,
    /** Engines Auto may try. */
    val included: Set<EngineMode> = DEFAULT_ORDER.toSet() - EngineMode.UAC_DIRECT,
    /** Remember the engine that last connected on each network and try it first. */
    val networkMemory: Boolean = true,
    /** Reconnect when the tunnel's real URL test keeps failing. */
    val autoReconnect: Boolean = true,
    val results: Map<EngineMode, EngineResult> = emptyMap(),
    /** network key -> engine that last connected there. */
    val memory: Map<String, EngineMode> = emptyMap(),
) {
    fun plan(networkKey: String?): List<EngineMode> {
        val base = order.filter { it in included }
        val remembered = networkKey?.let { memory[it] }?.takeIf { networkMemory && it in included }
        return if (remembered == null) base else listOf(remembered) + (base - remembered)
    }

    companion object {
        val DEFAULT_ORDER = listOf(
            EngineMode.XRAY_CF,
            EngineMode.FOXY_FIREFOX,
            EngineMode.TOR_WEBTUNNEL,
            EngineMode.UAC_POW,
            EngineMode.UAC_DIRECT,
        )
    }
}

class AutoEngineStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val settings: StateFlow<AutoEngineSettings> = mutable.asStateFlow()

    fun snapshot(): AutoEngineSettings = mutable.value

    @Synchronized
    fun update(transform: (AutoEngineSettings) -> AutoEngineSettings) {
        val next = normalize(transform(mutable.value))
        mutable.value = next
        write(next)
    }

    fun recordResult(mode: EngineMode, ok: Boolean, latencyMs: Long?) = update {
        it.copy(results = it.results + (mode to EngineResult(ok, latencyMs, System.currentTimeMillis())))
    }

    fun rememberNetwork(networkKey: String, mode: EngineMode) = update {
        val trimmed = (it.memory - networkKey).entries.toList().takeLast(MAX_NETWORKS - 1)
            .associate { e -> e.key to e.value }
        it.copy(memory = trimmed + (networkKey to mode))
    }

    private fun normalize(value: AutoEngineSettings): AutoEngineSettings {
        val order = (value.order.distinct() + EngineMode.entries).distinct().filter { it in EngineMode.entries }
        return value.copy(order = order, included = value.included.intersect(EngineMode.entries.toSet()))
    }

    private fun read(): AutoEngineSettings = runCatching {
        val order = prefs.getString(KEY_ORDER, null)?.split(',')?.mapNotNull(::modeOf)
            ?: AutoEngineSettings.DEFAULT_ORDER
        val included = prefs.getString(KEY_INCLUDED, null)?.split(',')?.mapNotNull(::modeOf)?.toSet()
            ?: AutoEngineSettings().included
        val results = buildMap {
            val json = JSONObject(prefs.getString(KEY_RESULTS, "{}") ?: "{}")
            json.keys().forEach { key ->
                val mode = modeOf(key) ?: return@forEach
                val o = json.optJSONObject(key) ?: return@forEach
                put(mode, EngineResult(o.optBoolean("ok"), o.optLong("ms", -1L).takeIf { it >= 0 }, o.optLong("at")))
            }
        }
        val memory = buildMap {
            val json = JSONObject(prefs.getString(KEY_MEMORY, "{}") ?: "{}")
            json.keys().forEach { key -> modeOf(json.optString(key))?.let { put(key, it) } }
        }
        normalize(
            AutoEngineSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                order = order,
                included = included,
                networkMemory = prefs.getBoolean(KEY_NET_MEMORY, true),
                autoReconnect = prefs.getBoolean(KEY_RECONNECT, true),
                results = results,
                memory = memory,
            ),
        )
    }.getOrDefault(AutoEngineSettings())

    private fun write(value: AutoEngineSettings) {
        val results = JSONObject()
        value.results.forEach { (mode, r) ->
            results.put(mode.id, JSONObject().put("ok", r.ok).put("ms", r.latencyMs ?: -1L).put("at", r.atMs))
        }
        val memory = JSONObject()
        value.memory.forEach { (k, v) -> memory.put(k, v.id) }
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_ORDER, value.order.joinToString(",") { it.id })
            .putString(KEY_INCLUDED, value.included.joinToString(",") { it.id })
            .putBoolean(KEY_NET_MEMORY, value.networkMemory)
            .putBoolean(KEY_RECONNECT, value.autoReconnect)
            .putString(KEY_RESULTS, results.toString())
            .putString(KEY_MEMORY, memory.toString())
            .apply()
    }

    companion object {
        private const val PREFS = "simorgh_auto_engine_v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ORDER = "order"
        private const val KEY_INCLUDED = "included"
        private const val KEY_NET_MEMORY = "net_memory"
        private const val KEY_RECONNECT = "auto_reconnect"
        private const val KEY_RESULTS = "results"
        private const val KEY_MEMORY = "memory"
        private const val MAX_NETWORKS = 24

        private fun modeOf(id: String): EngineMode? = EngineMode.entries.firstOrNull { it.id == id.trim() }

        @Volatile private var instance: AutoEngineStore? = null

        fun get(context: Context): AutoEngineStore = instance ?: synchronized(this) {
            instance ?: AutoEngineStore(context.applicationContext).also { instance = it }
        }
    }
}
