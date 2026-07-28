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

    private var currentConfig: ServerConfig? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null

    companion object {
        const val ACTION_START = "com.novavpn.action.START_VPN"
        const val ACTION_STOP = "com.novavpn.action.STOP_VPN"
        const val EXTRA_CONFIG = "extra_server_config"
        private const val NOTIFICATION_CHANNEL_ID = "novavpn_vpn"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NovaConfig.NOTIFICATION_ID, createNotification("Starting…"))
        connectUseCase.updateState(ConnectionState.Disconnected)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn(intent.getParcelableExtra(EXTRA_CONFIG))
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
                Timber.e(e, "VPN failed"); connectUseCase.updateState(ConnectionState.Error)
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
            connectUseCase.updateState(ConnectionState.Error)
            updateNotification("No server"); return
        }

        val tun = buildTun() ?: run {
            connectUseCase.updateState(ConnectionState.Error)
            updateNotification("TUN failed"); return
        }
        tunInterface = tun

        val engine = engineManager.activeEngine ?: run {
            connectUseCase.updateState(ConnectionState.Error)
            updateNotification("No engine"); return
        }

        val ctx = object : EngineContext {
            override val isVpnPermissionGranted = true
            override val tunFileDescriptor = tun.fd
            override val dnsServers = listOf("8.8.8.8", "1.1.1.1")
            override val routes = listOf("0.0.0.0/0")
        }

        engine.initialize(ctx).onFailure {
            connectUseCase.updateState(ConnectionState.Error); updateNotification("Init failed"); return
        }
        engine.start(cfg).onFailure {
            connectUseCase.updateState(ConnectionState.Error); updateNotification("Start failed"); return
        }

        val running = try {
            withTimeout(15_000L) {
                engine.state.first { it == EngineRuntimeState.Running || it == EngineRuntimeState.Crashed }
            }
        } catch (_: TimeoutCancellationException) {
            engine.stop(); connectUseCase.updateState(ConnectionState.Error)
            updateNotification("Timeout"); return
        }
        if (running == EngineRuntimeState.Crashed) {
            connectUseCase.updateState(ConnectionState.Error); updateNotification("Crashed"); return
        }

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
        }.establish()
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
