package com.nexora.hammerscale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexora.hammerscale.model.*
import com.nexora.hammerscale.net.*
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.nio.ByteBuffer

class TrafficVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.nexora.hammerscale.START_VPN"
        const val ACTION_STOP  = "com.nexora.hammerscale.STOP_VPN"
        const val TARGET_PACKAGE = "com.nekki.shadowfight3"
        const val CHANNEL_ID = "hammerscale_vpn"
        const val NOTIF_ID = 1001
        const val VPN_ADDRESS = "10.0.0.1"
        const val VPN_ROUTE   = "0.0.0.0"

        @Volatile var instance: TrafficVpnService? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureJob: Job? = null
    private var duelHijackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var tcpHandler: TcpHandler? = null
    private var udpHandler: UdpHandler? = null

    val viewModel: ConnectionViewModel by lazy { AppState.viewModel }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY }
        }
    }

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("HAMMERSCALE")
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .setMtu(1500)

            try {
                builder.addAllowedApplication(TARGET_PACKAGE)
            } catch (e: Exception) {
            }

            vpnInterface = builder.establish()
            val fd = vpnInterface?.fileDescriptor ?: return

            tcpHandler = TcpHandler(
                vpnService = this,
                vpnFd = fd,
                onConnectionEvent = { entry -> viewModel.addOrUpdateConnection(entry) },
                onMessage = { id, msg -> viewModel.addMessage(id, msg) },
                onStatusChange = { id, status ->
                    viewModel.updateConnectionStatus(id, status)
                },
                onWebSocket = { id -> viewModel.markAsWebSocket(id) },
                onClanRounds = { rounds -> viewModel.setClanRounds(rounds) },
                onBattleSeq = { seq -> viewModel.setBattleSeq(seq) }
            )

            udpHandler = UdpHandler(
                vpnService = this,
                vpnFd = fd,
                onConnectionEvent = { entry -> viewModel.addOrUpdateConnection(entry) },
                onMessage = { id, msg -> viewModel.addMessage(id, msg) },
                onStatusChange = { id, status ->
                    viewModel.updateConnectionStatus(id, status)
                }
            )

            captureJob = scope.launch { captureLoop(fd) }
            viewModel.setVpnRunning(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e("TrafficVpnService", "Failed to start VPN", e)
            stopVpn()
        }
    }

    private suspend fun captureLoop(fd: java.io.FileDescriptor) {
        val input = FileInputStream(fd)
        val buf = ByteBuffer.allocate(32767)

        while (currentCoroutineContext().isActive) {
            try {
                buf.clear()
                val len = withContext(Dispatchers.IO) {
                    input.read(buf.array())
                }
                if (len <= 0) { delay(1); continue }

                buf.limit(len)
                val packet = PacketParser.parse(buf) ?: continue

                when (packet.ip.protocol) {
                    PacketParser.PROTO_TCP -> tcpHandler?.handlePacket(packet)
                    PacketParser.PROTO_UDP -> udpHandler?.handlePacket(packet)
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                delay(10)
            }
        }
    }

    fun injectToGameSocket(data: ByteArray) {
        injectToGameSocketDiag(data)
    }

    fun injectDirect(data: ByteArray): String {
        val handler = tcpHandler ?: return "FAIL: tcpHandler is null (VPN not running)"
        val vm = AppState.viewModel
        val battleId    = vm.battleSocketId.value
        val handshakeId = vm.gameSocketId.value
        return when {
            battleId != null -> {
                val r = handler.injectDirect(battleId, data)
                "battleSocket …${battleId.takeLast(16)}: $r"
            }
            handshakeId != null -> {
                val r = handler.injectDirect(handshakeId, data)
                "gameSocket …${handshakeId.takeLast(16)}: $r"
            }
            else -> handler.injectDirectToAny(data)
        }
    }

    fun injectToGameSocketDiag(data: ByteArray): String? {
        val handler = tcpHandler ?: return null
        val vm = AppState.viewModel
        val battleId    = vm.battleSocketId.value
        val handshakeId = vm.gameSocketId.value
        return when {
            battleId != null -> {
                val r = handler.injectToServer(battleId, data)
                "battleSocket …${battleId.takeLast(16)}: ${r ?: "handler returned null"}"
            }
            handshakeId != null -> {
                val r = handler.injectToServer(handshakeId, data)
                "gameSocket …${handshakeId.takeLast(16)}: ${r ?: "handler returned null"}"
            }
            else -> {
                val r = handler.injectToAny(data)
                "injectToAny: ${r ?: "handler returned null"}"
            }
        }
    }

    fun armIntercept(roundsToWin: Int = 3) {
        tcpHandler?.armIntercept(roundsToWin)
    }

    fun disarmIntercept() {
        tcpHandler?.disarmIntercept()
    }

    fun armRaidIntercept() { tcpHandler?.armRaidIntercept() }
    fun disarmRaidIntercept() { tcpHandler?.disarmRaidIntercept() }

    fun armBrawlerIntercept() { tcpHandler?.armBrawlerIntercept() }
    fun disarmBrawlerIntercept() { tcpHandler?.disarmBrawlerIntercept() }

    fun armPingAck(onAck: () -> Unit) { tcpHandler?.armPingAck(onAck) }
    fun disarmPingAck() { tcpHandler?.disarmPingAck() }

    fun setHijackBlocking(on: Boolean) { tcpHandler?.hijackBlockOutgoing = on }

    fun resetGameSocket() {
        val vm = AppState.viewModel
        val connId = vm.battleSocketId.value ?: vm.gameSocketId.value ?: return
        tcpHandler?.resetServerSocket(connId)
    }

    fun armLoginReady(onReady: () -> Unit) { tcpHandler?.armLoginReady(onReady) }
    fun disarmLoginReady() { tcpHandler?.disarmLoginReady() }

    /**
     * Duel Hijack — fully autonomous brawler farm loop.
     *
     * SF3 stays running throughout. The VPN drops SF3's outgoing packets while we
     * drive the session ourselves (hijackBlockOutgoing = true). When the server's
     * per-session brawler quota is exhausted (brawler_start gets no reply even though
     * pings still work), we force-close the server socket so SF3 gets a FIN, lets it
     * reconnect and log back in, then re-block and keep farming.
     *
     * Loops until cancelDuelHijack() is called.
     */
    fun runDuelHijack(onStatus: (String) -> Unit) {
        duelHijackJob?.cancel()

        val handler = tcpHandler ?: run { onStatus("❌ VPN not running"); return }
        val vm = AppState.viewModel

        duelHijackJob = scope.launch {

            // Block SF3's outgoing packets so it doesn't race with us on the server.
            setHijackBlocking(true)
            onStatus("⏳ Blocking SF3 traffic — waiting 3s for socket to quiet down…")
            delay(3_000)

            var netData = vm.lastPingNetDataBytes
            if (netData == null) {
                setHijackBlocking(false)
                onStatus("❌ No ping data — open SF3, let it connect, then tap again")
                return@launch
            }
            Log.d("HammerDuel", "Hijack started. netData=${netData.size}B")

            // ── Helpers ──────────────────────────────────────────────────────────

            fun inject(data: ByteArray, tag: String): Boolean {
                val r = injectDirect(data)
                Log.d("HammerDuel", "$tag → $r")
                return if (r.startsWith("FAIL")) {
                    onStatus("❌ Connection dead after $tag ($r)")
                    false
                } else true
            }

            suspend fun sendPingAndAwait(netDataBytes: ByteArray, label: String): Boolean {
                val c = vm.nextInjectCounter
                val ackDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()
                armPingAck { if (!ackDeferred.isCompleted) ackDeferred.complete(Unit) }
                val r = injectDirect(PacketInjector.buildPing(c, System.currentTimeMillis(), netDataBytes))
                Log.d("HammerDuel", "ping[$label] counter=$c → $r")
                if (r.startsWith("FAIL")) { disarmPingAck(); return false }
                return try {
                    withTimeout(5_000) { ackDeferred.await() }
                    Log.d("HammerDuel", "ping[$label] ack received"); true
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    disarmPingAck()
                    Log.w("HammerDuel", "ping[$label] no ack within 5s"); false
                }
            }

            // Forces SF3 to reconnect and waits for it to finish login.
            // Returns the fresh netData bytes, or null if reconnect failed.
            suspend fun reconnectAndResume(): ByteArray? {
                onStatus("🔄 Server quota hit — forcing reconnect…")

                val oldConnId = vm.battleSocketId.value ?: vm.gameSocketId.value

                // Unblock SF3 so it can reconnect normally, then drop the server socket.
                setHijackBlocking(false)
                if (oldConnId != null) handler.resetServerSocket(oldConnId)
                else Log.w("HammerDuel", "reconnect: no known connId to reset")

                // Wait for SF3 to establish a new connection (gameSocketId changes).
                onStatus("⏳ Waiting for SF3 to reconnect…")
                val gotNewConn = withTimeoutOrNull(30_000) {
                    while (true) {
                        val cur = vm.gameSocketId.value
                        if (cur != null && cur != oldConnId) break
                        delay(400)
                    }
                }
                if (gotNewConn == null) {
                    onStatus("❌ SF3 didn't reconnect in 30s — re-open SF3 and tap again")
                    return null
                }

                // Wait for SF3 to finish login: watch for its own outbound pings.
                onStatus("⏳ SF3 reconnected — waiting for login to complete…")
                val loginDone = kotlinx.coroutines.CompletableDeferred<Unit>()
                armLoginReady { if (!loginDone.isCompleted) loginDone.complete(Unit) }
                try {
                    withTimeout(25_000) { loginDone.await() }
                    Log.d("HammerDuel", "reconnect: login-ready signal received")
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    disarmLoginReady()
                    Log.w("HammerDuel", "reconnect: login-ready timed out — proceeding anyway")
                }

                val fresh = vm.lastPingNetDataBytes
                if (fresh == null) {
                    onStatus("❌ No ping data after reconnect — try again")
                    return null
                }

                // Re-block SF3 and let things settle before resuming.
                setHijackBlocking(true)
                delay(1_500)
                onStatus("✅ Reconnected — resuming loop")
                return fresh
            }

            // ── Initial ping ─────────────────────────────────────────────────────
            if (!sendPingAndAwait(netData, "init")) {
                setHijackBlocking(false)
                onStatus("❌ No ping ack — SF3 not connected yet, try again")
                return@launch
            }
            onStatus("📡 Ping ack — starting brawler loop")

            var round = 0
            var wins  = 0

            // Outer loop handles transparent reconnects when server quota is hit.
            outerLoop@ while (isActive) {

                // Background ping loop — fire-and-forget every 3s to keep socket alive.
                // We restart it fresh after each reconnect so it uses the latest netData.
                val currentNetData = netData   // capture for this session's bg loop
                val pingJob = launch(kotlinx.coroutines.SupervisorJob()) {
                    var n = 0
                    while (isActive) {
                        delay(3_000)
                        val c = vm.nextInjectCounter
                        val r = injectDirect(PacketInjector.buildPing(c, System.currentTimeMillis(), currentNetData))
                        Log.d("HammerDuel", "ping-bg[$n] counter=$c → $r")
                        n++
                    }
                }

                try {
                    while (isActive) {
                        round++

                        val replyDeferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
                        handler.armDuelHijack { _, blob ->
                            if (!replyDeferred.isCompleted) replyDeferred.complete(blob)
                        }

                        val startCounter = vm.nextInjectCounter
                        if (!inject(PacketInjector.buildBrawlerStart(startCounter), "brawler_start r$round")) {
                            break@outerLoop
                        }
                        Log.d("HammerDuel", "brawler_start round=$round counter=$startCounter")
                        onStatus("🎯 [Round $round | $wins wins] waiting for server reply…")

                        val enemyBlob = try {
                            withTimeout(15_000) { replyDeferred.await() }
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            handler.disarmDuelHijack()
                            Log.w("HammerDuel", "brawler_start timeout round=$round — checking connection")
                            onStatus("⏰ No reply in 15s — checking if connection is alive…")

                            // Probe: if pings still work the server silently ignored us = quota hit.
                            val alive = sendPingAndAwait(netData, "quota-probe-r$round")
                            if (!alive) {
                                onStatus("❌ Connection dead — re-open SF3 and tap again")
                                break@outerLoop
                            }

                            // Quota hit — reconnect and restart session.
                            pingJob.cancel()
                            val fresh = reconnectAndResume() ?: break@outerLoop
                            netData = fresh

                            // Confirm new session with a ping before restarting the outer loop.
                            if (!sendPingAndAwait(netData, "post-reconnect")) {
                                onStatus("❌ No ping ack after reconnect — tap again")
                                break@outerLoop
                            }
                            continue@outerLoop   // restart outer loop = new pingJob + new session
                        }

                        Log.d("HammerDuel", "brawler_start reply blob=${enemyBlob.size}B")
                        delay(300)

                        val finishCounter = vm.nextInjectCounter
                        if (!inject(PacketInjector.buildBrawlerFinishWin(enemyBlob, finishCounter), "brawler_finish r$round")) {
                            break@outerLoop
                        }
                        wins++
                        Log.d("HammerDuel", "WIN round=$round wins=$wins counter=$finishCounter")
                        onStatus("✅ [Round $round | $wins wins] WIN — cooldown 3s…")

                        delay(3_000)
                        if (!sendPingAndAwait(netData, "between-r$round")) {
                            onStatus("❌ Connection lost after round $round — tap again")
                            break@outerLoop
                        }
                        Log.d("HammerDuel", "inter-round ping ack, starting round ${round + 1}")
                    }
                } finally {
                    pingJob.cancel()
                }
                break@outerLoop   // inner loop exited cleanly (cancelled or error)
            }

            handler.disarmDuelHijack()
            setHijackBlocking(false)
            disarmLoginReady()
            disarmPingAck()
            onStatus("🛑 Loop ended — $wins wins in $round rounds")
            Log.d("HammerDuel", "Hijack stopped — wins=$wins rounds=$round")
        }
    }

    fun cancelDuelHijack() {
        duelHijackJob?.cancel()
        duelHijackJob = null
        tcpHandler?.disarmDuelHijack()
        tcpHandler?.disarmPingAck()
        tcpHandler?.disarmLoginReady()
        tcpHandler?.hijackBlockOutgoing = false   // always unblock on cancel
    }

    @Deprecated("use cancelDuelHijack()")
    fun disarmDuelHijack() = cancelDuelHijack()

    fun stopVpn() {
        captureJob?.cancel()
        tcpHandler?.shutdown()
        udpHandler?.shutdown()
        vpnInterface?.close()
        vpnInterface = null
        viewModel.setVpnRunning(false)
        stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAMMERSCALE VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Traffic monitoring VPN"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TrafficVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAMMERSCALE Active")
            .setContentText("Monitoring: $TARGET_PACKAGE")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }
}
