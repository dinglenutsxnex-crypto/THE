package com.nexora.hammerscale.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the run serialisation that stops a dying hijack run from switching off the run
 * that replaced it.
 */
class HijackRunGateTest {

    private class Recorder {
        val statuses = mutableListOf<String>()
        val terminals = mutableListOf<Boolean>()
        fun sink(): (String, Boolean, HijackTally) -> Unit = { s, t, _ ->
            statuses += s
            terminals += t
        }
    }

    private fun HijackRunGate.Run.report(rec: Recorder, s: String, t: Boolean, tally: HijackTally) =
        emit(rec.sink(), s, t, tally)

    @Test
    fun `a normal run reports through to the ui`() {
        val rec = Recorder()
        val run = HijackRunGate().newRun()

        run.report(rec, "starting…", false, HijackTally.EMPTY)
        run.report(rec, "1 accept", false, HijackTally(1, 0))
        run.report(rec, "STOPPED: 1 accept, 0 fail", true, HijackTally(1, 0))

        assertEquals(listOf("starting…", "1 accept", "STOPPED: 1 accept, 0 fail"), rec.statuses)
        assertEquals(listOf(false, false, true), rec.terminals)
    }

    @Test
    fun `the old run's closing STOPPED cannot reach the ui after a new run starts`() {
        val gate = HijackRunGate()
        val rec = Recorder()

        // Run 1 is live and accumulating.
        val old = gate.newRun()
        old.report(rec, "starting…", false, HijackTally.EMPTY)

        // Restart: run 2 replaces it. Run 1's finally now fires -- this is the exact sequence
        // that used to flip the toggle back off the moment it was switched on.
        val new = gate.newRun()
        old.report(rec, "STOPPED: 0 accept, 3 fail", true, HijackTally(0, 3))

        // The stale terminal must not have got through.
        assertTrue("stale STOPPED leaked to the ui: ${rec.statuses}", rec.statuses.none { it.startsWith("STOPPED") })
        assertTrue("a terminal leaked and would have switched the toggle off", rec.terminals.none { it })

        // The new run still works.
        new.report(rec, "1 accept", false, HijackTally(1, 0))
        assertEquals(listOf("starting…", "1 accept"), rec.statuses)
    }

    @Test
    fun `invalidating drops the in-flight run's finally`() {
        val gate = HijackRunGate()
        val rec = Recorder()
        val run = gate.newRun()

        run.report(rec, "starting…", false, HijackTally.EMPTY)
        gate.invalidate()
        run.report(rec, "STOPPED: 0 accept, 1 fail", true, HijackTally(0, 1))

        assertEquals(listOf("starting…"), rec.statuses)
    }

    @Test
    fun `failures are reported as non-terminal so they only tick the counter`() {
        val gate = HijackRunGate()
        val rec = Recorder()
        val run = gate.newRun()

        run.report(rec, "FAIL: no finish reply in 15s — retrying", false, HijackTally(2, 1))

        assertEquals(listOf("FAIL: no finish reply in 15s — retrying"), rec.statuses)
        assertEquals(listOf(false), rec.terminals)
    }

    @Test
    fun `a superseded run must not tear down the live run's ack hook`() {
        val gate = HijackRunGate()
        val old = gate.newRun()
        val new = gate.newRun()

        // A superseded run's finally disarms the ack handler. If it were allowed to, the new
        // run would wait 15s for a reply that is no longer being delivered.
        assertTrue("superseded run thinks it is still current", !old.isCurrent)
        assertTrue("live run was marked stale", new.isCurrent)
        assertTrue("a cancelled run must not be current after invalidate", gate.newRun().let { gate.invalidate(); !it.isCurrent })
    }

    @Test
    fun `each run is isolated from the next across many restarts`() {
        val gate = HijackRunGate()
        val rec = Recorder()
        val runs = (1..5).map { gate.newRun() }

        // Every superseded run tries to report its own stop, oldest first.
        runs.dropLast(1).forEachIndexed { i, r ->
            r.report(rec, "STOPPED: run $i", true, HijackTally.EMPTY)
        }
        runs.last().report(rec, "5 accept", false, HijackTally(5, 0))

        assertEquals(listOf("5 accept"), rec.statuses)
    }
}
