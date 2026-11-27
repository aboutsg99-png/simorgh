package com.uacspoofer.mobile.engine.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorRelayMtuTest {
    @Test fun relayMtuDefaultsToAppMtuAndKeepsExplicitChoice() {
        assertEquals(1280, TorTunRelayConfig.relayMtu())
        assertEquals(1350, TorTunRelayConfig.relayMtu(1350))
        assertEquals(1400, TorTunRelayConfig.relayMtu(null, 1400))
        assertEquals(1280, TorTunRelayConfig.relayMtu(9000)) // out of range -> default
        assertEquals(1280, TorTunRelayConfig.relayMtu(null, 9000))
        assertTrue(TorTunRelayConfig.yaml(TorTunRelayConfig.relayMtu(), 19050).contains("mtu: 1280"))
    }
}
