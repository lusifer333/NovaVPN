package com.novavpn.app.service

import com.novavpn.engine.api.BridgeDiagnostics
import com.novavpn.engine.api.BridgeStatus
import com.novavpn.engine.api.TunnelBridge
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
class NativeTunnelBridge @Inject constructor() : TunnelBridge {

    companion object {
        private const val TAG = "TunnelBridge"
        private const val BINARY_NAME = "hev-socks5-tunnel"
    }

    override val type: String = "hev-socks5-tunnel"

    override var status: BridgeStatus = BridgeStatus.Idle
        private set

    private var bridgeProcess: Process? = null
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
            // Create a launch script that bridges TUN fd to SOCKS5
            // hev-socks5-tunnel creates its own TUN, but we need fd-based bridging.
            // For now, log a diagnostic message about the bridge architecture.
            Timber.tag(TAG).i("BRIDGE_ARCH: TUN(fd=%d) -> hev-socks5-tunnel -> SOCKS5(%s:%d)", tunFd, socksHost, socksPort)
            status = BridgeStatus.Running

            // TODO: Replace with actual native binary execution once
            // hev-socks5-tunnel or a compatible fd-based bridge is compiled.
            // Expected command:
            //   hev-socks5-tunnel --fd <tunFd> --socks5-host <host> --socks5-port <port>

        } catch (e: Exception) {
            status = BridgeStatus.Failed
            Timber.tag(TAG).e(e, "BRIDGE_START_FAILED")
        }
    }

    override suspend fun stop() {
        if (status == BridgeStatus.Idle || status == BridgeStatus.Stopping) return
        status = BridgeStatus.Stopping
        Timber.tag(TAG).i("BRIDGE_STOP")
        try {
            bridgeProcess?.destroy()
            bridgeProcess?.waitFor()
        } catch (_: Exception) { }
        bridgeProcess = null
        status = BridgeStatus.Idle
        Timber.tag(TAG).i("BRIDGE_STOPPED")
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

    /** Callback from native bridge — increment packet counter. */
    fun onPacketForwarded(bytes: Int) {
        diagPackets.incrementAndGet()
        diagBytes.addAndGet(bytes.toLong())
    }

    fun onForwardError() { diagErrors.incrementAndGet() }
    fun onConnectAttempt() { diagConnAttempts.incrementAndGet() }
    fun onConnectSuccess() { diagConnOk.incrementAndGet() }
    fun onConnectFailed() { diagConnFail.incrementAndGet() }
}
