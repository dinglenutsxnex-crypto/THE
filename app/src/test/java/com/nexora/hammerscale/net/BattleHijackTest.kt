package com.nexora.hammerscale.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact regression tests for the event-battle encoders, pinned to the captured
 * traffic for battle 1029011:
 *   - user_event_battle_start_fight_133.bin  (accepted start)
 *   - user_event_battle_finish_fight_139.bin (accepted round-4 win)
 *   - user_unknown_146.bin                   (rejected round-1 report)
 *   - server_event_battle_finish_fight_146.bin / _139.bin (server verdicts)
 */
class BattleHijackTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val acceptedStart = hex(
        "0122082712186576656e745f626174746c655f73746172745f66696768741a040893e73e"
    )

    private val acceptedWin = hex(
        "0159082912196576656e745f626174746c655f66696e6973685f66696768741a3a0893e73e20013207" +
        "08cdb496f98c34380452006a2310041a01002201002a010032040000803f3a040000803f4204000000" +
        "004a01005201002804"
    )

    /** My old injection: round index 1 and win flag 0x01 — the shape the server rejected. */
    private val rejectedOld = hex(
        "0159083d12196576656e745f626174746c655f66696e6973685f66696768741a3a0893e73e20013207" +
        "08a8a197f98c34380452006a2310011a01002201012a010032040000803f3a040000803f4204000000" +
        "004a01005201002804"
    )

    /** activate_ascension for 1029011 — precedes every accepted fight in both captures. */
    private val acceptedAscension = hex(
        "011c0819121261637469766174655f617363656e73696f6e1a040893e73e"
    )

    @Test
    fun `activate ascension matches accepted capture`() {
        assertEquals(
            acceptedAscension.toList(),
            PacketInjector.buildActivateAscension(1029011L, 25L).toList()
        )
    }

    @Test
    fun `activate ascension reply is parsed as a battle ack`() {
        val server = byteArrayOf(0x01, 0x1c) + hex(
            "0819121261637469766174655f617363656e73696f6e1a040893e73e"
        )
        assertEquals("activate_ascension" to 25L, GameProtocolParser.parseBattleAck(server))

        // An unrelated command must not be mistaken for a battle ack.
        val ping = byteArrayOf(0x01, 0x0c) + hex("081d120470696e67")
        assertEquals(null, GameProtocolParser.parseBattleAck(ping))
    }

    @Test
    fun `start fight matches accepted capture`() {
        assertEquals(
            acceptedStart.toList(),
            PacketInjector.buildEventBattleStart(1029011L, 39L).toList()
        )
    }

    @Test
    fun `finish fight win matches accepted capture byte for byte`() {
        val built = PacketInjector.buildEventBattleFinish(
            battleId = 1029011L,
            roundsToWin = 4,
            roundIdx = 4,
            timestampMs = 1790181743181L,
            counter = 41L,
            won = true
        )
        assertEquals(acceptedWin.toList(), built.toList())
    }

    @Test
    fun `old round index and win flag reproduce the rejected packet`() {
        val built = PacketInjector.buildEventBattleFinish(
            battleId = 1029011L,
            roundsToWin = 4,
            roundIdx = 1,
            timestampMs = 1790181757096L,
            counter = 61L,
            won = false
        )
        assertEquals(rejectedOld.toList(), built.toList())
    }

    @Test
    fun `rejected reply is classified as rejected`() {
        val frame = hex(
            "016d083d12196576656e745f626174746c655f66696e6973685f666967687420012a4c5b687a315d205b" +
            "6a6176612e6c616e672e496c6c6567616c5374617465457863657074696f6e5d204f7574206f66206174" +
            "74656d7074732028706c6179657249643a20333436393939323029"
        )
        val result = GameProtocolParser.classifyBattleResult(frame)
        assertTrue("expected Rejected, got $result", result is BattleResult.Rejected)
        assertTrue(
            (result as BattleResult.Rejected).reason.contains("Out of attempts")
        )
    }

    @Test
    fun `accepted reply is classified as accepted`() {
        // Same field shape as server_event_battle_finish_fight_139 (counter 41, cmd, params),
        // sized to fit a type-0x01 frame so no compression is needed for the fixture.
        val inner = ByteArray(80) { 0x5a }
        val prefix = hex("082912196576656e745f626174746c655f66696e6973685f66696768741a50")
        val payload = prefix + inner
        val frame = byteArrayOf(0x01, payload.size.toByte()) + payload
        val result = GameProtocolParser.classifyBattleResult(frame)
        assertTrue("expected Accepted, got $result", result is BattleResult.Accepted)
        assertEquals(80, (result as BattleResult.Accepted).resultBytes)
    }
}
