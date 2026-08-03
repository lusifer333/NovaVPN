package com.novavpn.data.repository

import androidx.datastore.core.DataStore
import com.novavpn.domain.probe.TestResultEntry
import com.novavpn.domain.repository.TestResultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [TestResultRepository]. The tested
 * config list (serverId → last ping) is persisted as JSON so it survives
 * app restarts; a server is only removed when a NEW re-test returns
 * negative.
 */
@Singleton
class TestResultRepositoryImpl @Inject constructor(
    private val testResultDataStore: DataStore<List<TestResultEntry>>
) : TestResultRepository {

    override fun observe(): Flow<List<TestResultEntry>> = testResultDataStore.data

    override suspend fun get(): List<TestResultEntry> = testResultDataStore.data.first()

    override suspend fun save(entries: List<TestResultEntry>) {
        testResultDataStore.updateData { entries }
    }

    override suspend fun clear() {
        testResultDataStore.updateData { emptyList() }
    }
}
