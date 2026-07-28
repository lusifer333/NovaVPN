package com.novavpn.subscription.importer

import com.novavpn.domain.model.ServerConfig
import com.novavpn.subscription.parser.SubscriptionParser
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports [ServerConfig] entries from subscription sources.
 *
 * Supports three import modes:
 * - [importFromUrl]: HTTP(S) GET with auto-detected encoding
 * - [importFromClipboard]: raw text pasted by the user
 * - [importFromFile]: content read from a local file
 */
@Singleton
class SubscriptionImporter @Inject constructor(
    private val parser: SubscriptionParser
) {

    companion object {
        private const val TAG = "SubscriptionImporter"
        private const val REQUEST_TIMEOUT_MS = 15_000
    }

    /**
     * Fetch subscription content from [url] over HTTP(S), auto-detect the
     * encoding from the Content-Type header (or fall back to UTF-8), and
     * parse it into a list of [ServerConfig].
     */
    suspend fun importFromUrl(url: String): List<ServerConfig> {
        Timber.tag(TAG).d("importFromUrl: fetching %s", url)

        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "NovaVPN/1.0")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Connection", "close")

            val responseCode = connection.responseCode
            Timber.tag(TAG).d("importFromUrl: HTTP %d for %s", responseCode, url)

            if (responseCode !in 200..399) {
                Timber.tag(TAG).w("importFromUrl: HTTP %d for %s", responseCode, url)
                return emptyList()
            }

            // Read the full response
            val inputStream = connection.inputStream ?: return emptyList()
            val text = inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            Timber.tag(TAG).d("importFromUrl: received %d chars", text.length)

            if (text.isBlank()) {
                Timber.tag(TAG).w("importFromUrl: empty response from %s", url)
                return emptyList()
            }

            val configs = parser.parse(text)
            Timber.tag(TAG).d("importFromUrl: parsed %d server configs", configs.size)
            configs
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "importFromUrl: failed to fetch %s", url)
            emptyList()
        }
    }

    /**
     * Parse subscription content from clipboard text.
     */
    suspend fun importFromClipboard(text: String): List<ServerConfig> {
        Timber.tag(TAG).d("importFromClipboard: %d chars", text.length)

        val configs = parser.parse(text)
        Timber.tag(TAG).d("importFromClipboard: parsed %d server configs", configs.size)
        return configs
    }

    /**
     * Parse subscription content from a file string.
     */
    suspend fun importFromFile(content: String): List<ServerConfig> {
        Timber.tag(TAG).d("importFromFile: %d chars", content.length)

        val configs = parser.parse(content)
        Timber.tag(TAG).d("importFromFile: parsed %d server configs", configs.size)
        return configs
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Extract the charset from a Content-Type header value.
     * Examples: "text/plain; charset=utf-8" → "UTF-8"
     *           "application/octet-stream" → "UTF-8"
     */
    private fun extractCharset(contentType: String): String {
        if (contentType.isBlank()) return "UTF-8"

        for (part in contentType.split(";")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("charset", ignoreCase = true)) {
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx >= 0) {
                    val charset = trimmed.substring(eqIdx + 1).trim().uppercase()
                    if (charset.isNotBlank()) return charset
                }
            }
        }
        return "UTF-8"
    }
}
