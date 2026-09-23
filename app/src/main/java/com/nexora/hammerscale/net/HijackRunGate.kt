package com.nexora.hammerscale.net

import java.util.concurrent.atomic.AtomicInteger

/**
 * Serialises battle hijack runs.
 *
 * A cancelled coroutine still runs its `finally` block, so the closing "STOPPED" of an old
 * run would otherwise arrive *after* the next run has started and knock it back to off --
 * which made the toggle appear to switch itself off the moment it was turned on. Each run
 * takes a generation and only the newest generation's statuses reach the UI, so a dying run
 * can no longer interfere with the one replacing it.
 *
 * The same check guards teardown: a superseded run must not disarm the acknowledgement hook
 * that the run replacing it has already armed.
 */
class HijackRunGate {

    private val generation = AtomicInteger(0)

    /** One run's view of the gate; all of its reports and teardown go through here. */
    inner class Run internal constructor(private val mine: Int) {

        /** False once this run has been replaced or cancelled. */
        val isCurrent: Boolean get() = mine == generation.get()

        /** Reports progress; dropped silently once this run has been superseded. */
        fun emit(
            onStatus: (status: String, terminal: Boolean, tally: HijackTally) -> Unit,
            status: String,
            terminal: Boolean,
            tally: HijackTally
        ) {
            if (isCurrent) onStatus(status, terminal, tally)
        }
    }

    /** Begins a new run, superseding any earlier one. */
    fun newRun(): Run = Run(generation.incrementAndGet())

    /** Invalidates the in-flight run so its remaining reports, including its finally, are dropped. */
    fun invalidate() {
        generation.incrementAndGet()
    }
}
