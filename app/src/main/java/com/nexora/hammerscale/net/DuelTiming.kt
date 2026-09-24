package com.nexora.hammerscale.net

/**
 * Every artificial pause in the duel and battle-hijack loops, in one place.
 *
 * These were scattered as literals across the run loops: a flat 300ms before each duel's finish
 * and a 1s gap between battle-hijack cycles. The 1s gap alone explained the ~1.3s per accepted
 * battle, since a cycle is only three round-trips; the 300ms was paid on every duel round. Both
 * were pure throttle — the replies already serialise the protocol, so neither pause was needed
 * for correctness, only for pacing.
 *
 * Kept out of [com.nexora.hammerscale.TrafficVpnService] so the values are unit-testable without
 * an Android runtime, and so a change in one caller cannot silently leave another slow.
 */
object DuelTiming {

    /**
     * Gap between a duel's start reply and its finish.
     *
     * The reply is the server's acknowledgement that the start was processed, so waiting longer
     * adds latency and buys nothing. 50ms keeps the finish from sharing a tick with the reply
     * without meaningfully delaying the round. Was 300ms.
     */
    const val PRE_FINISH_DELAY_MS = 50L

    /**
     * Pause between battle-hijack cycles. A cycle is `activate_ascension`, `start`, `finish`,
     * each already waiting on its own reply, so this gap only throttled throughput. Was 1s,
     * which dominated the per-cycle time.
     */
    const val INTER_CYCLE_DELAY_MS = 50L

    /** Gap after a completed Infinite Coin round at 1x. */
    const val COIN_ROUND_DELAY_1X_MS = 150L

    /**
     * Gap after a completed Infinite Coin round at 2x: none. The next duel starts the moment the
     * previous finish is on the wire.
     */
    const val COIN_ROUND_DELAY_2X_MS = 0L

    /**
     * The Infinite Coin gap for a given speed setting. 2x must always be the faster of the two —
     * the button is the only thing the user sees, so a 2x that was slower than 1x would read as
     * the feature being backwards.
     */
    fun coinRoundDelayMs(speed2x: Boolean): Long =
        if (speed2x) COIN_ROUND_DELAY_2X_MS else COIN_ROUND_DELAY_1X_MS
}
