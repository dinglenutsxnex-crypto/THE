package com.nexora.hammerscale.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Infinite Coin outcome schedule. Keeping the win/loss ratio level is the entire
 * point of the feature, so the ordering is asserted directly rather than inferred from the
 * run loop's status strings.
 */
class DuelAlternationTest {

    private fun take(n: Int): List<Boolean> =
        DuelAlternation().let { a -> List(n) { a.nextDuelWins() } }

    @Test
    fun `duels alternate starting with a win`() {
        assertEquals(
            listOf(true, false, true, false, true, false),
            take(6)
        )
    }

    @Test
    fun `the ratio stays level so the coin payout cannot be driven up`() {
        val outcomes = take(100)
        assertEquals(50, outcomes.count { it })
        assertEquals(50, outcomes.count { !it })
    }

    @Test
    fun `every win is followed by a loss and every loss by a win`() {
        val outcomes = take(50)
        outcomes.zipWithNext().forEachIndexed { i, (prev, next) ->
            assertTrue("duels $i and ${i + 1} were both $prev", prev != next)
        }
    }

    @Test
    fun `the first duel of a run is a win`() {
        assertTrue(DuelAlternation().nextDuelWins())
    }

    @Test
    fun `rounds counts the duels scheduled`() {
        val a = DuelAlternation()
        a.nextDuelWins()
        a.nextDuelWins()
        a.nextDuelWins()
        assertEquals(3, a.rounds)
    }
}
