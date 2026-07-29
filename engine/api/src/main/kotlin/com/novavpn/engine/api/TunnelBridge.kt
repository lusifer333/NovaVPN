package com.novavpn.engine.api

/**
 * Bridge between Android TUN interface and a SOCKS5 proxy.
 *
 * Reads raw IP packets from the TUN file descriptor and forwards
 * TCP/UDP connections through a SOCKS5 proxy (typically Xray's
 * SOCKS5 inbound on 127.0.0.1:10808).
 *
 * Implementations use native binaries (not Kotlin TCP/IP stack):
 * - hev-socks5-tunnel (preferred)
 * - Custom Go-based fd-forwarder (fallback)
 */
interface TunnelBridge {
    /** Unique bridge type identifier. */
    val type: String

    /** Current bridge status. */
    val status: BridgeStatus

    /**
     * Start forwarding traffic between TUN fd and SOCKS5.
     * @param tunFd TUN file descriptor from VpnService.Builder.establish()
     * @param socksHost SOCKS5 proxy host (e.g. "127.0.0.1")
     * @param socksPort SOCKS5 proxy port (e.g. 10808)
     */
    suspend fun start(tunFd: Int, socksHost: String = "127.0.0.1", socksPort: Int = 10808)

    /** Stop the bridge and release all resources. */
    suspend fun stop()

    /** Current diagnostic snapshot. */
    fun diagnostics(): BridgeDiagnostics
}

enum class BridgeStatus {
    Idle,
    Starting,
    Running,
    Stopping,
    Failed
}

data class BridgeDiagnostics(
    val status: BridgeStatus = BridgeStatus.Idle,
    val forwardedPackets: Long = 0,
    val forwardedBytes: Long = 0,
    val forwardErrors: Long = 0,
    val connectAttempts: Long = 0,
    val connectSuccess: Long = 0,
    val connectFailed: Long = 0,
    val processAlive: Boolean = false,
    val errorMessage: String = ""
)
