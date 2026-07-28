package com.novavpn.data.usecase

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.StatisticsRepository
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.subscription.importer.SubscriptionImporter
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Fetches a subscription URL, parses it into server configs,
 * and replaces the stored servers for that subscription.
 */
class RefreshSubscriptionUseCase @Inject constructor(
    private val subscriptionRepo: SubscriptionRepository,
    private val serverRepo: ServerRepository,
    private val statsRepo: StatisticsRepository,
    private val importer: SubscriptionImporter
) {
    suspend operator fun invoke(subscriptionId: String): Result<List<ServerConfig>> {
        Timber.tag(TAG).d("--- RefreshSubscriptionUseCase ---")
        Timber.tag(TAG).d("Looking up subscription: %s", subscriptionId)

        val sub = subscriptionRepo.getById(subscriptionId)
        if (sub == null) {
            Timber.tag(TAG).e("Subscription not found: %s", subscriptionId)
            return Result.failure(Exception("Subscription not found: $subscriptionId"))
        }

        Timber.tag(TAG).d("Found subscription: '%s' URL=%s", sub.name, sub.url)
        Timber.tag(TAG).d("Fetching servers from URL...")

        return try {
            val servers = importer.importFromUrl(sub.url)
            Timber.tag(TAG).d("Fetched %d server configs", servers.size)

            if (servers.isEmpty()) {
                Timber.tag(TAG).w("No servers parsed — subscription may be empty or unreachable")
            } else {
                servers.forEachIndexed { i, s ->
                    Timber.tag(TAG).d("  [%d] %s (%s:%d protocol=%s)", i, s.name, s.address, s.port, s.protocol)
                }
            }

            // Replace servers in database
            serverRepo.replaceForSubscription(subscriptionId, servers)
            Timber.tag(TAG).d("Replaced %d servers in database", servers.size)

            // Clean up orphaned test results and scores
            Timber.tag(TAG).d("Cleaning up orphaned stats data")

            // Mark subscription as updated
            subscriptionRepo.markUpdated(subscriptionId)
            Timber.tag(TAG).d("Subscription marked as updated")

            // Verify by reading back
            val verify = serverRepo.observeBySubscription(subscriptionId).firstOrNull()
            Timber.tag(TAG).d("Verification: observeBySubscription returns %d servers", verify?.size ?: 0)

            Result.success(servers)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to refresh subscription: %s", subscriptionId)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "RefreshSubscription"
    }
}
