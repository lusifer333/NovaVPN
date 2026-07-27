package com.novavpn.subscription.di

import com.novavpn.subscription.importer.SubscriptionImporter
import com.novavpn.subscription.parser.SubscriptionParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SubscriptionModule {

    @Provides
    @Singleton
    fun provideSubscriptionParser(): SubscriptionParser {
        return SubscriptionParser
    }

    @Provides
    @Singleton
    fun provideSubscriptionImporter(parser: SubscriptionParser): SubscriptionImporter {
        return SubscriptionImporter(parser)
    }
}
