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
import com.novavpn.domain.model.ConnectionState
import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.NovaConfig
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.engine.api.EngineContext
import com.novavpn.engine.api.EngineError
import com.novavpn.engine.api.EngineManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class NovaVpnService : VpnService() {

    @Inject lateinit var engineManager: EngineManager
    @Inject lateinit var connectUseCase: ConnectUseCase
    @Inject lateinit var serverRepository: com.novavpn.domain.repository.ServerRepository

    private var currentConfig: ServerConfig? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var tunHealthJob: Job? = null

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
                    val config = serverId?.let { serverRepository.getById(it) }
                    startVpn(config)
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
        connectUseCase.updateState(ConnectionState.Error)
        serviceScope.launch { stopVpnInternal() }
    }

    fun startVpn(config: ServerConfig?) {
        Timber.tag(TAG).i("CONNECT_START: server=%s", config?.name ?: "null")
        currentConfig = config
        // Cancel previous job safely — new job is created after cancellation
        val previousJob = connectionJob
        connectionJob = null
        previousJob?.cancel()
        connectionJob = serviceScope.launch {
            try {
                connect(config)
            } catch (e: CancellationException) {
                Timber.tag(TAG).w("CONNECT_CANCELLED: %s", e.message)
                connectUseCase.updateState(ConnectionState.Error, "Connection cancelled")
                updateNotification("Cancelled")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "CONNECT_FAILED: %s", e.message)
                connectUseCase.updateState(ConnectionState.Error, e.message ?: "VPN failed")
                updateNotification("Connection failed")
            }
        }
    }

    fun stopVpn() {
        Timber.tag(TAG).i("DISCONNECT_START")
        connectionJob?.cancel()
        connectionJob = null
        serviceScope.launch { stopVpnInternal() }
    }

    fun reconnect() {
        val config = currentConfig ?: return
        Timber.tag(TAG).i("RECONNECT_START")
        connectionJob?.cancel()
        connectionJob = null
        connectionJob = serviceScope.launch {
            stopVpnInternal(); connect(config)
        }
    }

    private suspend fun connect(config: ServerConfig?) {
        Timber.tag(TAG).i("LIFECYCLE: CONNECTING → state=Connecting")
        connectUseCase.updateState(ConnectionState.Connecting)
        updateNotification("Connecting…")

        val cfg = config ?: run {
            Timber.tag(TAG).e("LIFECYCLE: FAILED → no config")
            connectUseCase.updateState(ConnectionState.Error, "No server config provided")
            updateNotification("No server"); return
        }

        Timber.tag(TAG).i("LIFECYCLE: CONNECT_STEP — building TUN for %s:%d", cfg.address, cfg.port)

        val tun = buildTun() ?: run {
            connectUseCase.updateState(ConnectionState.Error, "TUN interface failed to establish")
            updateNotification("TUN failed"); return
        }
        tunInterface = tun
        Timber.tag(TAG).i("TUN established: fd=%d, interface=%s",
            tun.fd, NovaConfig.VPN_SESSION_NAME)

        val engine = engineManager.activeEngine ?: run {
            connectUseCase.updateState(ConnectionState.Error, "No engine selected")
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

        Timber.tag(TAG).i("LIFECYCLE: ENGINE_INIT")
        engine.initialize(ctx).onFailure { error ->
            Timber.tag(TAG).e("LIFECYCLE: ENGINE_INIT_FAILED → %s", error.message)
            connectUseCase.updateState(ConnectionState.Error, error.message ?: "Init failed")
            updateNotification("Init failed"); return
        }
        Timber.tag(TAG).i("LIFECYCLE: ENGINE_START")
        engine.start(cfg).onFailure { error ->
            Timber.tag(TAG).e("LIFECYCLE: ENGINE_START_FAILED → %s", error.message)
            connectUseCase.updateState(ConnectionState.Error, error.message ?: "Start failed")
            updateNotification("Start failed"); return
        }

        Timber.tag(TAG).i("LIFECYCLE: WAITING_FOR_RUNNING")
        val running = try {
            withTimeout(30_000L) {
                engine.state.first { it == EngineRuntimeState.Running || it == EngineRuntimeState.Crashed }
            }
        } catch (_: TimeoutCancellationException) {
            engine.stop()
            Timber.tag(TAG).e("LIFECYCLE: TIMEOUT → engine not running after 30s")
            connectUseCase.updateState(ConnectionState.Error, "Engine start timed out"); return
        }
        if (running == EngineRuntimeState.Crashed) {
            Timber.tag(TAG).e("LIFECYCLE: ENGINE_CRASHED")
            connectUseCase.updateState(ConnectionState.Error, "Engine crashed during startup"); return
        }

        Timber.tag(TAG).i("LIFECYCLE: ENGINE_RUNNING → state=Connected")
        connectUseCase.updateState(ConnectionState.Connected)
        updateNotification("Connected")

        // Start TUN health monitor coroutine
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
                if (counter % 2 == 1) { // every other cycle
                    try {
                        val sock = java.net.Socket()
                        sock.connect(java.net.InetSocketAddress("127.0.0.1", 10808), 200)
                        com.novavpn.domain.model.TunDiagnostics.socks5Listening = true
                        sock.close()
                    } catch (_: Exception) {
                        com.novavpn.domain.model.TunDiagnostics.socks5Listening = false
                    }
                }

                Timber.tag(TAG).i("DIAG[%d]: tunFd=%d, fdAlive=%s, engine=%s, " +
                    "rawFd=%d, inheritFd=%d, dupOK=%s, inbound=%s, nInbound=%d, " +
                    "socks5=%s, tunReads=%d",
                    counter, fd, tunFdValid, engineState,
                    com.novavpn.domain.model.TunDiagnostics.rawFd,
                    com.novavpn.domain.model.TunDiagnostics.inheritableFd,
                    com.novavpn.domain.model.TunDiagnostics.dupOK,
                    com.novavpn.domain.model.TunDiagnostics.inboundType,
                    com.novavpn.domain.model.TunDiagnostics.numInbounds,
                    com.novavpn.domain.model.TunDiagnostics.socks5Listening,
                    com.novavpn.domain.model.TunDiagnostics.tunReadAttempts)
            }
        }

        try {
            engine.state.collect { state ->
                if (state == EngineRuntimeState.Crashed && connectUseCase.connectionState.value == ConnectionState.Connected) {
                    connectUseCase.updateState(ConnectionState.Error)
                    updateNotification("Reconnecting…")
                    engine.restart(cfg).onSuccess {
                        connectUseCase.updateState(ConnectionState.Connected)
                        updateNotification("Connected")
                    }
                }
            }
        } catch (_: CancellationException) { }
    }

    private suspend fun stopVpnInternal() {
        if (connectUseCase.connectionState.value == ConnectionState.Disconnected) {
            Timber.tag(TAG).d("LIFECYCLE: DISCONNECT_SKIP — already disconnected")
            return
        }
        Timber.tag(TAG).i("LIFECYCLE: DISCONNECTING")
        connectUseCase.updateState(ConnectionState.Disconnecting)
        tunHealthJob?.cancel()
        tunHealthJob = null
        Timber.tag(TAG).i("LIFECYCLE: ENGINE_STOP")
        try { engineManager.activeEngine?.stop() } catch (_: Exception) { }
        Timber.tag(TAG).i("LIFECYCLE: TUN_CLOSE")
        try { tunInterface?.close() } catch (_: Exception) { }
        tunInterface = null; currentConfig = null
        com.novavpn.domain.model.TunDiagnostics.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Timber.tag(TAG).i("LIFECYCLE: DISCONNECT_COMPLETE → state=Disconnected")
        connectUseCase.updateState(ConnectionState.Disconnected)
        stopSelf()
    }

    private fun buildTun(): ParcelFileDescriptor? = try {
        Builder().apply {
            setSession(NovaConfig.VPN_SESSION_NAME); setMtu(1500)
            addAddress("10.0.0.2", 32)
            addDnsServer("8.8.8.8"); addDnsServer("1.1.1.1")
            addRoute("0.0.0.0", 0); setBlocking(true)
        }.establish().also { tun ->
            if (tun != null) {
                // Validate the TUN fd is usable
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
