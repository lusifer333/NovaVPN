package com.novavpn.domain.model

/**
 * Persistent TUN diagnostic state, stored outside the log buffer.
 * Written by [XrayEngine] during engine start, read by [NovaVpnService]
 * for the periodic DIAG log. This ensures TUN status is always visible
 * even if the log buffer overflows with Xray debug output.
 */
object TunDiagnostics {
    @Volatile
    var rawFd: Int = -1
    @Volatile
    var inheritableFd: Int = -1
    @Volatile
    var dupOK: Boolean = false
    @Volatile
    var inboundType: String = "unknown"
    @Volatile
    var numInbounds: Int = 0
    @Volatile
    var processArgs: String = ""
    @Volatile
    var xrayPid: Int = -1
    @Volatile
    var socks5Listening: Boolean = false
    @Volatile
    var tunReadAttempts: Int = 0
    @Volatile
    var bridgeRunning: Boolean = false
    @Volatile
    var bridgePackets: Long = 0
    @Volatile
    var bridgeBytes: Long = 0
    @Volatile
    var bridgeErrors: Long = 0

    fun storePid(pid: Int) { xrayPid = pid }

    fun reset() {
        rawFd = -1
        inheritableFd = -1
        dupOK = false
        inboundType = "unknown"
        numInbounds = 0
        processArgs = ""
        xrayPid = -1
        socks5Listening = false
        tunReadAttempts = 0
        bridgeRunning = false
        bridgePackets = 0
        bridgeBytes = 0
        bridgeErrors = 0
    }
}
