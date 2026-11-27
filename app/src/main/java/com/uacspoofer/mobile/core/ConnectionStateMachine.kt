package com.uacspoofer.mobile.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The connection lifecycle. Allowed moves:
 *
 * ```
 * DISCONNECTED/ERROR --tryBeginConnect--> CONNECTING --markConnected--> CONNECTED
 * CONNECTING/CONNECTED --tryBeginDisconnect--> DISCONNECTING --markDisconnected--> DISCONNECTED
 * CONNECTED --markConnecting--> CONNECTING (country/engine switch, reconnect)
 * any (but DISCONNECTING) --markError--> ERROR, which is retryable (tryBeginConnect)
 * any --markDisconnected--> DISCONNECTED
 * ```
 * Every transition also bumps [enteredAtMs] so watchdogs can tell how long a busy state lasted.
 */
class ConnectionStateMachine(
    initial: ConnectionState = ConnectionState.DISCONNECTED,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /** Wall time of the last state change. */
    @Volatile var enteredAtMs: Long = clock()
        private set

    private fun set(next: ConnectionState) {
        if (mutableState.value != next) enteredAtMs = clock()
        mutableState.value = next
    }

    @Synchronized
    fun tryBeginConnect(): Boolean {
        if (mutableState.value != ConnectionState.DISCONNECTED && mutableState.value != ConnectionState.ERROR) {
            return false
        }
        set(ConnectionState.CONNECTING)
        return true
    }

    @Synchronized
    fun markConnecting() {
        if (mutableState.value != ConnectionState.DISCONNECTING) {
            set(ConnectionState.CONNECTING)
        }
    }

    @Synchronized
    fun markConnected(): Boolean {
        // Idempotent: PoW coordinator may publish Connected before the VPN service
        // also calls markConnected. Returning false here used to trigger cleanupRoute()
        // which stopped a live Psiphon while the UI stayed green.
        if (mutableState.value == ConnectionState.CONNECTED) return true
        if (mutableState.value != ConnectionState.CONNECTING) return false
        set(ConnectionState.CONNECTED)
        return true
    }

    @Synchronized
    fun tryBeginDisconnect(): Boolean {
        if (mutableState.value != ConnectionState.CONNECTING && mutableState.value != ConnectionState.CONNECTED) {
            return false
        }
        set(ConnectionState.DISCONNECTING)
        return true
    }

    @Synchronized
    fun markDisconnected() {
        set(ConnectionState.DISCONNECTED)
    }

    @Synchronized
    fun markError() {
        if (mutableState.value != ConnectionState.DISCONNECTING) {
            set(ConnectionState.ERROR)
        }
    }

    /** Only if the state is still [expected]: used by watchdogs so they never undo a newer move. */
    @Synchronized
    fun resetIf(expected: ConnectionState, next: ConnectionState): Boolean {
        if (mutableState.value != expected) return false
        set(next)
        return true
    }
}
