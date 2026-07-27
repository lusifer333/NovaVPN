package com.novavpn.data.mapper

import com.novavpn.domain.model.EngineFormat
import com.novavpn.domain.model.LogEntry
import com.novavpn.domain.model.LogLevel
import com.novavpn.domain.model.Protocol
import com.novavpn.domain.model.Security
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerScore
import com.novavpn.domain.model.Subscription
import com.novavpn.domain.model.TestResult
import com.novavpn.domain.model.Transport
import com.novavpn.storage.room.entity.LogEntryEntity
import com.novavpn.storage.room.entity.ServerConfigEntity
import com.novavpn.storage.room.entity.ServerScoreEntity
import com.novavpn.storage.room.entity.SubscriptionEntity
import com.novavpn.storage.room.entity.TestResultEntity

// ─── Subscription ────────────────────────────────────────────────────────────

fun SubscriptionEntity.toDomain(): Subscription = Subscription(
    id = id,
    name = name,
    url = url,
    isEnabled = isEnabled,
    lastUpdated = lastUpdated,
    autoUpdate = autoUpdate,
    updateIntervalHours = updateIntervalHours
)

fun Subscription.toEntity(): SubscriptionEntity = SubscriptionEntity(
    id = id,
    name = name,
    url = url,
    isEnabled = isEnabled,
    lastUpdated = lastUpdated,
    autoUpdate = autoUpdate,
    updateIntervalHours = updateIntervalHours
)

// ─── Server Config ───────────────────────────────────────────────────────────

fun ServerConfigEntity.toDomain(): ServerConfig = ServerConfig(
    id = id,
    subscriptionId = subscriptionId,
    name = name,
    address = address,
    port = port,
    protocol = safeEnumValueOf<Protocol>(protocol, Protocol.Unknown),
    transport = safeEnumValueOf<Transport>(transport, Transport.Unknown),
    security = safeEnumValueOf<Security>(security, Security.None),
    rawConfig = rawConfig,
    engineFormat = safeEnumValueOf<EngineFormat>(engineFormat, EngineFormat.XrayJson)
)

fun ServerConfig.toEntity(): ServerConfigEntity = ServerConfigEntity(
    id = id,
    subscriptionId = subscriptionId,
    name = name,
    address = address,
    port = port,
    protocol = protocol.name,
    transport = transport.name,
    security = security.name,
    rawConfig = rawConfig,
    engineFormat = engineFormat.name
)

// ─── Test Result ─────────────────────────────────────────────────────────────

fun TestResultEntity.toDomain(): TestResult = TestResult(
    serverId = serverId,
    timestamp = timestamp,
    connectionSuccess = connectionSuccess,
    latencyMs = latencyMs,
    dnsSuccess = dnsSuccess,
    downloadSpeedBps = downloadSpeedBps,
    errorMessage = errorMessage
)

fun TestResult.toEntity(): TestResultEntity = TestResultEntity(
    serverId = serverId,
    timestamp = timestamp,
    connectionSuccess = connectionSuccess,
    latencyMs = latencyMs,
    dnsSuccess = dnsSuccess,
    downloadSpeedBps = downloadSpeedBps,
    errorMessage = errorMessage
)

// ─── Server Score ────────────────────────────────────────────────────────────

fun ServerScoreEntity.toDomain(): ServerScore = ServerScore(
    serverId = serverId,
    connectionSuccessRate = connectionSuccessRate,
    averageLatencyMs = averageLatencyMs,
    recentSuccessRate = recentSuccessRate,
    reconnectCount = reconnectCount,
    disconnectCount = disconnectCount,
    lastSuccessfulTime = lastSuccessfulTime,
    startupTimeMs = startupTimeMs,
    dnsSuccessRate = dnsSuccessRate,
    speedSampleBps = speedSampleBps,
    lastTestTime = lastTestTime
)

fun ServerScore.toEntity(): ServerScoreEntity = ServerScoreEntity(
    serverId = serverId,
    connectionSuccessRate = connectionSuccessRate,
    averageLatencyMs = averageLatencyMs,
    recentSuccessRate = recentSuccessRate,
    reconnectCount = reconnectCount,
    disconnectCount = disconnectCount,
    lastSuccessfulTime = lastSuccessfulTime,
    startupTimeMs = startupTimeMs,
    dnsSuccessRate = dnsSuccessRate,
    speedSampleBps = speedSampleBps,
    lastTestTime = lastTestTime
)

// ─── Log Entry ───────────────────────────────────────────────────────────────

fun LogEntryEntity.toDomain(): LogEntry = LogEntry(
    id = id,
    timestamp = timestamp,
    level = safeEnumValueOf<LogLevel>(level, LogLevel.Info),
    tag = tag,
    message = message
)

fun LogEntry.toEntity(): LogEntryEntity = LogEntryEntity(
    timestamp = timestamp,
    level = level.name,
    tag = tag,
    message = message
)

// ─── Helper ──────────────────────────────────────────────────────────────────

/**
 * Safely parse a string to an enum value, returning [defaultValue] on failure.
 */
private inline fun <reified T : Enum<T>> safeEnumValueOf(name: String, defaultValue: T): T {
    return try {
        enumValueOf<T>(name)
    } catch (_: IllegalArgumentException) {
        defaultValue
    }
}
