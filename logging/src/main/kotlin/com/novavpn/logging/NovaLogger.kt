package com.novavpn.logging

import com.novavpn.domain.model.LogEntry
import com.novavpn.domain.model.LogLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised logging service for NovaVPN.
 *
 * Internally delegates to [Timber] for platform output, maintains an in-memory
 * circular buffer (max [BUFFER_CAPACITY] entries), and emits every entry to a
 * [SharedFlow] so that the UI layer can observe logs in real time.
 */
@Singleton
class NovaLogger @Inject constructor() {

    companion object {
        /** Default tag used when none is explicitly supplied. */
        const val NOVA_TAG = "NovaVPN"

        /** Maximum number of entries kept in the circular buffer. */
        private const val BUFFER_CAPACITY = 1000
    }

    // Thread-safe buffer: all mutations happen on the coroutine dispatcher
    // that calls into the public methods. For a production app consider an
    // actual locking mechanism; the circular buffer here is simple and
    // sufficient for single-threaded or co-operative access.
    private val buffer = CircularBuffer<LogEntry>(BUFFER_CAPACITY)

    private val _logFlow = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = 64
    )

    /** Hot stream of all log entries, observable from the UI layer. */
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    // ------------------------------------------------------------------
    // Tagged methods
    // ------------------------------------------------------------------

    fun d(tag: String, message: String) {
        val entry = buildEntry(LogLevel.Debug, tag, message)
        Timber.tag(tag).d(message)
        append(entry)
    }

    fun i(tag: String, message: String) {
        val entry = buildEntry(LogLevel.Info, tag, message)
        Timber.tag(tag).i(message)
        append(entry)
    }

    fun w(tag: String, message: String) {
        val entry = buildEntry(LogLevel.Warning, tag, message)
        Timber.tag(tag).w(message)
        append(entry)
    }

    fun e(tag: String, message: String) {
        val entry = buildEntry(LogLevel.Error, tag, message)
        Timber.tag(tag).e(message)
        append(entry)
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

    /**
     * Return all buffered log entries as a single newline-separated string,
     * optionally filtered by [level].
     *
     * Format per line: `[LEVEL] tag: message`
     */
    fun exportAsText(level: LogLevel?): String {
        val snapshot = buffer.toList()
        return snapshot
            .filter { level == null || it.level == level }
            .joinToString("\n") { entry ->
                "[${entry.level.name.uppercase()}] ${entry.tag}: ${entry.message}"
            }
    }

    /**
     * Return the most recent [count] log entries from the buffer.
     */
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
        // Offer to flow; if the buffer is full the emission is silently dropped.
        _logFlow.tryEmit(entry)
    }
}

/**
 * A fixed-size circular (ring) buffer backed by [LinkedList].
 * When the buffer reaches [capacity], the oldest element is evicted
 * before adding a new one.
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
