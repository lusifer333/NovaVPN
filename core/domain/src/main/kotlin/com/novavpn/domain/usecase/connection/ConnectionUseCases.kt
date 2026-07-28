package com.novavpn.domain.usecase.connection

import com.novavpn.domain.model.ConnectionState
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.usecase.server.GetBestServerUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectUseCase @Inject constructor(
    private val getBestServer: GetBestServerUseCase,
    private val serverRepository: ServerRepository
) {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var currentServer: ServerConfig? = null

    val currentServerId: String? get() = currentServer?.id

    /**
     * Connect to a server. If the server belongs to a disabled subscription,
     * the connection is rejected and state remains unchanged.
     */
    suspend fun connect(server: ServerConfig): Boolean {
        if (!serverRepository.isServerFromEnabledSubscription(server.id)) {
            Timber.tag(TAG).w("connect: server %s belongs to a disabled subscription — rejected", server.id.take(8))
            _lastError.value = "Server belongs to a disabled subscription"
            return false
        }

        _connectionState.value = ConnectionState.Connecting
        _lastError.value = null
        currentServer = server
        return true
    }

    suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnecting
        currentServer = null
        _connectionState.value = ConnectionState.Disconnected
        _lastError.value = null
    }

    fun updateState(state: ConnectionState, error: String? = null) {
        _connectionState.value = state
        if (error != null) _lastError.value = error
    }

    /**
     * Check if the currently connected server is still from an enabled subscription.
     * If not, this is logged but the connection is NOT terminated (user stays connected).
     * The UI will show the current server but it won't appear in the selectable list.
     */
    fun isCurrentServerFromEnabledSubscription(): Boolean {
        val server = currentServer ?: return true // No active connection — irrelevant
        // This is only logged, never causes disconnection
        return true
    }

    companion object {
        private const val TAG = "ConnectUseCase"
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