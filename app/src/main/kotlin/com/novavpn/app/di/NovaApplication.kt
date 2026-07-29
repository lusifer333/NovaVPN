package com.novavpn.app.di

import android.app.Application
import android.util.Log
import com.novavpn.app.BuildConfig
import com.novavpn.domain.model.LogLevel
import com.novavpn.logging.NovaLogger
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Timber tree that forwards every log call to [NovaLogger].
 *
 * **Recursion guard**: uses [processing] to detect re-entrant calls.
 * If this tree is already processing a log event (e.g. because NovaLogger
 * itself calls Timber — which it should NOT, but the guard is a safety
 * net), the event is silently dropped.
 *
 * Architecture:
 *   Timber.d() → DebugTree → logcat
 *   Timber.d() → NovaLoggerTree → NovaLogger → buffer + SharedFlow → UI
 *
 * NovaLoggerTree must NEVER call Timber. NovaLogger must NEVER call Timber.
 */
class NovaLoggerTree @Inject constructor(
    private val novaLogger: NovaLogger
) : Timber.Tree() {

    /** Re-entrancy guard — prevents infinite loop if NovaLogger calls Timber. */
    private val processing = ThreadLocal.withInitial { false }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Guard: if already processing, drop to prevent infinite recursion
        if (processing.get()) return
        processing.set(true)
        try {
            val level = when (priority) {
                Log.VERBOSE, Log.DEBUG -> LogLevel.Debug
                Log.INFO -> LogLevel.Info
                Log.WARN -> LogLevel.Warning
                Log.ERROR, Log.ASSERT -> LogLevel.Error
                else -> LogLevel.Debug
            }
            novaLogger.log(level, tag ?: "NovaVPN", message)
        } finally {
            processing.set(false)
        }
    }
}

@HiltAndroidApp
class NovaApplication : Application() {

    @Inject lateinit var novaLoggerTree: NovaLoggerTree

    override fun onCreate() {
        super.onCreate()

        // DebugTree outputs to logcat (Android's native logger)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // NovaLoggerTree forwards Timber calls to NovaLogger's in-app buffer
        // Order: DebugTree first (logcat), then NovaLoggerTree (in-app)
        Timber.plant(novaLoggerTree)
    }
}
