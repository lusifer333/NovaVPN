package com.novavpn.logging.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Logging module — can be extended with bindings for [NovaLogger]
 * or related logging dependencies as needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule
