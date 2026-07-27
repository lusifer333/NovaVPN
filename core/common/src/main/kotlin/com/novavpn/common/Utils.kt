package com.novavpn.common

import java.text.SimpleDateFormat
import java.util.*

/**
 * Application-wide constants.
 */
object NovaConstants {
    const val TAG = "NovaVPN"
    const val MAX_LOG_BUFFER = 1000
    const val TEST_TIMEOUT_MS = 30_000L
    const val CONNECT_TIMEOUT_MS = 15_000L
    const val SPEED_TEST_TIMEOUT_MS = 10_000L
    const val DNS_TIMEOUT_MS = 5_000L
    const val MAX_RECONNECT_ATTEMPTS = 3
    const val SCORE_HISTORY_SIZE = 50
    const val RECENT_SUCCESS_WINDOW = 10
}

/**
 * Time utilities.
 */
object TimeUtils {
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    private val timeFormat by lazy { SimpleDateFormat("HH:mm:ss", Locale.US) }

    fun now(): Long = System.currentTimeMillis()

    fun formatTimestamp(ms: Long): String = dateFormat.format(Date(ms))

    fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0) append("${minutes}m ")
            append("${seconds}s")
        }
    }
}

/**
 * Byte size formatting.
 */
object FormatUtils {
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun formatBitsPerSecond(bps: Long): String = when {
        bps < 1000 -> "$bps bps"
        bps < 1000_000 -> "${bps / 1000} Kbps"
        bps < 1000_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
        else -> "%.2f Gbps".format(bps / 1_000_000_000.0)
    }

    fun formatLatency(ms: Long): String = if (ms >= 0) "${ms}ms" else "N/A"
}

/**
 * ID generator for domain models.
 */
object IdGenerator {
    private val chars = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun newId(length: Int = 12): String {
        return (1..length).map { chars.random() }.joinToString("")
    }

    fun newSessionId(): String = "nova_${TimeUtils.now()}_${newId(6)}"
}
