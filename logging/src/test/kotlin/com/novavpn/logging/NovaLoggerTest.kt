package com.novavpn.logging

import com.novavpn.domain.model.LogLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for [NovaLogger].
 *
 * Key contract: NovaLogger must NEVER call Timber. It must only:
 * 1. Store to circular buffer
 * 2. Emit to SharedFlow
 *
 * These tests verify the buffer + flow work correctly and that there
 * is no recursive behaviour (which would cause StackOverflowError).
 */
class NovaLoggerTest {

    private val logger = NovaLogger()

    @Test
    fun `log one message is stored in buffer`() {
        logger.i("TestTag", "hello world")
        val recent = logger.getRecent(10)
        assertEquals("Should have 1 entry", 1, recent.size)
        assertEquals("TestTag", recent[0].tag)
        assertEquals("hello world", recent[0].message)
        assertEquals(LogLevel.Info, recent[0].level)
    }

    @Test
    fun `log multiple messages all appear in order`() {
        logger.d("T1", "first")
        logger.i("T2", "second")
        logger.w("T3", "third")
        logger.e("T4", "fourth")

        val all = logger.getRecent(10)
        assertEquals(4, all.size)
        assertEquals("first", all[0].message)
        assertEquals("second", all[1].message)
        assertEquals("third", all[2].message)
        assertEquals("fourth", all[3].message)
    }

    @Test
    fun `buffer does not exceed capacity`() {
        // Log more than capacity
        for (i in 0 until 1500) {
            logger.d("Stress", "message $i")
        }
        val all = logger.getRecent(5000)
        assertTrue("Buffer should not exceed capacity", all.size <= 1000)
        // The last entry should be message 1499
        assertEquals("message 1499", all.last().message)
    }

    @Test
    fun `log with convenience methods uses default tag`() {
        logger.i("convenience test")
        val recent = logger.getRecent(10)
        assertEquals(NovaLogger.NOVA_TAG, recent.last().tag)
    }

    @Test
    fun `exportAsText returns formatted text`() {
        logger.i("TagA", "alpha")
        logger.w("TagB", "beta")
        val text = logger.exportAsText(null)
        assertTrue(text.contains("[INFO] TagA: alpha"))
        assertTrue(text.contains("[WARNING] TagB: beta"))
    }

    @Test
    fun `flow emits every log entry`() = runTest {
        val emitted = mutableListOf<com.novavpn.domain.model.LogEntry>()
        val job = kotlinx.coroutines.launch {
            logger.logFlow.collect { emitted.add(it) }
        }
        logger.i("Flow", "one")
        logger.i("Flow", "two")
        logger.i("Flow", "three")

        // Give flow time to emit
        kotlinx.coroutines.delay(100)

        assertTrue("Flow should emit entries", emitted.size >= 3)
        job.cancel()
    }

    @Test
    fun `logging 5000 messages does not cause stack overflow`() {
        // This test verifies the fix for StackOverflowError
        // The bug was: NovaLogger called Timber, which called NovaLoggerTree,
        // which called NovaLogger again → infinite recursion
        for (i in 0 until 5000) {
            logger.d("Safe", "message $i")
        }
        val all = logger.getRecent(10)
        assertTrue("Logger should survive 5000 messages without crash", all.isNotEmpty())
    }

    @Test
    fun `log with all levels preserves correct level`() {
        logger.d("L", "debug")
        logger.i("L", "info")
        logger.w("L", "warn")
        logger.e("L", "error")

        val all = logger.getRecent(10)
        assertEquals(LogLevel.Debug, all[0].level)
        assertEquals(LogLevel.Info, all[1].level)
        assertEquals(LogLevel.Warning, all[2].level)
        assertEquals(LogLevel.Error, all[3].level)
    }

    @Test
    fun `generic log method works correctly`() {
        logger.log(LogLevel.Warning, "Generic", "via log()")
        val recent = logger.getRecent(10)
        assertEquals("via log()", recent.last().message)
        assertEquals(LogLevel.Warning, recent.last().level)
    }
}
