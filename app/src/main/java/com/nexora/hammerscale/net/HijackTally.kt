package com.nexora.hammerscale.net

/**
 * Running tally of a battle-hijack run.
 *
 * [accepts] counts server-accepted battles; [fails] counts rejected rounds and
 * timed-out or failed injections. The UI shows the fail count only when it is non-zero.
 */
data class HijackTally(val accepts: Int, val fails: Int) {
    companion object {
        val EMPTY = HijackTally(0, 0)
    }
}
