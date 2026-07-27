package com.novavpn.domain.usecase.subscription

import com.novavpn.domain.model.Subscription
import com.novavpn.domain.repository.SubscriptionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveSubscriptionsUseCase @Inject constructor(
    private val repo: SubscriptionRepository
) {
    operator fun invoke(): Flow<List<Subscription>> = repo.observeAll()
}

class AddSubscriptionUseCase @Inject constructor(
    private val repo: SubscriptionRepository
) {
    suspend operator fun invoke(name: String, url: String): String {
        val sub = Subscription(
            id = generateId(),
            name = name.ifBlank { "Subscription" },
            url = url.trim(),
            lastUpdated = System.currentTimeMillis()
        )
        return repo.add(sub)
    }

    private fun generateId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }
}

class DeleteSubscriptionUseCase @Inject constructor(
    private val repo: SubscriptionRepository
) {
    suspend operator fun invoke(id: String) = repo.delete(id)
}

class UpdateSubscriptionUseCase @Inject constructor(
    private val repo: SubscriptionRepository
) {
    suspend operator fun invoke(subscription: Subscription) = repo.update(subscription)
}

class ToggleSubscriptionUseCase @Inject constructor(
    private val repo: SubscriptionRepository
) {
    suspend operator fun invoke(id: String, enabled: Boolean) = repo.setEnabled(id, enabled)
}

class MarkSubscriptionUpdatedUseCase @Inject constructor(
    private val repo: SubscriptionRepository
) {
    suspend operator fun invoke(id: String) = repo.markUpdated(id)
}
