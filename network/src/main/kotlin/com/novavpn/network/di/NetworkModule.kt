package com.novavpn.network.di

import com.novavpn.domain.model.EngineRuntimeState
import com.novavpn.network.ConnectivityTestStep
import com.novavpn.network.DnsCheckStep
import com.novavpn.network.EngineStartStep
import com.novavpn.network.InternetCheckStep
import com.novavpn.network.LatencyStep
import com.novavpn.network.SmartTester
import com.novavpn.network.SpeedSampleStep
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideSmartTester(): SmartTester {
        val steps: List<ConnectivityTestStep> = listOf(
            // EngineStartStep with a mock that always returns Running.
            // In production, inject the actual EngineManager and read its state flow.
            EngineStartStep(
                getEngineState = { EngineRuntimeState.Running }
            ),
            InternetCheckStep(),
            DnsCheckStep(),
            LatencyStep(),
            SpeedSampleStep()
        )
        return SmartTester(steps)
    }
}
