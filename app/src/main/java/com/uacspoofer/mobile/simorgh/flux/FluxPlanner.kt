package com.uacspoofer.mobile.simorgh.flux

import kotlin.random.Random

/**
 * Decides what to try, before anything is tried:
 *
 *  - warm: this network has a known good route for the user's choice -> it goes first, so a
 *    second connect on the same network is near instant;
 *  - then known-good routes by score, interleaved 3:1 with untried ones so new configs get a
 *    chance (exploration), routes resting after failures left out;
 *  - a chosen country puts proven matches first, then routes whose name claims it; a route proven
 *    to exit elsewhere is dropped;
 *  - the IP version filters routes whose literal address is the wrong family.
 */
object FluxPlanner {
    data class Input(
        val routes: List<FluxRoute>,
        val state: FluxState,
        val net: String,
        val now: Long,
        val country: String? = null,
        val mode: IpMode = IpMode.BOTH,
        /** Families each named host resolves to (literal hosts need no entry). Unknown = allowed. */
        val resolved: Map<String, Set<Family>> = emptyMap(),
        /** Whether the device has a working IPv6 path; null = unknown. */
        val hasV6: Boolean? = null,
        val budget: Int = 12,
        val random: Random = Random(0),
    )

    data class Plan(
        val ordered: List<FluxRoute>,
        /** Routes left out and why (for the log / UI). */
        val skipped: Map<String, String>,
    )

    fun plan(inp: Input): Plan {
        val skipped = LinkedHashMap<String, String>()
        val fams = inp.mode.families.filter { it == Family.V4 || inp.hasV6 != false }
        if (fams.isEmpty()) {
            inp.routes.forEach { skipped[it.key] = "no-ipv6" }
            return Plan(emptyList(), skipped)
        }
        val country = inp.country?.uppercase()
        val usable = inp.routes.distinctBy { it.key }.filter { r ->
            val m = FluxMemory.metrics(inp.state, inp.net, r.key)
            when {
                m?.coolingDown(inp.now) == true -> { skipped[r.key] = "cooldown"; false }
                !familyOk(r, fams, inp.resolved) -> { skipped[r.key] = "family"; false }
                country != null && r.provenCountry != null && !r.provenCountry.equals(country, true) -> {
                    skipped[r.key] = "country"; false
                }
                else -> true
            }
        }
        fun proven(r: FluxRoute) = country != null && r.provenCountry.equals(country, true)
        fun hinted(r: FluxRoute) = country != null && r.countryHint.equals(country, true)
        fun score(r: FluxRoute) = FluxScorer.score(FluxMemory.metrics(inp.state, inp.net, r.key), null, inp.now)

        val warmKeys = FluxMemory.best(inp.state, inp.net, country, inp.mode)
        val byKey = usable.associateBy { it.key }
        val warm = warmKeys.mapNotNull { byKey[it] }.filter { r ->
            val m = FluxMemory.metrics(inp.state, inp.net, r.key)
            m != null && inp.now - m.lastOkAt < WARM_MAX_AGE_MS
        }
        val rest = usable - warm.toSet()
        val (known, unknown) = rest.partition {
            (FluxMemory.metrics(inp.state, inp.net, it.key)?.lastOkAt ?: 0L) > 0L
        }
        val knownSorted = known.sortedWith(
            compareByDescending<FluxRoute> { proven(it) }.thenByDescending { hinted(it) }.thenByDescending { score(it) },
        )
        // Untried: the user's own configs first (proven on their line), then the rest, shuffled
        // with a stable seed so one bad subscription cannot always fill the head of the list.
        val unknownSorted = unknown.shuffled(inp.random).sortedWith(
            compareByDescending<FluxRoute> { proven(it) }
                .thenByDescending { hinted(it) }
                .thenBy { SOURCE_ORDER.indexOf(it.source).let { i -> if (i < 0) 9 else i } }
                .thenBy { FluxMemory.metrics(inp.state, inp.net, it.key)?.failCount ?: 0 },
        )
        val merged = ArrayList<FluxRoute>(usable.size)
        merged += warm
        var k = 0
        var u = 0
        while (k < knownSorted.size || u < unknownSorted.size) {
            repeat(3) { if (k < knownSorted.size) merged += knownSorted[k++] }
            if (u < unknownSorted.size) merged += unknownSorted[u++]
        }
        val ordered = if (country == null) merged else {
            // Stable: the warm/known/unknown mix holds inside each group.
            merged.sortedWith(compareByDescending<FluxRoute> { proven(it) }.thenByDescending { hinted(it) })
        }
        return Plan(ordered.take(inp.budget), skipped)
    }

    private fun familyOk(r: FluxRoute, fams: List<Family>, resolved: Map<String, Set<Family>>): Boolean {
        val lit = Family.ofLiteral(r.host)
        if (lit != null) return lit in fams
        val known = resolved[r.host] ?: return true
        return known.any { it in fams }
    }

    /** The countries the user can pick: proven exits first, then name hints. */
    fun countries(routes: List<FluxRoute>): List<String> =
        (routes.mapNotNull { it.provenCountry?.uppercase() } + routes.mapNotNull { it.countryHint })
            .filter { it.length == 2 && it != "XX" }.distinct()

    const val WARM_MAX_AGE_MS = 48 * 3600_000L
    private val SOURCE_ORDER = listOf("own", "provider", "sub")
}
