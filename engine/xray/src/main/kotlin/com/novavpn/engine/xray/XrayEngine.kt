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
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.io.FileDescriptor
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

    /** EngineContext from initialize() — stores TUN fd, DNS, routes. */
    private var engineContext: EngineContext? = null

    /** Dup'd TUN fd for the child process (FD_CLOEXEC cleared via Os.dup). */
    private var tunFdForChild: Int = -1

    /** The inheritable (dup'd) TUN fd that Xray child process actually receives. */
    private var inheritableTunFd: Int = -1

    /** Original TUN fd (before dup), for diagnostic. */
    private var rawTunFd: Int = -1

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
            // Store EngineContext for use in start()
            engineContext = context

            val rawFd = context.tunFileDescriptor
            if (rawFd < 0) {
                val msg = "Invalid TUN file descriptor: $rawFd"
                Timber.tag(TAG).e(msg)
                return@withContext Result.failure(
                    EngineError(code = EngineError.ErrorCode.TUN_SETUP_FAILED, message = msg)
                )
            }
            rawTunFd = rawFd
            tunFdForChild = rawFd

            Timber.tag(TAG).i(
                "Initialized: tunFd=%d, dns=%s, routes=%s",
                rawFd, context.dnsServers, context.routes
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

                // 3. Generate Xray JSON config with TUN inbound
                val ctx = engineContext
                    ?: throw EngineError(EngineError.ErrorCode.UNKNOWN, "Engine not initialized — no EngineContext")
                val rawTunFd = tunFdForChild
                if (rawTunFd < 0) {
                    throw EngineError(EngineError.ErrorCode.TUN_SETUP_FAILED, "Invalid TUN fd for child: $rawTunFd")
                }

                // Dup the TUN fd to create a non-CLOEXEC copy for the child process.
                inheritableTunFd = createInheritableTunFd(rawTunFd)
                val dupFailed = inheritableTunFd == rawTunFd

                // Store TUN diagnostic state outside log buffer
                com.novavpn.domain.model.TunDiagnostics.rawFd = rawTunFd
                com.novavpn.domain.model.TunDiagnostics.inheritableFd = inheritableTunFd
                com.novavpn.domain.model.TunDiagnostics.dupOK = !dupFailed

                Timber.tag(TAG).i("TUN_FD_PASS: rawFd=%d, inheritableFd=%d, dupOK=%s",
                    rawTunFd, inheritableTunFd, !dupFailed)

                Timber.tag(TAG).i("Generating config with TUN fd=%d, dns=%s",
                    inheritableTunFd, ctx.dnsServers)
                val jsonConfig = XrayConfigParser.toXrayJson(
                    config = config,
                    tunFd = inheritableTunFd,
                    dnsServers = ctx.dnsServers,
                    routes = ctx.routes
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
                        EngineError(EngineError.ErrorCode.CONFIG_ERROR,
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

                // Verify inheritable fd is valid before spawning child
                val fdPath = "/proc/self/fd/$inheritableTunFd"
                val fdType = try {
                    java.io.File(fdPath).exists()
                } catch (_: Exception) { false }
                Timber.tag(TAG).i("PROCFS_CHECK: fd=%d exists=%s", inheritableTunFd, fdType)

                val xrayProcess = pb.start()
                process = xrayProcess

                // 5. Start process output collector (reads stdout/stderr)
                startOutputCollector(xrayProcess)

                // 6. Cancellable init wait loop — checks actual running marker
                val initResult = awaitXrayReady(xrayProcess)

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

                    Timber.tag(TAG).i("XRAY_READY: rawFd=%d, inheritFd=%d, dupOK=%s",
                        rawTunFd, inheritableTunFd,
                        rawTunFd != inheritableTunFd)
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
                            "Xray did NOT emit 'running inbound' within init window — killing"
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
    // Init wait helper — cancellable and marker-based
    // ------------------------------------------------------------------

    private enum class ReadyResult { READY, DIED, TIMEOUT, CANCELLED }

    /**
     * Wait up to [INIT_WAIT_MS] for Xray to emit its "running inbound" marker.
     *
     * Cancellation-safe: uses [delay] and [ensureActive] instead of
     * `Thread.sleep()`, so a cancelled coroutine exits immediately.
     */
    private suspend fun awaitXrayReady(xrayProcess: Process): ReadyResult {
        val initWaitMs = 3000L
        val startTime = System.currentTimeMillis()
        var lastOutput = ""

        while (System.currentTimeMillis() - startTime < initWaitMs) {
            // 🎯 CANCELLATION CHECKPOINT — this is why we use delay, not Thread.sleep
            currentCoroutineContext().ensureActive()

            if (!xrayProcess.isAlive) {
                // Read remaining output
                val errorOutput = try {
                    xrayProcess.inputStream.bufferedReader().readText()
                } catch (_: Exception) { "" }
                Timber.tag(TAG).e("XRAY_DIED: output=\n%s", errorOutput.take(1000))
                return ReadyResult.DIED
            }

            // Read any available output (non-blocking)
            try {
                val avail = xrayProcess.inputStream.available()
                if (avail > 0) {
                    val buf = ByteArray(avail.coerceAtMost(4096))
                    xrayProcess.inputStream.read(buf, 0, buf.size)
                    val chunk = String(buf, Charsets.UTF_8)
                    lastOutput += chunk

                    // Check for fatal errors
                    if (chunk.contains("permission denied", ignoreCase = true) ||
                        chunk.contains("failed to start", ignoreCase = true)) {
                        Timber.tag(TAG).w("XRAY_ERROR_DURING_INIT: %s", chunk.take(500))
                    }

                    // ✅ SUCCESS MARKER — Xray confirmed it's running
                    if (chunk.contains("running inbound", ignoreCase = true)) {
                        Timber.tag(TAG).i("XRAY_INBOUND_READY: %s", chunk.take(200))
                        if (lastOutput.isNotBlank()) {
                            Timber.tag(TAG).i("XRAY_STDERR_DUMP:\n%s", lastOutput.take(2000))
                        }
                        return ReadyResult.READY
                    }
                }
            } catch (_: Exception) { }

            // 🎯 CANCELLATION SAFE — delay respects coroutine cancellation
            // Using delay(100) instead of Thread.sleep(100) ensures that
            // cancellation exceptions propagate immediately.
            delay(100)
        }

        // Timeout — process is still alive but never emitted "running inbound"
        Timber.tag(TAG).w("XRAY_INIT_TIMEOUT: process alive, lastOutput=\n%s",
            lastOutput.take(1000))
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

    /** Remove the temporary config file + close inheritable TUN fd + reset. */
    private fun cleanup() {
        configFile?.let {
            if (it.exists()) {
                it.delete()
                Timber.tag(TAG).d("Deleted temp config file: %s", it.absolutePath)
            }
        }
        configFile = null

        // Close the inheritable (dup'd) TUN fd — but NOT the original which
        // is owned by NovaVpnService (tunInterface).
        if (inheritableTunFd >= 0) {
            try {
                closeFd(inheritableTunFd)
                Timber.tag(TAG).d("Closed inheritable TUN fd: %d", inheritableTunFd)
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to close inheritable TUN fd: %s", e.message)
            }
            inheritableTunFd = -1
        }

        engineContext = null
        tunFdForChild = -1
    }

    /**
     * Create a non-CLOEXEC copy of the TUN fd so the child (Xray) process
     * can inherit it after fork() + exec().
     *
     * On Android 12+, ParcelFileDescriptor from VpnService.Builder.establish()
     * has the FD_CLOEXEC flag set. Java's ProcessBuilder closes all CLOEXEC fds
     * in the child before exec(), making the original TUN fd inaccessible to Xray.
     *
     * Os.dup() on Android (libcore) creates a new fd WITHOUT FD_CLOEXEC (POSIX
     * guarantee). We use reflection to wrap the int fd in a FileDescriptor.
     */
    private fun createInheritableTunFd(rawFd: Int): Int {
        return try {
            val fd = FileDescriptor()
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(fd, rawFd)
            val duped = android.system.Os.dup(fd)
            field.getInt(duped)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Os.dup failed, using raw fd: %s", e.message)
            rawFd
        }
    }

    /** Close a file descriptor by int value using reflection + Os.close. */
    private fun closeFd(fdInt: Int) {
        val fd = FileDescriptor()
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(fd, fdInt)
        android.system.Os.close(fd)
    }

    companion object {
        private const val TAG = "XrayEngine"

        /** How long (ms) to wait for Xray to emit its "running inbound" marker. */
        private const val INIT_WAIT_MS = 3000L
    }
}
