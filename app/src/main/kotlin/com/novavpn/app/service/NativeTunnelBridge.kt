package com.novavpn.app.service

import android.content.Context
import com.novavpn.domain.model.TunDiagnostics
import com.novavpn.engine.api.TunnelBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TunnelBridge implementation using the in-process hev-socks5-tunnel v2.16.0 library.
 *
 * Architecture:
 *   1. VpnService creates TUN → we receive its fd
 *   2. We write an upstream-compatible YAML config file
 *   3. We call nativeStartTunnel(configPath, tunFd), which launches the
 *      upstream library in a background pthread via hev_socks5_tunnel_main_from_file()
 *   4. The library uses the TUN fd directly (no /dev/net/tun, no TUNSETIFF)
 *   5. We monitor via nativeGetTunnelRunning() and nativeGetTunnelStats()
 *   6. On stop, we call nativeStopTunnel() → hev_socks5_tunnel_quit()
 *
 * Important: This bridge NEVER closes the TUN fd. The fd is owned by
 * NovaVpnService (ParcelFileDescriptor) and is closed there.
 */
@Singleton
class NativeTunnelBridge @Inject constructor(
    @ApplicationContext private val context: Context
) : TunnelBridge {

    private var configFilePath: String = ""
    private var socksHost: String = "127.0.0.1"
    private var socksPort: Int = 10808
    private var tunFd: Int = -1

    /** Directory for runtime bridge config files (app cache). */
    private val configDir: File by lazy {
        File(context.cacheDir, "novavpn/bridge").also { it.mkdirs() }
    }

    override val isRunning: Boolean
        get() = try {
            NativeBridgeRunner.nativeGetTunnelRunning()
        } catch (_: Exception) {
            false
        }

    override val diagnostics: TunDiagnostics
        get() {
            val running = isRunning
            val stats = try {
                NativeBridgeRunner.nativeGetTunnelStats()
            } catch (_: Exception) {
                null
            }
            val txPackets = stats?.getOrNull(0) ?: 0L
            val txBytes = stats?.getOrNull(1) ?: 0L
            val rxPackets = stats?.getOrNull(2) ?: 0L
            val rxBytes = stats?.getOrNull(3) ?: 0L
            return TunDiagnostics(
                bridgeAlive = running,
                bridgePid = -1,
                bridgeExitCode = -1,
                bridgeExitMessage = if (running) {
                    "In-process tunnel running (tx_p=$txPackets tx_b=$txBytes rx_p=$rxPackets rx_b=$rxBytes)"
                } else {
                    "Tunnel stopped"
                },
                tunName = "",
                tunReads = rxPackets,
                tunWrites = txPackets,
                socksHost = socksHost,
                socksPort = socksPort,
                bridgePath = ""
            )
        }

    override suspend fun start(
        tunFd: Int,
        socksHost: String,
        socksPort: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        Timber.tag(TAG).i("start(tunFd=%d, socks=%s:%d)", tunFd, socksHost, socksPort)

        if (isRunning) {
            val msg = "Tunnel already running"
            Timber.tag(TAG).w(msg)
            return@withContext Result.failure(Exception(msg))
        }

        if (tunFd < 0) {
            val msg = "Invalid TUN fd: $tunFd"
            Timber.tag(TAG).e(msg)
            return@withContext Result.failure(Exception(msg))
        }

        this@NativeTunnelBridge.tunFd = tunFd
        this@NativeTunnelBridge.socksHost = socksHost
        this@NativeTunnelBridge.socksPort = socksPort

        // Write upstream-compatible YAML config (keys verified against v2.16.0 parser)
        val configFile = writeConfig(socksHost, socksPort, tunFd)
        configFilePath = configFile.absolutePath
        Timber.tag(TAG).i("YAML path: %s", configFilePath)
        Timber.tag(TAG).d("Config:\n%s", configFile.readText())

        // Call the JNI bridge: nativeStartTunnel(configPath, tunFd)
        val started = try {
            NativeBridgeRunner.nativeStartTunnel(configFilePath, tunFd)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "nativeStartTunnel threw: %s", e.message)
            return@withContext Result.failure(e)
        }

        Timber.tag(TAG).i("nativeStartTunnel(%s, %d) returned: %s",
            configFilePath, tunFd, started)

        if (!started) {
            val msg = "nativeStartTunnel returned false — tunnel thread not created"
            Timber.tag(TAG).e(msg)
            return@withContext Result.failure(Exception(msg))
        }

        // Verify tunnel is running
        val running = try {
            NativeBridgeRunner.nativeGetTunnelRunning()
        } catch (_: Exception) {
            false
        }
        Timber.tag(TAG).i("Tunnel running state: %s", running)

        if (running) {
            val stats = try {
                NativeBridgeRunner.nativeGetTunnelStats()
            } catch (_: Exception) {
                null
            }
            Timber.tag(TAG).i("Tunnel stats: tx_p=%d tx_b=%d rx_p=%d rx_b=%d",
                stats?.getOrNull(0) ?: -1,
                stats?.getOrNull(1) ?: -1,
                stats?.getOrNull(2) ?: -1,
                stats?.getOrNull(3) ?: -1)
        }

        Result.success(Unit)
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        Timber.tag(TAG).i("stop() called (tunFd=%d)", tunFd)

        try {
            NativeBridgeRunner.nativeStopTunnel()
            Timber.tag(TAG).i("nativeStopTunnel() completed")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "nativeStopTunnel threw: %s", e.message)
        }

        // ── CRITICAL: nativeStopTunnel() is asynchronous. A subsequent
        // reconnect/switch that calls start() before the old tunnel is fully
        // down hits 'if (isRunning) → Tunnel already running' and fails, which
        // surfaces as a red connect button after a server switch. Poll until
        // nativeGetTunnelRunning() reports false (bounded) before returning.
        var stillRunning = false
        var waited = 0
        do {
            stillRunning = try {
                NativeBridgeRunner.nativeGetTunnelRunning()
            } catch (_: Exception) {
                false
            }
            if (stillRunning) {
                runCatching { Thread.sleep(50) }
                waited += 50
            }
        } while (stillRunning && waited < 3_000)
        if (stillRunning) {
            Timber.tag(TAG).w("Tunnel still running after %d ms of polling — continuing anyway", waited)
        } else {
            Timber.tag(TAG).i("Tunnel fully stopped after %d ms wait", waited)
        }

        // Check tunnel state after stop
        val running = try {
            NativeBridgeRunner.nativeGetTunnelRunning()
        } catch (_: Exception) {
            false
        }
        Timber.tag(TAG).i("Tunnel running after stop: %s", running)

        tunFd = -1
        Result.success(Unit)
    }

    override suspend fun checkHealth(): Boolean {
        return try {
            NativeBridgeRunner.nativeGetTunnelRunning()
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    /**
     * Write the upstream v2.16.0 YAML config file.
     *
     * All keys confirmed against hev-config.c parser.
     * See docs at: docs/upstream-yaml-schema.md
     *
     * In fd-passing mode (tunFd >= 0):
     *   - tunnel.name is cosmetic (skipped by tunnel_init when fd >= 0)
     *   - tunnel.ipv4/ipv6 are not used (no TUNSETIFF called)
     *   - tunnel.mtu affects lwIP read buffer size (should match VpnService MTU)
     *   - misc.pid-file is deliberately NOT set (would fork via daemon())
     *   - misc.log-file must be an absolute path on Android ("stderr" → /dev/null)
     */
    private fun writeConfig(socksHost: String, socksPort: Int, tunFd: Int): File {
        configDir.mkdirs()
        val file = File(configDir, "bridge.yml")

        file.writeText(buildString {
            appendLine("tunnel:")
            appendLine("  name: \"\"")
            appendLine("  mtu: 1500")
            appendLine("  multi-queue: false")
            appendLine("  icmp: \"reply\"")
            appendLine()
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: \"$socksHost\"")
            appendLine("  udp: \"udp\"")
            appendLine()
            appendLine("misc:")
            appendLine("  task-stack-size: 86016")
            appendLine("  connect-timeout: 10000")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-file: \"${tunnelLogPath()}\"")
            appendLine("  log-level: \"debug\"")
            appendLine("  limit-nofile: 65535")
        })

        return file
    }

    /**
     * Path for the upstream library's log output.
     * The library uses writev() to this file (dup of fd), not __android_log_print.
     */
    private fun tunnelLogPath(): String {
        return File(configDir, "tunnel.log").absolutePath
    }

    companion object {
        private const val TAG = "NativeTunnelBridge"
    }
}
