package com.novavpn.app.service

import android.content.Context
import com.novavpn.domain.model.EngineType
import com.novavpn.engine.api.BinaryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [BinaryManager].
 *
 * Engine binaries must be placed at:
 *   app/src/main/assets/engines/<engine>/<arch>/<binary>
 *
 * Run `scripts/download-engines.sh` to download them before building the APK.
 */
@Singleton
class AndroidBinaryManager @Inject constructor(
    @ApplicationContext private val context: Context
) : BinaryManager {

    override val engineDirectory: File
        get() = File(context.filesDir, ENGINE_ROOT)

    override fun getEnginePath(type: EngineType): String? {
        val bin = binaryFile(type)
        if (bin.exists() && bin.canExecute()) {
            Timber.tag(TAG).d("Engine binary found: %s (%d bytes)", bin.absolutePath, bin.length())
            return bin.absolutePath
        }
        // Check assets for embedded binary that hasn't been extracted yet
        val assetPath = assetsPath(type)
        try {
            context.assets.open(assetPath).use { Timber.tag(TAG).d("Binary exists in assets: %s", assetPath) }
        } catch (_: FileNotFoundException) {
            Timber.tag(TAG).w("Binary NOT in assets at: %s", assetPath)
        }
        return null
    }

    override fun getEngineVersion(type: EngineType): String? {
        // Try version file first
        val verFile = File(engineDirectory, "${type.name.lowercase()}.version")
        if (verFile.exists()) return verFile.readText().trim()

        // Try reading from binary via --version
        val path = getEnginePath(type) ?: return null
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf(path, "--version"))
            val output = proc.inputStream.bufferedReader().readText().lines().firstOrNull()?.trim()
            proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            output
        } catch (e: Exception) {
            Timber.tag(TAG).d("Could not read engine version: %s", e.message)
            null
        }
    }

    override suspend fun ensureEngine(type: EngineType): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            // Check if already extracted
            val existing = getEnginePath(type)
            if (existing != null) {
                Timber.tag(TAG).i("Engine already available: %s", existing)
                return@runCatching existing
            }

            val binFile = binaryFile(type)
            binFile.parentFile?.mkdirs()

            val assetPath = assetsPath(type)
            Timber.tag(TAG).i("Extracting engine binary from assets: %s", assetPath)

            // Try to extract from APK assets
            val inputStream = try {
                context.assets.open(assetPath)
            } catch (e: FileNotFoundException) {
                val msg = buildString {
                    appendLine("Engine binary not found!")
                    appendLine("  Path expected: app/src/main/assets/$assetPath")
                    appendLine("  Run: scripts/download-engines.sh")
                    appendLine("  Or download manually from:")
                    appendLine("    Xray:    https://github.com/XTLS/Xray-core/releases")
                    appendLine("    Sing-box: https://github.com/SagerNet/sing-box/releases")
                }
                throw FileNotFoundException(msg)
            }

            inputStream.use { input ->
                binFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Set executable permission
            binFile.setExecutable(true)
            if (!binFile.canExecute()) {
                throw SecurityException("Cannot set executable permission on ${binFile.absolutePath}")
            }

            val sizeKb = binFile.length() / 1024
            Timber.tag(TAG).i("Engine binary extracted: %s (%d KB)", binFile.absolutePath, sizeKb)
            binFile.absolutePath
        }
    }

    override fun getEngineDirectory(type: EngineType): File {
        val dir = File(engineDirectory, type.name.lowercase())
        dir.mkdirs()
        return dir
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private fun binaryFile(type: EngineType): File {
        val arch = archName()
        return File(engineDirectory, "${type.name.lowercase()}/$arch/${type.name.lowercase()}")
    }

    private fun assetsPath(type: EngineType): String {
        val arch = archName()
        return "engines/${type.name.lowercase()}/$arch/${type.name.lowercase()}"
    }

    private fun archName(): String {
        val abis = android.os.Build.SUPPORTED_ABIS
        return when {
            abis.any { it.startsWith("arm64") } -> "arm64-v8a"
            abis.any { it.startsWith("x86_64") } -> "x86_64"
            abis.any { it.startsWith("x86") } -> "x86"
            abis.any { it.startsWith("armeabi") } -> "armeabi-v7a"
            else -> "arm64-v8a"
        }
    }

    companion object {
        private const val TAG = "BinaryManager"
        private const val ENGINE_ROOT = "novavpn/engines"
    }
}
