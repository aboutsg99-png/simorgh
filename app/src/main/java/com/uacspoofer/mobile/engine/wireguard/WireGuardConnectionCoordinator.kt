package com.uacspoofer.mobile.engine.wireguard

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.tor.TorTunRelayConfig
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.mci.GeoAssets
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.DirectDomainWhitelist
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.settings.TunBackend
import com.uacspoofer.mobile.simorgh.flux.FailReason
import com.uacspoofer.mobile.simorgh.flux.FluxPicker
import com.uacspoofer.mobile.simorgh.flux.FluxStore
import com.uacspoofer.mobile.vpn.LivePing
import com.uacspoofer.mobile.vpn.SocksTunRelay
import com.uacspoofer.mobile.vpn.TunStats
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WgNoTunnelException : IllegalStateException("هنوز هیچ کانفیگ WireGuard اضافه نشده است")

/**
 * WireGuard engine: Xray's userspace WireGuard outbound behind a loopback SOCKS, TUN -> hev/zeptun
 * -> SOCKS (the same relay Rubah and Sayeh use).
 *
 * Which tunnel: the one the user picked; on «خودکار» Flux orders every tunnel for this network,
 * country and IP version, races the head (real request through each), and connects the best,
 * failing over to the next ones. Every outcome is remembered per network.
 */
internal class WireGuardConnectionCoordinator(private val service: VpnService) {
    private val app = service.applicationContext
    private val core = XrayRawCore(app, "wireguard")
    private val tunRelay = SocksTunRelay()
    private val opMutex = Mutex()
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var session: Session? = null

    private data class Session(
        val tunnel: WgTunnel,
        val port: Int,
        val proxyMode: Boolean,
        val paused: Boolean = false,
        val shadowsocks: WgXrayConfig.SsBridge? = null,
    )

    /**
     * One prepared attempt from another engine (Firefox VPN's Mozilla WireGuard modes).
     * [udp2tcp] is the TCP relay target when the WireGuard UDP must ride a TCP connection;
     * [tunnelFor] gets the loopback UDP port of that relay (0 without one).
     */
    data class Attempt(
        val label: String,
        val udp2tcp: Pair<String, Int>? = null,
        val shadowsocks: WgXrayConfig.SsBridge? = null,
        val tunnelFor: (localUdpPort: Int) -> WgTunnel,
    )

    @Volatile private var relay: Udp2TcpRelay? = null

    fun socksPort(): Int = session?.port ?: 0

    fun isRunning(): Boolean {
        val s = session ?: return false
        return core.isRunning() && (s.proxyMode || tunRelay.isRunning()) && (relay?.isRunning() ?: true)
    }

    fun tunStats(): TunStats = runCatching { tunRelay.stats() }.getOrDefault(TunStats.ZERO)

    fun activeLabel(): String = session?.tunnel?.let { "${it.source.label} · ${it.name}" }.orEmpty()

    fun activeTunnel(): WgTunnel? = session?.tunnel

    suspend fun connect(
        settings: AdvancedSettingsData,
        backend: TunBackend,
        establishTun: (AdvancedSettingsData) -> ParcelFileDescriptor?,
    ) = withContext(Dispatchers.IO) {
        opMutex.withLock {
            stopLocked()
            val store = WgTunnelStore.get(app)
            val library = store.snapshot()
            if (library.tunnels.isEmpty()) throw WgNoTunnelException()
            val proxyMode = settings.connectionMode == CONNECTION_MODE_PROXY
            val port = if (proxyMode) settings.socksPort else WgProbe.freePort()
            val flux = FluxStore.get(app).prefs()

            val order: List<WgTunnel> = library.selected?.let { listOf(it) } ?: run {
                publish(WgPhase.TESTING, "انتخاب بهترین سرور WireGuard برای این شبکه")
                val planned = FluxPicker.plan(app, EngineMode.WIREGUARD)
                val raced = FluxPicker.raceWireGuard(app, planned, flux.raceSize)
                val byId = library.tunnels.associateBy { it.id }
                raced.mapNotNull { byId[it.id] }.ifEmpty { library.tunnels }
            }
            val attempts = if (library.selected != null) order else order.take(maxOf(flux.failover, 1) + 1)
            var lastError: Throwable? = null
            for (tunnel in attempts) {
                coroutineContext.ensureActive()
                val route = FluxPicker.wgRoute(tunnel)
                publish(WgPhase.TESTING, "اتصال به ${tunnel.name}", tunnel)
                val live = runCatching { WgEndpoints.resolved(tunnel) }.getOrDefault(tunnel)
                val result = runCatching { startCore(Session(live, port, proxyMode)) }
                    .mapCatching {
                        // A lost first handshake initiation is retried by WireGuard after 5 s:
                        // three rounds cover one or two lost packets on a lossy mobile line.
                        LivePing.measure("127.0.0.1", port, HANDSHAKE_PROBE_MS)
                            ?: LivePing.measure("127.0.0.1", port, HANDSHAKE_PROBE_MS)
                            ?: LivePing.measure("127.0.0.1", port, HANDSHAKE_PROBE_MS)
                            ?: throw IllegalStateException("WireGuard handshake did not complete (no answer through the tunnel)")
                    }
                val latency = result.getOrNull()
                if (latency != null) {
                    FluxPicker.recordSuccess(app, route, latency)
                    runCatching { store.recordResult(tunnel.id, WgResult(true, latency, System.currentTimeMillis())) }
                    if (!proxyMode) startTun(settings, backend, port, WgEndpoints.tunMtu(tunnel), establishTun)
                    session = Session(live, port, proxyMode)
                    WgStatusStore.socksPort = port
                    publish(WgPhase.CONNECTED, "وصل شد · ${tunnel.source.label} · ${tunnel.name}", tunnel, latency)
                    AppLogRepository.success(LogSource.SERVICE, "[WG] connected ${tunnel.source.id} ${tunnel.primary.endpointHost} ${latency}ms")
                    return@withLock
                }
                lastError = result.exceptionOrNull()
                AppLogRepository.warning(LogSource.SERVICE, "[WG] ${tunnel.name} failed: ${lastError?.message.orEmpty().take(160)}")
                FluxPicker.recordFailure(app, route, FailReason.HANDSHAKE)
                runCatching { store.recordResult(tunnel.id, WgResult(false, null, System.currentTimeMillis(), lastError?.message.orEmpty().take(120))) }
                runCatching { core.stop() }
            }
            publish(WgPhase.FAILED, "هیچ سرور WireGuard جواب نداد")
            throw lastError ?: IllegalStateException("No WireGuard tunnel answered")
        }
    }

    /**
     * Tries [attempts] in order with the same checks as [connect] (real request through the
     * tunnel), keeps the first that answers and brings up the TUN on it. Throws the last error.
     */
    suspend fun connectAttempts(
        settings: AdvancedSettingsData,
        backend: TunBackend,
        attempts: List<Attempt>,
        establishTun: (AdvancedSettingsData) -> ParcelFileDescriptor?,
        onAttempt: (Attempt) -> Unit = {},
    ): WgTunnel = withContext(Dispatchers.IO) {
        opMutex.withLock {
            stopLocked()
            val proxyMode = settings.connectionMode == CONNECTION_MODE_PROXY
            val port = if (proxyMode) settings.socksPort else WgProbe.freePort()
            var lastError: Throwable? = null
            for (attempt in attempts) {
                coroutineContext.ensureActive()
                onAttempt(attempt)
                val result = runCatching {
                    val localUdp = attempt.udp2tcp?.let { (host, remotePort) ->
                        Udp2TcpRelay(host, remotePort).also { it.start(); relay = it }.localPort
                    } ?: 0
                    val tunnel = attempt.tunnelFor(localUdp)
                    val s = Session(tunnel, port, proxyMode, shadowsocks = attempt.shadowsocks)
                    startCore(s)
                    LivePing.measure("127.0.0.1", port, HANDSHAKE_PROBE_MS)
                        ?: LivePing.measure("127.0.0.1", port, HANDSHAKE_PROBE_MS)
                        ?: throw IllegalStateException("no answer through ${attempt.label}")
                    s
                }
                val s = result.getOrNull()
                if (s != null) {
                    if (!proxyMode) startTun(settings, backend, port, WgEndpoints.tunMtu(s.tunnel), establishTun)
                    session = s
                    WgStatusStore.socksPort = port
                    AppLogRepository.success(LogSource.SERVICE, "[WG] connected ${attempt.label}")
                    return@withLock s.tunnel
                }
                lastError = result.exceptionOrNull()
                AppLogRepository.warning(LogSource.SERVICE, "[WG] ${attempt.label} failed: ${lastError?.message.orEmpty().take(160)}")
                runCatching { core.stop() }
                relay?.stop()
                relay = null
            }
            throw lastError ?: IllegalStateException("No WireGuard attempt answered")
        }
    }

    private fun startCore(s: Session) {
        val guard = runCatching { NetworkGuardStore.get(app).snapshot().iranDirect }.getOrDefault(false)
        if (guard) runCatching { GeoAssets.ensure(app) }
        val config = WgXrayConfig.build(
            s.tunnel,
            WgXrayConfig.Options(
                socksPort = s.port,
                udp = true,
                iranDirect = guard,
                directDomains = runCatching { DirectDomainWhitelist.snapshotOrEmpty() }.getOrDefault(emptyList()),
                paused = s.paused,
                shadowsocks = s.shadowsocks,
            ),
        )
        core.start(config, s.port)
    }

    private fun startTun(
        settings: AdvancedSettingsData,
        backend: TunBackend,
        port: Int,
        mtu: Int,
        establishTun: (AdvancedSettingsData) -> ParcelFileDescriptor?,
    ) {
        val descriptor = checkNotNull(establishTun(settings.copy(tunMtu = mtu))) { "Android could not establish the VPN interface" }
        tun = descriptor
        try {
            tunRelay.start(
                service, backend, descriptor,
                SocksTunRelay.Config(port, mtu, udp = true, fakeDns = TorTunRelayConfig.MAP_DNS),
            ) {
                "wireguard/hev.yml" to TorTunRelayConfig.yaml(
                    mtu = mtu,
                    socksPort = port,
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

    /** Pause / resume, or a routing setting changed: restart the core on the same TUN and port. */
    suspend fun reload(paused: Boolean) = withContext(Dispatchers.IO) {
        opMutex.withLock {
            val live = session ?: return@withLock
            val next = live.copy(paused = paused)
            runCatching { startCore(next) }
                .onSuccess { session = next }
                .onFailure { AppLogRepository.warning(LogSource.SERVICE, "[WG] reload failed", it) }
        }
    }

    suspend fun healthy(): Boolean = withContext(Dispatchers.IO) {
        val port = socksPort().takeIf { it > 0 } ?: return@withContext false
        core.isRunning() && LivePing.measure("127.0.0.1", port, 8_000) != null
    }

    fun stop() {
        session = null
        WgStatusStore.socksPort = 0
        stopLocked()
    }

    private fun stopLocked() {
        runCatching { tunRelay.stop() }
        tun?.let { runCatching { it.close() } }
        tun = null
        runCatching { core.stop() }
        relay?.stop()
        relay = null
    }

    private companion object {
        const val HANDSHAKE_PROBE_MS = 7_000
    }

    private fun publish(phase: WgPhase, detail: String, tunnel: WgTunnel? = null, latency: Long? = null) {
        WgStatusStore.update {
            it.copy(
                phase = phase, detail = detail,
                tunnelId = tunnel?.id ?: it.tunnelId, tunnelName = tunnel?.name ?: it.tunnelName,
                source = tunnel?.source ?: it.source, countryCode = tunnel?.countryCode ?: it.countryCode,
                latencyMs = latency ?: it.latencyMs,
            )
        }
    }
}
