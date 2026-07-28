package com.novavpn.storage.room.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.novavpn.storage.room.entity.LogEntryEntity
import com.novavpn.storage.room.entity.ServerConfigEntity
import com.novavpn.storage.room.entity.ServerScoreEntity
import com.novavpn.storage.room.entity.SubscriptionEntity
import com.novavpn.storage.room.entity.TestResultEntity
import kotlinx.coroutines.flow.Flow

// ─── Subscription ────────────────────────────────────────────────────────────

@Dao
interface SubscriptionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(subscription: SubscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(subscriptions: List<SubscriptionEntity>)

    @Update
    suspend fun update(subscription: SubscriptionEntity)

    @Delete
    suspend fun delete(subscription: SubscriptionEntity)

    @Query("SELECT * FROM subscriptions ORDER BY name ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: String): SubscriptionEntity?

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE subscriptions SET lastUpdated = :timestamp WHERE id = :id")
    suspend fun markUpdated(id: String, timestamp: Long)

    @Query("UPDATE subscriptions SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun count(): Int
}

// ─── Server Config ───────────────────────────────────────────────────────────

@Dao
interface ServerConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: ServerConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(servers: List<ServerConfigEntity>)

    @Transaction
    suspend fun replaceForSubscription(subscriptionId: String, servers: List<ServerConfigEntity>) {
        deleteBySubscription(subscriptionId)
        insertAll(servers)
    }

    @Query("SELECT * FROM server_configs ORDER BY name ASC")
    fun observeAll(): Flow<List<ServerConfigEntity>>

    @Query("""
        SELECT sc.* FROM server_configs sc
        INNER JOIN subscriptions s ON sc.subscriptionId = s.id
        WHERE s.isEnabled = 1
        ORDER BY sc.name ASC
    """)
    fun observeSelectable(): Flow<List<ServerConfigEntity>>

    @Query("""
        SELECT COUNT(*) FROM server_configs sc
        INNER JOIN subscriptions s ON sc.subscriptionId = s.id
        WHERE sc.id = :serverId AND s.isEnabled = 1
    """)
    suspend fun isServerFromEnabledSubscription(serverId: String): Int

    @Query("""
        SELECT s.isEnabled FROM server_configs sc
        INNER JOIN subscriptions s ON sc.subscriptionId = s.id
        WHERE sc.id = :serverId
    """)
    suspend fun getServerSubscriptionEnabled(serverId: String): Boolean?

    @Query("SELECT * FROM server_configs WHERE subscriptionId = :subscriptionId ORDER BY name ASC")
    fun observeBySubscription(subscriptionId: String): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM server_configs WHERE id = :id")
    suspend fun getById(id: String): ServerConfigEntity?

    @Query("DELETE FROM server_configs WHERE subscriptionId = :subscriptionId")
    suspend fun deleteBySubscription(subscriptionId: String)

    @Query("DELETE FROM server_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE server_configs SET isFavourite = :isFavourite WHERE id = :serverId")
    suspend fun setFavourite(serverId: String, isFavourite: Boolean)

    @Query("UPDATE server_configs SET lastConnected = :timestamp WHERE id = :serverId")
    suspend fun setLastConnected(serverId: String, timestamp: Long)

    @Query("SELECT * FROM server_configs ORDER BY lastConnected DESC LIMIT 1")
    suspend fun getLastConnected(): ServerConfigEntity?

    @Query("SELECT * FROM server_configs WHERE lastConnected > 0 ORDER BY lastConnected DESC LIMIT 1")
    fun observeLastConnected(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM server_configs WHERE subscriptionId = :subscriptionId")
    suspend fun getAllBySubscription(subscriptionId: String): List<ServerConfigEntity>

    @Query("DELETE FROM server_configs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM server_configs")
    suspend fun count(): Int
}

// ─── Test Result ─────────────────────────────────────────────────────────────

@Dao
interface TestResultDao {

    @Insert
    suspend fun insert(result: TestResultEntity)

    @Insert
    suspend fun insertAll(results: List<TestResultEntity>)

    @Query("SELECT * FROM test_results WHERE serverId = :serverId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getHistory(serverId: String, limit: Int): List<TestResultEntity>

    @Query("SELECT * FROM test_results WHERE serverId = :serverId ORDER BY timestamp DESC")
    fun observeByServer(serverId: String): Flow<List<TestResultEntity>>

    @Query("DELETE FROM test_results WHERE timestamp < :beforeTimestamp")
    suspend fun pruneBefore(beforeTimestamp: Long)

    @Query("DELETE FROM test_results WHERE serverId = :serverId")
    suspend fun deleteByServer(serverId: String)

    @Query("SELECT COUNT(*) FROM test_results")
    suspend fun count(): Int
}

// ─── Server Score ────────────────────────────────────────────────────────────

@Dao
interface ServerScoreDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(score: ServerScoreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scores: List<ServerScoreEntity>)

    @Query("SELECT * FROM server_scores ORDER BY (connectionSuccessRate + dnsSuccessRate) DESC")
    fun observeAll(): Flow<List<ServerScoreEntity>>

    @Query("SELECT * FROM server_scores")
    suspend fun getAll(): List<ServerScoreEntity>

    @Query("SELECT * FROM server_scores WHERE serverId = :serverId")
    suspend fun getById(serverId: String): ServerScoreEntity?

    @Query("DELETE FROM server_scores WHERE serverId = :serverId")
    suspend fun deleteById(serverId: String)
}

// ─── Log Entry ───────────────────────────────────────────────────────────────

@Dao
interface LogEntryDao {

    @Insert
    suspend fun insert(entry: LogEntryEntity)

    @Insert
    suspend fun insertAll(entries: List<LogEntryEntity>)

    /**
     * Observe log entries with optional level and tag filters.
     * When a parameter is null the filter is ignored.
     */
    @Query(
        """
        SELECT * FROM log_entries
        WHERE (:level IS NULL OR level = :level)
        AND (:tag IS NULL OR tag = :tag)
        ORDER BY timestamp DESC
        """
    )
    fun observeFiltered(level: String? = null, tag: String? = null): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<LogEntryEntity>>

    @Query(
        """
        SELECT * FROM log_entries
        WHERE message LIKE '%' || :query || '%' OR tag LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 100): List<LogEntryEntity>

    @Query("DELETE FROM log_entries")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM log_entries WHERE level = :level")
    suspend fun countByLevel(level: String): Int

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun count(): Int

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LogEntryEntity>

    /**
     * Delete the oldest N log entries (by ascending timestamp).
     */
    @Query(
        """
        DELETE FROM log_entries
        WHERE id IN (
            SELECT id FROM log_entries ORDER BY timestamp ASC LIMIT :count
        )
        """
    )
    suspend fun pruneOldest(count: Int)

    /**
     * Delete entries older than the given timestamp.
     */
    @Query("DELETE FROM log_entries WHERE timestamp < :beforeTimestamp")
    suspend fun pruneBefore(beforeTimestamp: Long)
}
