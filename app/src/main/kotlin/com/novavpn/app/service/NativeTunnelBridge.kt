package com.novavpn.app.service

import android.content.Context
import com.novavpn.engine.api.BridgeDiagnostics
import com.novavpn.engine.api.BridgeStatus
import com.novavpn.engine.api.TunnelBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native tunnel bridge using hev-socks5-tunnel binary.
 *
 * The bridge binary reads from a TUN interface and forwards traffic
 * through a SOCKS5 proxy. It is spawned as a child process and its
 * lifecycle is managed here.
 *
 * Binary location: jniLibs/<abi>/hev-socks5-tunnel (inside APK)
 * The binary is bundled inside the APK under lib/<abi>/hev-socks5-tunnel
 * and extracted at runtime if the system doesn't extract it automatically.
 *
 * Architecture:
 *   TUN VpnService fd → hev-socks5-tunnel → SOCKS5(127.0.0.1:10808) → Xray → outbound
 *
 * IMPORTANT: If the binary is missing, start() throws — no silent diagnostic mode.
 * The caller (NovaVpnService) must verify BridgeStatus.Running after start().
 */
@Singleton
class NativeTunnelBridge @Inject constructor(
    @ApplicationContext private val context: Context
) : TunnelBridge {

    companion object {
        private const val TAG = "TunnelBridge"
        private const val BINARY_NAME = "libhev-socks5-tunnel.so"
        private const val BRIDGE_TIMEOUT_SEC = 5
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
            // Find binary — try native lib path first, then APK zip extraction
            ensureBinary()

            File(binaryPath).setExecutable(true, false)

            // The binary expects TUN fd and SOCKS5 proxy address
            val cmd = listOf(binaryPath, "--fd", tunFd.toString(), "--socks5", "$socksHost:$socksPort")
            Timber.tag(TAG).i("BRIDGE_COMMAND: %s", cmd.joinToString(" "))

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            bridgeProcess = pb.start()

            // Wait briefly and check if process stays alive
            val alive = bridgeProcess?.waitFor(BRIDGE_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS) == false

            if (alive) {
                status = BridgeStatus.Running
                Timber.tag(TAG).i("BRIDGE_RUNNING: alive=true")
                Timber.tag(TAG).i("BRIDGE_START_RESULT: SUCCESS")
            } else {
                val exitCode = bridgeProcess?.exitValue() ?: -1
                val output = try {
                    bridgeProcess?.inputStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                Timber.tag(TAG).w("BRIDGE_EXITED: exit=%d, output=%s", exitCode, output.take(500))
                Timber.tag(TAG).i("BRIDGE_START_RESULT: FAILED (exit=%d)", exitCode)
                status = BridgeStatus.Failed
                bridgeProcess = null
                throw BridgeStartException(
                    "hev-socks5-tunnel exited immediately (code=$exitCode): ${output.take(200)}"
                )
            }
            updateTunDiagnostics()

        } catch (e: BridgeStartException) {
            status = BridgeStatus.Failed
            updateTunDiagnostics()
            throw e
        } catch (e: Exception) {
            status = BridgeStatus.Failed
            Timber.tag(TAG).e(e, "BRIDGE_START_FAILED")
            updateTunDiagnostics()
            throw BridgeStartException("hev-socks5-tunnel failed: ${e.message}", e)
        }
    }

    override suspend fun stop() {
        if (status == BridgeStatus.Idle || status == BridgeStatus.Stopping) return
        status = BridgeStatus.Stopping
        Timber.tag(TAG).i("BRIDGE_STOP")
        try {
            bridgeProcess?.destroy()
            bridgeProcess?.waitFor(3, TimeUnit.SECONDS)
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
            errorMessage = if (status == BridgeStatus.Failed) "Bridge process not running" else ""
        )
    }

    // ------------------------------------------------------------------
    // Binary resolution
    // ------------------------------------------------------------------

    /**
     * Locate the hev-socks5-tunnel binary. Strategy:
     * 1. Check nativeLibraryDir (fast path if Android extracted it)
     * 2. Extract from APK zip (always works — binary is in lib/<abi>/ inside the APK)
     * 3. Throw FileNotFoundException if neither works
     */
    private fun ensureBinary() {
        // 1. Try nativeLibraryDir (where Android extracts jniLibs)
        val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: ""
        val nativePath = "$nativeLibDir/$BINARY_NAME"

        val file = File(nativePath)
        if (file.exists() && file.canExecute()) {
            binaryPath = nativePath
            Timber.tag(TAG).i("BRIDGE_BINARY_FOUND: nativeLib=%s (%d KB)",
                nativePath, file.length() / 1024)
            return
        }

        // 2. Extract from APK zip (always available)
        Timber.tag(TAG).i("BRIDGE_BINARY_NOT_IN_NATIVE_LIB — extracting from APK")
        try {
            binaryPath = extractFromApk()
            val file = File(binaryPath)
            Timber.tag(TAG).i("BRIDGE_BINARY_EXTRACTED: %s (%d KB)",
                binaryPath, file.length() / 1024)
            return
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "BRIDGE_BINARY_EXTRACT_FAILED")
        }

        // 3. Nothing worked — hard failure, no diagnostic mode
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val msg = buildString {
            appendLine("libhev-socks5-tunnel.so binary NOT FOUND!")
            appendLine("  Looked in nativeLibraryDir: $nativeLibDir")
            appendLine("  APK zip entry: lib/$abi/libhev-socks5-tunnel.so")
            appendLine("  Expected location: app/src/main/jniLibs/$abi/libhev-socks5-tunnel.so")
            appendLine("  Run: scripts/download-engines.sh")
        }
        Timber.tag(TAG).w(msg)
        throw FileNotFoundException(msg)
    }

    /**
     * Extract hev-socks5-tunnel from inside the APK zip.
     * APK internal path: lib/<abi>/hev-socks5-tunnel
     * Works even when extractNativeLibs="false".
     */
    private fun extractFromApk(): String {
        val apkFile = File(context.applicationInfo.sourceDir)
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val entryPath = "lib/$abi/$BINARY_NAME"

        // Extract to app's internal private directory
        val targetDir = File(context.filesDir, "novavpn/bridge")
        targetDir.mkdirs()
        val target = File(targetDir, BINARY_NAME)

        // Only extract if not already extracted or APK was updated
        if (target.exists() && target.canExecute()) {
            val apkMtime = apkFile.lastModified()
            val binMtime = target.lastModified()
            if (binMtime >= apkMtime) {
                Timber.tag(TAG).d("BRIDGE_ALREADY_EXTRACTED: %s", target.absolutePath)
                return target.absolutePath
            }
            Timber.tag(TAG).d("BRIDGE_EXTRACT_STALE: APK updated, re-extracting")
        }

        Timber.tag(TAG).i("BRIDGE_EXTRACT: from APK %s!/%s", apkFile.name, entryPath)

        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry(entryPath)
                ?: throw FileNotFoundException(
                    "hev-socks5-tunnel not found in APK under $entryPath. " +
                    "Run scripts/download-engines.sh to download it."
                )

            zip.getInputStream(entry).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }

        target.setExecutable(true, false)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", target.absolutePath))
                .waitFor(2L, TimeUnit.SECONDS)
        } catch (_: Exception) { }

        val sizeKb = target.length() / 1024
        Timber.tag(TAG).i("BRIDGE_EXTRACTED: %s (%d KB)", target.absolutePath, sizeKb)
        return target.absolutePath
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

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

/** Thrown when the bridge binary fails to start. */
class BridgeStartException(message: String, cause: Throwable? = null) : Exception(message, cause)
