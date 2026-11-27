package com.uacspoofer.mobile.core

/** What one tap on the power button does in a given (shown) state. */
enum class PowerAction { CONNECT, CANCEL, DISCONNECT, NONE }

object PowerControl {
    /** Taps closer together than this are one tap (double taps, bouncy screens). */
    const val TAP_DEBOUNCE_MS = 650L

    /** A CONNECTING that never reached the service (start refused, integrity, crash). */
    const val SERVICE_START_GRACE_MS = 12_000L

    /** The service forces its own teardown after its hard timeout; this is the UI's last resort. */
    const val DISCONNECT_STUCK_MS = 25_000L

    fun actionFor(state: ConnectionState): PowerAction = when (state) {
        ConnectionState.DISCONNECTED, ConnectionState.ERROR -> PowerAction.CONNECT
        ConnectionState.CONNECTING -> PowerAction.CANCEL
        ConnectionState.CONNECTED -> PowerAction.DISCONNECT
        ConnectionState.DISCONNECTING -> PowerAction.NONE
    }

    /**
     * One tap on the power button: debounced, then exactly one of [connect] / [disconnect]
     * (cancel = disconnect) for the state the user is looking at. Returns what was done.
     */
    fun dispatchTap(shown: ConnectionState, gate: TapDebouncer, connect: () -> Unit, disconnect: () -> Unit): PowerAction {
        if (!gate.accept()) return PowerAction.NONE
        val action = actionFor(shown)
        when (action) {
            PowerAction.CONNECT -> connect()
            PowerAction.CANCEL, PowerAction.DISCONNECT -> disconnect()
            PowerAction.NONE -> Unit
        }
        return action
    }

    /** While Auto walks its engines the service is briefly down between attempts: still CONNECTING. */
    fun shownState(state: ConnectionState, autoActive: Boolean): ConnectionState =
        if (autoActive && state != ConnectionState.CONNECTED && state != ConnectionState.DISCONNECTING) ConnectionState.CONNECTING else state

    /** What a watchdog should do about a busy state that has lasted [elapsedMs]. */
    enum class Repair { NONE, CONNECT_NEVER_STARTED, DISCONNECT_STUCK }

    fun repairFor(state: ConnectionState, elapsedMs: Long, serviceAlive: Boolean, autoRunning: Boolean): Repair = when {
        state == ConnectionState.CONNECTING && !serviceAlive && !autoRunning && elapsedMs >= SERVICE_START_GRACE_MS ->
            Repair.CONNECT_NEVER_STARTED
        state == ConnectionState.DISCONNECTING && !serviceAlive && elapsedMs >= SERVICE_START_GRACE_MS ->
            Repair.DISCONNECT_STUCK
        state == ConnectionState.DISCONNECTING && elapsedMs >= DISCONNECT_STUCK_MS -> Repair.DISCONNECT_STUCK
        else -> Repair.NONE
    }
}

/**
 * Drops taps that come too soon after the last accepted one. Not thread-safe on purpose:
 * it is only used from the main thread.
 */
class TapDebouncer(private val windowMs: Long = PowerControl.TAP_DEBOUNCE_MS, private val clock: () -> Long = System::currentTimeMillis) {
    private var lastAccepted = Long.MIN_VALUE / 2

    fun accept(): Boolean {
        val now = clock()
        if (now - lastAccepted < windowMs) return false
        lastAccepted = now
        return true
    }
}
