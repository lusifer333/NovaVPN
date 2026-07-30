package com.novavpn.engine.api

import com.novavpn.domain.model.TunDiagnostics

/**
 * Low-level bridge to hev-socks5-tunnel library.
 *
 * The bridge manages the lifecycle of the in-process hev-socks5-tunnel
 * library which forwards TUN traffic to a SOCKS5 proxy.
 *
 * Architecture:
 *   1. VpnService creates the TUN interface via Builder.establish()
 *   2. The TUN fd is passed directly to the bridge start()
 *   3. A YAML config file is written for the upstream library
 *   4. The library opens a background thread: hev_socks5_tunnel_main_from_file(config, fd)
 *   5. The library uses the existing fd for all TUN I/O without owning it
 *   6. The bridge reports diagnostics (running state + traffic stats)
 */
interface TunnelBridge {

    /** Whether the tunnel thread is currently running. */
    val isRunning: Boolean

    /** Get current diagnostics snapshot. */
    val diagnostics: TunDiagnostics

    /**
     * Start the tunnel bridge in-process.
     *
     * @param tunFd        TUN fd from VpnService.Builder.establish()
     * @param socksHost    SOCKS5 proxy host (127.0.0.1 for local Xray)
     * @param socksPort    SOCKS5 proxy port (10808)
     * @return Result.success when the tunnel thread is confirmed running
     */
    suspend fun start(
        tunFd: Int,
        socksHost: String,
        socksPort: Int,
    ): Result<Unit>

    /** Stop the tunnel gracefully via hev_socks5_tunnel_quit(). */
    suspend fun stop(): Result<Unit>

    /** Check tunnel thread state. */
    suspend fun checkHealth(): Boolean
}
