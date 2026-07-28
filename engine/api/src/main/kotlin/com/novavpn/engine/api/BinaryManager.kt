package com.novavpn.engine.api

import com.novavpn.domain.model.EngineType
import java.io.File

/**
 * Manages native VPN engine binaries (Xray, Sing-box).
 *
 * Responsibilities:
 * - Check binary availability
 * - Extract from APK assets to app-private storage
 * - Provide executable path for engine processes
 * - Version tracking
 *
 * Implementations are platform-specific (Android copies from assets,
 * desktop uses system PATH or bundled binaries).
 */
interface BinaryManager {

    /** Root directory where all engine binaries are stored. */
    val engineDirectory: File

    /**
     * Get the absolute path to an engine's executable binary, or null
     * if the binary has not been deployed yet.
     */
    fun getEnginePath(type: EngineType): String?

    /**
     * Get the engine version string, or null if unknown.
     */
    fun getEngineVersion(type: EngineType): String?

    /**
     * Ensure the binary for the given [type] is available on disk,
     * extracting it from APK assets if necessary.
     *
     * @return the absolute path to the binary on success.
     */
    suspend fun ensureEngine(type: EngineType): Result<String>

    /**
     * Directory where an engine can write its runtime files
     * (configs, geoip databases, etc.).
     */
    fun getEngineDirectory(type: EngineType): File
}
