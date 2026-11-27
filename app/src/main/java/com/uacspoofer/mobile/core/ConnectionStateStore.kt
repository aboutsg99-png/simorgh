package com.uacspoofer.mobile.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object ConnectionStateStore {
    private val machine = ConnectionStateMachine()
    private val mutableRouteProgress = MutableStateFlow(ConnectRouteProgress.Idle)
    private val mutableErrorHint = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private var pendingError: Job? = null
    @Volatile private var watchdog: Job? = null
    val state = machine.state
    val routeProgress: StateFlow<ConnectRouteProgress> = mutableRouteProgress.asStateFlow()

    /** Short Persian reason for the last ERROR when the app knows it (permission denied...). */
    val errorHint: StateFlow<String?> = mutableErrorHint.asStateFlow()

    /** When the current state was entered (wall clock). */
    val enteredAtMs: Long get() = machine.enteredAtMs

    fun tryBeginConnect(): Boolean = synchronized(lock) {
        cancelPendingErrorLocked()
        val started = machine.tryBeginConnect()
        if (started) {
            mutableErrorHint.value = null
            clearRouteProgress()
        }
        started
    }

    fun markConnecting() = synchronized(lock) {
        cancelPendingErrorLocked()
        clearRouteProgress()
        machine.markConnecting()
    }

    fun markConnected(): Boolean = synchronized(lock) {
        cancelPendingErrorLocked()
        val connected = machine.markConnected()
        if (connected) {
            mutableErrorHint.value = null
            clearRouteProgress()
        }
        connected
    }

    fun tryBeginDisconnect(): Boolean = synchronized(lock) {
        cancelPendingErrorLocked()
        val started = machine.tryBeginDisconnect()
        if (started) clearRouteProgress()
        started
    }

    fun markDisconnected() = synchronized(lock) {
        cancelPendingErrorLocked()
        clearRouteProgress()
        machine.markDisconnected()
    }

    /**
     * A connect often reports a miss about a second before the winning path comes up.
     * Hold that error while the attempt is still in progress, and drop it if we connect.
     */
    fun markError() = synchronized(lock) {
        clearRouteProgress()
        if (machine.state.value != ConnectionState.CONNECTING) {
            cancelPendingErrorLocked()
            machine.markError()
            return@synchronized
        }
        if (pendingError?.isActive == true) return@synchronized
        pendingError = scope.launch {
            delay(ERROR_HOLD_MS)
            synchronized(lock) {
                pendingError = null
                if (machine.state.value == ConnectionState.CONNECTING) machine.markError()
            }
        }
    }

    /** An error the app itself knows the reason for (shown under the button). Immediate, no hold. */
    fun markErrorNow(hint: String?) = synchronized(lock) {
        cancelPendingErrorLocked()
        clearRouteProgress()
        mutableErrorHint.value = hint
        if (machine.state.value == ConnectionState.DISCONNECTING) machine.markDisconnected() else machine.markError()
    }

    private fun cancelPendingErrorLocked() {
        pendingError?.cancel()
        pendingError = null
    }

    fun updateConnectRouteProgress(current: Int, total: Int) {
        if (state.value != ConnectionState.CONNECTING) return
        mutableRouteProgress.value = ConnectRouteProgress(
            current = current.coerceAtLeast(0),
            total = total.coerceAtLeast(0),
        )
    }

    private fun clearRouteProgress() {
        mutableRouteProgress.value = ConnectRouteProgress.Idle
    }

    /**
     * Watches the busy states so the power button can never stay dead: a CONNECTING whose
     * service never came up turns into ERROR, a DISCONNECTING that outlives the service's own
     * hard timeout (or whose service is gone) turns into DISCONNECTED. Installed once from the
     * main process; [serviceAlive] and [autoRunning] are cheap probes.
     */
    fun installWatchdog(serviceAlive: () -> Boolean, autoRunning: () -> Boolean) {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            state.collectLatest { s ->
                if (s != ConnectionState.CONNECTING && s != ConnectionState.DISCONNECTING) return@collectLatest
                while (true) {
                    delay(WATCHDOG_TICK_MS)
                    val elapsed = System.currentTimeMillis() - machine.enteredAtMs
                    val alive = runCatching(serviceAlive).getOrDefault(true)
                    val auto = runCatching(autoRunning).getOrDefault(false)
                    when (PowerControl.repairFor(s, elapsed, alive, auto)) {
                        PowerControl.Repair.CONNECT_NEVER_STARTED -> synchronized(lock) {
                            if (machine.resetIf(ConnectionState.CONNECTING, ConnectionState.ERROR)) {
                                mutableErrorHint.value = "سرویس اتصال شروع نشد؛ دوباره امتحان کنید"
                            }
                        }
                        PowerControl.Repair.DISCONNECT_STUCK -> synchronized(lock) {
                            machine.resetIf(ConnectionState.DISCONNECTING, ConnectionState.DISCONNECTED)
                        }
                        PowerControl.Repair.NONE -> Unit
                    }
                }
            }
        }
    }

    private const val ERROR_HOLD_MS = 1_600L
    private const val WATCHDOG_TICK_MS = 2_000L
}
