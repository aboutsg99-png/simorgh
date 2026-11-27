package com.uacspoofer.mobile.simorgh.flux

import org.json.JSONArray
import org.json.JSONObject

/** How one route has done on one network. The best route on MCI is often dead on Irancell. */
data class FluxMetrics(
    /** Exponentially weighted success, 0..1; 0.5 for a route never tried. */
    val successEwma: Double = 0.5,
    /** Recent real-request round trips through the route (ms), newest last. */
    val rtts: List<Long> = emptyList(),
    val okCount: Int = 0,
    val failCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastOkAt: Long = 0L,
    val lastFailAt: Long = 0L,
    /** Circuit breaker: not tried again before this time. */
    val cooldownUntil: Long = 0L,
    val lastReason: FailReason? = null,
) {
    val p50: Long? get() = percentile(50)
    val p95: Long? get() = percentile(95)

    private fun percentile(p: Int): Long? {
        if (rtts.isEmpty()) return null
        val sorted = rtts.sorted()
        return sorted[((sorted.size - 1) * p / 100.0).toInt()]
    }

    fun coolingDown(now: Long) = cooldownUntil > now

    companion object { const val RTT_KEEP = 16 }
}

/** What Flux learned about one network: the routes in use per policy (`country|mode`), primary first. */
data class FluxNet(
    val lastSeen: Long = 0L,
    val best: Map<String, List<String>> = emptyMap(),
)

/** Everything Flux keeps. Per-device, about the networks this device used; excluded from backups. */
data class FluxState(
    val nets: Map<String, FluxNet> = emptyMap(),
    /** `net|routeKey` -> metrics. */
    val metrics: Map<String, FluxMetrics> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val n = JSONObject()
        nets.forEach { (k, v) ->
            val best = JSONObject()
            v.best.forEach { (p, routes) -> best.put(p, JSONArray(routes)) }
            n.put(k, JSONObject().put("seen", v.lastSeen).put("best", best))
        }
        val m = JSONObject()
        metrics.forEach { (k, v) ->
            m.put(
                k,
                JSONObject()
                    .put("s", v.successEwma)
                    .put("r", JSONArray(v.rtts))
                    .put("ok", v.okCount)
                    .put("f", v.failCount)
                    .put("cf", v.consecutiveFailures)
                    .put("lo", v.lastOkAt)
                    .put("lf", v.lastFailAt)
                    .put("cd", v.cooldownUntil)
                    .put("why", v.lastReason?.code ?: ""),
            )
        }
        return JSONObject().put("v", VERSION).put("nets", n).put("metrics", m)
    }

    companion object {
        const val VERSION = 1

        fun metricsKey(net: String, routeKey: String) = "$net|$routeKey"
        fun policyKey(country: String?, mode: IpMode) = "${country?.uppercase() ?: "*"}|${mode.code}"

        fun fromJson(raw: String?): FluxState = runCatching {
            if (raw.isNullOrBlank()) return FluxState()
            val root = JSONObject(raw)
            if (root.optInt("v") != VERSION) return FluxState()
            val nets = buildMap {
                val n = root.optJSONObject("nets") ?: JSONObject()
                n.keys().forEach { k ->
                    val o = n.optJSONObject(k) ?: return@forEach
                    val bestJ = o.optJSONObject("best") ?: JSONObject()
                    val best = buildMap {
                        bestJ.keys().forEach { p ->
                            val a = bestJ.optJSONArray(p) ?: return@forEach
                            put(p, (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() })
                        }
                    }
                    put(k, FluxNet(o.optLong("seen"), best))
                }
            }
            val metrics = buildMap {
                val m = root.optJSONObject("metrics") ?: JSONObject()
                m.keys().forEach { k ->
                    val o = m.optJSONObject(k) ?: return@forEach
                    val r = o.optJSONArray("r") ?: JSONArray()
                    put(
                        k,
                        FluxMetrics(
                            successEwma = o.optDouble("s", 0.5),
                            rtts = (0 until r.length()).map { r.optLong(it) },
                            okCount = o.optInt("ok"),
                            failCount = o.optInt("f"),
                            consecutiveFailures = o.optInt("cf"),
                            lastOkAt = o.optLong("lo"),
                            lastFailAt = o.optLong("lf"),
                            cooldownUntil = o.optLong("cd"),
                            lastReason = o.optString("why").takeIf { it.isNotBlank() }?.let(FailReason::of),
                        ),
                    )
                }
            }
            FluxState(nets, metrics)
        }.getOrDefault(FluxState())
    }
}

/**
 * Pure transformations of [FluxState]; every change Flux makes to what it remembers goes
 * through here, so the unit tests exercise the code the app runs.
 */
object FluxMemory {
    /** Weight of the newest sample: roughly the last ten attempts matter. */
    private const val ALPHA = 0.2
    /** Consecutive failures that open the breaker. */
    const val BREAKER_THRESHOLD = 3
    private const val COOLDOWN_BASE_MS = 60_000L
    private const val COOLDOWN_MAX_MS = 6 * 3600_000L
    /** A route that keeps failing and almost never worked is parked for a day. */
    const val QUARANTINE_MS = 24 * 3600_000L
    private const val NET_PRUNE_MS = 30L * 24 * 3600_000L
    private const val MAX_METRICS = 2_000

    fun metrics(s: FluxState, net: String, routeKey: String): FluxMetrics? =
        s.metrics[FluxState.metricsKey(net, routeKey)]

    fun recordSuccess(s: FluxState, net: String, routeKey: String, now: Long, rttMs: Long?): FluxState {
        val key = FluxState.metricsKey(net, routeKey)
        val m = s.metrics[key] ?: FluxMetrics()
        val next = m.copy(
            successEwma = m.successEwma * (1 - ALPHA) + ALPHA,
            rtts = if (rttMs == null) m.rtts else (m.rtts + rttMs).takeLast(FluxMetrics.RTT_KEEP),
            okCount = m.okCount + 1,
            consecutiveFailures = 0,
            lastOkAt = now,
            cooldownUntil = 0L,
            lastReason = null,
        )
        return markSeen(s.copy(metrics = s.metrics + (key to next)), net, now)
    }

    fun recordFailure(s: FluxState, net: String, routeKey: String, now: Long, reason: FailReason): FluxState {
        val key = FluxState.metricsKey(net, routeKey)
        val m = s.metrics[key] ?: FluxMetrics()
        val streak = m.consecutiveFailures + 1
        val ewma = m.successEwma * (1 - ALPHA)
        val cooldown = when {
            m.failCount + 1 >= 8 && ewma < 0.1 -> now + QUARANTINE_MS
            streak >= BREAKER_THRESHOLD ->
                now + minOf(COOLDOWN_MAX_MS, COOLDOWN_BASE_MS shl minOf(16, streak - BREAKER_THRESHOLD))
            else -> 0L
        }
        val next = m.copy(
            successEwma = ewma,
            failCount = m.failCount + 1,
            consecutiveFailures = streak,
            lastFailAt = now,
            cooldownUntil = cooldown,
            lastReason = reason,
        )
        return markSeen(s.copy(metrics = s.metrics + (key to next)), net, now)
    }

    fun setBest(s: FluxState, net: String, country: String?, mode: IpMode, routes: List<String>): FluxState =
        updateNet(s, net) { it.copy(best = it.best + (FluxState.policyKey(country, mode) to routes.distinct().take(4))) }

    fun best(s: FluxState, net: String, country: String?, mode: IpMode): List<String> =
        s.nets[net]?.best?.get(FluxState.policyKey(country, mode)).orEmpty()

    fun markSeen(s: FluxState, net: String, now: Long): FluxState = updateNet(s, net) { it.copy(lastSeen = now) }

    /** Networks unseen for a month and what was learned on them are dropped; metrics are capped. */
    fun prune(s: FluxState, now: Long): FluxState {
        val stale = s.nets.filter { now - it.value.lastSeen > NET_PRUNE_MS }.keys
        var metrics = s.metrics.filterKeys { k -> stale.none { k.startsWith("$it|") } }
        if (metrics.size > MAX_METRICS) {
            metrics = metrics.entries.sortedByDescending { maxOf(it.value.lastOkAt, it.value.lastFailAt) }
                .take(MAX_METRICS).associate { it.key to it.value }
        }
        return s.copy(nets = s.nets - stale, metrics = metrics)
    }

    /** Forget everything about routes that no longer exist (a deleted config). */
    fun retain(s: FluxState, liveRouteKeys: Set<String>): FluxState = s.copy(
        metrics = s.metrics.filterKeys { k -> k.substringAfter('|') in liveRouteKeys },
        nets = s.nets.mapValues { (_, n) -> n.copy(best = n.best.mapValues { (_, r) -> r.filter { it in liveRouteKeys } }) },
    )

    private inline fun updateNet(s: FluxState, net: String, f: (FluxNet) -> FluxNet): FluxState =
        s.copy(nets = s.nets + (net to f(s.nets[net] ?: FluxNet())))
}
