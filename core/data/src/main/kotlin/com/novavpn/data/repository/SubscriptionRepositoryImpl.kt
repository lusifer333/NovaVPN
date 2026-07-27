package com.novavpn.data.repository

import com.novavpn.data.mapper.toDomain
import com.novavpn.data.mapper.toEntity
import com.novavpn.domain.model.Subscription
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.storage.room.dao.SubscriptionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [SubscriptionRepository].
 */
@Singleton
class SubscriptionRepositoryImpl @Inject constructor(
    private val subscriptionDao: SubscriptionDao
) : SubscriptionRepository {

    override fun observeAll(): Flow<List<Subscription>> {
        return subscriptionDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getById(id: String): Subscription? {
        return subscriptionDao.getById(id)?.toDomain()
    }

    override suspend fun add(subscription: Subscription): String {
        val id = if (subscription.id.isBlank()) {
            UUID.randomUUID().toString()
        } else {
            subscription.id
        }
        subscriptionDao.insert(subscription.copy(id = id).toEntity())
        return id
    }

    override suspend fun update(subscription: Subscription) {
        subscriptionDao.update(subscription.toEntity())
    }

    override suspend fun delete(id: String) {
        subscriptionDao.deleteById(id)
    }

    override suspend fun markUpdated(id: String) {
        subscriptionDao.markUpdated(id, System.currentTimeMillis())
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        subscriptionDao.setEnabled(id, enabled)
    }
}
