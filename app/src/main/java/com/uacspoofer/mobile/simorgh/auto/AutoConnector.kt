package com.uacspoofer.mobile.simorgh.auto

import android.content.Context
import androidx.compose.runtime.Immutable
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.pow.PowNetworkScoreboard
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
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
 * code is untouched.
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

    fun timeoutMs(mode: EngineMode): Long = when (mode) {
        EngineMode.TOR_WEBTUNNEL -> 120_000L
        EngineMode.UAC_POW -> 80_000L
        EngineMode.FOXY_FIREFOX -> 50_000L
        EngineMode.UAC_DIRECT -> 45_000L
        EngineMode.XRAY_CF -> 50_000L
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        val store = AutoEngineStore.get(app)
        val netKey = runCatching { PowNetworkScoreboard.networkKey(app) }.getOrNull()
        val plan = store.snapshot().plan(netKey).ifEmpty { listOf(EngineModeStore.get(app).snapshot()) }
        job = scope.launch {
            val engines = EngineModeStore.get(app)
            var connected = false
            try {
                for ((i, mode) in plan.withIndex()) {
                    mutable.value = AutoProgress(true, mode, i + 1, plan.size)
                    // Open this attempt (the previous one, if any, is fully stopped).
                    if (!ConnectionStateStore.tryBeginConnect()) ConnectionStateStore.markConnecting()
                    engines.setModeForAuto(mode)
                    log("Auto: trying ${mode.id} (${i + 1}/${plan.size})")
                    runCatching { VpnController.startEngine(app) }
                    val outcome = withTimeoutOrNull(timeoutMs(mode)) {
                        ConnectionStateStore.state.first {
                            it == ConnectionState.CONNECTED || it == ConnectionState.ERROR ||
                                it == ConnectionState.DISCONNECTED
                        }
                    }
                    if (outcome == ConnectionState.CONNECTED) {
                        connected = true
                        store.recordResult(mode, ok = true, latencyMs = null)
                        if (netKey != null && store.snapshot().networkMemory) store.rememberNetwork(netKey, mode)
                        log("Auto: connected with ${mode.id}")
                        return@launch
                    }
                    store.recordResult(mode, ok = false, latencyMs = null)
                    log("Auto: ${mode.id} failed (${outcome ?: "timeout"})")
                    stopAndWait(app)
                }
                ConnectionStateStore.markError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching { AppLogRepository.warning(LogSource.SERVICE, "Auto failed", error) }
                ConnectionStateStore.markError()
            } finally {
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
