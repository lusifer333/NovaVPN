package com.novavpn.subscription.importer

import com.novavpn.domain.model.ServerConfig
import com.novavpn.subscription.parser.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionImporter @Inject constructor(
    private val parser: SubscriptionParser
) {
    companion object {
        private const val TAG = "SubscriptionImporter"
        private const val REQUEST_TIMEOUT_MS = 15_000
    }

    /**
     * Fetch subscription content from [url] and parse into server configs.
     * Network IO runs on [Dispatchers.IO].
     */
    suspend fun importFromUrl(url: String): List<ServerConfig> {
        Timber.tag(TAG).d("importFromUrl: fetching %s", url)

        // Fetch URL content on IO dispatcher (includes DNS resolution)
        val rawText: String = withContext(Dispatchers.IO) {
            // Pre-resolve DNS for diagnostics
            try {
                val uri = java.net.URI(url)
                val host = uri.host ?: "unknown"
                Timber.tag(TAG).d("Resolving DNS for: %s", host)
                val addresses = java.net.InetAddress.getAllByName(host)
                Timber.tag(TAG).d("DNS resolved: %s → %d address(es)", host, addresses.size)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "DNS resolution FAILED — will try fetch anyway")
                // Don't return — let the fetch fail with its own error
            }

            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = REQUEST_TIMEOUT_MS
                connection.readTimeout = REQUEST_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "NovaVPN/1.0")
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Connection", "close")

                val code = connection.responseCode
                Timber.tag(TAG).d("importFromUrl: HTTP %d", code)

                if (code !in 200..399) {
                    connection.disconnect()
                    throw RuntimeException("HTTP $code for $url")
                }

                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                text
            } catch (e: java.net.UnknownHostException) {
                Timber.tag(TAG).e("DNS lookup failed for URL: %s — %s", url, e.message)
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "importFromUrl: fetch failed: %s", url)
                throw e
            }
        }

        // Parse on CPU dispatcher: parser.parse can be heavy for huge
        // subscriptions (a 2000-server base64 blob) and must never run on
        // the caller's main thread.
        Timber.tag(TAG).d("importFromUrl: received %d chars", rawText.length)
        if (rawText.isBlank()) {
            Timber.tag(TAG).w("importFromUrl: empty response")
            return emptyList()
        }

        val configs = withContext(Dispatchers.Default) { parser.parse(rawText) }
        Timber.tag(TAG).d("importFromUrl: parsed %d configs", configs.size)
        return configs
    }

    suspend fun importFromClipboard(text: String): List<ServerConfig> {
        Timber.tag(TAG).d("importFromClipboard: %d chars", text.length)
        val configs = withContext(Dispatchers.Default) { parser.parse(text) }
        Timber.tag(TAG).d("importFromClipboard: parsed %d configs", configs.size)
        return configs
    }

    suspend fun importFromFile(content: String): List<ServerConfig> {
        Timber.tag(TAG).d("importFromFile: %d chars", content.length)
        val configs = withContext(Dispatchers.Default) { parser.parse(content) }
        Timber.tag(TAG).d("importFromFile: parsed %d configs", configs.size)
        return configs
    }
}
