package com.novavpn.domain.usecase.connection

import com.novavpn.domain.model.ConnectionState
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.usecase.server.GetBestServerUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectUseCase @Inject constructor(
    private val getBestServer: GetBestServerUseCase
) {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var currentServer: ServerConfig? = null

    val currentServerId: String? get() = currentServer?.id

    suspend fun connect(server: ServerConfig) {
        _connectionState.value = ConnectionState.Connecting
        currentServer = server
    }

    suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnecting
        currentServer = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun updateState(state: ConnectionState) {
        _connectionState.value = state
    }
}

@Singleton
class AutoConnectUseCase @Inject constructor(
    private val getBestServer: GetBestServerUseCase,
    private val connect: ConnectUseCase
) {
    suspend fun connectToBest() {
        val best = getBestServer() ?: return
        connect.connect(best)
    }
}

@Singleton
class ObserveConnectionStateUseCase @Inject constructor(
    private val connectUseCase: ConnectUseCase
) {
    operator fun invoke(): StateFlow<ConnectionState> = connectUseCase.connectionState
}
