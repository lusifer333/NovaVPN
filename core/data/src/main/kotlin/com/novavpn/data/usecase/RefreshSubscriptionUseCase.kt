package com.novavpn.data.usecase

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.subscription.importer.SubscriptionImporter
import kotlinx.coroutines.flow.firstOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * Fetches subscription URL, parses, stores servers.
 *
 * Returns [Result.failure] when:
 * - Subscription not found in database
 * - Network error (DNS, timeout, HTTP error)
 * - Response is empty or contains no valid server configs
 *
 * Returns [Result.success] only when all steps complete:
 * HTTP fetch → parse → store → mark updated
 */
class RefreshSubscriptionUseCase @Inject constructor(
    private val subscriptionRepo: SubscriptionRepository,
    private val serverRepo: ServerRepository,
    private val importer: SubscriptionImporter
) {
    suspend operator fun invoke(subscriptionId: String): Result<List<ServerConfig>> {
        Timber.tag(TAG).d("--- RefreshSubscriptionUseCase ---")
        Timber.tag(TAG).d("Looking up subscription: %s", subscriptionId)

        val sub = subscriptionRepo.getById(subscriptionId)
        if (sub == null) {
            Timber.tag(TAG).e("Subscription not found: %s", subscriptionId)
            return Result.failure(Exception("Subscription not found"))
        }

        Timber.tag(TAG).d("URL: %s", sub.url)
        Timber.tag(TAG).d("Fetching...")

        return try {
            // Step 1: HTTP fetch + parse
            val servers = importer.importFromUrl(sub.url)
            Timber.tag(TAG).d("[DEBUG-servers] Parsed %d configs", servers.size)
            val blankIds = servers.count { it.id.isBlank() }
            Timber.tag(TAG).w("[DEBUG-servers] %d of %d parsed servers have BLANK id", blankIds, servers.size)

            // Step 2: Validate result
            if (servers.isEmpty()) {
                Timber.tag(TAG).w("No valid servers found")
                return Result.failure(Exception(
                    "Subscription contains no valid servers. " +
                    "The URL may be expired, invalid, or the format is unsupported."
                ))
            }

            // Step 3: Replace servers in database
            serverRepo.replaceForSubscription(subscriptionId, servers)
            Timber.tag(TAG).d("Stored %d servers", servers.size)

            // Step 4: Mark subscription as updated
            subscriptionRepo.markUpdated(subscriptionId)

            // Step 5: Verify
            val verify = serverRepo.observeBySubscription(subscriptionId).firstOrNull()
            Timber.tag(TAG).w("[DEBUG-servers] Readback: %d servers in DB for sub %s", verify?.size ?: 0, subscriptionId)

            Timber.tag(TAG).d("Refresh completed successfully")
            Result.success(servers)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Refresh failed")
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "RefreshSubscription"
    }
}
