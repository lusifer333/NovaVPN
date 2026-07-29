package com.novavpn.app.service

import android.content.Context
import com.novavpn.domain.model.EngineType
import com.novavpn.engine.api.BinaryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [BinaryManager].
 *
 * Engine binaries are bundled in jniLibs/<abi>/lib<engine>.so
 * which the system extracts to nativeLibraryDir with proper SELinux context
 * and executable permissions during APK installation.
 *
 * Fallback: assets/engines/<engine>/<arch>/<binary> for development builds.
 */
@Singleton
class AndroidBinaryManager @Inject constructor(
    @ApplicationContext private val context: Context
) : BinaryManager {

    override val engineDirectory: File
        get() = File(context.filesDir, ENGINE_ROOT)

    override fun getEnginePath(type: EngineType): String? {
        // 1. Check native library path first (jniLibs — system-installed)
        val nativePath = nativeBinaryFile(type)
        if (nativePath.exists() && nativePath.canExecute()) {
            Timber.tag(TAG).d("Engine binary found in native lib: %s (%d bytes)",
                nativePath.absolutePath, nativePath.length())
            return nativePath.absolutePath
        }

        // 2. Check engine directory (previously extracted)
        val extractedPath = binaryFile(type)
        if (extractedPath.exists() && extractedPath.canExecute()) {
            Timber.tag(TAG).d("Engine binary found in engine dir: %s", extractedPath.absolutePath)
            return extractedPath.absolutePath
        }

        return null
    }

    override fun getEngineVersion(type: EngineType): String? {
        val verFile = File(engineDirectory, "${type.name.lowercase()}.version")
        if (verFile.exists()) return verFile.readText().trim()

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

    override suspend fun ensureEngine(type: EngineType): Result<String> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            // 1. Check native library path first (best — proper SELinux context)
            //    IMPORTANT: execute directly from nativeLibraryDir, NEVER copy to
            //    filesDir. SELinux policy (app_data_file) forbids execution from
            //    app data directories on Android 10+ — only apk_data_file in
            //    nativeLibraryDir has the execmem/execmod permission.
            val nativePath = nativeBinaryFile(type)
            if (nativePath.exists() && nativePath.canExecute()) {
                Timber.tag(TAG).i("Engine ready from native lib: %s (%d bytes)",
                    nativePath.absolutePath, nativePath.length())
                return@runCatching nativePath.absolutePath
            }

            // 2. Native lib exists but not executable — warn (SELinux issue)
            if (nativePath.exists()) {
                Timber.tag(TAG).w("Native lib exists but NOT executable: %s — SELinux?",
                    nativePath.absolutePath)
            } else {
                Timber.tag(TAG).w("Native lib NOT FOUND: %s",
                    nativePath.absolutePath)
            }

            // 3. Fallback: extract from assets to engine directory
            //    NOTE: on Android 10+ SELinux (app_data_file) may still block
            //    execution from filesDir even after chmod.
            try {
                return@runCatching extractFromAssets(type)
            } catch (e: FileNotFoundException) {
                Timber.tag(TAG).w("Assets fallback failed: %s", e.message)
            }

            // 4. Last resort: extract .so from APK zip directly
            try {
                return@runCatching extractFromApkZip(type)
            } catch (e: FileNotFoundException) {
                Timber.tag(TAG).w("APK zip fallback failed: %s", e.message)
            }

            // Nothing worked — throw a comprehensive error
            throw FileNotFoundException(buildString {
                appendLine("Engine binary not found — all methods exhausted!")
                appendLine("  1. nativeLibraryDir: ${nativePath.absolutePath}")
                appendLine("  2. Assets: ${assetsPath(type)}")
                appendLine("  3. APK zip: lib/$abi/lib${type.name.lowercase()}.so")
                appendLine("  Run: scripts/download-engines.sh")
            })
        }
    }

    /**
     * Fourth fallback: read .so file directly from the installed APK zip.
     * This works even when extractNativeLibs="false" (AGP default).
     *
     * APK internal path: lib/<abi>/lib<name>.so
     */
    private fun extractFromApkZip(type: EngineType): String {
        val apkFile = File(context.applicationInfo.sourceDir)
        val libName = "${libPrefix}${type.name.lowercase()}.so"
        val entryPath = "lib/$abi/$libName"
        val target = binaryFile(type)
        target.parentFile?.mkdirs()

        Timber.tag(TAG).i("Extracting engine from APK zip: %s!/%s", apkFile.name, entryPath)

        ZipFile(apkFile).use { zip ->
            val entry: ZipEntry = zip.getEntry(entryPath)
                ?: throw FileNotFoundException(buildString {
                    appendLine("Engine binary not found in APK zip!")
                    appendLine("  APK: ${apkFile.absolutePath}")
                    appendLine("  Entry: $entryPath")
                    appendLine("  Also tried nativeLibraryDir: ${context.applicationInfo.nativeLibraryDir}/$libName")
                    appendLine("  Run: scripts/download-engines.sh")
                })

            zip.getInputStream(entry).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }

        target.setExecutable(true, false)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", target.absolutePath))
                .waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) { }

        val sizeKb = target.length() / 1024
        Timber.tag(TAG).i("Engine extracted from APK zip: %s (%d KB)", target.absolutePath, sizeKb)
        return target.absolutePath
    }

    override fun getEngineDirectory(type: EngineType): File {
        val dir = File(engineDirectory, type.name.lowercase())
        dir.mkdirs()
        return dir
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private val abi: String by lazy {
        val abis = android.os.Build.SUPPORTED_ABIS
        when {
            abis.any { it.startsWith("arm64") } -> "arm64-v8a"
            abis.any { it.startsWith("x86_64") } -> "x86_64"
            abis.any { it.startsWith("x86") } -> "x86"
            abis.any { it.startsWith("armeabi") } -> "armeabi-v7a"
            else -> "arm64-v8a"
        }
    }

    private val libPrefix: String
        get() = "lib"

    private fun nativeBinaryFile(type: EngineType): File {
        // jniLibs/<abi>/lib<engine>.so → nativeLibraryDir/lib<engine>.so
        val libName = "${libPrefix}${type.name.lowercase()}.so"
        return File(context.applicationInfo.nativeLibraryDir, libName)
    }

    private fun binaryFile(type: EngineType): File {
        return File(engineDirectory, "${type.name.lowercase()}/$abi/${type.name.lowercase()}")
    }

    private fun extractFromAssets(type: EngineType): String {
        val assetPath = assetsPath(type)
        val binFile = binaryFile(type)
        binFile.parentFile?.mkdirs()

        Timber.tag(TAG).i("Extracting engine from assets: %s", assetPath)
        val inputStream = try {
            context.assets.open(assetPath)
        } catch (e: FileNotFoundException) {
            val msg = buildString {
                appendLine("Engine binary not found in assets or native libs!")
                appendLine("  Native lib expected: app/src/main/jniLibs/$abi/lib${type.name.lowercase()}.so")
                appendLine("  Assets fallback: app/src/main/assets/$assetPath")
                appendLine("  Run: scripts/download-engines.sh")
            }
            throw FileNotFoundException(msg)
        }

        inputStream.use { input ->
            binFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Set executable permission
        binFile.setExecutable(true, false)
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", binFile.absolutePath))
                .waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) { }

        val sizeKb = binFile.length() / 1024
        Timber.tag(TAG).i("Engine extracted from assets: %s (%d KB)", binFile.absolutePath, sizeKb)
        return binFile.absolutePath
    }

    private fun assetsPath(type: EngineType): String {
        return "engines/${type.name.lowercase()}/$abi/${type.name.lowercase()}"
    }

    companion object {
        private const val TAG = "BinaryManager"
        private const val ENGINE_ROOT = "novavpn/engines"
    }
}