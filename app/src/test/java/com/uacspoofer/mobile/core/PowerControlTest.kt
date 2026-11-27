package com.uacspoofer.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerControlTest {
    @Test
    fun everyStateHasOneClearAction() {
        assertEquals(PowerAction.CONNECT, PowerControl.actionFor(ConnectionState.DISCONNECTED))
        assertEquals(PowerAction.CONNECT, PowerControl.actionFor(ConnectionState.ERROR))
        assertEquals(PowerAction.CANCEL, PowerControl.actionFor(ConnectionState.CONNECTING))
        assertEquals(PowerAction.DISCONNECT, PowerControl.actionFor(ConnectionState.CONNECTED))
        assertEquals(PowerAction.NONE, PowerControl.actionFor(ConnectionState.DISCONNECTING))
    }

    @Test
    fun doubleTapIsOneTap() {
        var now = 10_000L
        val gate = TapDebouncer(650L) { now }
        assertTrue(gate.accept())
        now += 120
        assertFalse(gate.accept())
        now += 400
        assertFalse(gate.accept())
        now += 200 // 720 ms after the first accepted tap
        assertTrue(gate.accept())
    }

    @Test
    fun firstTapIsNeverDropped() {
        val gate = TapDebouncer(650L) { 0L }
        assertTrue(gate.accept())
    }

    @Test
    fun connectingWithoutServiceBecomesRetryableError() {
        val grace = PowerControl.SERVICE_START_GRACE_MS
        assertEquals(
            PowerControl.Repair.NONE,
            PowerControl.repairFor(ConnectionState.CONNECTING, grace - 1, serviceAlive = false, autoRunning = false),
        )
        assertEquals(
            PowerControl.Repair.CONNECT_NEVER_STARTED,
            PowerControl.repairFor(ConnectionState.CONNECTING, grace, serviceAlive = false, autoRunning = false),
        )
        // A live service (Tor can bootstrap for minutes) or a running Auto loop owns the state.
        assertEquals(
            PowerControl.Repair.NONE,
            PowerControl.repairFor(ConnectionState.CONNECTING, 600_000L, serviceAlive = true, autoRunning = false),
        )
        assertEquals(
            PowerControl.Repair.NONE,
            PowerControl.repairFor(ConnectionState.CONNECTING, 600_000L, serviceAlive = false, autoRunning = true),
        )
    }

    @Test
    fun stuckDisconnectAlwaysEnds() {
        assertEquals(
            PowerControl.Repair.NONE,
            PowerControl.repairFor(ConnectionState.DISCONNECTING, 3_000L, serviceAlive = true, autoRunning = false),
        )
        assertEquals(
            PowerControl.Repair.DISCONNECT_STUCK,
            PowerControl.repairFor(ConnectionState.DISCONNECTING, PowerControl.SERVICE_START_GRACE_MS, serviceAlive = false, autoRunning = false),
        )
        assertEquals(
            PowerControl.Repair.DISCONNECT_STUCK,
            PowerControl.repairFor(ConnectionState.DISCONNECTING, PowerControl.DISCONNECT_STUCK_MS, serviceAlive = true, autoRunning = false),
        )
    }

    @Test
    fun idleStatesNeedNoRepair() {
        for (s in listOf(ConnectionState.DISCONNECTED, ConnectionState.CONNECTED, ConnectionState.ERROR)) {
            assertEquals(PowerControl.Repair.NONE, PowerControl.repairFor(s, 10_000_000L, serviceAlive = false, autoRunning = false))
        }
    }
}

class PowerTapSequenceTest {
    private var now = 0L
    private val gate = TapDebouncer(PowerControl.TAP_DEBOUNCE_MS) { now }
    private val machine = ConnectionStateMachine(clock = { now })
    private var connects = 0
    private var disconnects = 0

    private fun tap(shown: ConnectionState = machine.state.value): PowerAction =
        PowerControl.dispatchTap(shown, gate, {
            connects++
            machine.tryBeginConnect()
        }, {
            disconnects++
            machine.tryBeginDisconnect()
        })

    @Test
    fun doubleTapOnConnectStartsOnceAndDoesNotCancel() {
        assertEquals(PowerAction.CONNECT, tap())
        now += 150
        assertEquals(PowerAction.NONE, tap())
        assertEquals(1, connects)
        assertEquals(0, disconnects)
        assertEquals(ConnectionState.CONNECTING, machine.state.value)
    }

    @Test
    fun tapDuringConnectingCancelsCleanly() {
        tap()
        now += 2_000
        assertEquals(PowerAction.CANCEL, tap())
        assertEquals(ConnectionState.DISCONNECTING, machine.state.value)
        assertFalse(machine.markConnected()) // late success of the cancelled attempt
        machine.markDisconnected()
        now += 1_000
        assertEquals(PowerAction.CONNECT, tap()) // and the next tap connects again
        assertEquals(2, connects)
    }

    @Test
    fun tapWhileDisconnectingDoesNothing() {
        tap(); now += 1_000; machine.markConnected()
        tap(); now += 1_000
        assertEquals(ConnectionState.DISCONNECTING, machine.state.value)
        assertEquals(PowerAction.NONE, tap())
        assertEquals(1, disconnects)
    }

    @Test
    fun errorIsRetryable() {
        tap(); machine.markError(); now += 1_000
        assertEquals(PowerAction.CONNECT, tap())
        assertEquals(ConnectionState.CONNECTING, machine.state.value)
    }

    @Test
    fun autoBetweenAttemptsStillShowsConnectingSoTapCancels() {
        assertEquals(ConnectionState.CONNECTING, PowerControl.shownState(ConnectionState.ERROR, autoActive = true))
        assertEquals(ConnectionState.CONNECTING, PowerControl.shownState(ConnectionState.DISCONNECTED, autoActive = true))
        assertEquals(ConnectionState.CONNECTED, PowerControl.shownState(ConnectionState.CONNECTED, autoActive = true))
        assertEquals(ConnectionState.DISCONNECTING, PowerControl.shownState(ConnectionState.DISCONNECTING, autoActive = true))
        assertEquals(PowerAction.CANCEL, tap(PowerControl.shownState(ConnectionState.ERROR, autoActive = true)))
    }
}
