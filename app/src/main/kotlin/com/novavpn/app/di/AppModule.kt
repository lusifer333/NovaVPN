package com.novavpn.app.di

import android.content.Context
import com.novavpn.engine.api.EngineManager
import com.novavpn.engine.api.EngineManagerImpl
import com.novavpn.logging.NovaLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideEngineManager(impl: EngineManagerImpl): EngineManager = impl

    @Provides
    @Singleton
    fun provideNovaLogger(): NovaLogger = NovaLogger()

    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context = context
}
