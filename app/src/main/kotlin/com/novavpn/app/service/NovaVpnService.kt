package com.novavpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.novavpn.app.MainActivity
import com.novavpn.app.R
import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.NovaConfig
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.VpnState
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.engine.api.EngineContext
import com.novavpn.engine.api.EngineManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject

/**
 * Android VpnService responsible for the full connection lifecycle.
 *
 * ## Cancellation Safety
 *
 * Every critical step in [connect] is preceded by [ensureActive] so that a
 * cancelled coroutine stops immediately instead of building a half-baked
 * VPN interface.  A [Mutex] serialises connect and disconnect so that the
 * previous session's teardown finishes before a new session starts.
 *
 * ## Atomic Teardown
 *
 * When the connection coroutine is cancelled mid-flight, [atomicTeardown]
 * closes the TUN fd, stops the engine, stops the bridge, removes the
 * foreground notification, and calls [stopSelf] — all inside the mutex so
 * the next connection attempt starts from a clean slate.
 *
 * ## Single Source of Truth
 *
 * UI state flows through [ConnectUseCase.connectionState] as a
 * [StateFlow<VpnState>]. The service is the sole writer; the ViewModel
 * only reads. [VpnState] is a sealed interface that embeds error messages
 * directly into [VpnState.Error], eliminating the dual-state problem
 * (header says Error while button says Disconnect).
 */
@AndroidEntryPoint
class NovaVpnService : VpnService() {

    @Inject lateinit var engineManager: EngineManager
    @Inject lateinit var connectUseCase: ConnectUseCase
    @Inject lateinit var serverRepository: com.novavpn.domain.repository.ServerRepository
    @Inject lateinit var tunnelBridge: NativeTunnelBridge

    private var currentConfig: ServerConfig? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var tunHealthJob: Job? = null

    /** Guards the entire connect / disconnect flow — one at a time. */
    private val connectMutex = Mutex()

    /** Tracks whether engine / bridge have been started (for precise teardown). */
    private var engineStarted = false
    private var bridgeStarted = false

    companion object {
        const val ACTION_START = "com.novavpn.action.START_VPN"
        const val ACTION_STOP = "com.novavpn.action.STOP_VPN"
        const val EXTRA_CONFIG_ID = "extra_server_id"
        private const val NOTIFICATION_CHANNEL_ID = "novavpn_vpn"
        private const val TAG = "NovaVpnService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NovaConfig.NOTIFICATION_ID, createNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val serverId = intent.getStringExtra(EXTRA_CONFIG_ID)
                serviceScope.launch {
                    startVpnInternal(serverId?.let { serverRepository.getById(it) })
                }
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch { stopVpnInternal() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        connectUseCase.updateState(VpnState.Error("VPN revoked"))
        serviceScope.launch { stopVpnInternal() }
    }

    // ------------------------------------------------------------------
    // Public API (called from Activity / ViewModel)
    // ------------------------------------------------------------------

    /**
     * Initiate a new VPN connection.
     * Cancels any in-flight connection — the [Mutex] guarantees the
     * previous session's teardown completes before this one starts.
     */
    fun startVpn(config: ServerConfig?) {
        Timber.tag(TAG).i("CONNECT_START: server=%s", config?.name ?: "null")
        currentConfig = config
        startVpnInternal(config)
    }

    /**
     * Gracefully stop the VPN.
     * The cancellation takes effect at the next [ensureActive] check in
     * [connect]; [atomicTeardown] runs in the cancelled job's finally block.
     * A fallback [stopVpnInternal] is scheduled to catch the case where
     * no connection job is running.
     */
    fun stopVpn() {
        Timber.tag(TAG).i("DISCONNECT_START")
        connectionJob?.cancel()
        connectionJob = null
        // Fallback teardown — runs after the cancelled job releases the mutex
        serviceScope.launch {
            connectMutex.withLock {
                if (connectUseCase.connectionState.value != VpnState.Disconnected) {
                    stopVpnInternal()
                }
            }
        }
    }

    /** Quick reconnect — cancels current, then immediately retries. */
    fun reconnect() {
        val config = currentConfig ?: return
        Timber.tag(TAG).i("RECONNECT_START")
        stopVpn()
        startVpnInternal(config)
    }

    // ------------------------------------------------------------------
    // Connection coroutine lifecycle
    // ------------------------------------------------------------------

    /**
     * Launch the connection coroutine inside the [connectMutex].
     *
     * Cancellation handling:
     * - [ensureActive] before every critical step throws [CancellationException]
     *   immediately when the job was cancelled.
     * - The [finally] block runs [atomicTeardown] when [isActive] is false,
     *   guaranteeing that TUN fd, engine, bridge, notification and service
     *   are all cleaned up before the mutex is released.
     */
    private fun startVpnInternal(config: ServerConfig?) {
        connectionJob = serviceScope.launch {
            connectMutex.withLock {
                try {
                    ensureActive()
                    connect(config)
                } catch (e: CancellationException) {
                    Timber.tag(TAG).w("CONNECT_CANCELLED: %s", e.message)
                    connectUseCase.updateState(VpnState.Error("Connection cancelled"))
                    updateNotification("Cancelled")
                    throw e // re-throw so the coroutine framework handles it
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "CONNECT_FAILED: %s", e.message)
                    connectUseCase.updateState(VpnState.Error(e.message ?: "VPN failed"))
                    updateNotification("Connection failed")
                } finally {
                    if (!isActive) {
                        Timber.tag(TAG).i("CONNECT_TEARDOWN: job cancelled — performing atomic teardown")
                        atomicTeardown()
                    }
                }
            }
        }
    }

    /**
     * Core connection flow.
     *
     * Each critical step is preceded by [ensureActive] so that a cancelled
     * coroutine never builds a TUN, starts an engine, or spawns a bridge
     * after being told to stop.
     */
    private suspend fun connect(config: ServerConfig?) {
        Timber.tag(TAG).i("LIFECYCLE: CONNECTING → state=Connecting")
        connectUseCase.updateState(VpnState.Connecting)
        updateNotification("Connecting…")

        val cfg = config ?: run {
            Timber.tag(TAG).e("LIFECYCLE: FAILED → no config")
            connectUseCase.updateState(VpnState.Error("No server config provided"))
            updateNotification("No server"); return
        }

        // ── CHECK CANCELLATION before TUN build ──
        currentCoroutineContext().ensureActive()
        Timber.tag(TAG).i("LIFECYCLE: CONNECT_STEP — building TUN for %s:%d", cfg.address, cfg.port)

        val tun = buildTun() ?: run {
            connectUseCase.updateState(VpnState.Error("TUN interface failed to establish"))
            updateNotification("TUN failed"); return
        }
        tunInterface = tun
        Timber.tag(TAG).i("TUN established: fd=%d, interface=%s",
            tun.fd, NovaConfig.VPN_SESSION_NAME)

        // ── CHECK CANCELLATION before engine init ──
        currentCoroutineContext().ensureActive()
        val engine = engineManager.activeEngine ?: run {
            connectUseCase.updateState(VpnState.Error("No engine selected"))
            updateNotification("No engine"); return
        }
        Timber.tag(TAG).d("Active engine: %s", engine.type.displayName)

        val ctx = object : EngineContext {
            override val isVpnPermissionGranted = true
            override val tunFileDescriptor = tun.fd
            override val dnsServers = listOf("8.8.8.8", "1.1.1.1")
            override val routes = listOf("0.0.0.0/0")
        }
        Timber.tag(TAG).i("EngineContext created: tunFd=%d, dns=%s, routes=%s",
            ctx.tunFileDescriptor, ctx.dnsServers, ctx.routes)

        // ── ENGINE INIT ──
        Timber.tag(TAG).i("LIFECYCLE: ENGINE_INIT")
        engine.initialize(ctx).onFailure { error ->
            Timber.tag(TAG).e("LIFECYCLE: ENGINE_INIT_FAILED → %s", error.message)
            connectUseCase.updateState(VpnState.Error(error.message ?: "Init failed"))
            updateNotification("Init failed"); return
        }
        engineStarted = true

        // ── CHECK CANCELLATION before engine start ──
        currentCoroutineContext().ensureActive()
        Timber.tag(TAG).i("LIFECYCLE: ENGINE_START")
        engine.start(cfg).onFailure { error ->
            Timber.tag(TAG).e("LIFECYCLE: ENGINE_START_FAILED → %s", error.message)
            connectUseCase.updateState(VpnState.Error(error.message ?: "Start failed"))
            updateNotification("Start failed"); return
        }

        // ── CHECK CANCELLATION before waiting for running state ──
        currentCoroutineContext().ensureActive()
        Timber.tag(TAG).i("LIFECYCLE: WAITING_FOR_RUNNING")
        val running = try {
            withTimeout(30_000L) {
                engine.state.first { it == EngineRuntimeState.Running || it == EngineRuntimeState.Crashed }
            }
        } catch (_: TimeoutCancellationException) {
            engine.stop(); engineStarted = false
            Timber.tag(TAG).e("LIFECYCLE: TIMEOUT → engine not running after 30s")
            connectUseCase.updateState(VpnState.Error("Engine start timed out")); return
        }
        if (running == EngineRuntimeState.Crashed) {
            Timber.tag(TAG).e("LIFECYCLE: ENGINE_CRASHED")
            connectUseCase.updateState(VpnState.Error("Engine crashed during startup")); return
        }

        // ── CHECK CANCELLATION before bridge start ──
        currentCoroutineContext().ensureActive()
        Timber.tag(TAG).i("LIFECYCLE: BRIDGE_STARTING")
        try {
            tunnelBridge.start(tun.fd, "127.0.0.1", 10808)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "LIFECYCLE: BRIDGE_START_FAILED → %s", e.message)
            connectUseCase.updateState(VpnState.Error("Bridge failed: ${e.message}"))
            updateNotification("Bridge failed")
            engine.stop(); engineStarted = false
            return
        }
        if (tunnelBridge.status != com.novavpn.engine.api.BridgeStatus.Running) {
            Timber.tag(TAG).e("LIFECYCLE: BRIDGE_STATUS=%s — aborting", tunnelBridge.status.name)
            connectUseCase.updateState(VpnState.Error("Bridge status: ${tunnelBridge.status.name}"))
            updateNotification("Bridge failed")
            engine.stop(); engineStarted = false
            return
        }
        bridgeStarted = true
        Timber.tag(TAG).i("LIFECYCLE: BRIDGE_STATUS=%s", tunnelBridge.status.name)

        // ── ALL SYSTEMS GO → Connected (single source of truth) ──
        Timber.tag(TAG).i("LIFECYCLE: ENGINE_RUNNING → state=Connected")
        connectUseCase.updateState(VpnState.Connected)
        updateNotification("Connected")

        // ── TUN health monitor (runs as long as the connection is alive) ──
        startTunHealthMonitor(engine)

        // ── Block until engine crashes or coroutine is cancelled ──
        try {
            engine.state.collect { state ->
                if (state == EngineRuntimeState.Crashed
                    && connectUseCase.connectionState.value == VpnState.Connected) {
                    connectUseCase.updateState(VpnState.Error("Engine crashed — reconnecting"))
                    updateNotification("Reconnecting…")
                    engine.restart(cfg).onSuccess {
                        connectUseCase.updateState(VpnState.Connected)
                        updateNotification("Connected")
                    }
                }
            }
        } catch (_: CancellationException) { }
    }

    // ------------------------------------------------------------------
    // Health monitoring
    // ------------------------------------------------------------------

    private fun startTunHealthMonitor(engine: com.novavpn.engine.api.Engine) {
        tunHealthJob?.cancel()
        tunHealthJob = serviceScope.launch {
            var counter = 0
            while (isActive) {
                delay(5_000L)
                counter++
                val tun = tunInterface
                val fd = tun?.fd ?: -1
                val engineState = (engineManager.activeEngine?.state?.value)?.name ?: "unknown"
                val rx = engineManager.activeEngine?.bytesReceived?.value ?: 0L
                val tx = engineManager.activeEngine?.bytesSent?.value ?: 0L

                // Check if TUN fd is still open in this process
                val tunFdValid = if (fd >= 0) {
                    try {
                        java.io.File("/proc/self/fd/$fd").exists()
                    } catch (_: Exception) { false }
                } else false

                // Check if SOCKS5 port 10808 is listening
                if (counter % 2 == 1) {
                    try {
                        val sock = java.net.Socket()
                        sock.connect(java.net.InetSocketAddress("127.0.0.1", 10808), 200)
                        com.novavpn.domain.model.TunDiagnostics.socks5Listening = true
                        sock.close()
                    } catch (_: Exception) {
                        com.novavpn.domain.model.TunDiagnostics.socks5Listening = false
                    }
                }

                val bridgeDiag = tunnelBridge.diagnostics()
                com.novavpn.domain.model.TunDiagnostics.bridgeRunning = bridgeDiag.processAlive
                com.novavpn.domain.model.TunDiagnostics.bridgePackets = bridgeDiag.forwardedPackets
                com.novavpn.domain.model.TunDiagnostics.bridgeBytes = bridgeDiag.forwardedBytes
                com.novavpn.domain.model.TunDiagnostics.bridgeErrors = bridgeDiag.forwardErrors

                Timber.tag(TAG).i("DIAG[%d]: tunFd=%d, fdAlive=%s, engine=%s, " +
                    "rawFd=%d, inheritFd=%d, dupOK=%s, inbound=%s, nInbound=%d, " +
                    "socks5=%s, tunReads=%d, bridge=%s, bPkts=%d, bBytes=%d, bErr=%d",
                    counter, fd, tunFdValid, engineState,
                    com.novavpn.domain.model.TunDiagnostics.rawFd,
                    com.novavpn.domain.model.TunDiagnostics.inheritableFd,
                    com.novavpn.domain.model.TunDiagnostics.dupOK,
                    com.novavpn.domain.model.TunDiagnostics.inboundType,
                    com.novavpn.domain.model.TunDiagnostics.numInbounds,
                    com.novavpn.domain.model.TunDiagnostics.socks5Listening,
                    com.novavpn.domain.model.TunDiagnostics.tunReadAttempts,
                    com.novavpn.domain.model.TunDiagnostics.bridgeRunning,
                    com.novavpn.domain.model.TunDiagnostics.bridgePackets,
                    com.novavpn.domain.model.TunDiagnostics.bridgeBytes,
                    com.novavpn.domain.model.TunDiagnostics.bridgeErrors)
            }
        }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /**
     * Atomic teardown — closes TUN fd, stops engine, stops bridge,
     * removes notification and stops the service.
     *
     * Safe to call multiple times (idempotent via [engineStarted] /
     * [bridgeStarted] flags and null-checks).
     */
    private suspend fun atomicTeardown() {
        Timber.tag(TAG).i("ATOMIC_TEARDOWN: engine=%s, bridge=%s",
            engineStarted, bridgeStarted)

        tunHealthJob?.cancel()
        tunHealthJob = null

        if (bridgeStarted) {
            Timber.tag(TAG).i("TEARDOWN: BRIDGE_STOP")
            try { tunnelBridge.stop() } catch (e: Exception) {
                Timber.tag(TAG).e(e, "TEARDOWN: BRIDGE_STOP_EXCEPTION: %s", e.message)
            }
            bridgeStarted = false
        }

        if (engineStarted) {
            Timber.tag(TAG).i("TEARDOWN: ENGINE_STOP")
            try { engineManager.activeEngine?.stop() } catch (_: Exception) { }
            engineStarted = false
        }

        Timber.tag(TAG).i("TEARDOWN: TUN_CLOSE")
        try { tunInterface?.close() } catch (_: Exception) { }
        tunInterface = null
        currentConfig = null
        com.novavpn.domain.model.TunDiagnostics.reset()

        stopForeground(STOP_FOREGROUND_REMOVE)
        Timber.tag(TAG).i("TEARDOWN: COMPLETE → state=Disconnected")
        connectUseCase.updateState(VpnState.Disconnected)
        stopSelf()
    }

    /**
     * Direct teardown used by [stopVpn] (fallback) and [onRevoke].
     * Prefer [atomicTeardown] for cancelled-in-flight cleanup.
     */
    private suspend fun stopVpnInternal() {
        if (connectUseCase.connectionState.value == VpnState.Disconnected) {
            Timber.tag(TAG).d("LIFECYCLE: DISCONNECT_SKIP — already disconnected")
            return
        }
        Timber.tag(TAG).i("LIFECYCLE: DISCONNECTING")
        connectUseCase.updateState(VpnState.Disconnecting)
        tunHealthJob?.cancel()
        tunHealthJob = null
        Timber.tag(TAG).i("LIFECYCLE: ENGINE_STOP")
        try { engineManager.activeEngine?.stop() } catch (_: Exception) { }
        engineStarted = false
        Timber.tag(TAG).i("LIFECYCLE: BRIDGE_STOP")
        try { tunnelBridge.stop() } catch (e: Exception) {
            Timber.tag(TAG).e(e, "BRIDGE_STOP_EXCEPTION: %s", e.message)
        }
        bridgeStarted = false
        Timber.tag(TAG).i("LIFECYCLE: TUN_CLOSE")
        try { tunInterface?.close() } catch (_: Exception) { }
        tunInterface = null; currentConfig = null
        com.novavpn.domain.model.TunDiagnostics.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Timber.tag(TAG).i("LIFECYCLE: DISCONNECT_COMPLETE → state=Disconnected")
        connectUseCase.updateState(VpnState.Disconnected)
        stopSelf()
    }

    // ------------------------------------------------------------------
    // TUN interface
    // ------------------------------------------------------------------

    private fun buildTun(): ParcelFileDescriptor? = try {
        Builder().apply {
            setSession(NovaConfig.VPN_SESSION_NAME); setMtu(1500)
            addAddress("10.0.0.2", 32)
            addDnsServer("8.8.8.8"); addDnsServer("1.1.1.1")
            addRoute("0.0.0.0", 0); setBlocking(true)
        }.establish().also { tun ->
            if (tun != null) {
                val fd = tun.fd
                val fdValid = fd >= 0
                val fileDesc = tun.fileDescriptor
                Timber.tag(TAG).i("TUN BUILT: fd=%d, fdValid=%s, fileDesc=%s, mtu=1500, " +
                    "addr=10.0.0.2/32, dns=[8.8.8.8,1.1.1.1], routes=[0.0.0.0/0], " +
                    "blocking=true, session=%s",
                    fd, fdValid, if (fileDesc != null) "valid" else "null",
                    NovaConfig.VPN_SESSION_NAME)
            } else {
                Timber.tag(TAG).e("TUN establish() returned null!")
            }
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "TUN establish() threw exception")
        null
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL_ID, getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW))
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NovaVPN").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi).setOngoing(text == "Connected")
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NovaConfig.NOTIFICATION_ID, createNotification(text))
    }
}
