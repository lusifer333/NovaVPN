package com.novavpn.domain.usecase.server

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.StatisticsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ObserveServersUseCase @Inject constructor(
    private val repo: ServerRepository
) {
    operator fun invoke(): Flow<List<ServerConfig>> = repo.observeAll()
}

class ObserveSelectableServersUseCase @Inject constructor(
    private val repo: ServerRepository
) {
    operator fun invoke(): Flow<List<ServerConfig>> = repo.observeSelectable()
}

class ObserveServersBySubscriptionUseCase @Inject constructor(
    private val repo: ServerRepository
) {
    operator fun invoke(subscriptionId: String): Flow<List<ServerConfig>> =
        repo.observeBySubscription(subscriptionId)
}

class ToggleFavouriteServerUseCase @Inject constructor(
    private val repo: ServerRepository
) {
    suspend operator fun invoke(serverId: String, isFavourite: Boolean) =
        repo.setFavourite(serverId, isFavourite)
}

class SelectServerUseCase @Inject constructor(
    private val repo: ServerRepository
) {
    suspend operator fun invoke(serverId: String) {
        repo.setLastConnected(serverId)
    }
}

class GetBestServerUseCase @Inject constructor(
    private val serverRepo: ServerRepository,
    private val statsRepo: StatisticsRepository
) {
    suspend operator fun invoke(): ServerConfig? {
        val scores = statsRepo.getAllScores()
            .filter { it.lastSuccessfulTime > 0L }
            .sortedByDescending { it.calculate() }

        for (score in scores) {
            val server = serverRepo.getById(score.serverId) ?: continue
            // Only return servers from enabled subscriptions
            if (serverRepo.isServerFromEnabledSubscription(server.id)) {
                return server
            }
        }

        // Fallback: return any selectable server
        val servers = serverRepo.observeSelectable()
        return servers.firstOrNull()?.firstOrNull()
    }
}

class IsServerFromEnabledSubscriptionUseCase @Inject constructor(
    private val repo: ServerRepository
) {
    suspend operator fun invoke(serverId: String): Boolean =
        repo.isServerFromEnabledSubscription(serverId)
}