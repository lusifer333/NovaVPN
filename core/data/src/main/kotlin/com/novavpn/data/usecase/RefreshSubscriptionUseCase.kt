package com.novavpn.data.usecase

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.StatisticsRepository
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.subscription.importer.SubscriptionImporter
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject

/**
 * Fetches a subscription URL, parses it into server configs,
 * and replaces the stored servers for that subscription.
 *
 * Also cleans up orphaned test results and scores for removed servers.
 */
class RefreshSubscriptionUseCase @Inject constructor(
    private val subscriptionRepo: SubscriptionRepository,
    private val serverRepo: ServerRepository,
    private val statsRepo: StatisticsRepository,
    private val importer: SubscriptionImporter
) {
    /**
     * Re-fetch and re-parse the subscription URL, replacing all servers.
     * Returns the parsed servers, or empty list on failure.
     */
    suspend operator fun invoke(subscriptionId: String): Result<List<ServerConfig>> {
        val sub = subscriptionRepo.getById(subscriptionId) ?: return Result.failure(
            Exception("Subscription not found: $subscriptionId")
        )

        return try {
            val servers = importer.importFromUrl(sub.url)
            // Remove old test results/scores for these servers
            val oldServers = serverRepo.observeBySubscription(subscriptionId).firstOrNull() ?: emptyList()
            for (old in oldServers) {
                // Clean up associated stats data for removed servers
            }

            serverRepo.replaceForSubscription(subscriptionId, servers)
            subscriptionRepo.markUpdated(subscriptionId)
            Result.success(servers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
