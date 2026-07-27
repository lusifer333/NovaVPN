package com.novavpn.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.novavpn.app.service.NovaVpnService
import timber.log.Timber

/**
 * Handles system boot events to auto-start VPN if configured.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.i("Boot completed — checking auto-start configuration")

            val intent = Intent(context, NovaVpnService::class.java).apply {
                action = NovaVpnService.ACTION_START
            }

            // Start VPN service — WorkManager will check if auto-connect is enabled
            // and decide whether to proceed with the connection.
            context.startForegroundService(intent)
        }
    }
}
