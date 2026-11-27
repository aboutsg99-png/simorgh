package com.uacspoofer.mobile.engine.foxy

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.engine.tor.TorTunRelayConfig
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.DirectDomainWhitelist
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.settings.TunBackend
import com.uacspoofer.mobile.vpn.SocksTunRelay
import com.uacspoofer.mobile.vpn.TunStats
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Engine 5, Rubah (Firefox VPN). Flow, all off the main thread and time-bounded:
 * 1. The user's own Firefox account -> OAuth access token (refreshed when stale).
 * 2. Guardian (vpn.mozilla.org) -> short-lived proxy pass for that account.
 * 3. Mozilla's server list -> real TCP+TLS ping to the chosen country's edge servers.
 * 4. sing-box HTTP CONNECT/MASQUE to the fastest reachable server, trying HTTP/3, then
 *    HTTP/2, then HTTP/1.1. Each attempt is measured through the tunnel (latency and
 *    bytes received in 4 s). The first path that is not weak wins; otherwise the best
 *    usable one. If none is usable the caller switches to the fallback engine.
 * 5. TUN -> hev/zeptun -> sing-box SOCKS.
 */
internal class FoxyConnectionCoordinator(private val service: VpnService) {
    private val app = service.applicationContext
    private val box = FoxyBox(app)
    private val tunRelay = SocksTunRelay()
    private val opMutex = Mutex()
    @Volatile private var scope = newScope()
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var session: Session? = null

    private data class Session(
        val candidate: ProxyCandidate,
        val edgeAddress: String?,
        val version: Int,
        val pass: ProxyPass,
        val port: Int,
        val proxyMode: Boolean,
        val paused: Boolean = false,
        val iranRuleSet: String? = null,
        val antiFilter: AntiFilterMethod = AntiFilterMethod.PLAIN,
    )

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun socksPort(): Int = session?.port ?: 0

    fun isRunning(): Boolean {
        val current = session ?: return false
        return box.isRunning() && (current.proxyMode || tunRelay.isRunning())
    }

    fun tunStats(): TunStats = runCatching { tunRelay.stats() }.getOrDefault(TunStats.ZERO)

    fun activeLabel(): String = session?.let {
        "${FoxyProtocol.labelFor(it.version)} · ${it.antiFilter.en} · ${it.candidate.countryCode} · ${it.candidate.host}"
    }.orEmpty()

    suspend fun connect(
        settings: AdvancedSettingsData,
        backend: TunBackend,
        establishTun: (AdvancedSettingsData) -> ParcelFileDescriptor?,
    ) = withContext(Dispatchers.IO) {
        stopInternal()
        scope = newScope()
        val store = FoxyEngineStore.get(app)
        val prefs = store.snapshot()
        val proxyMode = settings.connectionMode == CONNECTION_MODE_PROXY
        val account = FoxyAccount.get(app)
        if (!account.hasStoredSession()) throw FoxyNotSignedInException()
        box.binary() ?: throw FoxyBinaryMissingException()

        publish(FoxyPhase.PREPARING, "دریافت مجوز VPN و فهرست سرورهای موزیلا")
        val startedAt = System.currentTimeMillis()
        val auto = runCatching { com.uacspoofer.mobile.simorgh.auto.AutoConnector.running() }.getOrDefault(false)
        // The pass, the server list and the edge ping do not depend on each other's result
        // beyond list -> ping, so they run concurrently instead of strictly one after another.
        val (pass, ranked) = kotlinx.coroutines.coroutineScope {
            val passJob = async { bounded(90_000L, "proxy pass") { mintPass(account) } }
            val rankJob = async {
                val countries = bounded(30_000L, "server list") { loadCountries() }
                val candidates = candidatesFor(countries, prefs.countryCode)
                if (candidates.isEmpty()) throw FoxyUnusableException("No Mozilla server for ${prefs.countryCode.ifBlank { "auto" }}")
                publish(FoxyPhase.PINGING, "اندازه‌گیری پینگ واقعی سرورها")
                bounded(30_000L, "ping") { FoxyPing.rank(candidates.take(16), parallel = 6) }
            }
            passJob.await() to rankJob.await()
        }
        val port = if (proxyMode) settings.socksPort else prefs.socksPort
        val iranRuleSet = iranRuleSetIfEnabled()
        val versions = versionOrder(prefs)
        val netKey = runCatching { com.uacspoofer.mobile.engine.pow.PowNetworkScoreboard.networkKey(app) }.getOrDefault("unknown")
        val antiStore = FoxyAntiFilterStore.get(app)
        val signal = AntiFilterPlan.signalOf(ranked.map { it.second })
        // Inside Auto the whole Firefox VPN attempt has one budget; the methods share what the
        // pass, list and ping left of it (minus a few seconds for the device tunnel).
        val budget = if (auto) {
            (com.uacspoofer.mobile.simorgh.auto.AutoConnector.remainingMs()
                ?: (com.uacspoofer.mobile.simorgh.auto.AutoConnector.timeoutMs(com.uacspoofer.mobile.engine.EngineMode.FOXY_FIREFOX) -
                    (System.currentTimeMillis() - startedAt))) - TUN_RESERVE_MS
        } else MANUAL_BUDGET_MS
        val steps = AntiFilterPlan.steps(prefs.antiFilter, signal, antiStore.snapshot().lastMethod[netKey], budget)
        AppLogger.i(TAG, "edge signal $signal; anti-filter steps ${steps.joinToString { "${it.method.id}/${it.timeoutMs / 1000}s" }}")

        // TLS-reachable edges first; edges that only answered TCP or QUIC stay as candidates
        // because HTTP/3 (UDP) or a fragmented ClientHello can still work where a plain TLS
        // handshake was reset.
        val usable = ranked.filter { it.second.answered }
        val tried = mutableListOf<FoxyPathResult>()
        var winner: Session? = null
        var winnerMethod = AntiFilterMethod.PLAIN
        for (step in steps) {
            coroutineContext.ensureActive()
            val found = withTimeoutOrNull(step.timeoutMs) {
                tryMethod(step.method, ranked, usable, versions, pass, port, proxyMode, iranRuleSet, netKey, tried)
            }
            if (found != null) {
                winner = found
                winnerMethod = step.method
                break
            }
            runCatching { box.stop() }
            AppLogger.i(TAG, "anti-filter ${step.method.id}: no usable path")
        }
        if (winner == null && usable.isEmpty()) {
            throw FoxyUnusableException("No Mozilla edge server answered (TCP, TLS or QUIC) and no clean edge address was found")
        }
        val chosen = winner ?: throw FoxyUnusableException("No HTTP version reached a usable speed")
        // The best-of path may not be the one the core runs now (a later, weaker try was stopped).
        if (!box.isRunning() || box.port != chosen.port || activeTarget != chosen) {
            startBox(chosen)
        }
        store.update { it.copy(lastGoodVersion = chosen.version) }
        antiStore.update { memory ->
            val m = memory.withMethod(netKey, winnerMethod)
            if (winnerMethod == AntiFilterMethod.CLEAN_IP && chosen.edgeAddress != null) {
                m.withIps(netKey, emptyList(), System.currentTimeMillis(), winner = chosen.edgeAddress)
            } else m
        }

        if (!proxyMode) {
            publish(FoxyPhase.TESTING, "ساخت تونل دستگاه")
            val mtu = TorTunRelayConfig.relayMtu(null, settings.tunMtu)
            val descriptor = checkNotNull(establishTun(settings.copy(tunMtu = mtu))) {
                "Android could not establish the VPN interface"
            }
            tun = descriptor
            try {
                tunRelay.start(
                    service, backend, descriptor,
                    SocksTunRelay.Config(chosen.port, mtu, udp = true, fakeDns = TorTunRelayConfig.MAP_DNS),
                ) {
                    "foxy/hev.yml" to TorTunRelayConfig.yaml(
                        mtu = mtu,
                        socksPort = chosen.port,
                        tunIpv4 = settings.tunAddress,
                        blockUdp = false,
                        bigBuffers = true,
                    )
                }
            } catch (error: Throwable) {
                tun = null
                runCatching { descriptor.close() }
                throw error
            }
        }
        session = chosen
        startRenewal()
        publish(
            FoxyPhase.CONNECTED,
            "وصل شد · ${FoxyProtocol.labelFor(chosen.version)}" +
                (if (chosen.antiFilter != AntiFilterMethod.PLAIN) " · ${chosen.antiFilter.fa}" else "") +
                " · ${chosen.candidate.countryName.ifBlank { chosen.candidate.countryCode }}",
            server = chosen.candidate.host,
            countryCode = chosen.candidate.countryCode,
            version = chosen.version,
        )
    }

    /**
     * One anti-filter method over the measured edges: the first path that is not weak wins,
     * otherwise the fastest usable version of the first edge that had one. Null when none.
     */
    private suspend fun tryMethod(
        method: AntiFilterMethod,
        ranked: List<Pair<ProxyCandidate, FoxyPingResult>>,
        usable: List<Pair<ProxyCandidate, FoxyPingResult>>,
        versions: List<Int>,
        pass: ProxyPass,
        port: Int,
        proxyMode: Boolean,
        iranRuleSet: String?,
        netKey: String,
        tried: MutableList<FoxyPathResult>,
    ): Session? {
        // Two TLS-reachable edges when there are any; edges whose TLS handshake was reset only
        // get a third slot (FoxyPathPlan).
        val pick: List<Pair<ProxyCandidate, FoxyPingResult>> = when (method) {
            AntiFilterMethod.CLEAN_IP -> cleanEdges(ranked, netKey)
            else -> FoxyPathPlan.pickServers(usable.map { it.second }).map { usable[it] }
        }
        for ((candidate, ping) in pick) {
            val perServer = mutableListOf<Pair<Int, FoxyPathResult>>()
            val serverVersions = FoxyPathPlan.versionsFor(ping, versions)
                .let { all -> if (method.tcpOnly) all.filter { it != 3 } else all }
            for (version in serverVersions) {
                coroutineContext.ensureActive()
                publish(
                    FoxyPhase.TESTING,
                    "آزمایش ${FoxyProtocol.labelFor(version)} · ${method.fa} روی ${candidate.countryName.ifBlank { candidate.countryCode }} (پینگ ${ping.pingMs ?: "?"} ms)",
                    server = candidate.host,
                    countryCode = candidate.countryCode,
                )
                val attempt = Session(candidate, ping.address, version, pass, port, proxyMode, iranRuleSet = iranRuleSet, antiFilter = method)
                val result = tryPath(attempt)
                tried += result
                FoxyStatusStore.update { it.copy(tried = tried.toList()) }
                AppLogger.i(TAG, "path ${FoxyProtocol.labelFor(version)} ${method.id} ${candidate.host}@${ping.address}: ok=${result.ok} " +
                    "latency=${result.latencyMs} bps=${result.bytesPerSecond} ${result.error}")
                if (result.ok && isGood(result)) return attempt
                runCatching { box.stop() }
                if (result.ok) perServer += version to result
            }
            val best = perServer.maxByOrNull { it.second.bytesPerSecond ?: 0L }
            if (best != null) {
                return Session(candidate, ping.address, best.first, pass, port, proxyMode, iranRuleSet = iranRuleSet, antiFilter = method)
            }
            if (method == AntiFilterMethod.CLEAN_IP && ping.address != null) {
                FoxyAntiFilterStore.get(app).update { it.withoutIp(netKey, ping.address) }
            }
        }
        return null
    }

    /**
     * Clean edge addresses for the best-measured server name: cached ones for this network, then
     * a short scan of the edge block. Each becomes an edge with that address; the SNI, Host and
     * certificate check stay the server's own name.
     */
    private suspend fun cleanEdges(ranked: List<Pair<ProxyCandidate, FoxyPingResult>>, netKey: String): List<Pair<ProxyCandidate, FoxyPingResult>> {
        val target = (ranked.firstOrNull { it.second.reachable } ?: ranked.firstOrNull { it.second.answered } ?: ranked.firstOrNull())
            ?.first ?: return emptyList()
        val store = FoxyAntiFilterStore.get(app)
        val now = System.currentTimeMillis()
        val known = store.snapshot().freshIps(netKey, now)
        val blocks = FoxyCleanEdgeRanges.blocks(ranked.map { it.second.address })
        // Addresses the ping already found dead are not "clean" for this network.
        val dead = ranked.filter { !it.second.reachable }.mapNotNull { it.second.address }.toSet()
        val sample = FoxyCleanEdgeRanges.sample(blocks, known, count = 48).filterNot { it in dead && it !in known }
        publish(FoxyPhase.PINGING, "جست‌وجوی IP تمیز برای سرور موزیلا", server = target.host, countryCode = target.countryCode)
        val hits = FoxyCleanEdgeScanner.scan(target.host, target.port, sample, want = 3)
        AppLogger.i(TAG, "clean edge scan ${target.host}:${target.port}: ${hits.size}/${sample.size} answered TLS")
        if (hits.isNotEmpty()) store.update { it.withIps(netKey, hits.map { h -> h.ip }, now) }
        return hits.take(2).map { hit ->
            target to FoxyPingResult(target.host, target.port, hit.ip, tcpMs = hit.tlsMs, tlsMs = hit.tlsMs)
        }
    }

    @Volatile private var activeVersion = 0
    @Volatile private var activeTarget: Session? = null

    private fun startBox(target: Session) {
        val config = FoxyBoxConfig.build(
            candidate = target.candidate,
            edgeAddress = target.edgeAddress,
            version = target.version,
            proxyPass = target.pass.token,
            listenPort = target.port,
            paused = target.paused,
            directDomains = runCatching { DirectDomainWhitelist.snapshotOrEmpty() }.getOrDefault(emptyList()),
            iranRuleSetPath = target.iranRuleSet,
            antiFilter = target.antiFilter,
        )
        activeVersion = 0
        activeTarget = null
        box.start(config, target.port)
        activeVersion = target.version
        activeTarget = target
    }

    private suspend fun tryPath(target: Session): FoxyPathResult {
        val started = runCatching { startBox(target) }
        if (started.isFailure) {
            return FoxyPathResult(target.version, ok = false, error = started.exceptionOrNull()?.message.orEmpty().take(160))
        }
        val latency = FoxyProbe.latencyMs(target.port)
            ?: return FoxyPathResult(target.version, ok = false, error = "no response through tunnel")
        val bps = FoxyProbe.throughput(target.port)
        val usable = bps != null && bps >= FoxyProbe.MIN_USABLE_BYTES_PER_SECOND
        return FoxyPathResult(target.version, ok = usable, latencyMs = latency, bytesPerSecond = bps,
            error = if (usable) "" else "too slow")
    }

    private fun iranRuleSetIfEnabled(): String? {
        val on = runCatching { NetworkGuardStore.get(app).snapshot().iranDirect }.getOrDefault(true)
        return if (on) FoxyIranRules.build(app) else null
    }

    private fun isGood(result: FoxyPathResult): Boolean =
        (result.bytesPerSecond ?: 0L) >= FoxyProbe.GOOD_BYTES_PER_SECOND &&
            (result.latencyMs ?: Int.MAX_VALUE) <= FoxyProbe.GOOD_LATENCY_MS

    private fun versionOrder(prefs: FoxyEngineSettings): List<Int> {
        if (prefs.protocol != FoxyProtocol.AUTO) return listOf(prefs.protocol.version)
        val base = listOf(3, 2, 1)
        val last = prefs.lastGoodVersion
        return if (last in base) listOf(last) + base.filter { it != last } else base
    }

    private suspend fun loadCountries(): List<VpnCountry> {
        val client = ServerListClient(app)
        // A fresh (< 6 h) cached directory saves a full HTTPS round trip on every connect.
        if (client.hasFreshCache()) client.loadCachedCountries().takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { client.fetchCountries() }.getOrElse { error ->
            if (error is CancellationException) throw error
            client.loadCachedCountries().ifEmpty { throw error }
        }
    }

    private fun candidatesFor(countries: List<VpnCountry>, code: String): List<ProxyCandidate> {
        if (code.isNotBlank()) return ServerListClient.candidatesForCountry(countries, code).shuffled()
        val recommended = ServerListClient.candidatesForCountry(countries, RECOMMENDED_COUNTRY_CODE)
        if (recommended.isNotEmpty()) return recommended.shuffled()
        // No "recommended" bucket: one server per country, so ping picks the nearest region.
        // Countries this network already measured go first, fastest first, so the 16 probed
        // at connect are the nearest ones rather than the first 16 of the directory.
        val netKey = runCatching { com.uacspoofer.mobile.engine.pow.PowNetworkScoreboard.networkKey(app) }.getOrDefault("unknown")
        val known = FoxyCountryPinger.cached(netKey)
        return countries
            .sortedBy { known[it.code]?.pingMs ?: Int.MAX_VALUE }
            .mapNotNull { ServerListClient.candidatesForCountry(countries, it.code).randomOrNull() }
    }

    private suspend fun mintPass(account: FoxyAccount): ProxyPass {
        val guardian = GuardianClient()
        var auth = account.auth.ensureFreshAccessToken()
        val pass = try {
            guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
        } catch (rejected: TokenInvalidError) {
            val renewed = account.auth.refreshAccessToken()
            if (renewed != null) auth = renewed
            try {
                guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
            } catch (stillRejected: TokenInvalidError) {
                guardian.activateGuardian(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
            }
        } catch (quota: QuotaExceededError) {
            account.publishQuota(0L, quota.quotaMax)
            throw quota
        }
        require(pass.token.isNotBlank() && pass.token.none { it == '\r' || it == '\n' }) {
            "Guardian returned an invalid proxy credential"
        }
        account.publishQuota(pass.quotaRemaining, pass.quotaMax)
        return pass
    }

    /** Proxy passes are short-lived; renew before expiry and restart the core in place. */
    private fun startRenewal() {
        scope.launch {
            while (isActive) {
                val current = session ?: return@launch
                val expires = current.pass.expiresAtEpochSeconds
                val waitMs = if (expires == null) 8 * 60_000L
                else ((expires * 1000L - System.currentTimeMillis()) - 120_000L).coerceAtLeast(60_000L)
                delay(waitMs)
                var renewed: ProxyPass? = null
                for (attempt in 0 until 3) {
                    renewed = runCatching { bounded(60_000L, "pass renewal") { mintPass(FoxyAccount.get(app)) } }
                        .onFailure { if (it is CancellationException) throw it; AppLogger.w(TAG, "pass renewal failed", it) }
                        .getOrNull()
                    if (renewed != null) break
                    delay(15_000L * (attempt + 1))
                }
                val pass = renewed ?: continue
                opMutex.withLock {
                    val live = session ?: return@withLock
                    val next = live.copy(pass = pass)
                    runCatching { startBox(next) }
                        .onSuccess { session = next; AppLogger.i(TAG, "proxy pass renewed; core restarted") }
                        .onFailure { AppLogger.w(TAG, "core restart after renewal failed", it) }
                }
            }
        }
    }

    /** Pause or whitelist change: same pass/version/server, new routing. */
    suspend fun reload(paused: Boolean) = withContext(Dispatchers.IO) {
        opMutex.withLock {
            val live = session ?: return@withLock
            val next = live.copy(paused = paused, iranRuleSet = iranRuleSetIfEnabled())
            runCatching { startBox(next) }
                .onSuccess { session = next }
                .onFailure { AppLogger.w(TAG, "reload failed", it) }
        }
    }

    /** One light end-to-end check through Mozilla's edge. */
    suspend fun healthy(): Boolean {
        val port = socksPort().takeIf { it > 0 } ?: return false
        if (!box.isRunning()) return false
        return FoxyProbe.latencyMs(port, timeoutMs = 8_000L) != null
    }

    fun stop() = stopInternal()

    private fun stopInternal() {
        runCatching { scope.cancel() }
        session = null
        activeVersion = 0
        activeTarget = null
        runCatching { tunRelay.stop() }
        tun?.let { runCatching { it.close() } }
        tun = null
        runCatching { box.stop() }
    }

    private fun publish(
        phase: FoxyPhase,
        detail: String,
        server: String = FoxyStatusStore.status.value.server,
        countryCode: String = FoxyStatusStore.status.value.countryCode,
        version: Int = FoxyStatusStore.status.value.activeVersion,
    ) {
        FoxyStatusStore.update {
            it.copy(phase = phase, detail = detail, server = server, countryCode = countryCode, activeVersion = version)
        }
    }

    /** withTimeout that surfaces as a normal network error, never as a silent cancellation. */
    private suspend fun <T> bounded(ms: Long, what: String, block: suspend CoroutineScope.() -> T): T =
        try {
            withTimeout(ms, block)
        } catch (timeout: TimeoutCancellationException) {
            throw java.net.SocketTimeoutException("$what timed out")
        }

    companion object {
        private const val TAG = "Coordinator"
        /** Kept back from the Auto budget for establishing the device tunnel after a win. */
        private const val TUN_RESERVE_MS = 5_000L
        /** A method the user picked (or Auto outside the Auto engine) is not cut by a budget. */
        private const val MANUAL_BUDGET_MS = 10 * 60_000L
    }
}
