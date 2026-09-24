package com.nexora.hammerscale.net

/**
 * Classifies a status line from a duel or battle run as ending the run or not.
 *
 * The run loops emit plain strings, so the terminal decision is made by inspecting the prefix.
 * That makes the wording load-bearing: a retry that reports "No reply in 2s — restarting round"
 * must NOT read as terminal, or the run stops exactly as if the retry had not been added. The
 * rule lives here, once, so the producers and this classifier cannot drift apart silently.
 */
object RunStatus {

    private val TERMINAL_PREFIXES = listOf("STOPPED", "ERROR", "TIMEOUT")

    /**
     * True when [status] means the run is over and the toggle should drop to off.
     *
     * Note that a *fatal* error still uses the ERROR prefix. A missing reply is deliberately not
     * one of these: it is reported without a terminal prefix and the caller restarts the round.
     */
    fun isTerminal(status: String): Boolean = TERMINAL_PREFIXES.any { status.startsWith(it) }
}
