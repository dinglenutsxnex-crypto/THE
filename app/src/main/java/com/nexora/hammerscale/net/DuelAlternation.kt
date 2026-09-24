package com.nexora.hammerscale.net

/**
 * Outcome schedule for the Infinite Coin run: the duels alternate win, loss, win, loss...
 *
 * The coin payout tracks the win/loss ratio, so a run of wins moves it in the wrong
 * direction. Holding the ratio level is the whole point of the feature, which is why the
 * schedule is a separate, directly testable unit rather than a modulo buried in the run
 * loop: an off-by-one that made the first duel a loss, or that skipped a swap, would still
 * "work" but would not keep the ratio level.
 *
 * Only the schedule lives here. Counting outcomes stays with the run loop, which counts a
 * duel only once its finish packet was actually accepted.
 */
class DuelAlternation {

    var rounds = 0
        private set

    /**
     * Advances to the next duel and reports whether it should be won. Odd rounds win, so the
     * first duel of a run is a win and every win is followed by a loss.
     */
    fun nextDuelWins(): Boolean {
        rounds++
        return rounds % 2 == 1
    }

    /**
     * Gives back the outcome reserved by the last [nextDuelWins] for a duel that never ran.
     * Without this a failed round consumes an alternation slot, so the next real duel takes the
     * wrong side and the ratio drifts.
     */
    fun rewind() {
        if (rounds > 0) rounds--
    }
}
