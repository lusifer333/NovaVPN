package com.novavpn.engine.api

/**
 * Platform-specific context passed to engines on init.
 *
 * Android: contains VpnService tun info, DNS, and routing data.
 */
interface EngineContext {
    val isVpnPermissionGranted: Boolean
    val tunFileDescriptor: Int
    val tunName: String
    val dnsServers: List<String>
    val routes: List<String>
}
