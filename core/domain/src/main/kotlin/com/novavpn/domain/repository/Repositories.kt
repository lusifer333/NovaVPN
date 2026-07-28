package com.novavpn.domain.repository

import com.novavpn.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for subscription management.
 * Implementations can be swapped (local Room, remote API, etc.).
 */
interface SubscriptionRepository {

    /** Observe all subscriptions as a flow. */
    fun observeAll(): Flow<List<Subscription>>

    /** Get a single subscription by ID. */
    suspend fun getById(id: String): Subscription?

    /** Add a new subscription. Returns generated ID. */
    suspend fun add(subscription: Subscription): String

    /** Update an existing subscription. */
    suspend fun update(subscription: Subscription)

    /** Delete a subscription and all its servers. */
    suspend fun delete(id: String)

    /** Mark subscription as updated now. */
    suspend fun markUpdated(id: String)

    /** Enable or disable a subscription. */
    suspend fun setEnabled(id: String, enabled: Boolean)
}

/**
 * Repository interface for server config management.
 */
interface ServerRepository {

    /** Observe all servers as a flow. */
    fun observeAll(): Flow<List<ServerConfig>>

    /** Observe servers from enabled subscriptions only (selectable). */
    fun observeSelectable(): Flow<List<ServerConfig>>

    /** Observe servers for a specific subscription. */
    fun observeBySubscription(subscriptionId: String): Flow<List<ServerConfig>>

    /** Get a single server by ID. */
    suspend fun getById(id: String): ServerConfig?

    /** Check if a server belongs to an enabled subscription. */
    suspend fun isServerFromEnabledSubscription(serverId: String): Boolean

    /** Replace all servers for a subscription (after parsing). */
    suspend fun replaceForSubscription(subscriptionId: String, servers: List<ServerConfig>)

    /** Delete all servers for a subscription. */
    suspend fun deleteBySubscription(subscriptionId: String)

    /** Save favourite server list. */
    suspend fun setFavourite(serverId: String, isFavourite: Boolean)

    /** Get last connected server. */
    suspend fun getLastConnected(): ServerConfig?

    /** Set last connected server. */
    suspend fun setLastConnected(serverId: String)
}

/**
 * Repository interface for connection statistics and scores.
 */
interface StatisticsRepository {

    /** Save a test result. */
    suspend fun recordTestResult(result: TestResult)

    /** Get test history for a server. */
    suspend fun getTestHistory(serverId: String, limit: Int = 20): List<TestResult>

    /** Get or calculate score for a server. */
    suspend fun getScore(serverId: String): ServerScore?

    /** Persist a calculated score. */
    suspend fun saveScore(score: ServerScore)

    /** Observe scores for all servers. */
    fun observeScores(): Flow<List<ServerScore>>

    /** Record connection event (reconnect, disconnect). */
    suspend fun recordConnectionEvent(serverId: String, wasReconnect: Boolean)

    /** Get all scores (for auto-connect decision). */
    suspend fun getAllScores(): List<ServerScore>

    /** Clean old data. */
    suspend fun pruneOldData(keepDays: Int = 30)
}

/**
 * Repository for app settings.
 */
interface SettingsRepository {

    /** Observe all settings. */
    fun observe(): Flow<AppSettings>

    /** Get current settings snapshot. */
    suspend fun get(): AppSettings

    /** Update settings. */
    suspend fun update(settings: AppSettings)

    /** Update single engine selection. */
    suspend fun setEngine(engine: EngineType)

    /** Update DNS setting. */
    suspend fun setCustomDns(dns: String)

    /** Update auto-connect preference. */
    suspend fun setAutoConnect(enabled: Boolean)
}

/**
 * Repository for log entries.
 */
interface LogRepository {

    /** Insert a log entry. */
    suspend fun insert(entry: LogEntry)

    /** Insert multiple log entries. */
    suspend fun insertAll(entries: List<LogEntry>)

    /** Observe logs with optional filter. */
    fun observe(levelFilter: LogLevel? = null, tagFilter: String? = null): Flow<List<LogEntry>>

    /** Search logs by query. */
    suspend fun search(query: String, limit: Int = 100): List<LogEntry>

    /** Clear all logs. */
    suspend fun clear()

    /** Export logs to string. */
    suspend fun export(levelFilter: LogLevel? = null): String

    /** Count logs by level. */
    suspend fun countByLevel(): Map<LogLevel, Int>
}
