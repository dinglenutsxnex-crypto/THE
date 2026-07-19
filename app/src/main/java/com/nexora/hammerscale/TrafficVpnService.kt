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

    /**
     * Duel Hijack — fully autonomous brawler farm loop.
     * Brings our app to foreground, sends pings every 3s forever to keep
     * the connection alive, and loops: brawler_start → wait for reply →
     * brawler_finish WIN → repeat.  Runs until cancelDuelHijack() is called.
     * Every injected frame flows through injectDirect → onMessage → dev-mode log.
     */
    fun runDuelHijack(onStatus: (String) -> Unit) {
        duelHijackJob?.cancel()

        val handler = tcpHandler ?: run { onStatus("❌ VPN not running"); return }
        val vm = AppState.viewModel

        // Bring HammerScale to foreground — SF3 goes to background and stops sending
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("HammerDuel", "Could not bring app to foreground: ${e.message}")
        }

        duelHijackJob = scope.launch {
            onStatus("⏳ Foregrounded — waiting 2s for game to quiet down…")
            delay(2000)

            val netData = vm.lastPingNetDataBytes
            if (netData == null) {
                onStatus("❌ No ping data — open SF3, let it connect, then tap again")
                return@launch
            }

            Log.d("HammerDuel", "Starting hijack loop. netData=${netData.size}B")

            // Returns true on success, false + onStatus if connection is dead.
            fun inject(data: ByteArray, tag: String): Boolean {
                val r = injectDirect(data)
                Log.d("HammerDuel", "$tag → $r")
                return if (r.startsWith("FAIL")) {
                    onStatus("❌ Connection dead after $tag ($r)\nRe-open SF3 → let it connect → tap again")
                    false
                } else true
            }

            // ── Ping loop — runs the ENTIRE time, every 3s ──────────────────────
            // Uses SupervisorJob so a failed ping doesn't kill the parent coroutine.
            val pingJob = launch(kotlinx.coroutines.SupervisorJob()) {
                var n = 0
                while (isActive) {
                    val c = vm.nextInjectCounter
                    val r = injectDirect(PacketInjector.buildPing(c, System.currentTimeMillis(), netData))
                    Log.d("HammerDuel", "ping[$n] counter=$c → $r")
                    if (n == 0) onStatus("📡 Ping OK (counter=$c) — starting brawler loop")
                    n++
                    delay(3_000)
                }
            }

            // Brief pause so first ping gets acknowledged before we start brawling
            delay(1_500)

            // ── Brawler loop ─────────────────────────────────────────────────────
            var round = 0
            var wins  = 0
            try {
                while (isActive) {
                    round++

                    // Arm the reply deferred BEFORE sending so there's zero race window
                    val replyDeferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
                    handler.armDuelHijack { _, blob ->
                        if (!replyDeferred.isCompleted) replyDeferred.complete(blob)
                    }

                    val startCounter = vm.nextInjectCounter
                    if (!inject(PacketInjector.buildBrawlerStart(startCounter), "brawler_start r$round")) {
                        break  // dead socket — error already posted by inject()
                    }
                    Log.d("HammerDuel", "brawler_start  round=$round counter=$startCounter")
                    onStatus("🎯 [Round $round | $wins wins] brawler_start (counter=$startCounter)…")

                    // Wait for server's brawler_start reply
                    val enemyBlob = try {
                        withTimeout(15_000) { replyDeferred.await() }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        handler.disarmDuelHijack()
                        Log.w("HammerDuel", "Timeout waiting for brawler_start reply round=$round")
                        onStatus("⏰ [Round $round] No server reply in 15s — is connection still alive?")
                        delay(3_000)
                        continue
                    }

                    Log.d("HammerDuel", "brawler_start reply  blob=${enemyBlob.size}B")
                    delay(200) // let server finish writing the reply

                    val finishCounter = vm.nextInjectCounter
                    if (!inject(PacketInjector.buildBrawlerFinishWin(enemyBlob, finishCounter), "brawler_finish r$round")) {
                        break
                    }
                    wins++
                    Log.d("HammerDuel", "brawler_finish WIN  round=$round wins=$wins counter=$finishCounter blob=${enemyBlob.size}B")
                    onStatus("✅ [Round $round | $wins wins] WIN (counter=$finishCounter) — looping…")

                    delay(1_500) // brief breathe before next round
                }
            } finally {
                pingJob.cancel()
                handler.disarmDuelHijack()
                onStatus("🛑 Loop ended — $wins wins in $round rounds")
                Log.d("HammerDuel", "Hijack loop stopped — wins=$wins rounds=$round")
            }
        }
    }

    fun cancelDuelHijack() {
        duelHijackJob?.cancel()
        duelHijackJob = null
        tcpHandler?.disarmDuelHijack()
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
