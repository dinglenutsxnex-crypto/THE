package com.nexora.hammerscale.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the terminal/non-terminal split for run statuses.
 *
 * The retry added for missing replies only works if its status stays non-terminal — otherwise
 * the toggle drops to off and the run ends exactly as it did before the retry existed. That
 * coupling is invisible in the loop code, so it is asserted here directly.
 */
class RunStatusTest {

    @Test
    fun `a graceful stop is terminal`() {
        assertTrue(RunStatus.isTerminal("STOPPED: 12 wins in 12 rounds"))
        assertTrue(RunStatus.isTerminal("STOPPED: 0 wins, 4 losses in 4 rounds (2 retries)"))
    }

    @Test
    fun `an unrecoverable error is terminal`() {
        assertTrue(RunStatus.isTerminal("ERROR: Inject failed: FAIL socket closed"))
        assertTrue(RunStatus.isTerminal("ERROR: VPN not running"))
    }

    @Test
    fun `the no-reply retry status must not be terminal`() {
        // This is the exact wording runOneDuelRound emits on a 2s reply timeout. If it ever
        // read as terminal the retry would be dead code: the run would end on the first
        // dropped reply, which is the bug the timeout was added to fix.
        assertFalse(
            "no-reply retry treated as terminal — the run would stop instead of restarting",
            RunStatus.isTerminal("No reply in 2s — restarting round")
        )
    }

    @Test
    fun `progress lines are not terminal`() {
        assertFalse(RunStatus.isTerminal("Infinite Coin armed — alternating win/loss"))
        assertFalse(RunStatus.isTerminal("[Round 3 | W2 L1] waiting for server..."))
        assertFalse(RunStatus.isTerminal("[Round 3 | W2 L1] WIN"))
        assertFalse(RunStatus.isTerminal("running…"))
    }
}
