package com.novavpn.engine.xray

import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.TunDiagnostics
import com.novavpn.engine.api.BinaryManager
import com.novavpn.engine.api.ConfigValidator
import com.novavpn.engine.api.Engine
import com.novavpn.engine.api.EngineContext
import com.novavpn.engine.api.EngineError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Xray-core VPN engine implementation.
 *
 * Manages an Xray subprocess, handling its full lifecycle:
 * - Config generation via [XrayConfigParser]
 * - Temporary config file management
 * - Process start / stop / health-check
 * - Runtime state emissions ([Idle] → [Preparing] → [Starting] → [Running] → [Stopping] → [Idle])
 *
 * ## Thread safety
 *
 * - [start] uses a [Mutex] to prevent concurrent starts.
 * - [stop] does NOT use the mutex (avoids deadlock when Xray hangs during initialisation).
 * - `process` and related state fields are `@Volatile` for safe direct access.
 *
 * ## Why stop() doesn't use the mutex
 *
 * If Xray freezes during its init phase, the blocking portion inside `start()`'s
 * mutex-withLock prevents `stop()` from ever acquiring the lock.  Instead,
 * `stop()` directly destroys the OS subprocess via `@Volatile process`, then
 * synchronises state under the mutex only for the remaining cleanup.
 *
 * @see Engine
 * @see XrayConfigParser
 */
@Singleton
class XrayEngine @Inject constructor(
    private val binaryManager: BinaryManager
) : Engine {

    override val type: EngineType = EngineType.Xray

    // ------------------------------------------------------------------
    // Observable state flows
    // ------------------------------------------------------------------

    private val _state = MutableStateFlow(EngineRuntimeState.Idle)
    override val state: StateFlow<EngineRuntimeState> = _state.asStateFlow()

    private val _bytesReceived = MutableStateFlow(0L)
    override val bytesReceived: StateFlow<Long> = _bytesReceived.asStateFlow()

    private val _bytesSent = MutableStateFlow(0L)
    override val bytesSent: StateFlow<Long> = _bytesSent.asStateFlow()

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    /** DNS servers passed from VpnService (stored at init for config generation). */
    private var dnsServers: List<String> = emptyList()

    /** Routes passed from VpnService (stored at init for config generation). */
    private var routes: List<String> = emptyList()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var configFile: File? = null

    /** Serialises *start* only — stop() bypasses this to avoid deadlock. */
    private val startMutex = Mutex()

    /** Coroutine scope for background tasks (output collection, process watching). */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Job that reads the engine's stdout/stderr. */
    private var outputCollector: Job? = null

    /** Job that waits for unexpected process death. */
    private var deathWatcher: Job? = null

    /** Recent log lines from the engine process (circular buffer). */
    private val _logBuffer = java.util.LinkedList<String>().apply {
        // Pre-size to avoid reallocation
    }

    // ------------------------------------------------------------------
    // Engine lifecycle
    // ------------------------------------------------------------------

    override suspend fun initialize(context: EngineContext): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Store DNS servers and routes from VpnService for config generation.
            // Xray acts as a pure SOCKS5 proxy — the TUN fd is managed exclusively
            // by NovaVpnService and passed directly to hev-socks5-tunnel bridge.
            dnsServers = context.dnsServers
            routes = context.routes

            Timber.tag(TAG).i(
                "Initialized: dns=%s, routes=%s",
                context.dnsServers, context.routes
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Xray engine initialization failed")
            Result.failure(
                EngineError(
                    code = EngineError.ErrorCode.UNKNOWN,
                    message = "Failed to initialize Xray engine",
                    cause = e
                )
            )
        }
    }

    override suspend fun start(config: ServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        startMutex.withLock {
            Timber.tag(TAG).i("Starting Xray engine with config '%s' (%s:%d, protocol=%s)",
                config.name, config.address, config.port, config.protocol)

            _state.value = EngineRuntimeState.Preparing

            try {
                // 0. Validate config first
                ConfigValidator.validate(config).getOrElse { error ->
                    _state.value = EngineRuntimeState.Crashed
                    Timber.tag(TAG).e("Config validation failed: %s", error.message)
                    return@withLock Result.failure(error)
                }

                // 1. Ensure engine binary is available
                _state.value = EngineRuntimeState.Starting
                val binaryPath = binaryManager.ensureEngine(EngineType.Xray).getOrThrow()
                Timber.tag(TAG).i("Xray binary: %s (%d KB)", binaryPath,
                    java.io.File(binaryPath).length() / 1024)

                // 2. Get engine version
                val version = binaryManager.getEngineVersion(EngineType.Xray)
                Timber.tag(TAG).i("Xray version: %s", version ?: "unknown")

                // 3. Generate Xray JSON config (SOCKS5 proxy only — no TUN inbound)
                // Xray acts purely as a SOCKS5 proxy; hev-socks5-tunnel bridges
                // TUN traffic to the SOCKS5 port.  TUN fd management is exclusively
                // handled by NovaVpnService.
                Timber.tag(TAG).i("Generating config with dns=%s", dnsServers)
                val jsonConfig = XrayConfigParser.toXrayJson(
                    config = config,
                    dnsServers = dnsServers,
                    routes = routes
                )

                // 3. Write to engine directory
                val engineDir = binaryManager.getEngineDirectory(EngineType.Xray)
                val tempFile = File(engineDir, "config.json")
                tempFile.writeText(jsonConfig)
                configFile = tempFile
                Timber.tag(TAG).i("CONFIG WRITTEN: path=%s, size=%d bytes",
                    tempFile.absolutePath, jsonConfig.length)
                // Dump first 300 chars of config for debugging
                Timber.tag(TAG).i("CONFIG DUMP: %s", jsonConfig.take(500))

                // 4. Debug: comprehensive binary check before execution
                val binFile = java.io.File(binaryPath)
                Timber.tag(TAG).d("Binary check: exists=%s, size=%d, readable=%s, executable=%s",
                    binFile.exists(), binFile.length(),
                    binFile.canRead(), binFile.canExecute())
                Timber.tag(TAG).d("Binary path absolute: %s", binFile.absolutePath)
                Timber.tag(TAG).d("Binary parent dir: exists=%s, readable=%s, executable=%s",
                    binFile.parentFile?.exists(), binFile.parentFile?.canRead(), binFile.parentFile?.canExecute())

                // Force permission again right before execution
                binFile.setExecutable(true, false)
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("chmod", "755", binFile.absolutePath))
                    proc.waitFor(3, TimeUnit.SECONDS)
                    Timber.tag(TAG).d("chmod 755 exit: %d", proc.exitValue())
                } catch (e: Exception) {
                    Timber.tag(TAG).w("chmod failed before exec: %s", e.message)
                }
                Timber.tag(TAG).d("After chmod: executable=%s", binFile.canExecute())

                // 5. Validate config with xray -test before running
                Timber.tag(TAG).i("Running config validation: %s -test -c %s",
                    binFile.name, tempFile.name)
                val testProcess = ProcessBuilder(
                    binaryPath, "-test", "-c", tempFile.absolutePath
                ).redirectErrorStream(true).start()
                val testOutput = try {
                    if (testProcess.waitFor(5, TimeUnit.SECONDS)) {
                        testProcess.inputStream.bufferedReader().readText()
                    } else {
                        testProcess.destroyForcibly()
                        "Config validation timed out after 5s"
                    }
                } catch (e: Exception) {
                    "Config validation failed: ${e.message}"
                }
                val testExitCode = try { testProcess.exitValue() } catch (_: Exception) { -1 }
                Timber.tag(TAG).i("Config validation: exit=%d, output:\n%s",
                    testExitCode, testOutput.take(1000))

                // ════════════════════════════════════════════════════════════════
                // HARD FAIL on config validation error
                // ════════════════════════════════════════════════════════════════
                if (testExitCode != 0) {
                    _state.value = EngineRuntimeState.Crashed
                    val errMsg = "Config validation FAILED (exit=$testExitCode): ${testOutput.take(200)}"
                    Timber.tag(TAG).e("XRAY_CONFIG_TEST_FAILED: %s", errMsg)
                    return@withLock Result.failure(
                        EngineError(EngineError.ErrorCode.CONFIG_PARSE_FAILURE,
                            "Xray config invalid: ${testOutput.take(100)}")
                    )
                }

                // 6. Start the xray subprocess
                val pb = ProcessBuilder(
                    binaryPath, "run", "-c", tempFile.absolutePath
                )
                pb.redirectErrorStream(true)
                pb.environment()?.put("XRAY_LOCATION_ASSET", ".") // if geo files are local

                Timber.tag(TAG).i("XRAY_PROCESS_ARGS: %s run -c %s",
                    binaryPath, tempFile.absolutePath)
                com.novavpn.domain.model.TunDiagnostics.processArgs =
                    "$binaryPath run -c ${tempFile.absolutePath}"

                val xrayProcess = pb.start()
                process = xrayProcess

                // 5. 🔴 CRITICAL: awaitXrayReady MUST run BEFORE startOutputCollector,
                //    otherwise the collector consumes all stderr and awaitXrayReady
                //    never sees the startup marker ("Xray ... started" / "listening TCP").
                //    See: output collector uses reader.readLine() on the same InputStream.
                val initResult = awaitXrayReady(xrayProcess)

                // 5b. Only start the real-time output collector AFTER we've seen
                //     the startup marker, so it doesn't steal output.
                startOutputCollector(xrayProcess)

                if (initResult === ReadyResult.READY) {
                    // ✅ Xray is alive, confirming Running
                    _state.value = EngineRuntimeState.Running

                    // Store PID and alive state
                    try {
                        val pidF = xrayProcess.javaClass.getDeclaredField("pid")
                        pidF.isAccessible = true
                        val pidVal = pidF.getInt(xrayProcess)
                        TunDiagnostics.storePid(pidVal)
                    } catch (_: Exception) { }

                    Timber.tag(TAG).i("XRAY_READY: SOCKS5 proxy started successfully")
                    Result.success(Unit)
                } else {
                    // ❌ Xray died, timed out, or cancelled — kill and fail
                    val reason = when (initResult) {
                        ReadyResult.DIED -> {
                            val exitCode = xrayProcess.exitValue()
                            val errorOutput = try {
                                xrayProcess.inputStream.bufferedReader().readText()
                            } catch (_: Exception) { "" }
                            "Xray died during init (code=$exitCode): ${errorOutput.take(200)}"
                        }
                        ReadyResult.TIMEOUT -> {
                            "Xray did NOT emit startup marker within init window — killing"
                        }
                        else -> "Xray init cancelled by Cancel/Timeout"
                    }
                    Timber.tag(TAG).e("XRAY_INIT_FAILED: %s", reason)
                    hardKillProcess(xrayProcess)
                    process = null
                    _state.value = EngineRuntimeState.Crashed
                    return@withLock Result.failure(
                        EngineError(EngineError.ErrorCode.ENGINE_CRASH, reason)
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // from awaitXrayReady if the outer withTimeout fires
                Timber.tag(TAG).e(e, "XRAY_START_TIMEOUT: engine start exceeded timeout")
                hardKillProcess(process)
                process = null
                _state.value = EngineRuntimeState.Crashed
                cleanup()
                Result.failure(
                    EngineError(EngineError.ErrorCode.ENGINE_CRASH,
                        "Xray engine start timed out")
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Connection was cancelled — kill Xray before propagating
                Timber.tag(TAG).w(e, "XRAY_START_CANCELLED: %s", e.message)
                hardKillProcess(process)
                process = null
                cleanup()
                throw e
            } catch (e: Exception) {
                _state.value = EngineRuntimeState.Crashed
                Timber.tag(TAG).e(e, "Failed to start Xray engine")
                cleanup()
                Result.failure(
                    EngineError(
                        code = EngineError.ErrorCode.ENGINE_CRASH,
                        message = "Exception during Xray engine start: ${e.message}",
                        cause = e
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Init wait helper — polls SOCKS5 port instead of parsing stderr
    // ------------------------------------------------------------------

    private enum class ReadyResult { READY, DIED, TIMEOUT, CANCELLED }

    /**
     * Wait up to [INIT_WAIT_MS] for Xray's SOCKS5 inbound port to open.
     *
     * Instead of trying to parse process stderr (which may be buffered or
     * consumed differently on Android ARM64), this polls the actual inbound
     * port (10808) until it accepts a TCP connection.
     *
     * Cancellation-safe: uses [delay] and [ensureActive] instead of
     * `Thread.sleep()`, so a cancelled coroutine exits immediately.
     */
    private suspend fun awaitXrayReady(xrayProcess: Process): ReadyResult {
        val initWaitMs = 12000L
        val socksPort = 10808 // matches XrayConfigParser.buildInbounds()
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < initWaitMs) {
            currentCoroutineContext().ensureActive()

            if (!xrayProcess.isAlive) {
                val errorOutput = try {
                    xrayProcess.inputStream.bufferedReader().readText()
                } catch (_: Exception) { "" }
                Timber.tag(TAG).e("XRAY_DIED during init: output=\n%s", errorOutput.take(1000))
                return ReadyResult.DIED
            }

            // Try connecting to the SOCKS5 inbound port
            try {
                val sock = java.net.Socket()
                sock.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 200)
                sock.close()
                Timber.tag(TAG).i("XRAY_READY: SOCKS5 port %d is accepting connections", socksPort)
                return ReadyResult.READY
            } catch (_: Exception) {
                // Port not ready yet — keep waiting
            }

            delay(200)
        }

        Timber.tag(TAG).w("XRAY_INIT_TIMEOUT: process alive for %dms but port %d never opened",
            initWaitMs, socksPort)
        return ReadyResult.TIMEOUT
    }

    // ------------------------------------------------------------------
    // Stop — MUTEX-FREE to avoid deadlock with a hung start()
    // ------------------------------------------------------------------

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current == EngineRuntimeState.Idle || current == EngineRuntimeState.Stopping) {
            Timber.tag(TAG).w("stop() called but engine is already $current — no-op")
            return@withContext Result.success(Unit)
        }

        _state.value = EngineRuntimeState.Stopping
        Timber.tag(TAG).i("[XrayKill] Stopping Xray engine — immediate SIGKILL")

        try {
            // ════════════════════════════════════════════════════════════
            // DIRECT HARD KILL — bypasses the mutex to avoid deadlock
            // ════════════════════════════════════════════════════════════
            hardKillProcess(process)
            process = null

            cleanup()

            _state.value = EngineRuntimeState.Idle
            Timber.tag(TAG).i("[XrayKill] Xray engine stopped successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = EngineRuntimeState.Crashed
            Timber.tag(TAG).e(e, "Error while stopping Xray engine")
            Result.failure(
                EngineError(
                    code = EngineError.ErrorCode.UNKNOWN,
                    message = "Failed to stop Xray engine: ${e.message}",
                    cause = e
                )
            )
        }
    }

    override suspend fun restart(config: ServerConfig): Result<Unit> {
        Timber.tag(TAG).i("Restarting Xray engine")
        stop()
        return start(config)
    }

    override suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        process?.isAlive == true
    }

    override suspend fun destroy() {
        Timber.tag(TAG).i("Destroying Xray engine")
        stop()
        // Reset byte counters
        _bytesReceived.value = 0L
        _bytesSent.value = 0L
        Timber.tag(TAG).i("Xray engine destroyed")
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Continuously reads the subprocess stdout/stderr and forwards it to Timber.
     * Runs on [serviceScope] so it outlives the caller if needed.
     */
    private fun startOutputCollector(proc: Process) {
        outputCollector = serviceScope.launch {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    var line: String? = null
                    while (isActive && reader.readLine().also { line = it } != null) {
                        if (line != null) {
                            Timber.tag(TAG).i("[engine] %s", line)
                            _logBuffer.add(line!!)
                        }
                    }
                }
            } catch (_: IOException) { /* process died */ }
        }

        // Watch for unexpected process death
        deathWatcher = serviceScope.launch {
            try {
                val exitCode = proc.waitFor()
                if (_state.value == EngineRuntimeState.Running) {
                    Timber.tag(TAG).w("Engine process died unexpectedly (code=%d)", exitCode)
                    _state.value = EngineRuntimeState.Crashed
                }
            } catch (_: InterruptedException) { }
        }
    }

    /**
     * HARD KILL — immediate [destroyForcibly] (SIGKILL).
     *
     * A frozen native (Go/C) process does NOT respond to SIGTERM ([destroy]).
     * We go straight to SIGKILL so the process is guaranteed to die and
     * release the TUN fd / socket locks.
     *
     * Safe to call multiple times (idempotent via null-check and isAlive).
     */
    private fun hardKillProcess(procToKill: Process?) {
        val proc = procToKill ?: return
        if (!proc.isAlive) {
            Timber.tag(TAG).d("[XrayKill] Process already dead — no kill needed")
            return
        }

        Timber.tag(TAG).w("[XrayKill] Sending SIGKILL to Xray process")

        // Try Linux kill -9 via /proc/pid first (more reliable than Java process.destroyForcibly)
        try {
            val pidField = proc.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            val pid = pidField.getInt(proc)
            if (pid > 0) {
                Timber.tag(TAG).i("[XrayKill] killing PID %d with kill -9", pid)
                Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString()))
                    .waitFor(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            // Fallback to Java's destroyForcibly
        }

        // Java-level destroyForcibly (SIGKILL via Process.destroyForcibly)
        proc.destroyForcibly()
        try {
            val exited = proc.waitFor(2, TimeUnit.SECONDS)
            if (exited) {
                Timber.tag(TAG).i("[XrayKill] Xray process killed and reaped")
            } else {
                Timber.tag(TAG).w("[XrayKill] Xray process did NOT die after destroyForcibly")
            }
        } catch (e: InterruptedException) {
            Timber.tag(TAG).w("[XrayKill] Interrupted while waiting for process death")
            Thread.currentThread().interrupt()
        }
    }

    /** Remove the temporary config file and reset state. */
    private fun cleanup() {
        configFile?.let {
            if (it.exists()) {
                it.delete()
                Timber.tag(TAG).d("Deleted temp config file: %s", it.absolutePath)
            }
        }
        configFile = null
    }

    companion object {
        private const val TAG = "XrayEngine"

        /** How long (ms) to wait for Xray to emit its startup marker. */
        private const val INIT_WAIT_MS = 3000L
    }
}
