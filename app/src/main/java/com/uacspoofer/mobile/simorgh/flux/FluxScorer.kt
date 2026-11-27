package com.uacspoofer.mobile.simorgh.flux

import kotlin.math.max
import kotlin.math.min

/**
 * One number per route, higher is better. Not latency alone: a 40 ms route that fails every
 * third connect loses to a steady 70 ms one. Weights tuned, without the
 * throughput and handshake terms Blackdey does not measure per route.
 */
object FluxScorer {
    data class Weights(
        val rtt: Double = 0.40,
        val success: Double = 0.30,
        val stability: Double = 0.15,
        val fresh: Double = 0.15,
    )

    val DEFAULT = Weights()

    /** A switch has to beat the current route by this fraction of its score (hysteresis). */
    const val SWITCH_MARGIN = 0.15
    private const val MIN_SWITCH_GAIN = 0.05

    fun score(m: FluxMetrics?, liveRttMs: Long?, now: Long, w: Weights = DEFAULT): Double {
        val rtt = liveRttMs ?: m?.p50
        val rttTerm = rtt?.let { 1.0 - min(it, 2_000L) / 2_000.0 } ?: 0.3
        val successTerm = m?.successEwma ?: 0.5
        val stabilityTerm = m?.let { mm ->
            val p50 = mm.p50
            val p95 = mm.p95
            if (p50 == null || p95 == null || mm.rtts.size < 3) 0.5
            else max(0.0, 1.0 - (p95 - p50).toDouble() / max(p50, 50L) / 3.0)
        } ?: 0.5
        val freshTerm = m?.lastOkAt?.takeIf { it > 0 }
            ?.let { 1.0 - min(1.0, (now - it) / (7 * 86_400_000.0)) } ?: 0.0
        var s = w.rtt * rttTerm + w.success * successTerm + w.stability * stabilityTerm + w.fresh * freshTerm
        s -= 0.08 * (m?.consecutiveFailures ?: 0)
        return s
    }

    fun shouldSwitch(current: Double, challenger: Double): Boolean =
        challenger - current > max(MIN_SWITCH_GAIN, current * SWITCH_MARGIN)
}
