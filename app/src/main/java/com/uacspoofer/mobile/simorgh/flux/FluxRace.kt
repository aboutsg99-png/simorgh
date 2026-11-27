package com.uacspoofer.mobile.simorgh.flux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * First-success race: probes [items] concurrently (at most [parallel] at once). Once the first
 * success lands, the others get [graceMs] more to beat it, then everything still running is
 * cancelled. The whole race never takes longer than [totalMs].
 *
 * Returns the successes, fastest first. Failures and probes cut off by the grace window are
 * simply absent: callers keep them in their original order after the winners.
 */
object FluxRace {
    suspend fun <T> firstWins(
        items: List<T>,
        parallel: Int,
        perItemMs: Long,
        graceMs: Long,
        totalMs: Long,
        clock: () -> Long = System::currentTimeMillis,
        probe: suspend (T) -> Long?,
    ): List<Pair<T, Long>> {
        if (items.isEmpty()) return emptyList()
        val gate = Semaphore(parallel.coerceIn(1, items.size))
        val done = Channel<Pair<T, Long?>>(Channel.UNLIMITED)
        val wins = ArrayList<Pair<T, Long>>()
        coroutineScope {
            val jobs = items.map { item ->
                launch {
                    val ms = gate.withPermit {
                        try {
                            withTimeoutOrNull(perItemMs) { probe(item) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    done.send(item to ms)
                }
            }
            withTimeoutOrNull(totalMs) {
                var received = 0
                var firstAt = -1L
                var open = true
                while (open && received < items.size) {
                    val next = if (firstAt < 0) {
                        done.receive()
                    } else {
                        val left = graceMs - (clock() - firstAt)
                        if (left <= 0) null else withTimeoutOrNull(left) { done.receive() }
                    }
                    if (next == null) {
                        open = false
                    } else {
                        received++
                        val ms = next.second
                        if (ms != null) {
                            wins += next.first to ms
                            if (firstAt < 0) firstAt = clock()
                        }
                    }
                }
            }
            jobs.forEach { it.cancel() }
        }
        done.close()
        return wins.sortedBy { it.second }
    }
}
