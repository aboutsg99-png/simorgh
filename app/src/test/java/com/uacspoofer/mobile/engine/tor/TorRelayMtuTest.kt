package com.uacspoofer.mobile.engine.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorRelayMtuTest {
    @Test fun relayMtuDefaultsTo1500AndKeepsExplicitChoice() {
        assertEquals(1500, TorTunRelayConfig.relayMtu())
        assertEquals(1350, TorTunRelayConfig.relayMtu(1350))
        assertEquals(1500, TorTunRelayConfig.relayMtu(9000)) // out of range -> default
        assertTrue(TorTunRelayConfig.yaml(TorTunRelayConfig.relayMtu(), 19050).contains("mtu: 1500"))
    }
}
