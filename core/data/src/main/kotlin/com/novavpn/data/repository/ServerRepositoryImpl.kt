package com.novavpn.data.repository

import com.novavpn.data.mapper.toDomain
import com.novavpn.data.mapper.toEntity
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.storage.room.dao.ServerConfigDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [ServerRepository].
 */
@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val serverConfigDao: ServerConfigDao
) : ServerRepository {

    override fun observeAll(): Flow<List<ServerConfig>> {
        return serverConfigDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun observeBySubscription(subscriptionId: String): Flow<List<ServerConfig>> {
        return serverConfigDao.observeBySubscription(subscriptionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getById(id: String): ServerConfig? {
        return serverConfigDao.getById(id)?.toDomain()
    }

    override suspend fun replaceForSubscription(
        subscriptionId: String,
        servers: List<ServerConfig>
    ) {
        Timber.tag("ServerRepo").d("[DEBUG-servers] replaceForSubscription: id=%s, %d servers", subscriptionId, servers.size)

        // [DEBUG-servers] Check how many servers have blank IDs
        val blankIdCount = servers.count { it.id.isBlank() }
        Timber.tag("ServerRepo").w("[DEBUG-servers] %d of %d servers have BLANK id — will cause PK collision!", blankIdCount, servers.size)

        // Delete old servers for this subscription
        serverConfigDao.deleteBySubscription(subscriptionId)
        Timber.tag("ServerRepo").d("[DEBUG-servers] Deleted old servers for %s", subscriptionId)

        // Insert new servers — generate unique ID if blank, must set subscriptionId on each entity
        val entities = servers.map { server ->
            val uniqueId = if (server.id.isBlank()) {
                java.util.UUID.randomUUID().toString()
            } else {
                server.id
            }
            server.copy(
                id = uniqueId,
                subscriptionId = subscriptionId
            ).toEntity()
        }
        Timber.tag("ServerRepo").d("[DEBUG-servers] Inserting %d entities with subscriptionId=%s", entities.size, subscriptionId)
        Timber.tag("ServerRepo").d("[DEBUG-servers] Entity IDs: %s", entities.map { it.id.take(8) }.joinToString(", "))

        serverConfigDao.insertAll(entities)
        Timber.tag("ServerRepo").d("[DEBUG-servers] Insert complete — inserted %d entities", entities.size)

        // [DEBUG-servers] Immediately query DB to verify row count
        val dbCount = serverConfigDao.getAllBySubscription(subscriptionId).size
        Timber.tag("ServerRepo").w("[DEBUG-servers] DB readback after insert: %d rows for subscription %s", dbCount, subscriptionId)
    }

    override suspend fun deleteBySubscription(subscriptionId: String) {
        serverConfigDao.deleteBySubscription(subscriptionId)
    }

    override suspend fun setFavourite(serverId: String, isFavourite: Boolean) {
        serverConfigDao.setFavourite(serverId, isFavourite)
    }

    override suspend fun getLastConnected(): ServerConfig? {
        return serverConfigDao.getLastConnected()?.toDomain()
    }

    override suspend fun setLastConnected(serverId: String) {
        serverConfigDao.setLastConnected(serverId, System.currentTimeMillis())
    }
}
