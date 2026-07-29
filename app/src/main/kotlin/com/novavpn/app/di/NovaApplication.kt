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
 * This makes ALL Timber.tag(...).i/d/w/e(...) calls visible
 * in the in-app Logs screen via NovaLogger's SharedFlow.
 */
class NovaLoggerTree @Inject constructor(
    private val novaLogger: NovaLogger
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE, Log.DEBUG -> LogLevel.Debug
            Log.INFO -> LogLevel.Info
            Log.WARN -> LogLevel.Warning
            Log.ERROR, Log.ASSERT -> LogLevel.Error
            else -> LogLevel.Debug
        }
        novaLogger.log(level, tag ?: "NovaVPN", message)
    }
}

@HiltAndroidApp
class NovaApplication : Application() {

    @Inject lateinit var novaLoggerTree: NovaLoggerTree

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Plant the NovaLogger tree so ALL Timber calls appear in-app
        Timber.plant(novaLoggerTree)
    }
}
