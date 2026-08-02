package com.novavpn.data.repository

import androidx.datastore.core.DataStore
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.MineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [MineRepository]. The curated mine
 * (healthy relays) is persisted as JSON so it survives process death.
 */
@Singleton
class MineRepositoryImpl @Inject constructor(
    private val mineDataStore: DataStore<List<ServerConfig>>
) : MineRepository {

    override fun observe(): Flow<List<ServerConfig>> = mineDataStore.data

    override suspend fun get(): List<ServerConfig> = mineDataStore.data.first()

    override suspend fun save(mine: List<ServerConfig>) {
        mineDataStore.updateData { mine }
    }

    override suspend fun clear() {
        mineDataStore.updateData { emptyList() }
    }
}