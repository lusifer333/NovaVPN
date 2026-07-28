package com.novavpn.domain.usecase.connection

import android.content.Intent
import com.novavpn.domain.model.ServerConfig

/**
 * Abstraction for starting/stopping the Android VpnService.
 * Implemented in the app module to avoid UI modules depending on Android Service.
 */
interface VpnServiceStarter {
    fun startVpn(server: ServerConfig)
    fun stopVpn()

    /**
     * Check if VPN permission has been granted.
     * @return null if already granted, or an Intent to request permission.
     */
    fun requestVpnPermissionIntent(): Intent?
}