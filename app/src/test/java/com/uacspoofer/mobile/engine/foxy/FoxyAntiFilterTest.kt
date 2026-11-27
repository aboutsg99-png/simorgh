package com.uacspoofer.mobile.engine.foxy

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxyAntiFilterTest {
    private val candidate = ProxyCandidate("ca.m1.fastly-masque.net", 2499, "CA", "Canada")

    private fun tls(version: Int, method: AntiFilterMethod, edge: String? = null): JSONObject {
        val json = FoxyBoxConfig.build(candidate, edge, version, "pass", 10871, paused = false, directDomains = emptyList(), antiFilter = method)
        val proxy = JSONObject(json).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("proxy-out", proxy.getString("tag"))
        return proxy
    }

    @Test fun plainKeepsUtlsAndNoFragment() {
        val t = tls(2, AntiFilterMethod.PLAIN).getJSONObject("tls")
        assertEquals("ca.m1.fastly-masque.net", t.getString("server_name"))
        assertEquals("firefox", t.getJSONObject("utls").getString("fingerprint"))
        assertFalse(t.has("fragment"))
        assertFalse(t.has("record_fragment"))
        assertFalse(t.has("ech"))
    }

    @Test fun fragmentOnTcpVersions() {
        for (v in listOf(1, 2)) {
            val t = tls(v, AntiFilterMethod.FRAGMENT).getJSONObject("tls")
            assertTrue(t.getBoolean("fragment"))
            assertEquals("500ms", t.getString("fragment_fallback_delay"))
            assertFalse(t.has("record_fragment"))
            assertTrue(t.getJSONObject("utls").getBoolean("enabled"))
        }
    }

    @Test fun recordFragmentOnTcpVersions() {
        val t = tls(2, AntiFilterMethod.RECORD_FRAGMENT).getJSONObject("tls")
        assertTrue(t.getBoolean("record_fragment"))
        assertFalse(t.has("fragment"))
    }

    @Test fun http3NeverFragmentsOrUsesUtls() {
        for (m in AntiFilterMethod.entries) {
            val t = tls(3, m).getJSONObject("tls")
            assertFalse(t.has("utls"))
            assertFalse(t.has("fragment"))
            assertFalse(t.has("record_fragment"))
        }
    }

    @Test fun cleanIpDialsAddressWithRealSni() {
        val proxy = tls(2, AntiFilterMethod.CLEAN_IP, edge = "23.235.42.77")
        assertEquals("23.235.42.77", proxy.getString("server"))
        assertEquals(2499, proxy.getInt("server_port"))
        assertFalse(proxy.has("domain_resolver"))
        assertEquals("ca.m1.fastly-masque.net", proxy.getJSONObject("tls").getString("server_name"))
        val plain = tls(2, AntiFilterMethod.PLAIN)
        assertEquals("ca.m1.fastly-masque.net", plain.getString("server"))
        assertEquals("local", plain.getString("domain_resolver"))
    }

    @Test fun orderBySignalAndMemory() {
        assertEquals(
            listOf(AntiFilterMethod.PLAIN, AntiFilterMethod.FRAGMENT, AntiFilterMethod.CLEAN_IP, AntiFilterMethod.RECORD_FRAGMENT),
            AntiFilterPlan.order(EdgeSignal.TLS_OK, null),
        )
        assertEquals(AntiFilterMethod.FRAGMENT, AntiFilterPlan.order(EdgeSignal.TLS_RESET, null).first())
        assertTrue(AntiFilterMethod.PLAIN in AntiFilterPlan.order(EdgeSignal.TLS_RESET, null))
        assertEquals(listOf(AntiFilterMethod.CLEAN_IP), AntiFilterPlan.order(EdgeSignal.SILENT, null))
        assertEquals(listOf(AntiFilterMethod.CLEAN_IP), AntiFilterPlan.order(EdgeSignal.SILENT, AntiFilterMethod.FRAGMENT))
        val remembered = AntiFilterPlan.order(EdgeSignal.TLS_OK, AntiFilterMethod.RECORD_FRAGMENT)
        assertEquals(AntiFilterMethod.RECORD_FRAGMENT, remembered.first())
        assertEquals(remembered.distinct(), remembered)
        assertEquals(4, remembered.size)
    }

    @Test fun stepsFitTheBudget() {
        val all = AntiFilterPlan.steps(AntiFilterMethod.AUTO, EdgeSignal.TLS_OK, null, 10 * 60_000L)
        assertEquals(4, all.size)
        assertEquals(135_000L, all.sumOf { it.timeoutMs })
        val tight = AntiFilterPlan.steps(AntiFilterMethod.AUTO, EdgeSignal.TLS_OK, null, 90_000L)
        assertTrue(tight.sumOf { it.timeoutMs } <= 90_000L)
        assertEquals(listOf(AntiFilterMethod.PLAIN, AntiFilterMethod.FRAGMENT, AntiFilterMethod.CLEAN_IP), tight.map { it.method })
        assertEquals(15_000L, tight.last().timeoutMs)
        val spent = AntiFilterPlan.steps(AntiFilterMethod.AUTO, EdgeSignal.TLS_RESET, null, 3_000L)
        assertEquals(listOf(AntiFilterStep(AntiFilterMethod.FRAGMENT, AntiFilterPlan.MIN_STEP_MS)), spent)
        // The Auto engine's Firefox VPN budget minus a typical 20 s of pass/list/ping and the
        // tunnel reserve still runs every method.
        val autoLeft = com.uacspoofer.mobile.simorgh.auto.AutoConnector.timeoutMs(com.uacspoofer.mobile.engine.EngineMode.FOXY_FIREFOX) - 20_000L - 5_000L
        assertEquals(4, AntiFilterPlan.steps(AntiFilterMethod.AUTO, EdgeSignal.TLS_OK, null, autoLeft).size)
    }

    @Test fun manualMethodRunsAlone() {
        val steps = AntiFilterPlan.steps(AntiFilterMethod.RECORD_FRAGMENT, EdgeSignal.TLS_OK, AntiFilterMethod.PLAIN, 60_000L)
        assertEquals(listOf(AntiFilterStep(AntiFilterMethod.RECORD_FRAGMENT, 60_000L)), steps)
    }

    @Test fun signal() {
        val ok = FoxyPingResult("h", 2499, "1.1.1.1", 10, 20)
        val reset = FoxyPingResult("h", 2499, "1.1.1.1", 10, null, "tls")
        val dead = FoxyPingResult("h", 2499, null, null, null, "dns")
        assertEquals(EdgeSignal.TLS_OK, AntiFilterPlan.signalOf(listOf(reset, ok)))
        assertEquals(EdgeSignal.TLS_RESET, AntiFilterPlan.signalOf(listOf(reset, dead)))
        assertEquals(EdgeSignal.SILENT, AntiFilterPlan.signalOf(listOf(dead)))
        assertEquals(EdgeSignal.SILENT, AntiFilterPlan.signalOf(emptyList()))
    }

    @Test fun memoryRoundTripAndTtl() {
        val now = 1_000_000_000L
        var m = AntiFilterMemory()
            .withIps("wifi:a", listOf("23.235.42.9", "23.235.42.10", "not-an-ip"), now)
            .withIps("wifi:a", listOf("23.235.42.11"), now + 1, winner = "23.235.42.10")
            .withMethod("wifi:a", AntiFilterMethod.FRAGMENT)
            .withMethod("cell:b", AntiFilterMethod.AUTO)
        assertEquals(listOf("23.235.42.10", "23.235.42.11", "23.235.42.9"), m.freshIps("wifi:a", now + 2))
        val back = AntiFilterMemory.decode(m.encode())
        assertEquals(m, back)
        assertEquals(AntiFilterMethod.FRAGMENT, back.lastMethod["wifi:a"])
        assertFalse(back.lastMethod.containsKey("cell:b"))
        assertTrue(back.freshIps("wifi:a", now + AntiFilterMemory.CLEAN_IP_TTL_MS + 10).isEmpty())
        assertTrue(back.freshIps("other", now).isEmpty())
        m = back.withoutIp("wifi:a", "23.235.42.10")
        assertEquals(listOf("23.235.42.11", "23.235.42.9"), m.freshIps("wifi:a", now + 2))
        assertEquals(AntiFilterMemory(), AntiFilterMemory.decode("{broken"))
        assertEquals(AntiFilterMemory(), AntiFilterMemory.decode(null))
    }

    @Test fun memoryCaps() {
        var m = AntiFilterMemory()
        for (i in 0 until 20) m = m.withIps("net$i", (1..20).map { "23.235.42.$it" }, 1_000L + i).withMethod("net$i", AntiFilterMethod.PLAIN)
        assertEquals(AntiFilterMemory.MAX_NETWORKS, m.cleanIps.size)
        assertEquals(AntiFilterMemory.MAX_NETWORKS, m.lastMethod.size)
        assertTrue("net19" in m.cleanIps && "net0" !in m.cleanIps)
        assertTrue("net19" in m.lastMethod && "net0" !in m.lastMethod)
        assertEquals(AntiFilterMemory.MAX_IPS, m.cleanIps.getValue("net19").ips.size)
    }

    @Test fun cleanEdgeSampling() {
        val blocks = FoxyCleanEdgeRanges.blocks(listOf("23.235.42.16", "151.101.3.7", null, "2a04:4e42::1", "23.235.42.2"))
        assertEquals(listOf("23.235.42.0/24", "151.101.3.0/24"), blocks)
        val ips = FoxyCleanEdgeRanges.sample(blocks, listOf("23.235.42.200"), count = 30, random = Random(7))
        assertEquals(30, ips.size)
        assertEquals("23.235.42.200", ips.first())
        assertEquals(ips.distinct(), ips)
        assertTrue(ips.any { it.startsWith("151.101.3.") })
        assertTrue(ips.all { ip -> ip.substringAfterLast('.').toInt() in 1..254 })
        assertEquals(254, FoxyCleanEdgeRanges.sample(listOf("23.235.42.0/24", "bad"), emptyList(), count = 999).size)
    }
}
