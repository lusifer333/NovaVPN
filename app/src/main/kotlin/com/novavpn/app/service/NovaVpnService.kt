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
        currentConfig = config
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            try { connect(config) } catch (e: Exception) {
                Timber.e(e, "VPN failed")
                connectUseCase.updateState(ConnectionState.Error, e.message ?: "VPN failed")
                updateNotification("Connection failed")
            }
        }
    }

    fun stopVpn() {
        connectionJob?.cancel()
        serviceScope.launch { stopVpnInternal() }
    }

    fun reconnect() {
        val config = currentConfig ?: return
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            stopVpnInternal(); connect(config)
        }
    }

    private suspend fun connect(config: ServerConfig?) {
        connectUseCase.updateState(ConnectionState.Connecting)
        updateNotification("Connecting…")

        val cfg = config ?: run {
            connectUseCase.updateState(ConnectionState.Error, "No server config provided")
            updateNotification("No server"); return
        }

        Timber.tag(TAG).d("Connecting to %s (%s:%d, %s)", cfg.name, cfg.address, cfg.port, cfg.protocol)

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

        Timber.tag(TAG).d("Initializing engine...")
        engine.initialize(ctx).onFailure { error ->
            val msg = "Engine init failed: ${error.message}"
            Timber.tag(TAG).e(msg)
            connectUseCase.updateState(ConnectionState.Error, msg); updateNotification("Init failed"); return
        }
        Timber.tag(TAG).d("Engine initialized, starting...")
        engine.start(cfg).onFailure { error ->
            val msg = "Engine start failed: ${error.message}"
            Timber.tag(TAG).e(msg)
            connectUseCase.updateState(ConnectionState.Error, msg); updateNotification("Start failed"); return
        }

        Timber.tag(TAG).d("Waiting for engine Running state...")
        val running = try {
            withTimeout(30_000L) {
                engine.state.first { it == EngineRuntimeState.Running || it == EngineRuntimeState.Crashed }
            }
        } catch (_: TimeoutCancellationException) {
            engine.stop()
            val msg = "Engine start timed out after 30s"
            connectUseCase.updateState(ConnectionState.Error, msg); updateNotification("Timeout"); return
        }
        if (running == EngineRuntimeState.Crashed) {
            val msg = "Engine process crashed during startup"
            connectUseCase.updateState(ConnectionState.Error, msg); updateNotification("Crashed"); return
        }

        Timber.tag(TAG).i("VPN CONNECTED to %s (%s:%d) via %s",
            cfg.name, cfg.address, cfg.port, engine.type.displayName)
        connectUseCase.updateState(ConnectionState.Connected)
        updateNotification("Connected")

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
        if (connectUseCase.connectionState.value == ConnectionState.Disconnected) return
        connectUseCase.updateState(ConnectionState.Disconnecting)
        try { engineManager.activeEngine?.stop() } catch (_: Exception) { }
        try { tunInterface?.close() } catch (_: Exception) { }
        tunInterface = null; currentConfig = null
        stopForeground(STOP_FOREGROUND_REMOVE)
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
                Timber.tag(TAG).i("TUN BUILT: fd=%d, mtu=1500, addr=10.0.0.2/32, " +
                    "dns=[8.8.8.8,1.1.1.1], routes=[0.0.0.0/0], blocking=true",
                    tun.fd)
            }
        }
    } catch (e: Exception) { Timber.e(e, "TUN failed"); null }

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
