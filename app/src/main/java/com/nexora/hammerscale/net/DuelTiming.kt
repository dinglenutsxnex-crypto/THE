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
     * adds latency and buys nothing. 1ms keeps the finish from sharing a tick with the reply.
     * Was 300ms, then 50ms.
     */
    const val PRE_FINISH_DELAY_MS = 1L

    /**
     * Pause between battle-hijack cycles. A cycle is `activate_ascension`, `start`, `finish`,
     * each already waiting on its own reply, so this gap only throttled throughput. Was 1s,
     * which dominated the per-cycle time; then 50ms.
     */
    const val INTER_CYCLE_DELAY_MS = 1L

    /** Gap after a completed Infinite Coin round. Effectively none. */
    const val COIN_ROUND_DELAY_MS = 0L
}
