package com.novavpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.novavpn.app.MainActivity
import com.novavpn.app.R
import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.NovaConfig
import com.novavpn.engine.api.Engine
import com.novavpn.engine.api.EngineManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Android VpnService that bridges the VPN tun interface to the selected engine.
 */
@AndroidEntryPoint
class NovaVpnService : VpnService() {

    @Inject lateinit var engineManager: EngineManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NovaConfig.NOTIFICATION_ID, createNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
    }

    private fun startVpn() {
        serviceScope.launch {
            try {
                updateNotification("Connecting…")

                // Build VPN interface
                val builder = Builder()
                builder.setSession(NovaConfig.VPN_SESSION_NAME)
                builder.setMtu(1500)

                // Add address
                builder.addAddress("10.0.0.2", 32)

                // Add DNS
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("1.1.1.1")

                // Add route (all traffic)
                builder.addRoute("0.0.0.0", 0)

                // Set blocking mode
                builder.setBlocking(true)

                // Establish tun interface
                tunInterface = builder.establish()
                if (tunInterface == null) {
                    Timber.e("Failed to establish VPN interface")
                    updateNotification("Failed to start VPN")
                    stopSelf()
                    return@launch
                }

                Timber.i("VPN interface established")

                // Start engine with the tun fd
                val engine = engineManager.activeEngine
                if (engine != null) {
                    val engineContext = object : com.novavpn.engine.api.EngineContext {
                        override val isVpnPermissionGranted: Boolean = true
                        override val tunFileDescriptor: Int = tunInterface!!.fd
                        override val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1")
                        override val routes: List<String> = listOf("0.0.0.0/0")
                    }

                    engine.initialize(engineContext)

                    // Wait for engine to be running
                    val state = engine.state.first { it == EngineRuntimeState.Running }
                    if (state == EngineRuntimeState.Running) {
                        updateNotification("Connected")
                        Timber.i("VPN engine started successfully")
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Failed to start VPN")
                updateNotification("Connection error")
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        serviceScope.launch {
            try {
                engineManager.activeEngine?.stop()
            } catch (e: Exception) {
                Timber.e(e, "Error stopping engine")
            }

            tunInterface?.close()
            tunInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Timber.i("VPN stopped")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NovaVPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NovaConfig.NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START = "com.novavpn.action.START_VPN"
        const val ACTION_STOP = "com.novavpn.action.STOP_VPN"
        private const val NOTIFICATION_CHANNEL_ID = "novavpn_vpn"
    }
}
