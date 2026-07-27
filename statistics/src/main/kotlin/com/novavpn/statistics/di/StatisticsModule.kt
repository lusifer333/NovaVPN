package com.novavpn.statistics.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Statistics module — can be extended with bindings for ScoreCalculator
 * or related dependencies as needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object StatisticsModule
