package com.novavpn.domain.model

/**
 * Diagnostics snapshot for the TUN bridge (hev-socks5-tunnel).
 *
 * The bridge process runs the official upstream binary, which manages
 * its own TUN fd internally. We do not track fd-level details here;
 * we track process lifecycle and top-level traffic indicators.
 */
data class TunDiagnostics(
    /** Whether the bridge process is alive. */
    val bridgeAlive: Boolean = false,

    /** Bridge process PID (or -1 if not running). */
    val bridgePid: Int = -1,

    /** Exit code from last nativeWaitFor (-2=timeout, -1=ECHILD, 0-127=exit, 128+=signal). */
    val bridgeExitCode: Int = -1,

    /** Bridge process exit/reap message. */
    val bridgeExitMessage: String = "Bridge not started",

    /** The TUN interface name (e.g. "tun0"). */
    val tunName: String = "",

    /** Total packets read from TUN since last diagnostics poll. */
    val tunReads: Long = 0L,

    /** SOCKS5 proxy address. */
    val socksHost: String = "127.0.0.1",

    /** SOCKS5 proxy port. */
    val socksPort: Int = 10808,

    /** Path to the hev-socks5-tunnel binary. */
    val bridgePath: String = ""
) {
    companion object {
        /** Xray/core engine PID for hard-kill fallback in NovaVpnService. */
        @Volatile
        var xrayPid: Int = -1
    }
}
