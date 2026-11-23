package com.uacspoofer.mobile.simorgh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.PowerManager
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.pow.PowNetworkScoreboard
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.simorgh.auto.AutoConnector
import com.uacspoofer.mobile.simorgh.auto.AutoEngineStore
import com.uacspoofer.mobile.vpn.ConnectionMetricsStore
import com.uacspoofer.mobile.vpn.LivePing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-scope glue for 1.0.2, started once off the main thread from UacApplication:
 * location state, protected time, exit-IP checks, engine results, auto-reconnect.
 * Event driven. The only timer is the tunnel health check, which runs only while
 * connected, screen on, and not in the lowest power mode.
 */
@OptIn(FlowPreview::class)
object SimorghRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false
    private var healthJob: Job? = null
    private val reconnectTimes = ArrayDeque<Long>()

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            runCatching { ExitIpChecker.loadHome(app) }
            LocationState.start(app, scope)
            val protectedTime = ProtectedTimeStore.get(app)

            // Protected time + exit checks on connection changes.
            launch {
                var previous = ConnectionStateStore.state.value
                ConnectionStateStore.state.collect { state ->
                    if (state == ConnectionState.CONNECTED && previous != ConnectionState.CONNECTED) {
                        protectedTime.sessionStarted()
                        ExitIpChecker.clearTunnelResult()
                        launch {
                            delay(1_800L)
                            if (ConnectionStateStore.state.value == ConnectionState.CONNECTED) {
                                ExitIpChecker.check(app, "connected")
                            }
                        }
                        startHealth(app)
                    }
                    if (state != ConnectionState.CONNECTED && previous == ConnectionState.CONNECTED) {
                        protectedTime.sessionEnded()
                        ExitIpChecker.clearTunnelResult()
                        healthJob?.cancel()
                    }
                    if (state == ConnectionState.DISCONNECTED && previous != ConnectionState.DISCONNECTED) {
                        maybeCheckDirect(app)
                    }
                    previous = state
                }
            }

            // Engine or country changed while connected: re-check the exit (not in Ultra).
            launch {
                LocationState.selection
                    .map { it.engine to it.countryCode + it.profileId }
                    .distinctUntilChanged()
                    .drop(1)
                    .debounce(3_500L)
                    .collect {
                        if (ConnectionStateStore.state.value == ConnectionState.CONNECTED &&
                            PowerMode.current(app) != PowerMode.ULTRA
                        ) {
                            ExitIpChecker.check(app, "selection changed")
                        }
                    }
            }

            // First real tunnel ping of a session becomes the engine's last real result.
            launch {
                combine(ConnectionMetricsStore.metrics.map { it.latencyMs }, ActiveEngineStore.engine) { ms, engine -> ms to engine }
                    .distinctUntilChanged()
                    .collect { (ms, engine) ->
                        if (ms != null && engine != null && ConnectionStateStore.state.value == ConnectionState.CONNECTED) {
                            val store = AutoEngineStore.get(app)
                            val last = store.snapshot().results[engine]
                            if (last == null || last.latencyMs == null || System.currentTimeMillis() - last.atMs > 60_000L) {
                                store.recordResult(engine, ok = true, latencyMs = ms)
                            }
                        }
                    }
            }

            // Remember the engine that connected on this network (manual connects too).
            launch {
                ConnectionStateStore.state.collect { state ->
                    if (state == ConnectionState.CONNECTED) {
                        val engine = ActiveEngineStore.engine.value ?: return@collect
                        val store = AutoEngineStore.get(app)
                        if (store.snapshot().networkMemory) {
                            val key = runCatching { PowNetworkScoreboard.networkKey(app) }.getOrNull() ?: return@collect
                            store.rememberNetwork(key, engine)
                        }
                    }
                }
            }

            // Recent places: written on connect, corrected when the exit country is verified.
            launch {
                LocationState.current
                    .map { Triple(it.connection, it.engine, it.connectedCode) }
                    .distinctUntilChanged()
                    .collect { (conn, engine, code) ->
                        if (conn != ConnectionState.CONNECTED) return@collect
                        val recent = RecentLocations.get(app)
                        val sel = LocationState.current.value.selection
                        val first = recent.items.value.firstOrNull()
                        val same = first != null && first.engine == engine && first.selectedCode == sel.countryCode &&
                            first.profileId == sel.profileId && System.currentTimeMillis() - first.atMs < 10 * 60_000L
                        if (same) {
                            if (code.isNotEmpty()) recent.updateLatestCountry(code)
                        } else {
                            recent.add(
                                RecentLocation(
                                    engine, code, sel.countryCode,
                                    if (engine.isXray) sel.profileId else "", if (engine.isXray) sel.profileName else "",
                                    System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
            }

            if (ConnectionStateStore.state.value == ConnectionState.DISCONNECTED) maybeCheckDirect(app)
            registerNetworkCallback(app)
        }
    }

    /** User asked for a re-check (refresh icon). */
    fun recheckExit(context: Context) {
        scope.launch { ExitIpChecker.check(context.applicationContext, "manual") }
    }

    private fun maybeCheckDirect(app: Context) {
        if (PowerMode.current(app) == PowerMode.ULTRA) return
        val home = ExitIpChecker.home.value
        if (home.known && System.currentTimeMillis() - home.checkedAtMs < 30 * 60_000L) return
        scope.launch {
            delay(800L)
            if (ConnectionStateStore.state.value == ConnectionState.DISCONNECTED) ExitIpChecker.check(app, "direct")
        }
    }

    private fun interactive(app: Context): Boolean =
        runCatching { app.getSystemService(PowerManager::class.java)?.isInteractive ?: true }.getOrDefault(true)

    private fun startHealth(app: Context) {
        healthJob?.cancel()
        healthJob = scope.launch {
            delay(20_000L)
            var failures = 0
            while (isActive && ConnectionStateStore.state.value == ConnectionState.CONNECTED) {
                val mode = PowerMode.current(app)
                val interval = when (mode) {
                    PowerMode.BALANCED -> 60_000L
                    PowerMode.SAVER -> 180_000L
                    PowerMode.ULTRA -> Long.MAX_VALUE
                }
                if (interval == Long.MAX_VALUE) return@launch
                if (!AutoEngineStore.get(app).snapshot().autoReconnect || !interactive(app)) {
                    delay(interval)
                    continue
                }
                val ok = probeOnce(app)
                failures = if (ok) 0 else failures + 1
                if (failures >= 3) {
                    failures = 0
                    reconnect(app, "tunnel test failed 3 times")
                    return@launch
                }
                delay(if (ok) interval else 15_000L)
            }
        }
    }

    private suspend fun probeOnce(app: Context): Boolean = withContext(Dispatchers.IO) {
        LivePing.awaitChipSlot(app) { ConnectionStateStore.state.value == ConnectionState.CONNECTED }
        if (ConnectionStateStore.state.value != ConnectionState.CONNECTED) return@withContext true
        runCatching { LivePing.measure(app) }.getOrNull() != null
    }

    private suspend fun reconnect(app: Context, reason: String) {
        val now = System.currentTimeMillis()
        while (reconnectTimes.isNotEmpty() && now - reconnectTimes.first() > 15 * 60_000L) reconnectTimes.removeFirst()
        if (reconnectTimes.size >= 3) return
        if (AutoConnector.running()) return
        if (runCatching { VpnService.prepare(app) }.getOrNull() != null) return
        reconnectTimes.addLast(now)
        runCatching { AppLogRepository.warning(LogSource.SERVICE, "Auto-reconnect: $reason") }
        withContext(Dispatchers.Main) {
            if (!ConnectionStateStore.tryBeginDisconnect()) return@withContext
            runCatching { VpnController.stop(app) }
        }
        withTimeoutOrNull(12_000L) {
            ConnectionStateStore.state.first { it == ConnectionState.DISCONNECTED || it == ConnectionState.ERROR }
        } ?: return
        delay(1_000L)
        withContext(Dispatchers.Main) {
            if (ConnectionStateStore.tryBeginConnect()) runCatching { VpnController.start(app) }
        }
    }

    private fun registerNetworkCallback(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastNet: String? = null
            override fun onAvailable(network: Network) {
                val key = network.toString()
                if (lastNet != null && lastNet != key) onNetworkChanged(app)
                lastNet = key
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun onNetworkChanged(app: Context) {
        scope.launch {
            delay(6_000L)
            when (ConnectionStateStore.state.value) {
                ConnectionState.CONNECTED -> {
                    if (!AutoEngineStore.get(app).snapshot().autoReconnect) return@launch
                    if (probeOnce(app)) return@launch
                    delay(8_000L)
                    if (!probeOnce(app)) reconnect(app, "tunnel dead after network change")
                }
                ConnectionState.DISCONNECTED -> maybeCheckDirect(app)
                else -> Unit
            }
        }
    }
}
