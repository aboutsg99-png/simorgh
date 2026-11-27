package com.uacspoofer.mobile.simorgh.flux

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxRaceTest {
    @Test fun firstSuccessWinsAndSlowOnesAreCutAfterGrace() = runBlocking {
        val started = System.currentTimeMillis()
        val wins = FluxRace.firstWins(
            items = listOf("dead", "fast", "ok", "slow"),
            parallel = 4, perItemMs = 10_000, graceMs = 300, totalMs = 10_000,
        ) { item ->
            when (item) {
                "dead" -> { delay(50); null }
                "fast" -> { delay(100); 100L }
                "ok" -> { delay(250); 250L }
                else -> { delay(5_000); 40L } // would be fastest, but answers far too late
            }
        }
        assertEquals(listOf("fast", "ok"), wins.map { it.first })
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test fun failuresAndExceptionsAreDropped() = runBlocking {
        val wins = FluxRace.firstWins(listOf(1, 2, 3), parallel = 2, perItemMs = 1_000, graceMs = 100, totalMs = 5_000) {
            when (it) { 1 -> error("boom"); 2 -> null; else -> 70L }
        }
        assertEquals(listOf(3 to 70L), wins)
    }

    @Test fun totalBudgetHolds() = runBlocking {
        val started = System.currentTimeMillis()
        val wins = FluxRace.firstWins(listOf(1, 2), parallel = 2, perItemMs = 10_000, graceMs = 100, totalMs = 300) {
            delay(5_000); 1L
        }
        assertTrue(wins.isEmpty())
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }
}
