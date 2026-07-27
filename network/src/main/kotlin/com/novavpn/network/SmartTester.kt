package com.novavpn.network

import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.domain.model.TestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * Result of a single connectivity test step.
 */
data class StepResult(
    val label: String,
    val success: Boolean,
    val value: Long = -1L,
    val errorMessage: String = ""
)

/**
 * Consolidated result from all test steps.
 */
data class SmartTestResult(
    val connectionSuccess: Boolean = false,
    val latencyMs: Long = -1L,
    val dnsSuccess: Boolean = false,
    val downloadSpeedBps: Long = -1L,
    val errorMessage: String = "",
    val stepResults: List<StepResult> = emptyList()
)

/**
 * A single step in the connectivity test pipeline.
 */
interface ConnectivityTestStep {
    /** Unique label identifying this step. */
    val label: String

    /** Execute the step, returning a [StepResult]. Must never throw. */
    suspend fun execute(): StepResult
}

// ---------------------------------------------------------------------------
// Concrete steps
// ---------------------------------------------------------------------------

/**
 * Mock step: checks whether the engine state is [EngineRuntimeState.Running].
 * In a real implementation this would query the active engine's state flow.
 */
class EngineStartStep(
    private val getEngineState: suspend () -> EngineRuntimeState
) : ConnectivityTestStep {

    override val label: String = "engine_start"

    override suspend fun execute(): StepResult {
        return try {
            val state = getEngineState()
            val isRunning = state == EngineRuntimeState.Running
            Timber.tag("SmartTester").d("EngineStartStep: state=%s success=%b", state, isRunning)
            StepResult(
                label = label,
                success = isRunning,
                value = if (isRunning) 1L else 0L,
                errorMessage = if (isRunning) "" else "Engine state is $state, expected Running"
            )
        } catch (e: Exception) {
            Timber.tag("SmartTester").w(e, "EngineStartStep failed")
            StepResult(label = label, success = false, errorMessage = e.message ?: "Unknown error")
        }
    }
}

/**
 * Checks internet connectivity by issuing an HTTP HEAD request to https://1.1.1.1.
 */
class InternetCheckStep : ConnectivityTestStep {

    override val label: String = "internet_check"

    override suspend fun execute(): StepResult {
        return try {
            withTimeout(5_000L) {
                val url = URL("https://1.1.1.1")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = true
                val code = connection.responseCode
                connection.disconnect()

                val success = code in 200..399
                Timber.tag("SmartTester").d("InternetCheckStep: HTTP %d success=%b", code, success)
                StepResult(
                    label = label,
                    success = success,
                    value = code.toLong(),
                    errorMessage = if (success) "" else "HTTP $code"
                )
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag("SmartTester").w("InternetCheckStep timed out after 5s")
            StepResult(label = label, success = false, errorMessage = "Timed out after 5s")
        } catch (e: Exception) {
            Timber.tag("SmartTester").w(e, "InternetCheckStep failed")
            StepResult(label = label, success = false, errorMessage = e.message ?: "Unknown error")
        }
    }
}

/**
 * Resolves google.com via [InetAddress.getByName] with a 5-second timeout.
 */
class DnsCheckStep : ConnectivityTestStep {

    override val label: String = "dns_check"

    override suspend fun execute(): StepResult {
        return try {
            withTimeout(5_000L) {
                val address = InetAddress.getByName("google.com")
                val success = address != null && address.hostAddress != null
                Timber.tag("SmartTester").d(
                    "DnsCheckStep: resolved=%s success=%b",
                    address.hostAddress,
                    success
                )
                StepResult(
                    label = label,
                    success = success,
                    value = if (success) 1L else 0L,
                    errorMessage = if (success) "" else "Failed to resolve google.com"
                )
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag("SmartTester").w("DnsCheckStep timed out after 5s")
            StepResult(label = label, success = false, errorMessage = "DNS resolution timed out after 5s")
        } catch (e: UnknownHostException) {
            Timber.tag("SmartTester").w(e, "DnsCheckStep: UnknownHostException")
            StepResult(label = label, success = false, errorMessage = "Unknown host: google.com")
        } catch (e: Exception) {
            Timber.tag("SmartTester").w(e, "DnsCheckStep failed")
            StepResult(label = label, success = false, errorMessage = e.message ?: "Unknown error")
        }
    }
}

/**
 * Measures average latency by performing 3 ICMP-style HTTP pings to https://1.1.1.1,
 * each with a 3-second timeout. Returns the arithmetic mean of successful pings in ms.
 */
class LatencyStep(
    private val pingCount: Int = 3,
    private val pingTimeoutMs: Long = 3_000L
) : ConnectivityTestStep {

    override val label: String = "latency"

    override suspend fun execute(): StepResult {
        return try {
            val latencies = mutableListOf<Long>()

            for (i in 1..pingCount) {
                try {
                    withTimeout(pingTimeoutMs) {
                        val start = System.currentTimeMillis()
                        val url = URL("https://1.1.1.1")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "HEAD"
                        connection.connectTimeout = pingTimeoutMs.toInt()
                        connection.readTimeout = pingTimeoutMs.toInt()
                        connection.instanceFollowRedirects = true
                        val code = connection.responseCode
                        connection.disconnect()

                        if (code in 200..399) {
                            val elapsed = System.currentTimeMillis() - start
                            latencies.add(elapsed)
                            Timber.tag("SmartTester").d("LatencyStep ping #%d: %d ms", i, elapsed)
                        } else {
                            Timber.tag("SmartTester").d("LatencyStep ping #%d: HTTP %d", i, code)
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("SmartTester").d("LatencyStep ping #%d failed: %s", i, e.message)
                }
            }

            if (latencies.isEmpty()) {
                StepResult(label = label, success = false, errorMessage = "All $pingCount pings failed")
            } else {
                val avg = latencies.average().toLong()
                Timber.tag("SmartTester").d("LatencyStep: avg %d ms from %d pings", avg, latencies.size)
                StepResult(label = label, success = true, value = avg)
            }
        } catch (e: Exception) {
            Timber.tag("SmartTester").w(e, "LatencyStep failed")
            StepResult(label = label, success = false, errorMessage = e.message ?: "Unknown error")
        }
    }
}

/**
 * Downloads a 100 KB file from http://speedtest.tele2.net/100KB.zip and measures
 * the throughput in bytes per second.
 */
class SpeedSampleStep(
    private val downloadUrl: String = "http://speedtest.tele2.net/100KB.zip",
    private val downloadTimeoutMs: Long = 15_000L
) : ConnectivityTestStep {

    override val label: String = "speed_sample"

    override suspend fun execute(): StepResult {
        return try {
            withTimeout(downloadTimeoutMs) {
                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = downloadTimeoutMs.toInt()
                connection.instanceFollowRedirects = true

                val start = System.currentTimeMillis()
                val inputStream = connection.inputStream
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                }
                inputStream.close()
                connection.disconnect()

                val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val bytesPerSec = (totalBytes * 1000L) / elapsedMs

                Timber.tag("SmartTester").d(
                    "SpeedSampleStep: %d bytes in %d ms = %d B/s",
                    totalBytes, elapsedMs, bytesPerSec
                )
                StepResult(
                    label = label,
                    success = true,
                    value = bytesPerSec
                )
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag("SmartTester").w("SpeedSampleStep timed out")
            StepResult(label = label, success = false, errorMessage = "Download timed out")
        } catch (e: Exception) {
            Timber.tag("SmartTester").w(e, "SpeedSampleStep failed")
            StepResult(label = label, success = false, errorMessage = e.message ?: "Unknown error")
        }
    }
}

// ---------------------------------------------------------------------------
// SmartTester
// ---------------------------------------------------------------------------

/**
 * Runs a suite of connectivity test steps sequentially with a total timeout of 30 s.
 * Each step is executed one after another, catching and recording errors gracefully.
 * The consolidated [TestResult] is built from all step outcomes.
 */
class SmartTester @Inject constructor(
    private val steps: List<@JvmSuppressWildcards ConnectivityTestStep>
) {
    companion object {
        private const val TOTAL_TIMEOUT_MS = 30_000L
    }

    /**
     * Execute all steps sequentially within the total timeout.
     * Returns a [TestResult] that reflects the best-available data from all steps.
     */
    suspend fun runAll(serverId: String = ""): TestResult {
        return try {
            withTimeout(TOTAL_TIMEOUT_MS) {
                val stepResults = mutableListOf<StepResult>()

                for (step in steps) {
                    val result = step.execute()
                    stepResults.add(result)
                }

                consolidate(serverId, stepResults)
            }
        } catch (e: TimeoutCancellationException) {
            Timber.tag("SmartTester").w("SmartTester timed out after %d ms", TOTAL_TIMEOUT_MS)
            TestResult(
                serverId = serverId,
                timestamp = System.currentTimeMillis(),
                connectionSuccess = false,
                latencyMs = -1L,
                dnsSuccess = false,
                downloadSpeedBps = -1L,
                errorMessage = "SmartTester timed out after ${TOTAL_TIMEOUT_MS}ms"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("SmartTester").e(e, "SmartTester.runAll failed unexpectedly")
            TestResult(
                serverId = serverId,
                timestamp = System.currentTimeMillis(),
                connectionSuccess = false,
                latencyMs = -1L,
                dnsSuccess = false,
                downloadSpeedBps = -1L,
                errorMessage = e.message ?: "Unexpected error"
            )
        }
    }

    private fun consolidate(serverId: String, stepResults: List<StepResult>): TestResult {
        val engineOk = stepResults.firstOrNull { it.label == "engine_start" }?.success ?: false
        val internetOk = stepResults.firstOrNull { it.label == "internet_check" }?.success ?: false
        val dnsOk = stepResults.firstOrNull { it.label == "dns_check" }?.success ?: false
        val latency = stepResults.firstOrNull { it.label == "latency" }
        val speed = stepResults.firstOrNull { it.label == "speed_sample" }

        // Collect non-empty error messages
        val errors = stepResults
            .filter { !it.success && it.errorMessage.isNotBlank() }
            .joinToString("; ") { "[${it.label}] ${it.errorMessage}" }

        return TestResult(
            serverId = serverId,
            timestamp = System.currentTimeMillis(),
            connectionSuccess = engineOk && internetOk,
            latencyMs = if (latency?.success == true) latency.value else -1L,
            dnsSuccess = dnsOk,
            downloadSpeedBps = if (speed?.success == true) speed.value else -1L,
            errorMessage = errors
        )
    }
}
