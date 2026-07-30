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
 * through a SOCKS5 proxy. It is spawned as a child process via JNI
 * fork()+execv() (bypassing Android's ProcessBuilder which closes all
 * fds >= 3 before exec).
 *
 * Binary location: jniLibs/<abi>/hev-socks5-tunnel (inside APK)
 * Architecture:
 *   TUN VpnService fd → hev-socks5-tunnel → SOCKS5(127.0.0.1:10808) → Xray → outbound
 */
@Singleton
class NativeTunnelBridge @Inject constructor(
    @ApplicationContext private val context: Context
) : TunnelBridge {

    companion object {
        private const val TAG = "TunnelBridge"
        private const val BINARY_NAME = "libhev-socks5-tunnel.so"
        private const val BRIDGE_TIMEOUT_SEC = 5L
        private const val CONFIG_FILE_NAME = "bridge_config.yml"
        private const val CRASH_LOG_NAME = "hev_bridge_crash.log"
    }

    override val type: String = "hev-socks5-tunnel"

    override var status: BridgeStatus = BridgeStatus.Idle
        private set

    private var bridgePid: Int = -1           // PID from native fork()
    private var binaryPath: String = ""
    private var tunFd: Int = -1
    private var socksHost: String = ""
    private var socksPort: Int = 10808

    // File paths (resolved lazily)
    private val configDir: File get() = File(context.cacheDir, "novavpn/bridge").also { it.mkdirs() }
    private val configFile: File get() = File(configDir, CONFIG_FILE_NAME)
    private val crashLogFile: File get() = File(configDir, CRASH_LOG_NAME)

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
            ensureBinary()

            // Build args — fd comes as a string argument to the child
            val args = arrayOf("--fd", tunFd.toString(), "--socks5", "$socksHost:$socksPort")
            Timber.tag(TAG).i("BRIDGE_COMMAND: %s %s", binaryPath, args.joinToString(" "))

            // Clear any previous crash log
            crashLogFile.delete()

            // Fork+exec via JNI — preserves the TUN fd in the child
            val logPath = crashLogFile.absolutePath.also { Timber.tag(TAG).d("CRASH_LOG: %s", it) }
            val pid = NativeBridgeRunner.nativeForkExec(binaryPath, args, tunFd, logPath)
            if (pid <= 0) {
                val errno = -pid
                val msg = "nativeForkExec failed: errno=$errno"
                Timber.tag(TAG).e(msg)
                status = BridgeStatus.Failed
                throw BridgeStartException(msg)
            }
            bridgePid = pid
            Timber.tag(TAG).i("BRIDGE_FORKED: pid=%d", pid)

            // Poll with timeout — check if process stays alive
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BRIDGE_TIMEOUT_SEC)
            var alive = false
            while (System.nanoTime() < deadline) {
                val aliveStatus = NativeBridgeRunner.nativeIsAlive(pid)
                if (aliveStatus == 1) {
                    alive = true
                    break
                }
                if (aliveStatus == 0) {
                    alive = false
                    break
                }
                // aliveStatus == -1 (ECHILD), retry
                Thread.sleep(100)
            }

            if (alive) {
                status = BridgeStatus.Running
                Timber.tag(TAG).i("BRIDGE_RUNNING: pid=%d", pid)
                Timber.tag(TAG).i("BRIDGE_START_RESULT: SUCCESS")
            } else {
                // Reap exit status via waitpid
                val exitCode = reapExitCode(pid)
                val crashLog = readCrashLogContents()
                val crashReason = crashLog ?: "(no captured output)"
                android.util.Log.e("TunnelBridge", "BRIDGE_EXITED: pid=$pid, exit=$exitCode")
                android.util.Log.e("TunnelBridge", "CRASH_REASON:\n$crashReason")
                Timber.tag(TAG).i("BRIDGE_START_RESULT: FAILED (exit=%d)", exitCode)
                bridgePid = -1
                status = BridgeStatus.Failed
                throw BridgeStartException(
                    "hev-socks5-tunnel exited (pid=$pid, code=$exitCode)\n$crashReason"
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
        Timber.tag(TAG).i("BRIDGE_STOP: pid=%d", bridgePid)

        if (bridgePid > 0) {
            NativeBridgeRunner.nativeKillProcess(bridgePid)
            bridgePid = -1
        }
        status = BridgeStatus.Idle
        Timber.tag(TAG).i("BRIDGE_STOPPED")
        updateTunDiagnostics()
    }

    override fun diagnostics(): BridgeDiagnostics {
        val procAlive = bridgePid > 0 && NativeBridgeRunner.nativeIsAlive(bridgePid) == 1
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
    // Crash log
    // ------------------------------------------------------------------

    /**
     * Read the captured stderr/stdout from the bridge binary.
     * Called by [NovaVpnService] DIAG loop to surface crash output to Logcat.
     * @return the captured log text, or null if the file doesn't exist or is empty.
     */
    fun readCrashLogContents(): String? {
        return try {
            val content = crashLogFile.readText().trim()
            if (content.isNotEmpty()) content else null
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Binary resolution
    // ------------------------------------------------------------------

    private fun ensureBinary() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir ?: ""
        val nativePath = "$nativeLibDir/$BINARY_NAME"

        val file = File(nativePath)
        if (file.exists() && file.canExecute()) {
            binaryPath = nativePath
            Timber.tag(TAG).i("BRIDGE_BINARY_FOUND: nativeLib=%s (%d KB)",
                nativePath, file.length() / 1024)
            return
        }

        // Extract from APK zip
        Timber.tag(TAG).i("BRIDGE_BINARY_NOT_IN_NATIVE_LIB — extracting from APK")
        try {
            binaryPath = extractFromApk()
            Timber.tag(TAG).i("BRIDGE_BINARY_EXTRACTED: %s (%d KB)",
                binaryPath, File(binaryPath).length() / 1024)
            return
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "BRIDGE_BINARY_EXTRACT_FAILED")
        }

        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        throw FileNotFoundException(
            "libhev-socks5-tunnel.so NOT FOUND! " +
            "Looked in nativeLibraryDir, APK zip lib/$abi/. " +
            "Run scripts/download-engines.sh."
        )
    }

    private fun extractFromApk(): String {
        val apkFile = File(context.applicationInfo.sourceDir)
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val entryPath = "lib/$abi/$BINARY_NAME"

        val targetDir = File(context.filesDir, "novavpn/bridge")
        targetDir.mkdirs()
        val target = File(targetDir, BINARY_NAME)

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
                    "hev-socks5-tunnel not found in APK under $entryPath."
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

        Timber.tag(TAG).i("BRIDGE_EXTRACTED: %s (%d KB)", target.absolutePath, target.length() / 1024)
        return target.absolutePath
    }

    // ------------------------------------------------------------------
    // Process management helpers
    // ------------------------------------------------------------------

    /**
     * Reap the exit code of a child process via waitpid().
     * Returns the exit status (0–255), or -1 if waitpid fails.
     */
    private fun reapExitCode(pid: Int): Int {
        return NativeBridgeRunner.nativeWaitFor(pid, 0)
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
