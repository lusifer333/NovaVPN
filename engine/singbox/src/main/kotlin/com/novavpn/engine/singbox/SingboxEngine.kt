package com.novavpn.engine.singbox

import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.ServerConfig
import com.novavpn.engine.api.BinaryManager
import com.novavpn.engine.api.Engine
import com.novavpn.engine.api.EngineContext
import com.novavpn.engine.api.EngineError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * Sing-box VPN engine implementation.
 *
 * Manages a sing-box subprocess, handling its full lifecycle:
 * - Config generation via [SingboxConfigParser]
 * - Temporary config file management
 * - Process start / stop / health-check
 * - Runtime state emissions ([Idle] → [Starting] → [Running] → [Stopping] → [Idle])
 *
 * Thread safety is guaranteed by a [Mutex] that serialises all
 * process-affecting operations on [Dispatchers.IO].
 *
 * ## Hilt wiring
 * A companion [Module] binds this class into the engine multibinding map
 * under [EngineType.SingBox] so that [com.novavpn.engine.api.EngineManagerImpl]
 * can discover and activate it.
 *
 * @see Engine
 * @see SingboxConfigParser
 */
@Singleton
class SingboxEngine @Inject constructor(
    private val binaryManager: BinaryManager
) : Engine {

    override val type: EngineType = EngineType.SingBox

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

    @Volatile
    private var process: Process? = null

    @Volatile
    private var configFile: File? = null

    /** Serialises all process-affecting operations. */
    private val mutex = Mutex()

    /** Coroutine scope for background tasks. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Job that reads the engine's stdout/stderr. */
    private var outputCollector: Job? = null

    /** Job that waits for unexpected process death. */
    private var deathWatcher: Job? = null

    /** Recent log lines from the engine process. */
    private val _logBuffer = java.util.LinkedList<String>()

    // ------------------------------------------------------------------
    // Engine lifecycle
    // ------------------------------------------------------------------

    override suspend fun initialize(context: EngineContext): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).i("Initializing Sing-box engine with context (tunFd=%d, dns=%s, routes=%s)",
                context.tunFileDescriptor, context.dnsServers, context.routes)
            // Platform-specific preparation (e.g. binary availability check)
            // would go here in a production build.
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Sing-box engine initialization failed")
            Result.failure(
                EngineError(
                    code = EngineError.ErrorCode.UNKNOWN,
                    message = "Failed to initialize Sing-box engine",
                    cause = e
                )
            )
        }
    }

    override suspend fun start(config: ServerConfig): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            Timber.tag(TAG).i("Starting Sing-box engine with config '%s' (%s:%d, protocol=%s)",
                config.name, config.address, config.port, config.protocol)

            _state.value = EngineRuntimeState.Starting

            try {
                // 1. Ensure engine binary is available
                val binaryPath = binaryManager.ensureEngine(EngineType.SingBox).getOrThrow()
                Timber.tag(TAG).i("Binary path: %s", binaryPath)

                // 2. Generate sing-box JSON config
                val jsonConfig = SingboxConfigParser.toSingboxJson(config)
                Timber.tag(TAG).d("Generated Sing-box config:\\n%s", jsonConfig)

                // 3. Write to engine directory
                val engineDir = binaryManager.getEngineDirectory(EngineType.SingBox)
                val tempFile = File(engineDir, "config.json")
                tempFile.writeText(jsonConfig)
                configFile = tempFile
                Timber.tag(TAG).d("Config written to %s", tempFile.absolutePath)

                // 4. Start the sing-box subprocess
                val pb = ProcessBuilder(
                    binaryPath, "run", "-c", tempFile.absolutePath
                )
                pb.redirectErrorStream(true)

                val sbProcess = pb.start()
                process = sbProcess

                // 5. Start process output collector
                startOutputCollector(sbProcess)

                // 6. Give the process a moment to stabilise
                val alive = sbProcess.waitFor(2, TimeUnit.SECONDS)

                if (alive) {
                    // Process is still running after the brief wait
                    _state.value = EngineRuntimeState.Running
                    Timber.tag(TAG).i("Sing-box engine is RUNNING")
                    Result.success(Unit)
                } else {
                    // Process exited immediately — capture its stderr
                    val exitCode = sbProcess.exitValue()
                    val errorOutput = sbProcess.inputStream.bufferedReader().readText()
                    _state.value = EngineRuntimeState.Crashed
                    process = null
                    Timber.tag(TAG).e("Sing-box engine exited immediately (code=%d):\n%s", exitCode, errorOutput)
                    Result.failure(
                        EngineError(
                            code = EngineError.ErrorCode.ENGINE_CRASH,
                            message = "Sing-box process exited with code $exitCode: $errorOutput"
                        )
                    )
                }
            } catch (e: Exception) {
                _state.value = EngineRuntimeState.Crashed
                Timber.tag(TAG).e(e, "Failed to start Sing-box engine")
                cleanup()
                Result.failure(
                    EngineError(
                        code = EngineError.ErrorCode.ENGINE_CRASH,
                        message = "Exception during Sing-box engine start: ${e.message}",
                        cause = e
                    )
                )
            }
        }
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _state.value
            if (current == EngineRuntimeState.Idle || current == EngineRuntimeState.Stopping) {
                Timber.tag(TAG).w("stop() called but engine is already $current — no-op")
                return@withLock Result.success(Unit)
            }

            _state.value = EngineRuntimeState.Stopping
            Timber.tag(TAG).i("Stopping Sing-box engine")

            try {
                destroyProcess()
                cleanup()
                _state.value = EngineRuntimeState.Idle
                Timber.tag(TAG).i("Sing-box engine stopped successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                _state.value = EngineRuntimeState.Crashed
                Timber.tag(TAG).e(e, "Error while stopping Sing-box engine")
                Result.failure(
                    EngineError(
                        code = EngineError.ErrorCode.UNKNOWN,
                        message = "Failed to stop Sing-box engine: ${e.message}",
                        cause = e
                    )
                )
            }
        }
    }

    override suspend fun restart(config: ServerConfig): Result<Unit> {
        Timber.tag(TAG).i("Restarting Sing-box engine")
        stop()
        return start(config)
    }

    override suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        process?.isAlive == true
    }

    override suspend fun destroy() {
        Timber.tag(TAG).i("Destroying Sing-box engine")
        stop()
        // Reset byte counters
        _bytesReceived.value = 0L
        _bytesSent.value = 0L
        Timber.tag(TAG).i("Sing-box engine destroyed")
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Continuously reads the subprocess stdout/stderr and forwards it to Timber.
     */
    private fun startOutputCollector(proc: Process) {
        outputCollector = serviceScope.launch {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    var line: String? = null
                    while (isActive && reader.readLine().also { line = it } != null) {
                        if (line != null) {
                            Timber.tag(TAG).d("[engine] %s", line)
                            _logBuffer.add(line!!)
                        }
                    }
                }
            } catch (_: IOException) { }
        }

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
     * Gracefully terminate the sing-box subprocess.
     * Sends SIGTERM first, then forcibly kills if it doesn't respond.
     */
    private fun destroyProcess() {
        val proc = process ?: return
        if (!proc.isAlive) return

        Timber.tag(TAG).d("Destroying Sing-box process")
        proc.destroy() // SIGTERM
        try {
            val exited = proc.waitFor(3, TimeUnit.SECONDS)
            if (!exited) {
                Timber.tag(TAG).w("Sing-box process did not terminate gracefully — force killing")
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            Timber.tag(TAG).w("Interrupted while waiting for Sing-box process — force killing")
            proc.destroyForcibly()
            Thread.currentThread().interrupt()
        }
        process = null
    }

    /** Remove the temporary config file. */
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
        private const val TAG = "SingboxEngine"
    }
}
