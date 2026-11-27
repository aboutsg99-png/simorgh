package com.uacspoofer.mobile.engine.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CleanIpScannerTest {
    private fun inRange(ip: String, cidr: String): Boolean {
        val (base, bits) = CleanIpRanges.cidr4(cidr)!!
        val v = ip.split('.').fold(0L) { a, p -> (a shl 8) or p.toLong() }
        val mask = (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (v and mask) == base
    }

    @Test
    fun samplesStayInsideRangesAndSpreadAcrossSlash24s() {
        val ips = CleanIpRanges.sampleV4(CleanIpRanges.V4, 200, Random(7))
        assertEquals(200, ips.size)
        assertEquals(200, ips.toSet().size)
        assertTrue(ips.all { ip -> CleanIpRanges.V4.any { inRange(ip, it) } })
        assertTrue(ips.none { it.endsWith(".0") || it.endsWith(".255") })
        assertTrue(ips.map { it.substringBeforeLast('.') }.toSet().size >= 190)
    }

    @Test
    fun userRangesAndLiteralsAreUsed() {
        val user = CleanIpRanges.parseUser("# mine\n104.18.0.0/20\n1.2.3.4\nnonsense\n")
        assertEquals(listOf("104.18.0.0/20", "1.2.3.4"), user)
        val ips = CleanIpRanges.sampleV4(user, 5, Random(1))
        assertEquals("1.2.3.4", ips.first())
        assertTrue(ips.drop(1).all { inRange(it, "104.18.0.0/20") })
    }

    @Test
    fun scoringRewardsAnsweredStableFastAddresses() {
        val good = CleanIpSample("a", listOf(20, 21, 22), listOf(60, 61, 62), listOf(90, 95, 92))
        val slow = CleanIpSample("b", listOf(20, 21, 22), listOf(60, 61, 62), listOf(900, 950, 920))
        val flaky = CleanIpSample("c", listOf(20, 21, 22), listOf(60, 61, null), listOf(80, null, null))
        val tlsKilled = CleanIpSample("d", listOf(10, 10, 10), listOf(null, null, null), listOf(null, null, null))
        val ranked = CleanIpScoring.rank(listOf(tlsKilled, flaky, slow, good))
        assertEquals(listOf("a", "b", "c"), ranked.map { it.sample.ip })
        assertEquals(0.0, CleanIpScoring.score(tlsKilled), 0.0)
    }

    @Test
    fun readsCloudflareTrace() {
        val (cf, colo) = CleanIpScoring.parseTrace("fl=12f34\nh=speed.cloudflare.com\nip=5.6.7.8\ncolo=FRA\nloc=IR\n")
        assertTrue(cf)
        assertEquals("FRA", colo)
        assertEquals(false to null, CleanIpScoring.parseTrace("<html>nope</html>"))
    }
}
