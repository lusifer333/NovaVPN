package com.novavpn.domain.usecase.connection

import com.novavpn.domain.model.ServerConfig

/**
 * Abstraction for starting/stopping the Android VpnService.
 * Implemented in the app module to avoid UI modules depending on Android Service.
 */
interface VpnServiceStarter {
    fun startVpn(server: ServerConfig)
    fun stopVpn()
}