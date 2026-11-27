package com.uacspoofer.mobile.simorgh.flux

import android.content.Context
import android.net.ConnectivityManager
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.pow.PowNetworkScoreboard
import com.uacspoofer.mobile.engine.wireguard.WgProbe
import com.uacspoofer.mobile.engine.wireguard.WgResult
import com.uacspoofer.mobile.engine.wireguard.WgSource
import com.uacspoofer.mobile.engine.wireguard.WgTunnel
import com.uacspoofer.mobile.engine.wireguard.WgTunnelStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.SubscriptionStore
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android side of Flux: turns an engine's configs/tunnels into [FluxRoute]s, plans them with
 * [FluxPlanner], and records what really happened so the next connect on this network is better.
 *
 * Network lookups here: only DNS for config host names, and only when the user restricted the
 * IP version (needed to know which family a host has). Probes go through the route itself.
 */
object FluxPicker {
    /** Engines whose routes Flux chooses. */
    fun supports(mode: EngineMode): Boolean = mode == EngineMode.XRAY_CF || mode == EngineMode.WIREGUARD

    fun networkKey(context: Context): String = runCatching { PowNetworkScoreboard.networkKey(context) }.getOrDefault("unknown")

    /** Routes of [mode] in Flux's order for this network and the user's choice. */
    suspend fun plan(context: Context, mode: EngineMode): List<FluxRoute> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val store = FluxStore.get(app)
        val prefs = store.prefs()
        val routes = routesFor(app, mode)
        if (routes.isEmpty()) return@withContext emptyList()
        val resolved = if (prefs.ipMode == IpMode.BOTH) emptyMap() else resolveFamilies(routes.map { it.host })
        val result = FluxPlanner.plan(
            FluxPlanner.Input(
                routes = routes,
                state = store.state(),
                net = networkKey(app),
                now = System.currentTimeMillis(),
                country = prefs.country,
                mode = prefs.ipMode,
                resolved = resolved,
                hasV6 = hasGlobalIpv6(app),
                budget = 24,
            ),
        )
        if (result.skipped.isNotEmpty()) {
            log("Flux ${mode.id}: ${result.ordered.size} routes planned, skipped ${result.skipped.values.groupingBy { it }.eachCount()}")
        }
        result.ordered
    }

    fun routesFor(context: Context, mode: EngineMode): List<FluxRoute> = when (mode) {
        EngineMode.XRAY_CF -> xrayRoutes(context)
        EngineMode.WIREGUARD -> WgTunnelStore.get(context).snapshot().tunnels.map(::wgRoute)
        else -> emptyList()
    }

    private fun xrayRoutes(context: Context): List<FluxRoute> {
        val library = ProfileStore(context).snapshot()
        val subIds = runCatching { SubscriptionStore(context).all().flatMap { it.profileIds }.toSet() }.getOrDefault(emptySet())
        return library.customProfiles.filterNot { it.isBuiltIn || it.id.startsWith(ProxyProfile.AUTO_RUN_PREFIX) }.map { p ->
            FluxRoute(
                id = p.id,
                engine = EngineMode.XRAY_CF.id,
                // Imported links keep a loopback endpoint; the real server is in the link itself.
                host = runCatching { DirectCompatProfileParser.parse(p)?.address }.getOrNull() ?: p.serverHost,
                provenCountry = p.country.countryCode?.takeIf { it.length == 2 && it != "XX" },
                label = p.name,
                source = if (p.id in subIds) "sub" else "own",
            )
        }
    }

    fun wgRoute(t: WgTunnel) = FluxRoute(
        id = t.id,
        engine = EngineMode.WIREGUARD.id,
        host = t.primary.endpointHost,
        provenCountry = t.countryCode,
        label = t.name,
        source = if (t.source == WgSource.OWN) "own" else "provider",
    )

    /** Make [route] the one its engine connects with next. */
    fun apply(context: Context, route: FluxRoute) {
        when (route.engine) {
            EngineMode.XRAY_CF.id -> runCatching { ProfileStore(context).select(route.id) }
            // The WireGuard engine reads its plan itself; nothing to select.
        }
    }

    fun recordSuccess(context: Context, route: FluxRoute, rttMs: Long?) {
        val store = FluxStore.get(context)
        val net = networkKey(context)
        val prefs = store.prefs()
        val now = System.currentTimeMillis()
        store.update { s ->
            val next = FluxMemory.recordSuccess(s, net, route.key, now, rttMs)
            val best = listOf(route.key) + FluxMemory.best(next, net, prefs.country, prefs.ipMode)
            FluxMemory.setBest(next, net, prefs.country, prefs.ipMode, best)
        }
    }

    fun recordFailure(context: Context, route: FluxRoute, reason: FailReason) {
        val store = FluxStore.get(context)
        val net = networkKey(context)
        val prefs = store.prefs()
        store.update { s ->
            val next = FluxMemory.recordFailure(s, net, route.key, System.currentTimeMillis(), reason)
            FluxMemory.setBest(next, net, prefs.country, prefs.ipMode, FluxMemory.best(next, net, prefs.country, prefs.ipMode) - route.key)
        }
    }

    /**
     * Measures the head of the WireGuard plan in parallel (real request through each tunnel) and
     * returns it reordered: answered fastest first, then the rest in plan order. Failures of a
     * pre-connect probe only reorder; the breaker opens on real connect failures.
     */
    suspend fun raceWireGuard(context: Context, planned: List<FluxRoute>, size: Int): List<FluxRoute> {
        if (size <= 0 || planned.size <= 1) return planned
        val store = WgTunnelStore.get(context)
        val byId = store.snapshot().tunnels.associateBy { it.id }
        val head = planned.take(size).mapNotNull { byId[it.id] }
        val outcomes = WgProbe.measureAll(context, head, parallel = 3) { o ->
            runCatching { store.recordResult(o.tunnelId, WgResult(o.latencyMs != null, o.latencyMs, System.currentTimeMillis(), o.error)) }
        }
        val ok = outcomes.filter { it.latencyMs != null }.sortedBy { it.latencyMs }
        val okIds = ok.map { it.tunnelId }
        ok.forEach { o -> planned.firstOrNull { it.id == o.tunnelId }?.let { r ->
            FluxStore.get(context).update { s -> FluxMemory.recordSuccess(s, networkKey(context), r.key, System.currentTimeMillis(), o.latencyMs) }
        } }
        val first = okIds.mapNotNull { id -> planned.firstOrNull { it.id == id } }
        return first + planned.filterNot { it.id in okIds }
    }

    /**
     * Parvaz (Xray) race: the head of the plan is measured concurrently with the real delay
     * test (temporary Xray each, two at a time); the first config that answers wins, others
     * get a short grace window to beat it. Returns the plan reordered (answered fastest
     * first, then the rest in plan order) and the ids that answered.
     * Before, Auto connected config after config with a 50 s timeout each, so one dead
     * config at the top of the plan cost up to 50 s before the next one was even tried.
     */
    suspend fun raceXray(context: Context, planned: List<FluxRoute>, size: Int): Pair<List<FluxRoute>, Set<String>> =
        withContext(Dispatchers.IO) { raceXrayInternal(context, planned, size) }

    private suspend fun raceXrayInternal(context: Context, planned: List<FluxRoute>, size: Int): Pair<List<FluxRoute>, Set<String>> {
        if (size <= 1 || planned.size <= 1) return planned to emptySet()
        val app = context.applicationContext
        val byId = runCatching { ProfileStore(app).snapshot().customProfiles.associateBy { it.id } }.getOrDefault(emptyMap())
        val head = planned.take(size).filter { it.id in byId }
        if (head.size <= 1) return planned to emptySet()
        val tester = com.uacspoofer.mobile.profiles.ProfileLatencyTester(app)
        val wins = FluxRace.firstWins(
            items = head,
            parallel = XRAY_RACE_PARALLEL,
            perItemMs = XRAY_RACE_PER_ITEM_MS,
            graceMs = XRAY_RACE_GRACE_MS,
            totalMs = XRAY_RACE_TOTAL_MS,
        ) { route -> byId[route.id]?.let { tester.measure(it) } }
        val net = networkKey(app)
        wins.forEach { (route, ms) ->
            FluxStore.get(app).update { s -> FluxMemory.recordSuccess(s, net, route.key, System.currentTimeMillis(), ms) }
        }
        val first = wins.map { it.first }
        log("Flux xray race: ${wins.size}/${head.size} answered" + (wins.firstOrNull()?.let { " best ${it.second} ms" } ?: ""))
        return (first + planned.filterNot { r -> first.any { it.id == r.id } }) to first.map { it.id }.toSet()
    }

    const val XRAY_RACE_PARALLEL = 2
    const val XRAY_RACE_PER_ITEM_MS = 12_000L
    const val XRAY_RACE_GRACE_MS = 1_500L
    const val XRAY_RACE_TOTAL_MS = 20_000L

    private suspend fun resolveFamilies(hosts: List<String>): Map<String, Set<Family>> = coroutineScope {
        hosts.distinct().filter { Family.ofLiteral(it) == null }.take(64).map { h ->
            async(Dispatchers.IO) {
                h to (withTimeoutOrNull(1_500L) {
                    runCatching { InetAddress.getAllByName(h).map { if (it is Inet6Address) Family.V6 else Family.V4 }.toSet() }.getOrNull()
                } ?: emptySet())
            }
        }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
    }

    private fun hasGlobalIpv6(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val global = lp.linkAddresses.any { la ->
            val a = la.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress &&
                (a.address[0].toInt() and 0xFE) != 0xFC
        }
        val route = lp.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
        global && route
    }.getOrNull()

    private fun log(message: String) = runCatching { AppLogRepository.info(LogSource.SERVICE, message) }
}
