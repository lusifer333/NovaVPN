package com.novavpn.app.service

/**
 * Minimal JNI bridge to fork()+execv() a native binary while preserving a
 * TUN file descriptor in the child process.
 *
 * Android's [java.lang.ProcessBuilder] internally calls forkAndExec() which
 * closes ALL file descriptors >= 3 before exec() — making it impossible to
 * pass the TUN fd (from VpnService.Builder.establish()) to native binaries
 * such as hev-socks5-tunnel.
 *
 * This class bypasses the JVM's process-spawning machinery and invokes
 * fork() / execv() directly via JNI, which respects POSIX FD_CLOEXEC
 * semantics and keeps the TUN fd open in the child.
 */
object NativeBridgeRunner {

    init {
        System.loadLibrary("nativebridge")
    }

    /**
     * Fork a child process, clear FD_CLOEXEC on [tunFd], then exec [binaryPath].
     *
     * @param binaryPath absolute path to the executable
     * @param args       arguments (NOT including the binary path itself)
     * @param tunFd      TUN file descriptor to preserve in the child
     * @return child PID (positive) on success, or a negative errno on failure
     */
    external fun nativeForkExec(
        binaryPath: String,
        args: Array<String>,
        tunFd: Int
    ): Int

    /**
     * Check whether a child process is still alive.
     * Uses kill(pid, 0) — does NOT reap the child.
     * @return 1 = alive, 0 = exited/reaped, -1 = error
     */
    external fun nativeIsAlive(pid: Int): Int

    /**
     * Busy-poll waitpid(WNOHANG) for up to [timeoutMs] ms, then reap the
     * child and return its exit code.
     *
     * @return exit code (0–255), -1 on waitpid error, -2 on timeout
     */
    external fun nativeWaitFor(pid: Int, timeoutMs: Int): Int

    /**
     * Send SIGTERM then (after 200 ms) SIGKILL to [pid].
     */
    external fun nativeKillProcess(pid: Int)
}
