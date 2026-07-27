package com.novavpn.engine.api

import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.SettingsRepository
import dagger.Lazy
import dagger.MapKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Map key annotation for binding [Engine] implementations by [EngineType].
 *
 * Used with Dagger multibindings so that each engine module can contribute
 * its implementation to the [EngineManagerImpl] map via `@Binds @IntoMap`.
 *
 * Usage:
 * ```
 * @Binds @IntoMap @EngineKey(EngineType.Xray)
 * abstract fun bindXrayEngine(engine: XrayEngine): Engine
 * ```
 */
@MapKey
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class EngineKey(val type: EngineType)

/**
 * Default [EngineManager] implementation backed by Dagger multibindings.
 *
 * All available [Engine] implementations are injected as a [Map] keyed by
 * [EngineType]. The active engine is selected from user preferences on first
 * access and can be switched at runtime via [selectEngine].
 *
 * Lifecycle calls — [start], [stop], [restart], [destroy] — are
 * transparently delegated to whichever engine is currently active.
 *
 * Engines are lazily resolved (via [dagger.Lazy]) so an implementation is
 * only instantiated when it is first selected or accessed.
 */
@Singleton
class EngineManagerImpl @Inject constructor(
    /**
     * Multibound map of all available engines.
     *
     * Each engine module contributes its implementation via `@Binds @IntoMap
     * @EngineKey(...)`. Dagger collects them into this map, wrapping each
     * value in a [dagger.Lazy] so engines are created on demand.
     *
     * `@JvmSuppressWildcards` is required on all three type arguments to
     * prevent Kotlin from emitting `? extends` / `? super` wildcards, which
     * would break Dagger's exact-type lookup for `Map` multibindings.
     */
    private val engines: Map<
        @JvmSuppressWildcards EngineType,
        @JvmSuppressWildcards Lazy<@JvmSuppressWildcards Engine>
    >,
    /** Used to persist and recall the user's preferred engine. */
    private val settingsRepository: SettingsRepository
) : EngineManager {

    /** All [EngineType] keys for which an implementation was contributed. */
    override val availableEngines: List<EngineType>
        get() = engines.keys.toList()

    /**
     * Backing state for [activeEngine].
     *
     * On first access the stored preference ([SettingsRepository.get]) is
     * consulted and the corresponding engine is activated.
     */
    private val _activeEngine = MutableStateFlow<Engine?>(null)

    /** The currently active engine, or `null` if none has been selected. */
    override val activeEngine: Engine?
        get() = _activeEngine.value

    // ------------------------------------------------------------------
    // EngineManager interface
    // ------------------------------------------------------------------

    override suspend fun selectEngine(type: EngineType) {
        val engine = engines[type]?.get()
        if (engine != null) {
            _activeEngine.value = engine
            settingsRepository.setEngine(type)
            Timber.tag(TAG).i("Active engine switched to: ${type.displayName}")
        } else {
            Timber.tag(TAG).w("Engine type $type is not available in the multibound map")
        }
    }

    override suspend fun getEngine(type: EngineType): Engine? {
        return engines[type]?.get()
    }

    // ------------------------------------------------------------------
    // Lifecycle delegation
    // ------------------------------------------------------------------

    /**
     * Initialize the active engine with the given platform context.
     * Called once during app startup after the engine has been selected.
     */
    suspend fun initialize(context: EngineContext): Result<Unit> {
        val engine = activeEngine
        return if (engine != null) {
            engine.initialize(context)
        } else {
            Result.failure(
                EngineError(
                    code = EngineError.ErrorCode.UNKNOWN,
                    message = "No active engine selected — call selectEngine first"
                )
            )
        }
    }

    /**
     * Start the VPN tunnel via the active engine.
     *
     * Delegates to [Engine.start] with the provided [ServerConfig].
     */
    suspend fun start(config: ServerConfig): Result<Unit> {
        val engine = activeEngine
        Timber.tag(TAG).i("Delegating start() to ${engine?.type?.displayName ?: "no engine"}")
        return if (engine != null) {
            engine.start(config)
        } else {
            Result.failure(
                EngineError(
                    code = EngineError.ErrorCode.UNKNOWN,
                    message = "No active engine selected"
                )
            )
        }
    }

    /**
     * Stop the active VPN tunnel gracefully.
     *
     * Delegates to [Engine.stop].
     */
    suspend fun stop(): Result<Unit> {
        val engine = activeEngine
        Timber.tag(TAG).i("Delegating stop() to ${engine?.type?.displayName ?: "no engine"}")
        return if (engine != null) {
            engine.stop()
        } else {
            Result.failure(
                EngineError(
                    code = EngineError.ErrorCode.UNKNOWN,
                    message = "No active engine selected"
                )
            )
        }
    }

    /**
     * Restart the active engine with a new configuration.
     *
     * Delegates to [Engine.restart].
     */
    suspend fun restart(config: ServerConfig): Result<Unit> {
        val engine = activeEngine
        Timber.tag(TAG).i("Delegating restart() to ${engine?.type?.displayName ?: "no engine"}")
        return if (engine != null) {
            engine.restart(config)
        } else {
            Result.failure(
                EngineError(
                    code = EngineError.ErrorCode.UNKNOWN,
                    message = "No active engine selected"
                )
            )
        }
    }

    /**
     * Check whether the active engine process is still alive.
     */
    suspend fun isAlive(): Boolean {
        return activeEngine?.isAlive() ?: false
    }

    /**
     * Release all resources held by the active engine.
     */
    suspend fun destroy() {
        activeEngine?.destroy()
        _activeEngine.value = null
        Timber.tag(TAG).i("Engine manager — all engines destroyed")
    }

    companion object {
        private const val TAG = "EngineManager"
    }
}
