package com.novavpn.statistics

import com.novavpn.domain.model.ServerScore
import com.novavpn.domain.model.TestResult
import timber.log.Timber

/**
 * Updates [ServerScore] with new [TestResult] data using weighted running averages.
 */
object ScoreCalculator {

    private const val TAG = "ScoreCalculator"
    private const val NEW_DATA_WEIGHT = 0.8
    private const val RECENT_WINDOW_SIZE = 10

    /**
     * Merge [results] into [current] to produce an updated [ServerScore].
     *
     * - [connectionSuccessRate]: running average weighted 0.8 for new data.
     * - [averageLatencyMs]: weighted arithmetic mean.
     * - [recentSuccessRate]: ratio of successful connections in the last 10 results.
     * - [dnsSuccessRate]: running average weighted 0.8 for new DNS results.
     * - [speedSampleBps]: latest non-negative speed sample, else retain previous.
     * - [startupTimeMs]: if current value is negative, set to first-latency; otherwise
     *   compute a weighted average with new data.
     */
    fun calculate(current: ServerScore, results: List<TestResult>): ServerScore {
        if (results.isEmpty()) return current

        Timber.tag(TAG).d("calculate: current=%s, %d new results", current.serverId, results.size)

        val weight = NEW_DATA_WEIGHT
        val complement = 1.0 - weight

        // --- Connection success rate (running average) ---
        val newConnectionRatio = results.count { it.connectionSuccess }.toDouble() / results.size
        val updatedConnectionSuccessRate =
            current.connectionSuccessRate * complement + newConnectionRatio * weight

        // --- Average latency (weighted) ---
        val validLatencies = results.mapNotNull { r ->
            if (r.latencyMs >= 0) r.latencyMs else null
        }
        val updatedAverageLatencyMs = if (validLatencies.isNotEmpty()) {
            val newAvg = validLatencies.average().toLong()
            if (current.averageLatencyMs > 0) {
                (current.averageLatencyMs * complement + newAvg * weight).toLong()
            } else {
                newAvg
            }
        } else {
            current.averageLatencyMs
        }

        // --- Recent success rate (last 10 results) ---
        val recent = results.takeLast(RECENT_WINDOW_SIZE)
        val updatedRecentSuccessRate =
            if (recent.isNotEmpty()) {
                recent.count { it.connectionSuccess }.toDouble() / recent.size
            } else {
                current.recentSuccessRate
            }

        // --- DNS success rate (running average) ---
        val newDnsRatio = results.count { it.dnsSuccess }.toDouble() / results.size
        val updatedDnsSuccessRate =
            current.dnsSuccessRate * complement + newDnsRatio * weight

        // --- Speed sample (latest wins if ≥ 0) ---
        val latestSpeed = results.lastOrNull()?.downloadSpeedBps ?: -1L
        val updatedSpeedSampleBps = if (latestSpeed >= 0) latestSpeed else current.speedSampleBps

        // --- Startup time ---
        val updatedStartupTimeMs = if (current.startupTimeMs < 0) {
            // First time: use the first available latency
            validLatencies.firstOrNull() ?: current.startupTimeMs
        } else {
            // Weighted average with new latencies
            if (validLatencies.isNotEmpty()) {
                val newLatencyAvg = validLatencies.average()
                (current.startupTimeMs * complement + newLatencyAvg * weight).toLong()
            } else {
                current.startupTimeMs
            }
        }

        // --- Last test time ---
        val lastResultTime = results.maxOfOrNull { it.timestamp } ?: current.lastTestTime

        val updated = current.copy(
            connectionSuccessRate = updatedConnectionSuccessRate.coerceIn(0.0, 1.0),
            averageLatencyMs = updatedAverageLatencyMs,
            recentSuccessRate = updatedRecentSuccessRate.coerceIn(0.0, 1.0),
            dnsSuccessRate = updatedDnsSuccessRate.coerceIn(0.0, 1.0),
            speedSampleBps = updatedSpeedSampleBps,
            startupTimeMs = updatedStartupTimeMs,
            lastTestTime = lastResultTime
        )

        Timber.tag(TAG).d(
            "calculate: result connRate=%.2f avgLat=%d recentRate=%.2f dnsRate=%.2f speed=%d startup=%d",
            updated.connectionSuccessRate, updated.averageLatencyMs,
            updated.recentSuccessRate, updated.dnsSuccessRate,
            updated.speedSampleBps, updated.startupTimeMs
        )

        return updated
    }
}
