package com.nexora.hammerscale.net

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pacing constants. These are the knobs that decided throughput — a 1s battle-hijack
 * gap and a 300ms per-duel finish pause — so they are exactly the values liable to creep back
 * up by accident and quietly halve the rate again. The bounds are deliberately loose: the point
 * is to catch a return to the old order of magnitude, not to freeze a specific number.
 */
class DuelTimingTest {

    @Test
    fun `pre-finish pause is small, not the old 300ms`() {
        assertTrue(
            "pre-finish pause is ${DuelTiming.PRE_FINISH_DELAY_MS}ms — the old 300ms throttle is back",
            DuelTiming.PRE_FINISH_DELAY_MS <= 100
        )
        assertTrue(
            "pre-finish pause must not be negative",
            DuelTiming.PRE_FINISH_DELAY_MS >= 0
        )
    }

    @Test
    fun `battle-hijack cycle gap is small, not the old 1s`() {
        // A cycle is three round-trips; a 1s gap on top of that was the bulk of the ~1.3s
        // per accepted battle.
        assertTrue(
            "cycle gap is ${DuelTiming.INTER_CYCLE_DELAY_MS}ms — the old 1s throttle is back",
            DuelTiming.INTER_CYCLE_DELAY_MS <= 200
        )
    }

    @Test
    fun `infinite coin does not throttle between rounds`() {
        assertTrue(
            "coin round delay is ${DuelTiming.COIN_ROUND_DELAY_MS}ms — a gap between rounds is back",
            DuelTiming.COIN_ROUND_DELAY_MS <= 50
        )
    }
}
