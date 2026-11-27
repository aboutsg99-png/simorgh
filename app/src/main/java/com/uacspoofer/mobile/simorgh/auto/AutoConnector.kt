package com.uacspoofer.mobile.simorgh.auto

import android.content.Context
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.pow.PowNetworkScoreboard
import com.uacspoofer.mobile.engine.wireguard.WgTunnelStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.simorgh.flux.FailReason
import com.uacspoofer.mobile.simorgh.flux.FluxPicker
import com.uacspoofer.mobile.simorgh.flux.FluxRoute
import com.uacspoofer.mobile.simorgh.flux.FluxStore
import com.uacspoofer.mobile.vpn.LivePing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
data class AutoProgress(
    val active: Boolean = false,
    val engine: EngineMode? = null,
    val index: Int = 0,
    val total: Int = 0,
)

/**
 * Auto: tries the engines the user included, in their order, the engine that last
 * connected on this network first. Pure orchestration over the normal connect path:
 * select engine, start service, wait for CONNECTED or a failure, stop, next. Engine
 * code is untouched. Inside Xray, Flux (simorgh/flux) picks which configs to
 * try for this network, country and IP version, and fails over between them.
 */
object AutoConnector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AutoProgress())
    val progress: StateFlow<AutoProgress> = mutable.asStateFlow()
    private var job: Job? = null

    /** Next connect uses exactly the selected engine (user picked a specific server). */
    @Volatile var bypassOnce: Boolean = false

    fun shouldHandle(context: Context): Boolean {
        if (bypassOnce) {
            bypassOnce = false
            return false
        }
        return job?.isActive != true && AutoEngineStore.get(context).snapshot().enabled
    }

    fun running(): Boolean = job?.isActive == true

    @Volatile private var attemptDeadlineMs = 0L

    /**
     * Time left of the attempt Auto is running now, for engines that split their own work across
     * that budget (Firefox VPN's anti-filter methods). Null outside Auto.
     */
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long? =
        if (!running() || attemptDeadlineMs == 0L) null else (attemptDeadlineMs - nowMs).coerceAtLeast(0L)

    /**
     * A config that did not answer the pre-connect race still gets a real attempt, just a shorter
     * one. 25 s cut off Xray connects that were still walking their own edge candidates and then
     * opened the Flux breaker on configs that work; 40 s and no breaker penalty for that cut.
     */
    internal const val UNPROVEN_ROUTE_TIMEOUT_MS = 40_000L

    /**
     * Whole-attempt budgets. Each engine runs its own internal steps (Firefox VPN: pass + edge
     * ping + up to three HTTP versions per edge; Psiphon on a filtered line often needs over a
     * minute; WireGuard races its tunnels first), so the earlier 45-80 s limits stopped attempts
     * that were about to connect.
     */
    fun timeoutMs(mode: EngineMode): Long = when (mode) {
        EngineMode.TOR_WEBTUNNEL -> 150_000L
        EngineMode.UAC_POW -> 120_000L
        EngineMode.FOXY_FIREFOX -> 180_000L
        EngineMode.WIREGUARD -> 120_000L
        EngineMode.UAC_DIRECT -> 60_000L
        EngineMode.XRAY_CF -> 75_000L
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        val store = AutoEngineStore.get(app)
        val netKey = runCatching { PowNetworkScoreboard.networkKey(app) }.getOrNull()
        val plan = store.snapshot().plan(netKey)
            // An engine with nothing to run (no WireGuard tunnel added yet) is not an attempt.
            .filterNot { it == EngineMode.WIREGUARD && WgTunnelStore.get(app).snapshot().tunnels.isEmpty() }
            .ifEmpty { listOf(EngineModeStore.get(app).snapshot()) }
        job = scope.launch {
            val engines = EngineModeStore.get(app)
            var connected = false
            try {
                for ((i, mode) in plan.withIndex()) {
                    mutable.value = AutoProgress(true, mode, i + 1, plan.size)
                    // Flux: which config of this engine, in which order (Xray). The WireGuard
                    // engine runs Flux itself, including the race and its own failover.
                    val flux = FluxStore.get(app).prefs()
                    var proven: Set<String> = emptySet()
                    val routes: List<FluxRoute?> = if (flux.enabled && mode == EngineMode.XRAY_CF) {
                        if (!ConnectionStateStore.tryBeginConnect()) ConnectionStateStore.markConnecting()
                        val planned = runCatching { FluxPicker.plan(app, mode) }.getOrDefault(emptyList())
                        // Race the head of the plan first so the connect goes to a config that
                        // answered on this network right now, not just the historically best one.
                        val raced = runCatching { FluxPicker.raceXray(app, planned, flux.raceSize) }
                            .getOrElse { error -> if (error is CancellationException) throw error; planned to emptySet() }
                        proven = raced.second
                        // Every config that answered the race (up to 4) gets a turn, not only the
                        // first `failover` of the plan.
                        raced.first.take(maxOf(flux.failover, minOf(proven.size, 4))).ifEmpty { listOf(null) }
                    } else {
                        listOf(null)
                    }
                    for ((r, route) in routes.withIndex()) {
                        // Open this attempt (the previous one, if any, is fully stopped).
                        if (!ConnectionStateStore.tryBeginConnect()) ConnectionStateStore.markConnecting()
                        engines.setModeForAuto(mode)
                        route?.let { FluxPicker.apply(app, it) }
                        log("Auto: trying ${mode.id} (${i + 1}/${plan.size})" + (route?.let { " route ${r + 1}/${routes.size} ${it.id.takeLast(8)}" } ?: ""))
                        // A config that did not answer the race gets a shorter attempt once
                        // another one is known to work here.
                        val shortened = route != null && proven.isNotEmpty() && route.id !in proven
                        val limit = if (shortened) UNPROVEN_ROUTE_TIMEOUT_MS else timeoutMs(mode)
                        attemptDeadlineMs = System.currentTimeMillis() + limit
                        runCatching { VpnController.startEngine(app) }
                        val outcome = withTimeoutOrNull(limit) {
                            ConnectionStateStore.state.first {
                                it == ConnectionState.CONNECTED || it == ConnectionState.ERROR ||
                                    it == ConnectionState.DISCONNECTED
                            }
                        }
                        if (outcome == ConnectionState.CONNECTED) {
                            connected = true
                            val latency = runCatching { withContext(Dispatchers.IO) { LivePing.measure(app) } }.getOrNull()
                            store.recordResult(mode, ok = true, latencyMs = latency)
                            route?.let { FluxPicker.recordSuccess(app, it, latency) }
                            if (netKey != null && store.snapshot().networkMemory) store.rememberNetwork(netKey, mode)
                            log("Auto: connected with ${mode.id}")
                            return@launch
                        }
                        // A timeout we imposed early is not evidence the config is dead.
                        if (!(shortened && outcome == null)) {
                            route?.let { FluxPicker.recordFailure(app, it, if (outcome == null) FailReason.TIMEOUT else FailReason.OTHER) }
                        }
                        log("Auto: ${mode.id} failed (${outcome ?: "timeout"})")
                        stopAndWait(app)
                    }
                    store.recordResult(mode, ok = false, latencyMs = null)
                }
                ConnectionStateStore.markError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching { AppLogRepository.warning(LogSource.SERVICE, "Auto failed", error) }
                ConnectionStateStore.markError()
            } finally {
                attemptDeadlineMs = 0L
                mutable.value = AutoProgress()
            }
        }
    }

    /** User cancelled while Auto was trying: stop the loop; the caller stops the service. */
    fun cancel() {
        job?.cancel()
        job = null
        mutable.value = AutoProgress()
    }

    private suspend fun stopAndWait(context: Context) {
        val state = ConnectionStateStore.state.value
        if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) {
            ConnectionStateStore.tryBeginDisconnect()
        }
        runCatching { VpnController.stop(context) }
        val done = withTimeoutOrNull(12_000L) {
            ConnectionStateStore.state.first { it == ConnectionState.DISCONNECTED || it == ConnectionState.ERROR }
        }
        if (done == null) ConnectionStateStore.markDisconnected()
        delay(600L)
    }

    private fun log(message: String) {
        runCatching { AppLogRepository.info(LogSource.SERVICE, message) }
    }
}
