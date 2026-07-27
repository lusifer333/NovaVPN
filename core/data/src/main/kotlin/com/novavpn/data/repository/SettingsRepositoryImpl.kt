package com.novavpn.data.repository

import androidx.datastore.core.DataStore
import com.novavpn.domain.model.AppSettings
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [SettingsRepository].
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<AppSettings>
) : SettingsRepository {

    override fun observe(): Flow<AppSettings> = dataStore.data

    override suspend fun get(): AppSettings = dataStore.data.first()

    override suspend fun update(settings: AppSettings) {
        dataStore.updateData { settings }
    }

    override suspend fun setEngine(engine: EngineType) {
        dataStore.updateData { current -> current.copy(selectedEngine = engine) }
    }

    override suspend fun setCustomDns(dns: String) {
        dataStore.updateData { current -> current.copy(customDns = dns) }
    }

    override suspend fun setAutoConnect(enabled: Boolean) {
        dataStore.updateData { current -> current.copy(enableAutoConnect = enabled) }
    }
}
