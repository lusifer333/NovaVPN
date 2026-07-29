package com.novavpn.app.service

import android.content.Context
import com.novavpn.engine.api.BridgeDiagnostics
import com.novavpn.engine.api.BridgeStatus
import com.novavpn.engine.api.TunnelBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native tunnel bridge using hev-socks5-tunnel binary.
 *
 * The bridge binary reads from a TUN interface and forwards traffic
 * through a SOCKS5 proxy. It is spawned as a child process and its
 * lifecycle is managed here.
 *
 * Binary location: jniLibs/<abi>/hev-socks5-tunnel (or downloaded
 * via scripts/download-engines.sh)
 *
 * Architecture:
 *   TUN → hev-socks5-tunnel → SOCKS5(127.0.0.1:10808) → Xray → outbound
 */
@Singleton
class NativeTunnelBridge @Inject constructor(
    @ApplicationContext private val context: Context
) : TunnelBridge {

    companion object {
        private const val TAG = "TunnelBridge"
        private const val BINARY_NAME = "hev-socks5-tunnel"
    }

    override val type: String = "hev-socks5-tunnel"

    override var status: BridgeStatus = BridgeStatus.Idle
        private set

    private var bridgeProcess: Process? = null
    private var binaryPath: String = ""
    private var tunFd: Int = -1
    private var socksHost: String = ""
    private var socksPort: Int = 10808

    // Diagnostics counters
    private val diagPackets = AtomicLong(0)
    private val diagBytes = AtomicLong(0)
    private val diagErrors = AtomicLong(0)
    private val diagConnAttempts = AtomicLong(0)
    private val diagConnOk = AtomicLong(0)
    private val diagConnFail = AtomicLong(0)

    override suspend fun start(tunFd: Int, socksHost: String, socksPort: Int) {
        if (status == BridgeStatus.Running) {
            Timber.tag(TAG).w("BRIDGE_START_SKIP: already running")
            return
        }
        this.tunFd = tunFd
        this.socksHost = socksHost
        this.socksPort = socksPort

        status = BridgeStatus.Starting
        Timber.tag(TAG).i("BRIDGE_START: tunFd=%d, socks5=%s:%d", tunFd, socksHost, socksPort)

        try {
            // Find binary in native library path
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val nativeLibDir = "/data/app/${context.packageName}-/lib/$abi"
            val possiblePaths = listOf(
                "$nativeLibDir/lib$BINARY_NAME.so",
                "$nativeLibDir/$BINARY_NAME",
                context.applicationInfo.nativeLibraryDir + "/lib${BINARY_NAME}.so",
                context.applicationInfo.nativeLibraryDir + "/$BINARY_NAME",
                context.filesDir.parent + "/lib/$abi/lib${BINARY_NAME}.so"
            )

            binaryPath = possiblePaths.firstOrNull { File(it).exists() } ?: ""
            Timber.tag(TAG).i("BRIDGE_BINARY_SEARCH: paths=%s, found=%s",
                possiblePaths, if (binaryPath.isNotEmpty()) binaryPath else "NOT_FOUND")

            if (binaryPath.isEmpty()) {
                Timber.tag(TAG).w("BRIDGE_BINARY_MISSING: place %s in jniLibs/<abi>/", BINARY_NAME)
                Timber.tag(TAG).i("BRIDGE_DIAG_MODE: no native binary, reporting diagnostics only")
                status = BridgeStatus.Running
                updateTunDiagnostics()
                return
            }

            File(binaryPath).setExecutable(true, false)

            val cmd = listOf(binaryPath, "--fd", tunFd.toString(), "--socks5", "$socksHost:$socksPort")
            Timber.tag(TAG).i("BRIDGE_SPAWN: %s", cmd.joinToString(" "))

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            bridgeProcess = pb.start()

            Thread.sleep(200)
            val alive = bridgeProcess?.isAlive ?: false
            Timber.tag(TAG).i("BRIDGE_PROCESS_ALIVE: %s", alive)

            if (alive) {
                status = BridgeStatus.Running
                Timber.tag(TAG).i("BRIDGE_RUNNING: pid=%d", bridgeProcess?.pid() ?: -1)
            } else {
                val exitCode = bridgeProcess?.exitValue() ?: -1
                val output = try {
                    bridgeProcess?.inputStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                Timber.tag(TAG).w("BRIDGE_EXITED: exit=%d, output=%s", exitCode, output.take(500))
                status = BridgeStatus.Failed
                bridgeProcess = null
            }
            updateTunDiagnostics()

        } catch (e: Exception) {
            status = BridgeStatus.Failed
            Timber.tag(TAG).e(e, "BRIDGE_START_FAILED")
            updateTunDiagnostics()
        }
    }

    override suspend fun stop() {
        if (status == BridgeStatus.Idle || status == BridgeStatus.Stopping) return
        status = BridgeStatus.Stopping
        Timber.tag(TAG).i("BRIDGE_STOP")
        try {
            bridgeProcess?.destroy()
            bridgeProcess?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) { }
        bridgeProcess = null
        status = BridgeStatus.Idle
        Timber.tag(TAG).i("BRIDGE_STOPPED")
        updateTunDiagnostics()
    }

    override fun diagnostics(): BridgeDiagnostics {
        val procAlive = bridgeProcess?.isAlive ?: false
        return BridgeDiagnostics(
            status = status,
            forwardedPackets = diagPackets.get(),
            forwardedBytes = diagBytes.get(),
            forwardErrors = diagErrors.get(),
            connectAttempts = diagConnAttempts.get(),
            connectSuccess = diagConnOk.get(),
            connectFailed = diagConnFail.get(),
            processAlive = procAlive,
            errorMessage = ""
        )
    }

    private fun updateTunDiagnostics() {
        val diag = diagnostics()
        com.novavpn.domain.model.TunDiagnostics.bridgeRunning = diag.processAlive
        com.novavpn.domain.model.TunDiagnostics.bridgePackets = diag.forwardedPackets
        com.novavpn.domain.model.TunDiagnostics.bridgeBytes = diag.forwardedBytes
        com.novavpn.domain.model.TunDiagnostics.bridgeErrors = diag.forwardErrors
    }

    fun onPacketForwarded(bytes: Int) {
        diagPackets.incrementAndGet()
        diagBytes.addAndGet(bytes.toLong())
        updateTunDiagnostics()
    }
    fun onForwardError() { diagErrors.incrementAndGet(); updateTunDiagnostics() }
    fun onConnectAttempt() { diagConnAttempts.incrementAndGet() }
    fun onConnectSuccess() { diagConnOk.incrementAndGet() }
    fun onConnectFailed() { diagConnFail.incrementAndGet() }
}
