package com.novavpn.engine.api

import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [EngineManager] implementation.
 *
 * Engines are injected directly and registered in a simple map.
 */
@Singleton
class EngineManagerImpl @Inject constructor(
    /** Used to persist and recall the user's preferred engine. */
    private val settingsRepository: SettingsRepository
) : EngineManager {

    private val engineMap = mutableMapOf<EngineType, Engine>()

    override val availableEngines: List<EngineType>
        get() = engineMap.keys.toList()

    private val _activeEngine = MutableStateFlow<Engine?>(null)

    override val activeEngine: Engine?
        get() = _activeEngine.value

    /** Register an engine implementation. Called during app startup. */
    fun register(type: EngineType, engine: Engine) {
        engineMap[type] = engine
        Timber.tag(TAG).i("Registered engine: ${type.displayName}")
    }

    /**
     * Select an engine synchronously (does not persist to settings).
     * Used during DI initialization before coroutines are available.
     */
    fun selectEngineSync(type: EngineType) {
        val engine = engineMap[type]
        if (engine != null) {
            _activeEngine.value = engine
            Timber.tag(TAG).i("Active engine (sync): ${type.displayName}")
        } else {
            Timber.tag(TAG).w("Engine type $type is not registered")
        }
    }

    override suspend fun selectEngine(type: EngineType) {
        val engine = engineMap[type]
        if (engine != null) {
            _activeEngine.value = engine
            settingsRepository.setEngine(type)
            Timber.tag(TAG).i("Active engine switched to: ${type.displayName}")
        } else {
            Timber.tag(TAG).w("Engine type $type is not registered")
        }
    }

    override suspend fun getEngine(type: EngineType): Engine? {
        return engineMap[type]
    }

    /** Initialize the active engine. */
    suspend fun initialize(context: EngineContext): Result<Unit> {
        val engine = activeEngine
        return if (engine != null) {
            engine.initialize(context)
        } else {
            Result.failure(EngineError(EngineError.ErrorCode.UNKNOWN, "No active engine selected"))
        }
    }

    /** Start the VPN tunnel via the active engine. */
    suspend fun start(config: ServerConfig): Result<Unit> {
        val engine = activeEngine
        Timber.tag(TAG).i("Delegating start() to ${engine?.type?.displayName ?: "no engine"}")
        return if (engine != null) {
            engine.start(config)
        } else {
            Result.failure(EngineError(EngineError.ErrorCode.UNKNOWN, "No active engine selected"))
        }
    }

    /** Stop the active VPN tunnel gracefully. */
    suspend fun stop(): Result<Unit> {
        val engine = activeEngine
        Timber.tag(TAG).i("Delegating stop() to ${engine?.type?.displayName ?: "no engine"}")
        return if (engine != null) {
            engine.stop()
        } else {
            Result.failure(EngineError(EngineError.ErrorCode.UNKNOWN, "No active engine selected"))
        }
    }

    /** Restart the active engine with a new configuration. */
    suspend fun restart(config: ServerConfig): Result<Unit> {
        val engine = activeEngine
        return if (engine != null) {
            engine.restart(config)
        } else {
            Result.failure(EngineError(EngineError.ErrorCode.UNKNOWN, "No active engine selected"))
        }
    }

    /** Check whether the active engine process is still alive. */
    suspend fun isAlive(): Boolean {
        return activeEngine?.isAlive() ?: false
    }

    /** Release all resources held by the active engine. */
    suspend fun destroy() {
        activeEngine?.destroy()
        _activeEngine.value = null
        Timber.tag(TAG).i("Engine manager — all engines destroyed")
    }

    companion object {
        private const val TAG = "EngineManager"
    }
}
