package com.uacspoofer.mobile.engine.foxy

import org.junit.Assert.assertEquals
import org.junit.Test

class FoxyPathPlanTest {
    private fun tls(ms: Int) = FoxyPingResult("h", 443, "1.1.1.1", ms, ms + 10)
    private fun tcpOnly(ms: Int) = FoxyPingResult("h", 443, "1.1.1.1", ms, null, "tls")
    private fun quicOnly(ms: Int) = FoxyPingResult("h", 443, "1.1.1.1", null, null, "tcp", udpMs = ms)

    @Test fun tlsEdgesFirstThenOneResetEdge() {
        assertEquals(listOf(0, 1, 2), FoxyPathPlan.pickServers(listOf(tls(10), tls(20), tcpOnly(5), tcpOnly(6))))
        assertEquals(listOf(0, 1), FoxyPathPlan.pickServers(listOf(tcpOnly(5), quicOnly(6), tcpOnly(7))))
    }

    @Test fun versionsPerEdge() {
        val order = listOf(2, 3, 1)
        assertEquals(order, FoxyPathPlan.versionsFor(tls(10), order))
        assertEquals(listOf(3, 2, 1), FoxyPathPlan.versionsFor(tcpOnly(10), order))
        assertEquals(listOf(3), FoxyPathPlan.versionsFor(quicOnly(10), order))
        assertEquals(listOf(1), FoxyPathPlan.versionsFor(quicOnly(10), listOf(1)))
    }
}
