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
        /** Pause between battle-hijack cycles, mirroring the duel hijack's 1s gap. */
        const val INTER_CYCLE_DELAY_MS = 1_000L
        const val VPN_ADDRESS = "10.0.0.1"
        const val VPN_ROUTE   = "0.0.0.0"

        @Volatile var instance: TrafficVpnService? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var captureJob: Job? = null
    private var duelHijackJob: Job? = null
    private var duelHijackLossJob: Job? = null
    private var battleHijackJob: Job? = null
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

    fun armClanIntercept(roundsToWin: Int = 2) {
        tcpHandler?.armClanIntercept(roundsToWin)
    }
    fun disarmClanIntercept() { tcpHandler?.disarmClanIntercept() }

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

    fun runDuelHijack(onStatus: (String) -> Unit) {
        duelHijackJob?.cancel()

        val handler = tcpHandler ?: run { onStatus("ERROR: VPN not running"); return }
        val vm = AppState.viewModel

        duelHijackJob = scope.launch {
            var round = 0
            var wins  = 0

            var blobDeferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
            handler.armDuelHijack { _, blob ->
                if (!blobDeferred.isCompleted) blobDeferred.complete(blob)
            }

            onStatus("Hijack armed — starting brawler loop")

            while (isActive) {
                round++

                val startCounter = vm.nextInjectCounter
                val startResult  = injectDirect(PacketInjector.buildBrawlerStart(startCounter))
                Log.d("HammerDuel", "brawler_start r$round counter=$startCounter -> $startResult")

                if (startResult.startsWith("FAIL")) {
                    onStatus("ERROR: Inject failed: $startResult")
                    break
                }
                onStatus("[Round $round | $wins wins] waiting for server...")

                val enemyBlob = try {
                    withTimeout(15_000) { blobDeferred.await() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    handler.disarmDuelHijack()
                    onStatus("TIMEOUT: No reply in 15s — quota hit or disconnected. Tap again when ready.")
                    break
                }

                Log.d("HammerDuel", "r$round: got blob ${enemyBlob.size}B")
                delay(300)

                val finishCounter = vm.nextInjectCounter
                val finishResult  = injectDirect(PacketInjector.buildBrawlerFinishWin(enemyBlob, finishCounter))
                Log.d("HammerDuel", "brawler_finish r$round counter=$finishCounter -> $finishResult")

                if (finishResult.startsWith("FAIL")) {
                    onStatus("ERROR: Inject failed: $finishResult")
                    break
                }

                wins++
                onStatus("[Round $round | $wins wins] WIN")

                blobDeferred = kotlinx.coroutines.CompletableDeferred()
                handler.armDuelHijack { _, blob ->
                    if (!blobDeferred.isCompleted) blobDeferred.complete(blob)
                }

                delay(1_000)
            }

            handler.disarmDuelHijack()
            onStatus("STOPPED: $wins wins in $round rounds")
            Log.d("HammerDuel", "Hijack stopped — wins=$wins rounds=$round")
        }
    }

    fun cancelDuelHijack() {
        duelHijackJob?.cancel()
        duelHijackJob = null
        tcpHandler?.disarmDuelHijack()
    }

    fun runDuelHijackLoss(onStatus: (String) -> Unit) {
        duelHijackLossJob?.cancel()

        val handler = tcpHandler ?: run { onStatus("ERROR: VPN not running"); return }
        val vm = AppState.viewModel

        duelHijackLossJob = scope.launch {
            var round = 0
            var losses = 0

            var blobDeferred = kotlinx.coroutines.CompletableDeferred<ByteArray>()
            handler.armDuelHijack { _, blob ->
                if (!blobDeferred.isCompleted) blobDeferred.complete(blob)
            }

            onStatus("Loss hijack armed — starting brawler loop")

            while (isActive) {
                round++

                val startCounter = vm.nextInjectCounter
                val startResult  = injectDirect(PacketInjector.buildBrawlerStart(startCounter))
                Log.d("HammerDuel", "loss brawler_start r$round counter=$startCounter → $startResult")

                if (startResult.startsWith("FAIL")) {
                    onStatus("ERROR: Inject failed: $startResult")
                    break
                }
                onStatus("[Round $round | $losses losses] waiting for server...")

                val enemyBlob = try {
                    withTimeout(15_000) { blobDeferred.await() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    handler.disarmDuelHijack()
                    onStatus("TIMEOUT: No reply in 15s — quota hit or disconnected. Tap again when ready.")
                    break
                }

                Log.d("HammerDuel", "loss r$round: got blob ${enemyBlob.size}B")
                delay(300)

                val finishCounter = vm.nextInjectCounter
                val finishResult  = injectDirect(PacketInjector.buildBrawlerFinishLoss(enemyBlob, finishCounter))
                Log.d("HammerDuel", "loss brawler_finish r$round counter=$finishCounter → $finishResult")

                if (finishResult.startsWith("FAIL")) {
                    onStatus("ERROR: Inject failed: $finishResult")
                    break
                }

                losses++
                onStatus("[Round $round | $losses losses] LOSS")

                blobDeferred = kotlinx.coroutines.CompletableDeferred()
                handler.armDuelHijack { _, blob ->
                    if (!blobDeferred.isCompleted) blobDeferred.complete(blob)
                }

                delay(1_000)
            }

            handler.disarmDuelHijack()
            onStatus("STOPPED: $losses losses in $round rounds")
            Log.d("HammerDuel", "Loss hijack stopped — losses=$losses rounds=$round")
        }
    }

    fun cancelDuelHijackLoss() {
        duelHijackLossJob?.cancel()
        duelHijackLossJob = null
        tcpHandler?.disarmDuelHijack()
    }

    @Deprecated("use cancelDuelHijack()")
    fun disarmDuelHijack() = cancelDuelHijack()

    /**
     * Battle hijack: replays a whole event battle for a given battle id without playing it.
     *
     * Looks the battle id up in [BattleConfig] to get its rounds-to-win, then runs
     * `activate_ascension` -> server ack -> `event_battle_start_fight` -> server ack ->
     * `event_battle_finish_fight` (win) -> server verdict. Each packet is only sent after
     * the server's reply for the previous one has arrived, matching the packet count and
     * pacing of a real client-driven fight.
     *
     * The cycle repeats until [cancelBattleHijack] is called (the toggle is turned off).
     * A rejected or timed-out cycle does not end the loop: the status is reported and the
     * next cycle starts, since a single rejection is usually transient (cooldown, stale
     * socket) and ending the run on it would silently stop the user's farm.
     */
    fun runBattleHijack(battleId: String, onStatus: (String, Boolean, HijackTally) -> Unit) {
        battleHijackJob?.cancel()

        val handler = tcpHandler ?: run { onStatus("ERROR: VPN not running", true, HijackTally.EMPTY); return }
        val vm = AppState.viewModel

        val id = battleId.trim().toLongOrNull()
        if (id == null) { onStatus("ERROR: battle id must be numeric", true, HijackTally.EMPTY); return }

        val rounds = BattleConfig.roundsFor(battleId.trim())
        if (rounds == null) { onStatus("ERROR: battle $battleId not in table", true, HijackTally.EMPTY); return }

        battleHijackJob = scope.launch {
            var cycle = 0
            var accepts = 0
            var fails = 0

            // A failed step ends the cycle early; tally it and let the loop retry.
            fun tally() = HijackTally(accepts, fails)

            // Each step gets its own deferred so a late reply to a previous command can
            // never release the next step's wait.
            fun armAck(expectedCmd: String): CompletableDeferred<BattleResult?> {
                val deferred = CompletableDeferred<BattleResult?>()
                handler.armBattleAck { cmd, _, result ->
                    if (cmd == expectedCmd && !deferred.isCompleted) deferred.complete(result)
                }
                return deferred
            }

            // Sends one packet and waits for its reply. Returns null on success, else a
            // short reason. The caller reports it and counts the fail exactly once.
            suspend fun send(
                deferred: CompletableDeferred<BattleResult?>,
                counter: Long,
                frame: ByteArray,
                label: String
            ): String? {
                val result = injectDirect(frame)
                if (result.startsWith("FAIL")) return "$label inject failed: $result"
                Log.d("HammerBattle", "c$cycle $label ctr=$counter")
                return try {
                    withTimeout(15_000) { deferred.await() }
                    null
                } catch (_: TimeoutCancellationException) {
                    "no $label reply in 15s"
                }
            }

            try {
                while (isActive) {
                    cycle++

                    // Capture: accepted fights always begin with activate_ascension for the
                    // battle id, immediately followed by ONE start. The client then plays the
                    // fight and sends ONE finish whose round index is the battle's total round
                    // count. Omitting activate_ascension makes the server reject the finish
                    // with "Out of attempts".
                    fun fail(reason: String) {
                        fails++
                        onStatus("FAIL: $reason — retrying", false, tally())
                    }

                    val ascensionCounter = vm.nextInjectCounter
                    val ascensionAck = armAck("activate_ascension")
                    val ascensionErr = send(ascensionAck, ascensionCounter,
                        PacketInjector.buildActivateAscension(id, ascensionCounter), "activate_ascension")
                    if (ascensionErr != null) { fail(ascensionErr); delay(INTER_CYCLE_DELAY_MS); continue }

                    val startCounter = vm.nextInjectCounter
                    val startAck = armAck("event_battle_start_fight")
                    val startErr = send(startAck, startCounter,
                        PacketInjector.buildEventBattleStart(id, startCounter), "start")
                    if (startErr != null) { fail(startErr); delay(INTER_CYCLE_DELAY_MS); continue }

                    val finishCounter = vm.nextInjectCounter
                    val finishAck = armAck("event_battle_finish_fight")
                    val finishFrame = PacketInjector.buildEventBattleFinish(
                        battleId = id,
                        roundsToWin = rounds,
                        roundIdx = rounds,
                        timestampMs = System.currentTimeMillis(),
                        counter = finishCounter,
                        won = true
                    )
                    val finishErr = send(finishAck, finishCounter, finishFrame, "finish")
                    if (finishErr != null) { fail(finishErr); delay(INTER_CYCLE_DELAY_MS); continue }

                    when (val verdict = awaitResult(finishAck)) {
                        is BattleResult.Accepted -> {
                            accepts++
                            onStatus("$accepts accept (cycle $cycle, ${verdict.resultBytes} bytes)", false, tally())
                        }
                        is BattleResult.Rejected -> fail(verdict.reason)
                        null -> fail("no verdict")
                    }

                    delay(INTER_CYCLE_DELAY_MS)
                }
            } finally {
                handler.disarmBattleAck()
                onStatus("STOPPED: $accepts accept, $fails fail", true, tally())
                Log.d("HammerBattle", "Hijack stopped — accepts=$accepts fails=$fails cycles=$cycle")
            }
        }
    }

    private suspend fun awaitResult(deferred: CompletableDeferred<BattleResult?>): BattleResult? {
        return try {
            withTimeout(15_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            null
        }
    }

    fun cancelBattleHijack() {
        battleHijackJob?.cancel()
        battleHijackJob = null
        tcpHandler?.disarmBattleAck()
    }

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
