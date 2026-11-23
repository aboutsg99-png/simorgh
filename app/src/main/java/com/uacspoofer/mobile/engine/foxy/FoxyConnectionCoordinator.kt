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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    )

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun socksPort(): Int = session?.port ?: 0

    fun isRunning(): Boolean {
        val current = session ?: return false
        return box.isRunning() && (current.proxyMode || tunRelay.isRunning())
    }

    fun tunStats(): TunStats = runCatching { tunRelay.stats() }.getOrDefault(TunStats.ZERO)

    fun activeLabel(): String = session?.let {
        "${FoxyProtocol.labelFor(it.version)} · ${it.candidate.countryCode} · ${it.candidate.host}"
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

        publish(FoxyPhase.PREPARING, "دریافت مجوز VPN از حساب فایرفاکس")
        val pass = bounded(90_000L, "proxy pass") { mintPass(account) }

        publish(FoxyPhase.PREPARING, "دریافت فهرست سرورهای موزیلا")
        val countries = bounded(30_000L, "server list") { loadCountries() }
        val candidates = candidatesFor(countries, prefs.countryCode)
        if (candidates.isEmpty()) throw FoxyUnusableException("No Mozilla server for ${prefs.countryCode.ifBlank { "auto" }}")

        publish(FoxyPhase.PINGING, "اندازه‌گیری پینگ واقعی سرورها")
        val ranked = bounded(30_000L, "ping") { FoxyPing.rank(candidates.take(16)) }.filter { it.second.reachable }
        if (ranked.isEmpty()) throw FoxyUnusableException("TLS handshake to every Mozilla edge server failed")

        val port = if (proxyMode) settings.socksPort else prefs.socksPort
        val iranRuleSet = iranRuleSetIfEnabled()
        val versions = versionOrder(prefs)
        val tried = mutableListOf<FoxyPathResult>()
        var winner: Session? = null
        servers@ for ((candidate, ping) in ranked.take(2)) {
            val perServer = mutableListOf<Pair<Int, FoxyPathResult>>()
            for (version in versions) {
                coroutineContext.ensureActive()
                publish(
                    FoxyPhase.TESTING,
                    "آزمایش ${FoxyProtocol.labelFor(version)} روی ${candidate.countryName.ifBlank { candidate.countryCode }} (پینگ ${ping.tcpMs ?: "?"} ms)",
                    server = candidate.host,
                    countryCode = candidate.countryCode,
                )
                val attempt = Session(candidate, ping.address, version, pass, port, proxyMode, iranRuleSet = iranRuleSet)
                val result = tryPath(attempt)
                tried += result
                FoxyStatusStore.update { it.copy(tried = tried.toList()) }
                AppLogger.i(TAG, "path ${FoxyProtocol.labelFor(version)} ${candidate.host}: ok=${result.ok} " +
                    "latency=${result.latencyMs} bps=${result.bytesPerSecond} ${result.error}")
                if (result.ok && isGood(result)) {
                    winner = attempt
                    break@servers
                }
                runCatching { box.stop() }
                if (result.ok) perServer += version to result
            }
            val best = perServer.maxByOrNull { it.second.bytesPerSecond ?: 0L }
            if (best != null) {
                winner = Session(candidate, ping.address, best.first, pass, port, proxyMode, iranRuleSet = iranRuleSet)
                break@servers
            }
        }
        val chosen = winner ?: throw FoxyUnusableException("No HTTP version reached a usable speed")
        if (!box.isRunning() || box.port != chosen.port || activeVersion != chosen.version) {
            startBox(chosen)
        }
        store.update { it.copy(lastGoodVersion = chosen.version) }

        if (!proxyMode) {
            publish(FoxyPhase.TESTING, "ساخت تونل دستگاه")
            val mtu = settings.tunMtu.coerceIn(1280, 1500)
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
            "وصل شد · ${FoxyProtocol.labelFor(chosen.version)} · ${chosen.candidate.countryName.ifBlank { chosen.candidate.countryCode }}",
            server = chosen.candidate.host,
            countryCode = chosen.candidate.countryCode,
            version = chosen.version,
        )
    }

    @Volatile private var activeVersion = 0

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
        )
        activeVersion = 0
        box.start(config, target.port)
        activeVersion = target.version
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
        return countries.mapNotNull { ServerListClient.candidatesForCountry(countries, it.code).randomOrNull() }
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
    }
}
