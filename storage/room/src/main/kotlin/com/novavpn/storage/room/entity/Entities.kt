package com.novavpn.storage.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a VPN subscription source.
 */
@Entity(
    tableName = "subscriptions",
    indices = [
        Index(value = ["isEnabled"])
    ]
)
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val isEnabled: Boolean,
    val lastUpdated: Long,
    val autoUpdate: Boolean,
    val updateIntervalHours: Int
)

/**
 * Room entity representing a parsed proxy server configuration.
 * Foreign key cascades deletion when the parent subscription is removed.
 */
@Entity(
    tableName = "server_configs",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["subscriptionId"])
    ]
)
data class ServerConfigEntity(
    @PrimaryKey val id: String,
    val subscriptionId: String,
    val name: String,
    val address: String,
    val port: Int,
    val protocol: String,
    val transport: String,
    val security: String,
    val rawConfig: String,
    val engineFormat: String,
    val isFavourite: Boolean = false,
    val lastConnected: Long = 0L
)

/**
 * Room entity representing a connectivity test result.
 */
@Entity(
    tableName = "test_results",
    indices = [
        Index(value = ["serverId", "timestamp"])
    ]
)
data class TestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val timestamp: Long,
    val connectionSuccess: Boolean,
    val latencyMs: Long,
    val dnsSuccess: Boolean,
    val downloadSpeedBps: Long,
    val errorMessage: String
)

/**
 * Room entity representing a server's computed score.
 */
@Entity(tableName = "server_scores")
data class ServerScoreEntity(
    @PrimaryKey val serverId: String,
    val connectionSuccessRate: Double = 0.0,
    val averageLatencyMs: Long = -1L,
    val recentSuccessRate: Double = 0.0,
    val reconnectCount: Int = 0,
    val disconnectCount: Int = 0,
    val lastSuccessfulTime: Long = 0L,
    val startupTimeMs: Long = -1L,
    val dnsSuccessRate: Double = 0.0,
    val speedSampleBps: Long = -1L,
    val lastTestTime: Long = 0L
)

/**
 * Room entity representing a single log entry.
 */
@Entity(
    tableName = "log_entries",
    indices = [
        Index(value = ["level"]),
        Index(value = ["tag"]),
        Index(value = ["timestamp"])
    ]
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String
)
