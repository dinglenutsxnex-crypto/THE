package com.nexora.hammerscale.net

import java.security.MessageDigest

/**
 * Login takeover: swaps any outbound SF3 LOGIN with our hardcoded Chapter-5
 * account, so the device plays on the PC-farmed account without manual login.
 *
 * Wire recipe (proven live from PCAP, see sf3_acc/finally_cracked_*.md):
 *   session  = handshake-response field 2 (per TCP session, server-issued)
 *   password = MD5hex(session + guid).lowercase()      (session FIRST)
 *   f        = SHA1hex(session + X_CERT).uppercase()   (APK cert hash, static)
 *   sysid    = our SystemId token (any 15-hex passes; server binds it)
 * LoginRequest{1: version=6, 2: primary{1:Device(1), 2:compact JSON},
 *              3: secondary{1:SystemId(6), 2:sysid}, 4: extData compact JSON}
 * Out envelope keeps the CLIENT's counter so replies pair up.
 */
object LoginTakeover {

    const val ACCOUNT_GUID = "3882d4cb-4250-40f3-adc5-11058e7db77b" // NCKJNDHD, ch5 Mumbai, lvl 16, 100-duel winner
    const val ACCOUNT_SYSID = "0475805d646f441"
    private const val CERT_HASH_X = "D61109D768EDAA3AD2EFA9EF357BD1AE33D5F0AB"
    private const val APP_ID = "com.nekki.shadowfight3"
    private const val V = "19217"
    private const val CONFIG_VER = "1.45.0.175.16722-prod"
    private const val APP_VER = "1.45.5"
    private const val BNAME =
        "UnityClient_ShadowFight3_UnityClientShadowFight3Release_ConfigurationAndroid"

    @Volatile private var lastSession: String? = null

    /** Inbound HANDSHAKE response -> capture server-issued session. */
    fun onInboundFrame(frame: ByteArray) {
        try {
            val proto = GameProtocolParser.extractPayload(frame) ?: return
            val fields = GameProtocolParser.readProtoFields(proto)
            val cmd = (fields[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return
            if (cmd != "HANDSHAKE") return
            val hsPayload = fields[3] as? ByteArray ?: return
            val hs = GameProtocolParser.readProtoFields(hsPayload)
            val session = (hs[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return
            if (session.isNotBlank()) {
                lastSession = session
                android.util.Log.d("HammerLogin", "takeover: captured session=$session")
            }
        } catch (_: Exception) { }
    }

    /**
     * Outbound LOGIN -> rebuilt frame with our account (client counter kept).
     * Returns null when frame is not a LOGIN or no session captured yet
     * (caller forwards original).
     */
    fun takeoverLogin(frame: ByteArray): ByteArray? {
        try {
            val session = lastSession ?: return null
            val proto = GameProtocolParser.extractPayload(frame) ?: return null
            val fields = GameProtocolParser.readProtoFields(proto)
            val cmd = (fields[2] as? ByteArray)?.toString(Charsets.UTF_8) ?: return null
            if (cmd != "LOGIN") return null
            val counter = fields[1] as? Long ?: return null

            val pw = md5Hex(session + ACCOUNT_GUID).lowercase()
            val f = sha1Hex(session + CERT_HASH_X).uppercase()
            val primaryJson =
                "{\"login\":\"$ACCOUNT_GUID\",\"password\":\"$pw\"}"
            val primary = proto {
                varintField(1, 1L)
                stringField(2, primaryJson)
            }
            val secondary = proto {
                varintField(1, 6L)
                stringField(2, ACCOUNT_SYSID)
            }
            val ext =
                "{\"platform\":\"Android\",\"v\":\"$V\",\"app_id\":\"$APP_ID\"," +
                "\"f\":\"$f\",\"bnumber\":\"$V\",\"bname\":\"$BNAME\"}"
            val loginPayload = proto {
                varintField(1, 6L)
                bytesField(2, primary)
                bytesField(3, secondary)
                bytesField(4, ext.toByteArray(Charsets.UTF_8))
            }
            val body = proto {
                varintField(1, counter)
                stringField(2, "LOGIN")
                bytesField(3, loginPayload)
            }
            android.util.Log.d("HammerLogin", "takeover: swapped LOGIN ctr=$counter guid=$ACCOUNT_GUID")
            return frameOut(body)
        } catch (_: Exception) { return null }
    }

    private fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun sha1Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun frameOut(body: ByteArray): ByteArray {
        // Real client: small frames raw 0x01, big frames deflated (LOGIN ~276B).
        return if (body.size <= 255) {
            byteArrayOf(0x01, body.size.toByte()) + body
        } else {
            val deflater = java.util.zip.Deflater(6, true)
            deflater.setInput(body)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(body.size)
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            deflater.end()
            val compressed = out.toByteArray()
            val lenBytes = java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(compressed.size).array()
            byteArrayOf(0x02) + lenBytes + compressed
        }
    }

    private fun proto(block: ProtoW.() -> Unit): ByteArray {
        val w = ProtoW()
        w.block()
        return w.toByteArray()
    }

    private class ProtoW {
        private val buf = mutableListOf<Byte>()
        fun varintField(n: Int, v: Long) {
            writeVarint((n.toLong() shl 3) or 0L)
            writeVarint(v)
        }
        fun stringField(n: Int, v: String) = bytesField(n, v.toByteArray(Charsets.UTF_8))
        fun bytesField(n: Int, b: ByteArray) {
            writeVarint((n.toLong() shl 3) or 2L)
            writeVarint(b.size.toLong())
            b.forEach { buf.add(it) }
        }
        private fun writeVarint(v0: Long) {
            var v = v0
            while (v and -0x80L != 0L) {
                buf.add(((v and 0x7F) or 0x80L).toByte())
                v = v ushr 7
            }
            buf.add((v and 0x7F).toByte())
        }
        fun toByteArray(): ByteArray = buf.toByteArray()
    }
}
