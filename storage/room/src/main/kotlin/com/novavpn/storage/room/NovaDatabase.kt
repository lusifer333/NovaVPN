package com.novavpn.storage.room

import androidx.room.Database
import androidx.room.RoomDatabase
import com.novavpn.storage.room.dao.LogEntryDao
import com.novavpn.storage.room.dao.ServerConfigDao
import com.novavpn.storage.room.dao.ServerScoreDao
import com.novavpn.storage.room.dao.SubscriptionDao
import com.novavpn.storage.room.dao.TestResultDao
import com.novavpn.storage.room.entity.LogEntryEntity
import com.novavpn.storage.room.entity.ServerConfigEntity
import com.novavpn.storage.room.entity.ServerScoreEntity
import com.novavpn.storage.room.entity.SubscriptionEntity
import com.novavpn.storage.room.entity.TestResultEntity

/**
 * The single Room database for NovaVPN.
 * Schema version 1 — uses destructive migration fallback for simplicity.
 */
@Database(
    entities = [
        SubscriptionEntity::class,
        ServerConfigEntity::class,
        TestResultEntity::class,
        ServerScoreEntity::class,
        LogEntryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {

    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun testResultDao(): TestResultDao
    abstract fun serverScoreDao(): ServerScoreDao
    abstract fun logEntryDao(): LogEntryDao
}
