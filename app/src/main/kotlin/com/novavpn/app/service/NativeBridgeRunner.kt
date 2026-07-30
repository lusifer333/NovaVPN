package com.novavpn.app.service

import timber.log.Timber

/**
 * JNI bridge to NativeProcess.c for process lifecycle and tunnel management.
 *
 * All native methods map 1:1 to C functions in app/src/main/cpp/NativeProcess.c.
 *
 * Responsibilities:
 *   - Launch engine binaries via fork+exec (Xray only, no fd passing)
 *   - Monitor process state (isAlive / waitFor / kill)
 *   - Discover the TUN interface name from its file descriptor
 *   - Start/stop hev-socks5-tunnel via in-process library API
 *   - Query tunnel traffic statistics
 */
object NativeBridgeRunner {

    init {
        System.loadLibrary("nativebridge")
    }

    // ═══════════════════════════════════════════
    // Process lifecycle (fork+exec — used by Xray)
    // ═══════════════════════════════════════════

    /**
     * Fork+exec an engine binary.
     *
     * @param binaryPath  Absolute path to the executable
     * @param args        CLI arguments (excluding argv[0] which is binaryPath)
     * @param logFilePath Path to capture stderr (null = discard stderr)
     * @return Child PID on success
     * @throws RuntimeException on fork/exec failure
     */
    @JvmStatic
    external fun nativeForkExec(
        binaryPath: String,
        args: Array<String>,
        logFilePath: String? = null
    ): Int

    /**
     * Non-blocking check whether the child process is still running.
     * Uses waitpid(WNOHANG) internally.
     */
    @JvmStatic
    external fun nativeIsAlive(pid: Int): Boolean

    /**
     * Block until the child exits or timeout expires.
     *
     * @return exit code (0-127), 128+signal on signal death,
     *         -1 if already reaped (ECHILD), -2 on timeout
     */
    @JvmStatic
    external fun nativeWaitFor(pid: Int, timeoutMs: Int): Int

    /**
     * Terminate a child process (SIGTERM + SIGKILL fallback).
     */
    @JvmStatic
    external fun nativeKillProcess(pid: Int)

    /**
     * Retrieve the TUN interface name (e.g. "tun0") from the fd
     * returned by VpnService.Builder.establish().
     *
     * Uses TUNGETIFF ioctl internally.
     */
    @JvmStatic
    external fun nativeGetTunName(tunFd: Int): String

    // ═══════════════════════════════════════════
    // In-process tunnel library API (hev-socks5-tunnel)
    // ═══════════════════════════════════════════

    /**
     * Start hev-socks5-tunnel in a background pthread, passing the VpnService
     * TUN fd directly. The library handles all TUN I/O internally.
     *
     * The library API call [hev_socks5_tunnel_main_from_file] blocks until
     * [nativeStopTunnel] is called.
     *
     * @param configPath Absolute path to the YAML config file
     * @param tunFd      TUN fd from VpnService.Builder.establish()
     * @return true if the tunnel thread was started, false if already running
     */
    @JvmStatic
    external fun nativeStartTunnel(configPath: String, tunFd: Int): Boolean

    /**
     * Signal the tunnel to stop. Calls hev_socks5_tunnel_quit().
     * The tunnel thread exits asynchronously.
     */
    @JvmStatic
    external fun nativeStopTunnel()

    /**
     * Check whether the tunnel thread is currently running.
     */
    @JvmStatic
    external fun nativeGetTunnelRunning(): Boolean

    /**
     * Retrieve tunnel traffic statistics.
     *
     * @return LongArray of [txPackets, txBytes, rxPackets, rxBytes],
     *         or null if native call failed
     */
    @JvmStatic
    external fun nativeGetTunnelStats(): LongArray?

    /** Human-readable diagnostics for a forked process (Xray). */
    fun diagnostics(pid: Int): Triple<Boolean, Int, String> {
        if (pid <= 0) return Triple(false, -1, "Bridge not started (pid <= 0)")

        return try {
            val alive = nativeIsAlive(pid)
            if (alive) {
                Triple(true, -1, "Bridge process $pid is running")
            } else {
                val exitStatus = nativeWaitFor(pid, 0)
                Triple(false, exitStatus, when (exitStatus) {
                    -1 -> "Bridge process $pid already reaped (ECHILD)"
                    else -> "Bridge process $pid exited with status $exitStatus"
                })
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "diagnostics(%d) failed", pid)
            Triple(false, -1, "Bridge diagnostics error: ${e.message}")
        }
    }

    private const val TAG = "NativeBridge"
}
