package com.uacspoofer.mobile.engine.foxy

import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** The measured result of one Mozilla country. [best] is null only when nothing answered. */
data class FoxyCountryResult(
    val code: String,
    val best: FoxyPingResult?,
    val tested: Int,
    val reachable: Int,
    val measuredAtMs: Long,
) {
    /** One round trip to the best edge of the country (TCP, or QUIC where TCP is blocked). */
    val pingMs: Int? get() = best?.pingMs

    /** TLS over TCP reached at least one edge (HTTP/1.1 and HTTP/2 will work). */
    val tlsOk: Boolean get() = best?.reachable == true
}

/**
 * Pings every Mozilla country, one row per country.
 *
 * What was wrong before: each screen pinged 2 random servers per country (often both in the
 * same city, often both quarantined-adjacent), counted a country as reachable only when the
 * TLS handshake completed (Iran's SNI filter resets most of them, so the row showed no ping
 * although the edge answered), and the country screen published nothing until the whole
 * sweep had finished. Here:
 * - candidates are spread across the country's cities (round robin, distinct hosts);
 * - each country gets a first round of [perCountry] servers and, if none answered, a second
 *   round with the next ones;
 * - the shown ping is the TCP round trip (QUIC version negotiation when TCP is blocked), with
 *   TLS-reachable edges preferred, so a country is "no ping" only when nothing answered;
 * - all countries run concurrently behind one global cap, and every country is published the
 *   moment its own result is in;
 * - results are cached per network for [CACHE_TTL_MS].
 */
object FoxyCountryPinger {
    const val PER_COUNTRY = 3
    const val PARALLEL = 12
    const val CACHE_TTL_MS = 10 * 60_000L
    private const val PER_PROBE_TIMEOUT_MS = 9_000L

    private data class CacheEntry(val results: Map<String, FoxyCountryResult>)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Cached results of this network that are still fresh. */
    fun cached(networkKey: String, now: Long = System.currentTimeMillis()): Map<String, FoxyCountryResult> =
        cache[networkKey]?.results.orEmpty().filterValues { now - it.measuredAtMs in 0 until CACHE_TTL_MS }

    fun clearCache() = cache.clear()

    /**
     * Up to [limit] candidates for [code], one city after another so a single dead city does
     * not sink the whole country; distinct host:port only.
     */
    fun pickCandidates(
        countries: List<VpnCountry>,
        code: String,
        limit: Int,
        random: Random? = Random.Default,
    ): List<ProxyCandidate> {
        fun <T> List<T>.order(): List<T> = if (random == null) this else shuffled(random)
        val byCity = ServerListClient.candidatesForCountry(countries, code)
            .distinctBy { it.host.lowercase(java.util.Locale.ROOT) to it.port }
            .groupBy { it.cityCode }
            .values
            .map { it.order().toMutableList() }
            .order()
        val out = ArrayList<ProxyCandidate>(limit)
        var progressed = true
        while (out.size < limit && progressed) {
            progressed = false
            for (city in byCity) {
                if (out.size >= limit) break
                if (city.isNotEmpty()) {
                    out += city.removeAt(0)
                    progressed = true
                }
            }
        }
        return out
    }

    /**
     * Measures [codes] (default: every country) and calls [onResult] for each country as soon
     * as it is known. Returns all results. Fresh cached results of [networkKey] are reused
     * unless [force].
     */
    suspend fun measureAll(
        countries: List<VpnCountry>,
        networkKey: String,
        codes: List<String> = countries.map { it.code }.distinct(),
        force: Boolean = false,
        perCountry: Int = PER_COUNTRY,
        parallel: Int = PARALLEL,
        now: () -> Long = System::currentTimeMillis,
        random: Random? = Random.Default,
        probe: suspend (ProxyCandidate) -> FoxyPingResult = { FoxyPing.measure(it.host, it.port) },
        onResult: (FoxyCountryResult) -> Unit = {},
    ): Map<String, FoxyCountryResult> = coroutineScope {
        val fresh = if (force) emptyMap() else cached(networkKey, now())
        val results = ConcurrentHashMap<String, FoxyCountryResult>()
        fresh.filterKeys { it in codes }.forEach { (code, result) ->
            results[code] = result
            onResult(result)
        }
        val gate = Semaphore(parallel.coerceIn(1, 32))
        val todo = codes.filterNot { results.containsKey(it) }
        todo.map { code ->
            launch {
                val pool = pickCandidates(countries, code, perCountry * 2, random)
                val measured = ArrayList<FoxyPingResult>()
                for (round in pool.chunked(perCountry.coerceAtLeast(1))) {
                    measured += round.map { candidate ->
                        async {
                            gate.withPermit {
                                try {
                                    withTimeoutOrNull(PER_PROBE_TIMEOUT_MS) { probe(candidate) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Throwable) {
                                    null
                                } ?: FoxyPingResult(candidate.host, candidate.port, null, null, null, "timeout")
                            }
                        }
                    }.awaitAll()
                    // Second round only when nothing of the first one answered at all.
                    if (measured.any { it.answered }) break
                }
                val best = measured.filter { it.answered }.minWithOrNull(FoxyPing.BEST_FIRST)
                val result = FoxyCountryResult(
                    code = code,
                    best = best,
                    tested = measured.size,
                    reachable = measured.count { it.reachable },
                    measuredAtMs = now(),
                )
                results[code] = result
                onResult(result)
            }
        }.forEach { it.join() }
        // Only countries that answered are cached, so a dead row is retried next time.
        val keep = cached(networkKey, now()) + results.filterValues { it.best != null }
        cache[networkKey] = CacheEntry(keep)
        results.toMap()
    }
}
