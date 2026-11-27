package com.uacspoofer.mobile.engine.foxy

import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FoxyCountryPingerTest {
    private fun server(host: String) = VpnServerNode(hostname = host, port = 443)
    private fun country(code: String, vararg cities: Pair<String, List<String>>) = VpnCountry(
        name = code, code = code,
        cities = cities.map { (city, hosts) -> VpnCity(city, city, hosts.map(::server)) },
    )

    private val countries = listOf(
        country("DE", "FRA" to listOf("de-fra-1.example.net", "de-fra-2.example.net"), "BER" to listOf("de-ber-1.example.net")),
        country("US", "NYC" to listOf("us-nyc-1.example.net", "us-nyc-2.example.net", "us-nyc-3.example.net", "us-nyc-4.example.net")),
        country("JP", "TYO" to listOf("jp-tyo-1.example.net")),
        country("SE", "STO" to listOf("se-sto-1.example.net", "se-sto-2.example.net")),
    )

    private fun ok(c: ProxyCandidate, tcp: Int, tls: Int?) = FoxyPingResult(c.host, c.port, "203.0.113.1", tcp, tls)
    private fun dead(c: ProxyCandidate) = FoxyPingResult(c.host, c.port, null, null, null, "timeout")

    @Before fun reset() = FoxyCountryPinger.clearCache()

    @Test fun everyCountryGetsAPingEvenWhenTlsIsReset() = runBlocking {
        // The SNI filter resets every TLS handshake; TCP still answers. Before, these rows had no ping.
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val results = FoxyCountryPinger.measureAll(countries, "wifi:test", random = null, probe = { ok(it, 80, null) }) {
            seen += it.code
        }
        assertEquals(setOf("DE", "US", "JP", "SE"), results.keys)
        results.values.forEach {
            assertEquals(80, it.pingMs)
            assertFalse(it.tlsOk)
        }
        assertEquals(4, seen.size) // published per country
    }

    @Test fun prefersTlsReachableEdgeAndFastest() = runBlocking {
        val results = FoxyCountryPinger.measureAll(countries, "wifi:test", codes = listOf("US"), random = null, probe = {
            when (it.host) {
                "us-nyc-1.example.net" -> ok(it, 40, null)   // fastest TCP but TLS reset
                "us-nyc-2.example.net" -> ok(it, 90, 200)
                else -> ok(it, 70, 150)
            }
        })
        val us = results.getValue("US")
        assertTrue(us.tlsOk)
        assertEquals(70, us.pingMs)
        assertEquals("us-nyc-3.example.net", us.best?.host)
    }

    @Test fun secondRoundWhenFirstRoundIsDead() = runBlocking {
        val probed = Collections.synchronizedList(mutableListOf<String>())
        val results = FoxyCountryPinger.measureAll(countries, "wifi:test", codes = listOf("US"), random = null, probe = {
            probed += it.host
            if (it.host == "us-nyc-4.example.net") ok(it, 120, 300) else dead(it)
        })
        assertEquals(4, probed.size)
        assertEquals(120, results.getValue("US").pingMs)
    }

    @Test fun noSecondRoundWhenFirstAnswered() = runBlocking {
        val probed = AtomicInteger()
        FoxyCountryPinger.measureAll(countries, "wifi:test", codes = listOf("US"), random = null, probe = {
            probed.incrementAndGet(); ok(it, 50, 100)
        })
        assertEquals(FoxyCountryPinger.PER_COUNTRY, probed.get())
    }

    @Test fun quicOnlyEdgeStillShowsPing() = runBlocking {
        val results = FoxyCountryPinger.measureAll(countries, "wifi:test", codes = listOf("JP"), random = null, probe = {
            FoxyPingResult(it.host, it.port, "203.0.113.9", null, null, "tcp", udpMs = 160)
        })
        assertEquals(160, results.getValue("JP").pingMs)
    }

    @Test fun deadCountryHasNoPingAndIsNotCached() = runBlocking {
        val first = FoxyCountryPinger.measureAll(countries, "wifi:test", codes = listOf("SE"), random = null, probe = { dead(it) })
        assertNull(first.getValue("SE").pingMs)
        assertTrue(FoxyCountryPinger.cached("wifi:test").isEmpty())
    }

    @Test fun candidatesAreSpreadAcrossCities() {
        val picked = FoxyCountryPinger.pickCandidates(countries, "DE", 2, random = null)
        assertEquals(setOf("FRA", "BER"), picked.map { it.cityCode }.toSet())
    }

    @Test fun concurrencyIsCapped() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        FoxyCountryPinger.measureAll(countries, "wifi:test", parallel = 2, random = null, probe = {
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now, ::maxOf)
            delay(20)
            inFlight.decrementAndGet()
            ok(it, 60, 90)
        })
        assertTrue("peak ${peak.get()}", peak.get() <= 2)
    }

    @Test fun cachedPerNetwork() = runBlocking {
        FoxyCountryPinger.measureAll(countries, "wifi:home", random = null, probe = { ok(it, 70, 100) })
        val probed = AtomicInteger()
        val again = FoxyCountryPinger.measureAll(countries, "wifi:home", random = null, probe = { probed.incrementAndGet(); ok(it, 10, 20) })
        assertEquals(0, probed.get())
        assertEquals(70, again.getValue("DE").pingMs)
        FoxyCountryPinger.measureAll(countries, "cell:mci", random = null, probe = { probed.incrementAndGet(); ok(it, 10, 20) })
        assertTrue(probed.get() > 0)
        // Cache expires.
        val later = System.currentTimeMillis() + FoxyCountryPinger.CACHE_TTL_MS + 1
        assertTrue(FoxyCountryPinger.cached("wifi:home", later).isEmpty())
    }

    @Test fun quicVersionNegotiationRoundTrip() {
        val dcid = ByteArray(8) { it.toByte() }
        val scid = ByteArray(8) { (0x40 + it).toByte() }
        val probe = FoxyPing.quicVersionProbe(dcid, scid)
        assertEquals(1200, probe.size)
        assertEquals(0xC0.toByte(), probe[0])
        // Server's Version Negotiation: version 0, DCID = our SCID, SCID = our DCID, versions.
        val vn = byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 8) + scid + byteArrayOf(8) + dcid + byteArrayOf(0, 0, 0, 1)
        assertTrue(FoxyPing.isVersionNegotiation(vn, vn.size, scid))
        assertFalse(FoxyPing.isVersionNegotiation(vn, vn.size, dcid))
        val wrongVersion = vn.copyOf().also { it[4] = 1 }
        assertFalse(FoxyPing.isVersionNegotiation(wrongVersion, wrongVersion.size, scid))
    }

    @Test fun dohAnswerAndBogusAddresses() {
        val body = """{"Status":0,"Answer":[{"name":"x.","type":5,"data":"y."},{"name":"y.","type":1,"data":"151.101.1.1"}]}"""
        assertEquals("151.101.1.1", FoxyPing.parseDohA(body))
        assertNull(FoxyPing.parseDohA("""{"Status":3}"""))
        assertTrue(FoxyPing.isBogus(InetAddress.getByName("10.10.34.35")))
        assertTrue(FoxyPing.isBogus(InetAddress.getByName("0.0.0.0")))
        assertFalse(FoxyPing.isBogus(InetAddress.getByName("151.101.1.1")))
    }

    @Test fun rankOrderTlsThenAnsweredThenDead() {
        val a = FoxyPingResult("a", 443, null, 30, null)
        val b = FoxyPingResult("b", 443, null, 90, 200)
        val c = FoxyPingResult("c", 443, null, null, null)
        val d = FoxyPingResult("d", 443, null, null, null, udpMs = 50)
        assertEquals(listOf("b", "a", "d", "c"), listOf(c, d, a, b).sortedWith(FoxyPing.BEST_FIRST).map { it.host })
        assertNotNull(d.pingMs)
    }
}
