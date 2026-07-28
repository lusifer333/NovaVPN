package com.novavpn.app.di

import android.content.Context
import com.novavpn.app.service.AndroidBinaryManager
import com.novavpn.domain.model.EngineType
import com.novavpn.engine.api.BinaryManager
import com.novavpn.engine.api.EngineManager
import com.novavpn.engine.api.EngineManagerImpl
import com.novavpn.engine.singbox.SingboxEngine
import com.novavpn.engine.xray.XrayEngine
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
    fun provideEngineManager(
        impl: EngineManagerImpl,
        xrayEngine: XrayEngine,
        singboxEngine: SingboxEngine
    ): EngineManager {
        impl.register(EngineType.Xray, xrayEngine)
        impl.register(EngineType.SingBox, singboxEngine)
        return impl
    }

    @Provides
    @Singleton
    fun provideBinaryManager(
        @ApplicationContext context: Context
    ): BinaryManager = AndroidBinaryManager(context)

    @Provides
    @Singleton
    fun provideNovaLogger(): NovaLogger = NovaLogger()

    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context = context
}
