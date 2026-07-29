package com.novavpn.logging

import com.novavpn.domain.model.LogEntry
import com.novavpn.domain.model.LogLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised logging service for NovaVPN.
 *
 * **IMPORTANT**: This class is the FINAL storage layer. It must NEVER call
 * Timber — doing so creates an infinite loop:
 *   Timber → NovaLoggerTree → NovaLogger → Timber → NovaLoggerTree → ...
 *
 * NovaLogger only:
 * 1. Builds a [LogEntry]
 * 2. Appends to the circular buffer
 * 3. Emits to the SharedFlow for the UI layer
 *
 * logcat output is handled separately by Timber.DebugTree (planted in
 * NovaApplication). NovaLoggerTree forwards Timber calls here — do NOT
 * call Timber back from this class.
 */
@Singleton
class NovaLogger @Inject constructor() {

    companion object {
        const val NOVA_TAG = "NovaVPN"
        private const val BUFFER_CAPACITY = 1000
    }

    private val buffer = CircularBuffer<LogEntry>(BUFFER_CAPACITY)

    private val _logFlow = MutableSharedFlow<LogEntry>(
        replay = BUFFER_CAPACITY,
        extraBufferCapacity = 64
    )

    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    // ------------------------------------------------------------------
    // Tagged methods — stores to buffer + emits to flow ONLY
    // NO Timber calls here (prevents StackOverflow recursion)
    // ------------------------------------------------------------------

    fun d(tag: String, message: String) {
        append(buildEntry(LogLevel.Debug, tag, message))
    }

    fun i(tag: String, message: String) {
        append(buildEntry(LogLevel.Info, tag, message))
    }

    fun w(tag: String, message: String) {
        append(buildEntry(LogLevel.Warning, tag, message))
    }

    fun e(tag: String, message: String) {
        append(buildEntry(LogLevel.Error, tag, message))
    }

    /**
     * Generic log method for use by [NovaLoggerTree].
     * Maps [LogLevel] and forwards to the appropriate method.
     */
    fun log(level: LogLevel, tag: String, message: String) {
        append(buildEntry(level, tag, message))
    }

    // ------------------------------------------------------------------
    // Convenience methods (use NOVA_TAG)
    // ------------------------------------------------------------------

    fun d(message: String) = d(NOVA_TAG, message)
    fun i(message: String) = i(NOVA_TAG, message)
    fun w(message: String) = w(NOVA_TAG, message)
    fun e(message: String) = e(NOVA_TAG, message)

    // ------------------------------------------------------------------
    // Export / query
    // ------------------------------------------------------------------

    fun exportAsText(level: LogLevel?): String {
        val snapshot = buffer.toList()
        return snapshot
            .filter { level == null || it.level == level }
            .joinToString("\n") { entry ->
                "[${entry.level.name.uppercase()}] ${entry.tag}: ${entry.message}"
            }
    }

    fun getRecent(count: Int): List<LogEntry> {
        val snapshot = buffer.toList()
        return snapshot.takeLast(count.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun buildEntry(level: LogLevel, tag: String, message: String): LogEntry {
        return LogEntry(
            id = System.nanoTime(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
    }

    private fun append(entry: LogEntry) {
        buffer.add(entry)
        _logFlow.tryEmit(entry)
    }
}

/**
 * A fixed-size circular (ring) buffer backed by [LinkedList].
 * When the buffer reaches [capacity], the oldest element is evicted.
 */
internal class CircularBuffer<T>(private val capacity: Int) {

    private val list = LinkedList<T>()

    fun add(element: T) {
        if (list.size >= capacity) {
            list.removeFirst()
        }
        list.addLast(element)
    }

    fun toList(): List<T> = list.toList()

    val size: Int get() = list.size
}
