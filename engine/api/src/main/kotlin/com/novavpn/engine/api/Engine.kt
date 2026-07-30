package com.novavpn.engine.api

import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.ServerConfig
import kotlinx.coroutines.flow.StateFlow
/**
 * Abstract VPN engine interface.
 * All engine implementations (Xray, Sing-box, etc.) must implement this.
 * This is the core abstraction that makes the system engine-agnostic.
 */
interface Engine {

    /** The type of this engine. */
    val type: EngineType

    /** Current runtime state (observed). */
    val state: StateFlow<EngineRuntimeState>

    /** Total bytes transferred since last start. */
    val bytesReceived: StateFlow<Long>
    val bytesSent: StateFlow<Long>

    /**
     * Initialize the engine with platform-specific context.
     * Called once during app startup.
     */
    suspend fun initialize(context: EngineContext): Result<Unit>

    /**
     * Start the VPN tunnel with the given server configuration.
     * Returns success once the engine process is verified as running.
     */
    suspend fun start(config: ServerConfig): Result<Unit>

    /**
     * Stop the VPN tunnel gracefully.
     */
    suspend fun stop(): Result<Unit>

    /**
     * Restart with a new configuration (connect switch).
     */
    suspend fun restart(config: ServerConfig): Result<Unit>

    /**
     * Check if the engine process is currently alive.
     */
    suspend fun isAlive(): Boolean

    /**
     * Release all resources. Called on app shutdown.
     */
    suspend fun destroy()
}

/**
 * Platform-specific context passed to engines on init.
 * Android: contains VpnService.Builder, tun interface, etc.
 */
interface EngineContext {
    val isVpnPermissionGranted: Boolean
    val tunFileDescriptor: Int
    val dnsServers: List<String>
    val routes: List<String>
}

/**
 * Result of engine operations.
 */
sealed class EngineResult {
    data object Success : EngineResult()
    data class Failure(val reason: EngineError) : EngineResult()
}

/**
 * Structured engine error information.
 */
data class EngineError(
    val code: ErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Throwable(message, cause) {
    enum class ErrorCode {
        CONFIG_PARSE_FAILURE,
        ENGINE_BINARY_MISSING,
        ENGINE_CRASH,
        TIMEOUT,
        PERMISSION_DENIED,
        TUN_SETUP_FAILED,
        UNKNOWN
    }
}

/**
 * Manager that holds and routes to the active engine.
 */
interface EngineManager {
    val activeEngine: Engine?
    val availableEngines: List<EngineType>

    suspend fun selectEngine(type: EngineType)
    suspend fun getEngine(type: EngineType): Engine?
}
