package com.novavpn.data.repository

import com.novavpn.data.mapper.toDomain
import com.novavpn.data.mapper.toEntity
import com.novavpn.domain.model.LogEntry
import com.novavpn.domain.model.LogLevel
import com.novavpn.domain.repository.LogRepository
import com.novavpn.storage.room.dao.LogEntryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [LogRepository].
 */
@Singleton
class LogRepositoryImpl @Inject constructor(
    private val logEntryDao: LogEntryDao
) : LogRepository {

    override suspend fun insert(entry: LogEntry) {
        logEntryDao.insert(entry.toEntity())
    }

    override suspend fun insertAll(entries: List<LogEntry>) {
        logEntryDao.insertAll(entries.map { it.toEntity() })
    }

    override fun observe(
        levelFilter: LogLevel?,
        tagFilter: String?
    ): Flow<List<LogEntry>> {
        val levelParam = levelFilter?.name
        return logEntryDao.observeFiltered(level = levelParam, tag = tagFilter)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun search(query: String, limit: Int): List<LogEntry> {
        return logEntryDao.search(query, limit).map { it.toDomain() }
    }

    override suspend fun clear() {
        logEntryDao.clear()
    }

    override suspend fun export(levelFilter: LogLevel?): String {
        val levelParam = levelFilter?.name
        val entities = logEntryDao.observeFiltered(level = levelParam).first()
        return entities.joinToString("\n") { entry ->
            val log = entry.toDomain()
            "[${log.timestamp}] [${log.level.name}] [${log.tag}] ${log.message}"
        }
    }

    override suspend fun countByLevel(): Map<LogLevel, Int> {
        return LogLevel.entries.associateWith { level ->
            logEntryDao.countByLevel(level.name)
        }
    }
}
