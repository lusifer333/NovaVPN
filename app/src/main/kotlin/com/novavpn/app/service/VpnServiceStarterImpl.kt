package com.novavpn.app.service

import android.content.Context
import android.content.Intent
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.usecase.connection.VpnServiceStarter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnServiceStarterImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VpnServiceStarter {

    override fun startVpn(server: ServerConfig) {
        val intent = Intent(context, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_START
            putExtra(NovaVpnService.EXTRA_CONFIG_ID, server.id)
        }
        context.startForegroundService(intent)
    }

    override fun stopVpn() {
        val intent = Intent(context, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_STOP
        }
        context.startForegroundService(intent)
    }
}