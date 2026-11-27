package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * How the TLS hop to Mozilla's Fastly edge (HTTPS / MASQUE, the free Firefox account path) is
 * shaped against SNI filtering. Every method keeps the real SNI and the real certificate check.
 *
 * Not offered, on purpose:
 * - ECH: the fork supports it together with uTLS, but `*.m1.fastly-masque.net` publishes no
 *   HTTPS/SVCB record with an ECH config, so sing-box always fails with "no ECH config found".
 * - Chain through WARP: needs the Psiphon/WARP core running in proxy mode beside Firefox VPN
 *   inside one service; see docs/HANDOFF.md.
 */
enum class AntiFilterMethod(val id: String, val en: String, val fa: String) {
    AUTO("auto", "Auto", "خودکار"),
    PLAIN("plain", "Plain TLS", "TLS ساده"),
    /** ClientHello split into several TCP segments at the SNI (sing-box `tls.fragment`). */
    FRAGMENT("fragment", "TCP fragment", "تکه‌تکه (TCP)"),
    /** ClientHello split into several TLS records (sing-box `tls.record_fragment`). */
    RECORD_FRAGMENT("record_fragment", "TLS record fragment", "تکه‌تکه (رکورد TLS)"),
    /** Another address of the same Fastly edge block that this network still reaches. */
    CLEAN_IP("clean_ip", "Clean IP", "IP تمیز");

    /** Fragmenting only changes TCP TLS; HTTP/3 (QUIC) is unaffected by it. */
    val tcpOnly: Boolean get() = this == FRAGMENT || this == RECORD_FRAGMENT

    companion object {
        fun from(raw: String?): AntiFilterMethod = entries.firstOrNull { it.id == raw } ?: AUTO
        val concrete: List<AntiFilterMethod> get() = entries.filter { it != AUTO }
    }
}

/** What the edge ping saw on this network, the input of [AntiFilterPlan]. */
enum class EdgeSignal {
    /** At least one edge completed TLS with its real SNI. */
    TLS_OK,
    /** TCP or QUIC answered but every TLS handshake was reset: SNI filtering. */
    TLS_RESET,
    /** Nothing answered (wrong DNS answer, blocked addresses). */
    SILENT,
}

/** One anti-filter attempt: its method and the time it may take. Pure. */
data class AntiFilterStep(val method: AntiFilterMethod, val timeoutMs: Long)

internal object AntiFilterPlan {
    /** Time one method may take in Auto: core start + latency + 4 s throughput, a few times. */
    fun timeoutFor(method: AntiFilterMethod): Long = when (method) {
        AntiFilterMethod.PLAIN -> 45_000L
        AntiFilterMethod.FRAGMENT -> 30_000L
        AntiFilterMethod.RECORD_FRAGMENT -> 25_000L
        AntiFilterMethod.CLEAN_IP -> 35_000L
        AntiFilterMethod.AUTO -> 0L
    }

    /** A step shorter than this cannot finish one path test; it is left out. */
    const val MIN_STEP_MS = 15_000L

    fun order(signal: EdgeSignal, lastGood: AntiFilterMethod?): List<AntiFilterMethod> {
        val base = when (signal) {
            EdgeSignal.TLS_OK -> listOf(AntiFilterMethod.PLAIN, AntiFilterMethod.FRAGMENT, AntiFilterMethod.CLEAN_IP, AntiFilterMethod.RECORD_FRAGMENT)
            // PLAIN stays in: it is the one that tries HTTP/3 (QUIC is a different handshake).
            EdgeSignal.TLS_RESET -> listOf(AntiFilterMethod.FRAGMENT, AntiFilterMethod.RECORD_FRAGMENT, AntiFilterMethod.PLAIN, AntiFilterMethod.CLEAN_IP)
            // Only another address can help when the resolved ones never answer.
            EdgeSignal.SILENT -> listOf(AntiFilterMethod.CLEAN_IP)
        }
        val last = lastGood?.takeIf { it != AntiFilterMethod.AUTO && (signal != EdgeSignal.SILENT || it == AntiFilterMethod.CLEAN_IP) }
        return (listOfNotNull(last) + base).distinct()
    }

    /**
     * Steps for one connect. A method the user picked runs alone with the whole [budgetMs].
     * Auto walks [order] with each method's own timeout, cut to what is left of [budgetMs]
     * (the Auto engine's per-engine budget minus what pass, list and ping already used).
     */
    fun steps(chosen: AntiFilterMethod, signal: EdgeSignal, lastGood: AntiFilterMethod?, budgetMs: Long): List<AntiFilterStep> {
        if (chosen != AntiFilterMethod.AUTO) return listOf(AntiFilterStep(chosen, budgetMs.coerceAtLeast(MIN_STEP_MS)))
        var left = budgetMs
        val out = mutableListOf<AntiFilterStep>()
        for (method in order(signal, lastGood)) {
            if (left < MIN_STEP_MS) break
            val ms = minOf(timeoutFor(method), left)
            out += AntiFilterStep(method, ms)
            left -= ms
        }
        // Always one attempt, even on a budget that is already spent.
        return out.ifEmpty { listOf(AntiFilterStep(order(signal, lastGood).first(), MIN_STEP_MS)) }
    }

    fun signalOf(pings: List<FoxyPingResult>): EdgeSignal = when {
        pings.any { it.reachable } -> EdgeSignal.TLS_OK
        pings.any { it.answered } -> EdgeSignal.TLS_RESET
        else -> EdgeSignal.SILENT
    }
}

/** Per-network memory: clean edge IPs and the method that last connected. JSON, pure codec. */
internal data class AntiFilterMemory(
    val cleanIps: Map<String, CleanEdgeEntry> = emptyMap(),
    val lastMethod: Map<String, AntiFilterMethod> = emptyMap(),
) {
    data class CleanEdgeEntry(val ips: List<String>, val atMs: Long)

    fun freshIps(netKey: String, nowMs: Long, maxAgeMs: Long = CLEAN_IP_TTL_MS): List<String> =
        cleanIps[netKey]?.takeIf { nowMs - it.atMs in 0..maxAgeMs }?.ips.orEmpty()

    /** Working IPs first ([winner] at the head), capped; the oldest networks are dropped. */
    fun withIps(netKey: String, ips: List<String>, nowMs: Long, winner: String? = null): AntiFilterMemory {
        val merged = (listOfNotNull(winner) + ips + freshIps(netKey, nowMs)).filter(FoxyHosts::isValidIpAddress).distinct().take(MAX_IPS)
        if (merged.isEmpty()) return copy(cleanIps = cleanIps - netKey)
        val next = (cleanIps - netKey) + (netKey to CleanEdgeEntry(merged, nowMs))
        return copy(cleanIps = next.entries.sortedByDescending { it.value.atMs }.take(MAX_NETWORKS).associate { it.key to it.value })
    }

    fun withoutIp(netKey: String, ip: String): AntiFilterMemory {
        val entry = cleanIps[netKey] ?: return this
        val left = entry.ips - ip
        return copy(cleanIps = if (left.isEmpty()) cleanIps - netKey else cleanIps + (netKey to entry.copy(ips = left)))
    }

    fun withMethod(netKey: String, method: AntiFilterMethod): AntiFilterMemory {
        if (method == AntiFilterMethod.AUTO) return this
        val next = (lastMethod - netKey) + (netKey to method)
        return copy(lastMethod = next.entries.toList().takeLast(MAX_NETWORKS).associate { it.key to it.value })
    }

    fun encode(): String {
        val ips = JSONObject()
        cleanIps.forEach { (k, v) -> ips.put(k, JSONObject().put("ips", JSONArray(v.ips)).put("at", v.atMs)) }
        val methods = JSONObject()
        lastMethod.forEach { (k, v) -> methods.put(k, v.id) }
        return JSONObject().put("v", 1).put("clean", ips).put("method", methods).toString()
    }

    companion object {
        const val MAX_IPS = 8
        const val MAX_NETWORKS = 12
        const val CLEAN_IP_TTL_MS = 24 * 60 * 60_000L

        fun decode(raw: String?): AntiFilterMemory {
            if (raw.isNullOrBlank()) return AntiFilterMemory()
            return runCatching {
                val root = JSONObject(raw)
                val clean = root.optJSONObject("clean") ?: JSONObject()
                val ips = clean.keys().asSequence().mapNotNull { key ->
                    val o = clean.optJSONObject(key) ?: return@mapNotNull null
                    val arr = o.optJSONArray("ips") ?: return@mapNotNull null
                    val list = (0 until arr.length()).map { arr.optString(it) }.filter(FoxyHosts::isValidIpAddress).take(MAX_IPS)
                    if (list.isEmpty()) null else key to CleanEdgeEntry(list, o.optLong("at"))
                }.toMap()
                val m = root.optJSONObject("method") ?: JSONObject()
                val methods = m.keys().asSequence().mapNotNull { key ->
                    val method = AntiFilterMethod.entries.firstOrNull { it.id == m.optString(key) }
                    method?.takeIf { it != AntiFilterMethod.AUTO }?.let { key to it }
                }.toMap()
                AntiFilterMemory(ips, methods)
            }.getOrDefault(AntiFilterMemory())
        }
    }
}

internal class FoxyAntiFilterStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("foxy_antifilter_v1", Context.MODE_PRIVATE)
    @Volatile private var memory = AntiFilterMemory.decode(prefs.getString(KEY, null))

    fun snapshot(): AntiFilterMemory = memory

    @Synchronized
    fun update(transform: (AntiFilterMemory) -> AntiFilterMemory) {
        val next = transform(memory)
        memory = next
        prefs.edit().putString(KEY, next.encode()).apply()
    }

    companion object {
        private const val KEY = "memory"
        @Volatile private var instance: FoxyAntiFilterStore? = null
        fun get(context: Context): FoxyAntiFilterStore = instance ?: synchronized(this) {
            instance ?: FoxyAntiFilterStore(context.applicationContext).also { instance = it }
        }
    }
}
