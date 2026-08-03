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
import com.novavpn.domain.model.TunDiagnostics
import com.novavpn.domain.model.VpnState
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.engine.api.EngineContext
import com.novavpn.engine.api.EngineManager
import com.novavpn.engine.xray.XrayEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.TimeUnit
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
    @Inject lateinit var settingsRepository: com.novavpn.domain.repository.SettingsRepository
    @Inject lateinit var subscriptionRepository: com.novavpn.domain.repository.SubscriptionRepository
    @Inject lateinit var tunnelBridge: NativeTunnelBridge

    private var currentConfig: ServerConfig? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunName: String = ""

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var tunHealthJob: Job? = null

    /** Guards the entire connect / disconnect flow — one at a time. */
    private val connectMutex = Mutex()

    /** Connection timeout — if the entire connect() exceeds this, teardown triggers. */
    private val connectionTimeoutMs = 60_000L

    /** Tracks whether engine / bridge have been started (for precise teardown). */
    private var engineStarted = false
    private var bridgeStarted = false

    /** True while we're legitimately cancelling an old session to switch servers
     *  (re-connect / auto switch) — suppresses the spurious "Connection
     *  cancelled" error during that teardown. */
    @Volatile private var reconnectRequested = false

    /** Honors the "Notifications" settings toggle. When off, the foreground
     *  notification (which Android REQUIRES for a running VPN service) is kept
     *  minimal & silent and status text updates are suppressed. */
    @Volatile private var notificationsEnabled = true

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
        // Honor the "Notifications" toggle: Android forces a foreground
        // notification for the running VPN service, so when the user disabled
        // notifications we still post the mandatory minimal silent one. The
        // setting is read async (DataStore flow) and applied on first emission.
        serviceScope.launch {
            val s = settingsRepository.observe().first()
            notificationsEnabled = s.enableNotifications
        }
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
        Timber.tag(TAG).i("[VpnLifecycle] onRevoke() — OS revoked the VPN connection")
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
        Timber.tag(TAG).i("[VpnLifecycle] Cancel requested by user")
        connectionJob?.cancel()
        connectionJob = null
        // Fallback teardown — runs after the cancelled job releases the mutex
        serviceScope.launch {
            connectMutex.withLock {
                if (connectUseCase.connectionState.value != VpnState.Disconnected) {
                    Timber.tag(TAG).i("[VpnLifecycle] Fallback teardown — no connection job was active")
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
        // Cancel any in-flight connection job FIRST so it releases the mutex
        // (its atomicTeardown runs in the cancelled job's finally block) and a
        // new connection isn't blocked forever waiting on connectMutex. Without
        // this, a second tap while a session holds the mutex in holdConnection()
        // would sit stuck in Connecting forever (P1).
        val prevJob = connectionJob
        if (prevJob != null && prevJob.isActive) {
            Timber.tag(TAG).i("[VpnLifecycle] Cancelling previous connection job before re-connect")
            reconnectRequested = true
            prevJob.cancel()
        } else {
            reconnectRequested = false
        }
        connectionJob = serviceScope.launch {
            connectMutex.withLock {
                try {
                    ensureActive()
                    // Establishment watchdog: TUN → engine → bridge must reach
                    // Connected within connectionTimeoutMs. This must NOT wrap the
                    // whole session: connect() used to block until disconnect, so the
                    // timeout killed every healthy connection at exactly 60s regardless
                    // of traffic (log4: bTxP/bRxP climbing 122→2014 while CONNECTION_TIMEOUT
                    // fired; engine rxBytes/txBytes are 0 on Android 11+ because
                    // /proc/net/dev is EACCES-blocked). Session lifetime is governed
                    // by holdConnection() below — no hard cap.
                    withTimeout(connectionTimeoutMs) {
                        connectEstablish(config)
                    }
                    // ── Post-connect check ──
                    // If connectEstablish() returned without setting Connected (e.g., a
                    // 'return' inside it for engine/bridge failure), teardown immediately.
                    val afterConnect = connectUseCase.connectionState.value
                    if (afterConnect != VpnState.Connected) {
                        Timber.tag(TAG).w("[VpnLifecycle] connect() returned non-Connected (%s) — teardown",
                            afterConnect)
                        atomicTeardown()
                    }
                    // ── Session phase: no hard timeout ──
                    // Blocks until the user stops the VPN or the engine crashes.
                    // Bridge liveness (bTxP/bRxP) is reported by the health monitor.
                    val engine = engineManager.activeEngine
                    if (engine == null || config == null) {
                        Timber.tag(TAG).e("[VpnLifecycle] Missing engine/config after Connected — teardown")
                        atomicTeardown()
                    } else {
                        holdConnection(engine, config)
                    }
                } catch (e: TimeoutCancellationException) {
                    val diag = tunnelBridge.diagnostics
                    Timber.tag(TAG).e("[VpnLifecycle] CONNECTION_TIMEOUT — establishment exceeded %d ms (bridge: bTxP=%d, bRxP=%d)",
                        connectionTimeoutMs, diag.tunWrites, diag.tunReads)
                    connectUseCase.updateState(VpnState.Error("Connection timed out after ${connectionTimeoutMs}ms"))
                    updateNotification("Timed out")
                    atomicTeardown()
                } catch (e: CancellationException) {
                    Timber.tag(TAG).w("[VpnLifecycle] CONNECT_CANCELLED: %s", e.message)
                    if (reconnectRequested) {
                        // Legit server switch: tear down the old interface so the
                        // new connection can build a fresh TUN (Android allows only
                        // one active VPN interface) and FREE the mutex for the new
                        // job. Keep the service alive (stopService=false) — stopSelf()
                        // would trigger onDestroy → serviceScope.cancel(), killing
                        // the new connection job. Don't emit a spurious error.
                        //
                        // MUST run in NonCancellable: this catch fires because the
                        // coroutine was cancelled, so every suspend call here
                        // (tunnelBridge.stop / engine.stop) would otherwise throw
                        // CancellationException instantly, the bridge would never
                        // actually stop, and the NEXT connect hits "Tunnel already
                        // running" (proved from device log). NonCancellable lets the
                        // teardown run to completion.
                        try {
                            withContext(NonCancellable) {
                                atomicTeardown(stopService = false)
                            }
                        } catch (te: Exception) {
                            Timber.tag(TAG).w(te, "[VpnLifecycle] teardown during reconnect failed: %s", te.message)
                        }
                    } else {
                        connectUseCase.updateState(VpnState.Error("Connection cancelled"))
                        updateNotification("Cancelled")
                    }
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "[VpnLifecycle] CONNECT_FAILED: %s", e.message)
                    connectUseCase.updateState(VpnState.Error(e.message ?: "VPN failed"))
                    updateNotification("Connection failed")
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
    private suspend fun connectEstablish(config: ServerConfig?) {
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

        // ── Discover TUN interface name via TUNGETIFF ioctl ──
        tunName = try {
            NativeBridgeRunner.nativeGetTunName(tun.fd)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "TUNGETIFF failed for fd=%d", tun.fd)
            connectUseCase.updateState(VpnState.Error("TUN name discovery failed: ${e.message}"))
            updateNotification("TUN failed"); return
        }
        Timber.tag(TAG).i("TUN established: fd=%d, name=%s", tun.fd, tunName)

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
            override val tunName = this@NovaVpnService.tunName
            override val dnsServers = listOf("8.8.8.8", "1.1.1.1")
            override val routes = listOf("0.0.0.0/0")
        }
        Timber.tag(TAG).i("EngineContext created: tunFd=%d, tunName=%s, dns=%s, routes=%s",
            ctx.tunFileDescriptor, ctx.tunName, ctx.dnsServers, ctx.routes)

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
        // Apply user toggles that shape the generated config (e.g. QUIC block)
        val appSettings = settingsRepository.get()
        (engine as? XrayEngine)?.setBlockQuic(appSettings.enableBlockQuic)
        (engine as? XrayEngine)?.setTlsFragment(appSettings.enableTlsFragment)
        (engine as? XrayEngine)?.setKeepAlive(appSettings.enableTcpKeepAlive)
        (engine as? XrayEngine)?.setFakeDns(appSettings.enableFakeDns)
        Timber.tag(TAG).i(
            "LIFECYCLE: ENGINE_START blockQuic=%b fragmentTls=%b keepAlive=%b fakeDns=%b",
            appSettings.enableBlockQuic, appSettings.enableTlsFragment,
            appSettings.enableTcpKeepAlive, appSettings.enableFakeDns
        )

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
            Timber.tag(TAG).e("[VpnLifecycle] TIMEOUT → engine not running after 30s")
            connectUseCase.updateState(VpnState.Error("Engine start timed out"))
            updateNotification("Engine timed out"); return
        }
        if (running == EngineRuntimeState.Crashed) {
            Timber.tag(TAG).e("[VpnLifecycle] ENGINE_CRASHED → during startup")
            connectUseCase.updateState(VpnState.Error("Engine crashed during startup"))
            updateNotification("Engine crashed"); return
        }

        // ── CHECK CANCELLATION before bridge start ──
        currentCoroutineContext().ensureActive()
        Timber.tag(TAG).i("LIFECYCLE: BRIDGE_STARTING (in-process library, fd=%d)", tun.fd)

        // Start the in-process hev-socks5-tunnel library with the TUN fd
        tunnelBridge.start(
            tunFd = tun.fd,
            socksHost = "127.0.0.1",
            socksPort = 10808,
        ).onFailure { error ->
            Timber.tag(TAG).e("LIFECYCLE: BRIDGE_START_FAILED → %s", error.message)
            connectUseCase.updateState(VpnState.Error("Bridge failed: ${error.message}"))
            updateNotification("Bridge failed")
            engine.stop(); engineStarted = false
            return
        }
        if (!tunnelBridge.isRunning) {
            Timber.tag(TAG).e("LIFECYCLE: BRIDGE_NOT_RUNNING — aborting")
            connectUseCase.updateState(VpnState.Error("Bridge not running after start"))
            updateNotification("Bridge failed")
            engine.stop(); engineStarted = false
            return
        }
        bridgeStarted = true
        Timber.tag(TAG).i("LIFECYCLE: BRIDGE_RUNNING (tunFd=%d, running=%s)",
            tun.fd, tunnelBridge.isRunning)

        // ── ALL SYSTEMS GO → Connected (single source of truth) ──
        Timber.tag(TAG).i("LIFECYCLE: ENGINE_RUNNING → state=Connected")
        connectUseCase.updateState(VpnState.Connected)
        updateNotification("Connected")
    }

    /**
     * Session phase: block until the engine crashes or the coroutine is
     * cancelled (user stop).
     *
     * There is intentionally NO timeout here. The old flat 60s cap wrapped
     * [connectEstablish]'s session wait, so it killed every healthy connection
     * at exactly 60s — engine rxBytes/txBytes stay 0 on Android 11+ because
     * /proc/net/dev is EACCES-blocked, while the bridge counters (bTxP/bRxP,
     * from nativeGetTunnelStats) keep climbing. Bridge liveness is reported by
     * the health monitor instead of being used to kill the session (an idle
     * VPN is a legitimate state).
     *
     * No try-catch here: CancellationException propagates naturally up to
     * startVpnInternal() which handles it with proper teardown.
     */
    private suspend fun holdConnection(engine: com.novavpn.engine.api.Engine, config: ServerConfig) {
        // ── LIVE settings re-apply (request: settings toggles take effect
        // immediately, no app restart required) ──
        // Watch the config-shaping toggles (Block QUIC / TLS Fragment /
        // TCP Keep-Alive / FakeDNS). When any changes while the VPN is running,
        // reconnect via the SAME server-switch mechanism so the new setting is
        // applied to a fresh config/engine immediately. coroutineScope keeps the
        // watcher exactly as long as this session; cancelling the connection job
        // cancels the watcher too.
        //
        // DEBOUNCE + BASELINE (v0.16.29): a raw "reconnect on every emission"
        // watch made rapid off/on toggling hammer the VPN with several overlapping
        // teardown→rebuild cycles (and a pointless one if the user flipped back to
        // the original value). That compounding on an unstable network (wifi→data
        // or 3G↔4G handoffs) is exactly when the VPN "نرind". So:
        //  - baseline = settings captured when THIS session started.
        //  - A reconnect only fires after the settings have been QUIET for ~300ms
        //    (bursts collapse to one reconnect).
        //  - The session's own immutable `config` arg is used, never the global
        //    `currentConfig` (which atomicTeardown nulls).
        //    If the settings return to the baseline before the quiet window
        //    elapses, the pending reconnect is cancelled — no pointless rebuild.
        val baseline = settingsRepository.get()
        var reconnectJob: Job? = null
        coroutineScope {
            launch {
                settingsRepository.observe().collect { s ->
                    val differsFromBaseline = s.enableBlockQuic != baseline.enableBlockQuic ||
                        s.enableTlsFragment != baseline.enableTlsFragment ||
                        s.enableTcpKeepAlive != baseline.enableTcpKeepAlive ||
                        s.enableFakeDns != baseline.enableFakeDns
                    reconnectJob?.cancel()
                    reconnectJob = null
                    if (differsFromBaseline &&
                        connectUseCase.connectionState.value == VpnState.Connected) {
                        reconnectJob = launch {
                            delay(300)   // debounce: collapse a burst of toggles
                            if (connectUseCase.connectionState.value == VpnState.Connected) {
                                Timber.tag(TAG).i(
                                    "[VpnLifecycle] Connection-shaping setting changed (stable 300ms) → reconnecting to apply (blockQuic=%b tls=%b keepAlive=%b fakeDns=%b)",
                                    s.enableBlockQuic, s.enableTlsFragment, s.enableTcpKeepAlive, s.enableFakeDns
                                )
                                connectUseCase.updateState(VpnState.Connecting)
                                startVpnInternal(config)
                            }
                        }
                    }
                }
            }

            // ── TUN health monitor (runs as long as the connection is alive) ──
            startTunHealthMonitor(engine)

            engine.state.collect { state ->
                if (state == EngineRuntimeState.Crashed
                    && connectUseCase.connectionState.value == VpnState.Connected) {
                    connectUseCase.updateState(VpnState.Error("Engine crashed — reconnecting"))
                    updateNotification("Reconnecting…")
                    engine.restart(config).onSuccess {
                        connectUseCase.updateState(VpnState.Connected)
                        updateNotification("Connected")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Karing-style auto-connect urltest loop
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Health monitoring
    // ------------------------------------------------------------------

    @Volatile
    private var lastTunnelLogSize = -1L
    private var lastXrayErrLogSize = -1L
    private var lastXrayAccLogSize = -1L

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
                        sock.close()
                    } catch (_: Exception) { }
                }

                // Bridge diagnostics (official binary — no fd details)
                val diag = tunnelBridge.diagnostics
                val bridgeAlive = diag.bridgeAlive

                Timber.tag(TAG).i("DIAG[%d]: tunFd=%d, fdAlive=%s, engine=%s, " +
                    "tunName=%s, bridge=%s, bPid=%d, bExit=%d, " +
                    "rxBytes=%d, txBytes=%d, bTxP=%d, bRxP=%d, kernel=%s",
                    counter, fd, tunFdValid, engineState,
                    diag.tunName, bridgeAlive, diag.bridgePid, diag.bridgeExitCode,
                    rx, tx, diag.tunWrites, diag.tunReads, readTunKernelStats())

                // Mirror log tails into logcat (no adb needed): upstream tunnel.log
                // carries [INSTRUMENT] + library debug lines; Xray access/error logs
                // (debug level, file-backed) show per-connection routing/outbound state.
                val tunnelLog = java.io.File(cacheDir, "novavpn/bridge/tunnel.log")
                lastTunnelLogSize = mirrorLogTail(tunnelLog, "TunnelLog", lastTunnelLogSize, 15)
                val xrayLogDir = java.io.File(filesDir, "novavpn/engines/xray")
                lastXrayErrLogSize = mirrorLogTail(
                    java.io.File(xrayLogDir, "error.log"), "XrayErr", lastXrayErrLogSize, 12)
                lastXrayAccLogSize = mirrorLogTail(
                    java.io.File(xrayLogDir, "access.log"), "XrayAcc", lastXrayAccLogSize, 12)

                // If the bridge died, dump crash log
                if (!bridgeAlive) {
                    val crashLog = diag.bridgeExitMessage
                    android.util.Log.e("TunnelBridge",
                        "BRIDGE_EXIT: pid=${diag.bridgePid}, code=${diag.bridgeExitCode}, msg=$crashLog")
                }
            }
        }
    }

    /**
     * Kernel-side counters for the TUN interface.
     *
     * This is the authoritative discriminator between:
     *  - routing failure: kernel RX stays 0 while browsing  -> packets never reach tun0
     *  - read failure:    kernel RX increases, bridge stats stay 0 -> library not reading the fd
     *
     * Read order: /sys/class/net/&lt;tun&gt;/statistics (sysfs, world-readable) then
     * /proc/net/dev. On failure the exact exception is reported instead of a bare
     * "UNREADABLE" so we can tell SELinux denial apart from a missing interface.
     */
    private fun readTunKernelStats(): String {
        val readStat = { f: java.io.File ->
            try { f.readText().trim() } catch (_: Exception) { "?" }
        }
        try {
            val tun = java.io.File("/sys/class/net").listFiles()
                ?.firstOrNull { it.name.startsWith("tun") }
            if (tun != null) {
                val s = java.io.File(tun, "statistics")
                return "${tun.name}:rx=${readStat(java.io.File(s, "rx_bytes"))}B/" +
                    "${readStat(java.io.File(s, "rx_packets"))}p " +
                    "tx=${readStat(java.io.File(s, "tx_bytes"))}B/" +
                    "${readStat(java.io.File(s, "tx_packets"))}p"
            }
        } catch (e: Exception) {
            return "tun:UNREADABLE:${e.javaClass.simpleName}:${e.message}"
        }
        try {
            val line = java.io.File("/proc/net/dev").readLines()
                .firstOrNull { it.contains(":") && it.substringBefore(":").trim().startsWith("tun") }
            if (line != null) {
                val parts = line.substringAfter(":").trim().split(Regex("\\s+"))
                if (parts.size >= 10) {
                    return "tun0:rx=${parts[0]}B/${parts[1]}p tx=${parts[8]}B/${parts[9]}p"
                }
            }
            return "tun:UNREADABLE:/proc/net/dev: no tun interface line"
        } catch (e: Exception) {
            return "tun:UNREADABLE:${e.javaClass.simpleName}:${e.message}"
        }
    }

    /**
     * Mirrors the tail of a growing log file into logcat when its size changes.
     * Used for the upstream tunnel.log and Xray access/error logs so all
     * diagnostics are visible in-app without adb.
     *
     * @return the current file size (caller stores it as last-seen size).
     */
    private fun mirrorLogTail(file: java.io.File, tag: String, lastSize: Long, lines: Int): Long {
        if (!file.exists()) return lastSize
        val size = try { file.length() } catch (_: Exception) { return lastSize }
        if (size == lastSize) return lastSize
        val tail = try {
            file.readLines().takeLast(lines).joinToString(" | ")
        } catch (_: Exception) {
            "(unreadable)"
        }
        Timber.tag(tag).i("%s (%d B): %s", file.name, size, tail)
        return size
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /**
     * Atomic teardown — closes TUN fd, stops engine (with hard-kill
     * fallback), stops bridge, removes notification and stops the service.
     *
     * ## Hard-kill fallback
     *
     * If the engine's [Engine.stop] does not complete within 3 seconds
     * (indicating the Xray process is frozen or unresponsive), we escalate
     * to a direct OS‑level SIGKILL via `kill -9 <pid>`.  This mirrors the
     * pattern used by Karing / LibVpnCore, where the Go library always
     * uses `os.Process.Kill()` (SIGKILL) for teardown.
     *
     * Safe to call multiple times (idempotent via [engineStarted] /
     * [bridgeStarted] flags and null-checks).
     */
    private suspend fun atomicTeardown(stopService: Boolean = true) {
        Timber.tag(TAG).i("[VpnLifecycle] Atomic teardown: engineStarted=%s, bridgeStarted=%s",
            engineStarted, bridgeStarted)

        tunHealthJob?.cancel()
        tunHealthJob = null

        if (bridgeStarted) {
            Timber.tag(TAG).i("[VpnLifecycle] Stopping bridge...")
            try { tunnelBridge.stop() } catch (e: Exception) {
                Timber.tag(TAG).e(e, "[VpnLifecycle] Bridge stop exception: %s", e.message)
            }
            bridgeStarted = false
        }

        if (engineStarted) {
            Timber.tag(TAG).i("[VpnLifecycle] Stopping Xray Core (3s timeout)...")
            try {
                withTimeout(3_000L) { engineManager.activeEngine?.stop() }
                Timber.tag(TAG).i("[VpnLifecycle] Xray Core stopped cleanly")
            } catch (e: TimeoutCancellationException) {
                Timber.tag(TAG).e("[VpnLifecycle] Xray engine stop TIMED OUT — forcing hard kill")
                hardKillXrayProcess()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "[VpnLifecycle] Engine stop threw: %s", e.message)
                hardKillXrayProcess()
            }
            engineStarted = false
        }

        Timber.tag(TAG).i("[VpnLifecycle] Closing tunFd...")
        try { tunInterface?.close() } catch (_: Exception) { }
        tunInterface = null
        currentConfig = null
        tunName = ""

        stopForeground(STOP_FOREGROUND_REMOVE)
        connectUseCase.updateState(VpnState.Disconnected)
        if (stopService) {
            Timber.tag(TAG).i("[VpnLifecycle] Calling stopSelf()...")
            stopSelf()
        } else {
            Timber.tag(TAG).i("[VpnLifecycle] Keeping service alive for reconnect — not calling stopSelf()")
        }
        Timber.tag(TAG).i("[VpnLifecycle] Atomic teardown complete — service %s", if (stopService) "stopped" else "kept for reconnect")
    }

    /**
     * Last‑resort hard kill of the Xray native process.
     *
     * Uses the PID stored by [TunDiagnostics] during engine start and sends
     * SIGKILL (`kill -9`) directly via the OS.  This is only reached when
     * [Engine.stop] itself times out or fails — normally the engine's own
     * [hardKillProcess][com.novavpn.engine.xray.XrayEngine] handles this.
     *
     * Mirrors the Karing / LibVpnCore pattern where the Go library always
     * kills the core child process with an OS‑level SIGKILL on teardown.
     */
    private fun hardKillXrayProcess() {
        val pid = TunDiagnostics.xrayPid
        if (pid <= 0) {
            Timber.tag(TAG).w("[VpnLifecycle] Hard-kill SKIPPED — no valid PID (%d)", pid)
            return
        }
        Timber.tag(TAG).w("[VpnLifecycle] Hard-killing Xray PID %d with kill -9", pid)
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString()))
            val exited = proc.waitFor(2, TimeUnit.SECONDS)
            if (exited && proc.exitValue() == 0) {
                Timber.tag(TAG).i("[VpnLifecycle] kill -9 %d succeeded", pid)
            } else {
                Timber.tag(TAG).w("[VpnLifecycle] kill -9 %d returned exit=%d",
                    pid, if (exited) proc.exitValue() else -1)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[VpnLifecycle] kill -9 %d failed: %s", pid, e.message)
        }
    }

    /**
     * Direct teardown used by [stopVpn] (fallback) and [onRevoke].
     * Prefer [atomicTeardown] for cancelled-in-flight cleanup.
     */
    private suspend fun stopVpnInternal() {
        if (connectUseCase.connectionState.value == VpnState.Disconnected) {
            Timber.tag(TAG).d("[VpnLifecycle] DISCONNECT_SKIP — already disconnected")
            return
        }
        Timber.tag(TAG).i("[VpnLifecycle] Disconnecting...")
        connectUseCase.updateState(VpnState.Disconnecting)
        tunHealthJob?.cancel()
        tunHealthJob = null
        Timber.tag(TAG).i("[VpnLifecycle] Stopping Xray Core (3s timeout)...")
        try {
            withTimeout(3_000L) { engineManager.activeEngine?.stop() }
            Timber.tag(TAG).i("[VpnLifecycle] Xray Core stopped cleanly")
        } catch (e: TimeoutCancellationException) {
            Timber.tag(TAG).e("[VpnLifecycle] Xray engine stop TIMED OUT — forcing hard kill")
            hardKillXrayProcess()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[VpnLifecycle] Engine stop threw: %s", e.message)
            hardKillXrayProcess()
        }
        engineStarted = false
        Timber.tag(TAG).i("[VpnLifecycle] Stopping bridge...")
        try { tunnelBridge.stop() } catch (e: Exception) {
            Timber.tag(TAG).e(e, "[VpnLifecycle] Bridge stop exception: %s", e.message)
        }
        bridgeStarted = false

        Timber.tag(TAG).i("[VpnLifecycle] Closing tunFd...")
        try { tunInterface?.close() } catch (_: Exception) { }
        tunInterface = null; currentConfig = null; tunName = ""

        stopForeground(STOP_FOREGROUND_REMOVE)
        Timber.tag(TAG).i("[VpnLifecycle] Calling stopSelf()...")
        connectUseCase.updateState(VpnState.Disconnected)
        stopSelf()
        Timber.tag(TAG).i("[VpnLifecycle] Teardown complete — service stopped")
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
            // Exclude our own app from VPN to prevent routing loopback
            addDisallowedApplication(packageName)
            // Force IPv6 into the tunnel — modern cell networks prefer IPv6
            addAddress("2606:4700:4700::1111", 128)
            addRoute("::", 0)
        }.establish().also { tun ->
            if (tun != null) {
                val fd = tun.fd
                val fdValid = fd >= 0
                val fileDesc = tun.fileDescriptor
                Timber.tag(TAG).i("TUN BUILT: fd=%d, fdValid=%s, fileDesc=%s, " +
                    "mtu=1500, addr=10.0.0.2/32 + 2606:4700:4700::1111/128, " +
                    "dns=[8.8.8.8,1.1.1.1], " +
                    "routes=[0.0.0.0/0 + ::/0 (IPv4+IPv6)], " +
                    "blocking=true, session=%s",
                    fd, fdValid,
                    if (fileDesc != null) "valid" else "null",
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
        if (!notificationsEnabled) {
            // Mandatory foreground notification when the user disabled the
            // "Notifications" toggle: minimal & silent header only, never
            // updated with status text.
            return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("NovaVPN")
                .setContentText(getString(R.string.app_name))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN).build()
        }
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NovaVPN").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi).setOngoing(text == "Connected")
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun updateNotification(text: String) {
        if (!notificationsEnabled) return   // user turned notifications off
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NovaConfig.NOTIFICATION_ID, createNotification(text))
    }
}
