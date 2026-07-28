package com.novavpn.app.service

import android.content.Context
import com.novavpn.domain.model.EngineType
import com.novavpn.engine.api.BinaryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [BinaryManager].
 *
 * Engine binaries are shipped inside the APK under
 * `src/main/assets/engines/<engine>/<arch>/<binary>` and extracted
 * to the app's private files directory on first launch.
 */
@Singleton
class AndroidBinaryManager @Inject constructor(
    @ApplicationContext private val context: Context
) : BinaryManager {

    override val engineDirectory: File
        get() = File(context.filesDir, ENGINE_ROOT)

    override fun getEnginePath(type: EngineType): String? {
        val binary = binaryFile(type)
        return if (binary.exists() && binary.canExecute()) binary.absolutePath else null
    }

    override fun getEngineVersion(type: EngineType): String? {
        val file = File(engineDirectory, "${type.name.lowercase()}.version")
        return if (file.exists()) file.readText().trim() else null
    }

    override suspend fun ensureEngine(type: EngineType): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val existing = getEnginePath(type)
            if (existing != null) return@runCatching existing

            val binFile = binaryFile(type)
            binFile.parentFile?.mkdirs()

            val assetPath = assetsPath(type)
            Timber.tag(TAG).i("Extracting engine binary: $assetPath → ${binFile.absolutePath}")

            try {
                context.assets.open(assetPath).use { input ->
                    binFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                // Binary not bundled — create a placeholder log entry
                Timber.tag(TAG).w("Engine binary not found in assets at '$assetPath'. " +
                    "Download Xray from https://github.com/XTLS/Xray-core/releases " +
                    "and place at app/src/main/assets/$assetPath")
                binFile.writeText("#!/system/bin/sh\necho \"Engine not bundled: $assetPath\"\nexit 1")
            }

            binFile.setExecutable(true)
            Timber.tag(TAG).i("Engine binary ready: ${binFile.absolutePath}")
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
