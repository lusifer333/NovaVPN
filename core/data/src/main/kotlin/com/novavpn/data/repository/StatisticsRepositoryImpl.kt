package com.novavpn.data.repository

import com.novavpn.data.mapper.toDomain
import com.novavpn.data.mapper.toEntity
import com.novavpn.domain.model.ServerScore
import com.novavpn.domain.model.TestResult
import com.novavpn.domain.repository.StatisticsRepository
import com.novavpn.storage.room.dao.ServerScoreDao
import com.novavpn.storage.room.dao.TestResultDao
import com.novavpn.storage.room.entity.ServerScoreEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [StatisticsRepository].
 *
 * Persists test results and maintains a computed [ServerScore] per server.
 * Scores are recalculated automatically each time a new test result is recorded.
 */
@Singleton
class StatisticsRepositoryImpl @Inject constructor(
    private val testResultDao: TestResultDao,
    private val serverScoreDao: ServerScoreDao
) : StatisticsRepository {

    override suspend fun recordTestResult(result: TestResult) {
        testResultDao.insert(result.toEntity())
        recalculateScore(result.serverId)
    }

    override suspend fun getTestHistory(serverId: String, limit: Int): List<TestResult> {
        return testResultDao.getHistory(serverId, limit).map { it.toDomain() }
    }

    override suspend fun getScore(serverId: String): ServerScore? {
        return serverScoreDao.getById(serverId)?.toDomain()
    }

    override suspend fun saveScore(score: ServerScore) {
        serverScoreDao.insert(score.toEntity())
    }

    override fun observeScores(): Flow<List<ServerScore>> {
        return serverScoreDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun recordConnectionEvent(serverId: String, wasReconnect: Boolean) {
        val existing = serverScoreDao.getById(serverId)
        val updated = if (existing != null) {
            existing.copy(
                reconnectCount = if (wasReconnect) existing.reconnectCount + 1
                else existing.reconnectCount,
                disconnectCount = if (!wasReconnect) existing.disconnectCount + 1
                else existing.disconnectCount
            )
        } else {
            ServerScoreEntity(
                serverId = serverId,
                reconnectCount = if (wasReconnect) 1 else 0,
                disconnectCount = if (!wasReconnect) 1 else 0
            )
        }
        serverScoreDao.insert(updated)
    }

    override suspend fun getAllScores(): List<ServerScore> {
        return serverScoreDao.getAll().map { it.toDomain() }
    }

    override suspend fun pruneOldData(keepDays: Int) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(keepDays.toLong())
        testResultDao.pruneBefore(cutoff)
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * Recalculate and persist the composite score for [serverId] based on its
     * most recent 50 test results (and recent 10 for the recency metric).
     */
    private suspend fun recalculateScore(serverId: String) {
        val history = testResultDao.getHistory(serverId, 50)
        if (history.isEmpty()) return

        val recentHistory = history.take(10)

        val totalTests = history.size.toDouble()
        val successfulConnections = history.count { it.connectionSuccess }
        val successfulDns = history.count { it.dnsSuccess }

        val averageLatency = if (history.any { it.latencyMs >= 0 }) {
            history.filter { it.latencyMs >= 0 }
                .map { it.latencyMs }
                .average()
                .toLong()
        } else {
            -1L
        }

        val recentSuccessCount = recentHistory.count { it.connectionSuccess }
        val recentSuccessRate = if (recentHistory.isNotEmpty()) {
            recentSuccessCount.toDouble() / recentHistory.size
        } else 0.0

        val latestSpeedSample = history
            .firstOrNull { it.downloadSpeedBps > 0 }
            ?.downloadSpeedBps ?: -1L
        val lastTestTime = history.firstOrNull()?.timestamp ?: 0L
        val lastSuccessfulTime = history
            .firstOrNull { it.connectionSuccess }
            ?.timestamp ?: 0L
        val dnsSuccessRate = if (totalTests > 0) successfulDns / totalTests else 0.0

        val existing = serverScoreDao.getById(serverId)

        val newScore = ServerScoreEntity(
            serverId = serverId,
            connectionSuccessRate = if (totalTests > 0) {
                successfulConnections / totalTests
            } else 0.0,
            averageLatencyMs = averageLatency,
            recentSuccessRate = recentSuccessRate,
            reconnectCount = existing?.reconnectCount ?: 0,
            disconnectCount = existing?.disconnectCount ?: 0,
            lastSuccessfulTime = lastSuccessfulTime,
            startupTimeMs = existing?.startupTimeMs ?: -1L,
            dnsSuccessRate = dnsSuccessRate,
            speedSampleBps = latestSpeedSample,
            lastTestTime = lastTestTime
        )
        serverScoreDao.insert(newScore)
    }
}
