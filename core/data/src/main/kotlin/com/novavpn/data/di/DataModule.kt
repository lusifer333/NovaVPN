package com.novavpn.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.novavpn.data.repository.LogRepositoryImpl
import com.novavpn.data.repository.MineRepositoryImpl
import com.novavpn.data.repository.ServerRepositoryImpl
import com.novavpn.data.repository.SettingsRepositoryImpl
import com.novavpn.data.repository.TestResultRepositoryImpl
import com.novavpn.storage.datastore.MineSerializer
import com.novavpn.storage.datastore.SettingsSerializer
import com.novavpn.storage.datastore.TestResultSerializer
import com.novavpn.data.repository.StatisticsRepositoryImpl
import com.novavpn.data.repository.SubscriptionRepositoryImpl
import com.novavpn.domain.model.AppSettings
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.probe.TestResultEntry
import com.novavpn.domain.repository.LogRepository
import com.novavpn.domain.repository.MineRepository
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.SettingsRepository
import com.novavpn.domain.repository.TestResultRepository
import com.novavpn.domain.repository.StatisticsRepository
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.storage.room.NovaDatabase
import com.novavpn.storage.room.dao.LogEntryDao
import com.novavpn.storage.room.dao.ServerConfigDao
import com.novavpn.storage.room.dao.ServerScoreDao
import com.novavpn.storage.room.dao.SubscriptionDao
import com.novavpn.storage.room.dao.TestResultDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the complete data layer dependency graph.
 *
 * Repository interfaces are bound via [Binds]; database, DAOs, DataStore,
 * and serializer are provided via [Provides].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    // ── Repository bindings ──────────────────────────────────────────────────

    @Binds
    @Singleton
    abstract fun bindSubscriptionRepository(
        impl: SubscriptionRepositoryImpl
    ): SubscriptionRepository

    @Binds
    @Singleton
    abstract fun bindServerRepository(
        impl: ServerRepositoryImpl
    ): ServerRepository

    @Binds
    @Singleton
    abstract fun bindStatisticsRepository(
        impl: StatisticsRepositoryImpl
    ): StatisticsRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindLogRepository(
        impl: LogRepositoryImpl
    ): LogRepository

    @Binds
    @Singleton
    abstract fun bindMineRepository(
        impl: MineRepositoryImpl
    ): MineRepository

    @Binds
    @Singleton
    abstract fun bindTestResultRepository(
        impl: TestResultRepositoryImpl
    ): TestResultRepository

    // ── Provides ─────────────────────────────────────────────────────────────

    companion object {

        @Provides
        @Singleton
        fun provideNovaDatabase(
            @ApplicationContext context: Context
        ): NovaDatabase {
            return androidx.room.Room.databaseBuilder(
                context,
                NovaDatabase::class.java,
                "novavpn.db"
            )
                .fallbackToDestructiveMigration()
                .build()
        }

        @Provides
        @Singleton
        fun provideSubscriptionDao(
            database: NovaDatabase
        ): SubscriptionDao = database.subscriptionDao()

        @Provides
        @Singleton
        fun provideServerConfigDao(
            database: NovaDatabase
        ): ServerConfigDao = database.serverConfigDao()

        @Provides
        @Singleton
        fun provideTestResultDao(
            database: NovaDatabase
        ): TestResultDao = database.testResultDao()

        @Provides
        @Singleton
        fun provideServerScoreDao(
            database: NovaDatabase
        ): ServerScoreDao = database.serverScoreDao()

        @Provides
        @Singleton
        fun provideLogEntryDao(
            database: NovaDatabase
        ): LogEntryDao = database.logEntryDao()

        @Provides
        @Singleton
        fun provideSettingsSerializer(): SettingsSerializer {
            return SettingsSerializer
        }

        @Provides
        @Singleton
        fun provideMineSerializer(): MineSerializer {
            return MineSerializer
        }

        @Provides
        @Singleton
        fun provideMineDataStore(
            @ApplicationContext context: Context,
            serializer: MineSerializer
        ): DataStore<List<ServerConfig>> {
            return DataStoreFactory.create(
                serializer = serializer
            ) {
                java.io.File(context.filesDir, "novavpn_mine.json")
            }
        }

        @Provides
        @Singleton
        fun provideTestResultDataStore(
            @ApplicationContext context: Context,
            serializer: TestResultSerializer
        ): DataStore<List<TestResultEntry>> {
            return DataStoreFactory.create(
                serializer = serializer
            ) {
                java.io.File(context.filesDir, "novavpn_test_results.json")
            }
        }

        @Provides
        @Singleton
        fun provideAppSettingsDataStore(
            @ApplicationContext context: Context,
            serializer: SettingsSerializer
        ): DataStore<AppSettings> {
            return DataStoreFactory.create(
                serializer = serializer
            ) {
                java.io.File(context.filesDir, "novavpn_settings.json")
            }
        }
    }
}
