package com.uacspoofer.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {
    @Test
    fun connectAndDisconnectAreGuardedAndIdempotent() {
        val machine = ConnectionStateMachine()

        assertTrue(machine.tryBeginConnect())
        assertFalse(machine.tryBeginConnect())
        assertTrue(machine.markConnected())
        // A second Connected (PoW coordinator + service) is idempotent, not a failure.
        assertTrue(machine.markConnected())
        assertEquals(ConnectionState.CONNECTED, machine.state.value)
        assertTrue(machine.tryBeginDisconnect())
        assertFalse(machine.tryBeginDisconnect())
        machine.markDisconnected()
        assertEquals(ConnectionState.DISCONNECTED, machine.state.value)
    }

    @Test
    fun connectedCountrySwitchGoesThroughConnectingAgain() {
        val machine = ConnectionStateMachine()
        assertTrue(machine.tryBeginConnect())
        assertTrue(machine.markConnected())
        machine.markConnecting()
        assertEquals(ConnectionState.CONNECTING, machine.state.value)
        assertTrue(machine.markConnected())
        assertEquals(ConnectionState.CONNECTED, machine.state.value)
    }

    @Test
    fun errorCanRetryButCannotBecomeConnectedWithoutConnecting() {
        val machine = ConnectionStateMachine()
        machine.markError()
        assertFalse(machine.markConnected())
        assertTrue(machine.tryBeginConnect())
        assertTrue(machine.markConnected())
    }

    @Test
    fun cancelDuringConnectingStopsTheAttempt() {
        val machine = ConnectionStateMachine()
        assertTrue(machine.tryBeginConnect())
        assertTrue(machine.tryBeginDisconnect())
        assertEquals(ConnectionState.DISCONNECTING, machine.state.value)
        // A late "connected" from the cancelled attempt must not turn the button green.
        assertFalse(machine.markConnected())
        // A late error while stopping is ignored too; the stop finishes as DISCONNECTED.
        machine.markError()
        assertEquals(ConnectionState.DISCONNECTING, machine.state.value)
        machine.markDisconnected()
        assertEquals(ConnectionState.DISCONNECTED, machine.state.value)
    }

    @Test
    fun cannotDisconnectWhenIdleOrFailed() {
        val machine = ConnectionStateMachine()
        assertFalse(machine.tryBeginDisconnect())
        machine.markError()
        assertFalse(machine.tryBeginDisconnect())
        assertEquals(ConnectionState.ERROR, machine.state.value)
    }

    @Test
    fun enteredAtMovesOnlyOnRealChanges() {
        var now = 1_000L
        val machine = ConnectionStateMachine(clock = { now })
        now = 2_000L
        machine.tryBeginConnect()
        assertEquals(2_000L, machine.enteredAtMs)
        now = 3_000L
        machine.markConnecting() // same state
        assertEquals(2_000L, machine.enteredAtMs)
        now = 4_000L
        machine.markConnected()
        assertEquals(4_000L, machine.enteredAtMs)
    }

    @Test
    fun resetIfNeverUndoesANewerMove() {
        val machine = ConnectionStateMachine()
        machine.tryBeginConnect()
        machine.markConnected()
        assertFalse(machine.resetIf(ConnectionState.CONNECTING, ConnectionState.ERROR))
        assertEquals(ConnectionState.CONNECTED, machine.state.value)
        machine.tryBeginDisconnect()
        assertTrue(machine.resetIf(ConnectionState.DISCONNECTING, ConnectionState.DISCONNECTED))
        assertEquals(ConnectionState.DISCONNECTED, machine.state.value)
    }
}
