package com.uacspoofer.mobile.simorgh.flux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxPlannerTest {
    private val net = "wifi:home"
    private val now = 10_000_000_000L

    private fun route(id: String, host: String = "$id.example.com", country: String? = null, label: String = id, source: String = "own") =
        FluxRoute(id = id, engine = "xray_cf", host = host, provenCountry = country, label = label, source = source)

    @Test
    fun warmRouteForThisPolicyGoesFirst() {
        val a = route("a"); val b = route("b"); val c = route("c")
        var s = FluxState()
        s = FluxMemory.recordSuccess(s, net, b.key, now - 1_000, 80)
        s = FluxMemory.setBest(s, net, null, IpMode.BOTH, listOf(b.key))
        val plan = FluxPlanner.plan(FluxPlanner.Input(listOf(a, b, c), s, net, now)).ordered
        assertEquals("b", plan.first().id)
        assertEquals(3, plan.size)
    }

    @Test
    fun warmRouteOnAnotherNetworkDoesNotLead() {
        val a = route("a"); val b = route("b")
        var s = FluxMemory.recordSuccess(FluxState(), "cell:mci", b.key, now - 1_000, 80)
        s = FluxMemory.setBest(s, "cell:mci", null, IpMode.BOTH, listOf(b.key))
        val plan = FluxPlanner.plan(FluxPlanner.Input(listOf(a, b), s, net, now)).ordered
        assertEquals(2, plan.size)
        // Nothing known on this network: own configs in a stable shuffled order, both present.
        assertEquals(setOf("a", "b"), plan.map { it.id }.toSet())
    }

    @Test
    fun breakerOpensAfterThreeFailuresAndSkipsTheRoute() {
        val a = route("a"); val b = route("b")
        var s = FluxState()
        repeat(3) { s = FluxMemory.recordFailure(s, net, a.key, now, FailReason.TIMEOUT) }
        assertTrue(FluxMemory.metrics(s, net, a.key)!!.coolingDown(now + 1))
        val result = FluxPlanner.plan(FluxPlanner.Input(listOf(a, b), s, net, now + 1))
        assertEquals(listOf("b"), result.ordered.map { it.id })
        assertEquals("cooldown", result.skipped[a.key])
    }

    @Test
    fun successClosesTheBreaker() {
        val a = route("a")
        var s = FluxState()
        repeat(3) { s = FluxMemory.recordFailure(s, net, a.key, now, FailReason.TIMEOUT) }
        s = FluxMemory.recordSuccess(s, net, a.key, now + 5, 50)
        val m = FluxMemory.metrics(s, net, a.key)!!
        assertFalse(m.coolingDown(now + 6))
        assertEquals(0, m.consecutiveFailures)
    }

    @Test
    fun chosenCountryPutsProvenThenHintedFirstAndDropsProvenElsewhere() {
        val de = route("de", country = "DE")
        val hint = route("hint", label = "🇩🇪 Frankfurt")
        val us = route("us", country = "US")
        val other = route("other")
        val plan = FluxPlanner.plan(FluxPlanner.Input(listOf(other, us, hint, de), FluxState(), net, now, country = "de"))
        assertEquals(listOf("de", "hint", "other"), plan.ordered.map { it.id })
        assertEquals("country", plan.skipped[us.key])
    }

    @Test
    fun ipVersionFiltersLiteralAndResolvedHosts() {
        val v4 = route("v4", host = "104.16.1.1")
        val v6 = route("v6", host = "2606:4700::1")
        val named = route("named", host = "n.example.com")
        val unknown = route("unknown", host = "u.example.com")
        val resolved = mapOf("n.example.com" to setOf(Family.V6))
        val only4 = FluxPlanner.plan(FluxPlanner.Input(listOf(v4, v6, named, unknown), FluxState(), net, now, mode = IpMode.V4, resolved = resolved))
        assertEquals(setOf("v4", "unknown"), only4.ordered.map { it.id }.toSet())
        val only6 = FluxPlanner.plan(FluxPlanner.Input(listOf(v4, v6, named, unknown), FluxState(), net, now, mode = IpMode.V6, resolved = resolved, hasV6 = true))
        assertEquals(setOf("v6", "named", "unknown"), only6.ordered.map { it.id }.toSet())
        val noV6 = FluxPlanner.plan(FluxPlanner.Input(listOf(v6), FluxState(), net, now, mode = IpMode.V6, hasV6 = false))
        assertTrue(noV6.ordered.isEmpty())
    }

    @Test
    fun knownRoutesInterleaveWithUntriedOnesThreeToOne() {
        val known = (1..6).map { route("k$it") }
        val fresh = (1..3).map { route("u$it") }
        var s = FluxState()
        known.forEachIndexed { i, r -> s = FluxMemory.recordSuccess(s, net, r.key, now - 100_000, 100L + i * 10) }
        val plan = FluxPlanner.plan(FluxPlanner.Input(known + fresh, s, net, now, budget = 20)).ordered.map { it.id }
        // Positions 0..2 known, 3 untried, 4..6 known, 7 untried.
        assertTrue(plan.take(3).all { it.startsWith("k") })
        assertTrue(plan[3].startsWith("u"))
        assertTrue(plan[7].startsWith("u"))
        // Faster known routes rank first.
        assertEquals("k1", plan.first())
    }

    @Test
    fun ownConfigsAreTriedBeforeSubscriptionOnesWhenNothingIsKnown() {
        val sub = (1..4).map { route("s$it", source = "sub") }
        val own = route("own")
        val plan = FluxPlanner.plan(FluxPlanner.Input(sub + own, FluxState(), net, now)).ordered
        assertEquals("own", plan.first().id)
    }

    @Test
    fun stateSurvivesJsonRoundTrip() {
        var s = FluxState()
        s = FluxMemory.recordSuccess(s, net, "xray_cf:a", now, 120)
        s = FluxMemory.recordFailure(s, net, "xray_cf:b", now, FailReason.HANDSHAKE)
        s = FluxMemory.setBest(s, net, "NL", IpMode.V4, listOf("xray_cf:a"))
        val back = FluxState.fromJson(s.toJson().toString())
        assertEquals(s, back)
        assertEquals(listOf("xray_cf:a"), FluxMemory.best(back, net, "nl", IpMode.V4))
        assertEquals(FluxState(), FluxState.fromJson("{not json"))
    }

    @Test
    fun retainDropsDeletedRoutes() {
        var s = FluxMemory.recordSuccess(FluxState(), net, "xray_cf:a", now, 1)
        s = FluxMemory.setBest(s, net, null, IpMode.BOTH, listOf("xray_cf:a", "xray_cf:gone"))
        s = FluxMemory.recordSuccess(s, net, "xray_cf:gone", now, 1)
        val kept = FluxMemory.retain(s, setOf("xray_cf:a"))
        assertEquals(listOf("xray_cf:a"), FluxMemory.best(kept, net, null, IpMode.BOTH))
        assertEquals(1, kept.metrics.size)
    }

    @Test
    fun scorerPrefersSteadyOverFlaky() {
        var steady = FluxMetrics()
        repeat(5) { steady = steady.copy(successEwma = steady.successEwma * 0.8 + 0.2, rtts = steady.rtts + 70L, lastOkAt = now) }
        val flaky = FluxMetrics(successEwma = 0.4, rtts = listOf(40, 40, 900), lastOkAt = now, consecutiveFailures = 1)
        assertTrue(FluxScorer.score(steady, null, now) > FluxScorer.score(flaky, null, now))
        assertFalse(FluxScorer.shouldSwitch(0.80, 0.83))
        assertTrue(FluxScorer.shouldSwitch(0.50, 0.70))
    }

    @Test
    fun countryHintReadsFlagsAndCodes() {
        assertEquals("DE", CountryHint.of("🇩🇪 Frankfurt"))
        assertEquals("NL", CountryHint.of("vless-NL-ws"))
        assertEquals(null, CountryHint.of("IP WS TV"))
    }
}
