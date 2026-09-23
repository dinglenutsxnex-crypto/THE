package com.nexora.hammerscale.net

/**
 * Server verdict on an injected event_battle_finish_fight.
 *
 * [Accepted] carries the byte size of the result payload; [Rejected] carries the
 * server's failure text (typically "Out of attempts ...").
 */
sealed class BattleResult {
    data class Accepted(val resultBytes: Int) : BattleResult()
    data class Rejected(val reason: String) : BattleResult()
}
