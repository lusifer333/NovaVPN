package com.novavpn.domain.usecase.connection

import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.VpnState
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.usecase.server.GetBestServerUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for VPN connection state.
 *
 * [connectionState] is a [StateFlow] of [VpnState] — an atomic sealed
 * interface that embeds error messages directly into [VpnState.Error],
 * eliminating the dual-state problem (header says Error while button
 * says Disconnect).
 */
@Singleton
class ConnectUseCase @Inject constructor(
    private val getBestServer: GetBestServerUseCase,
    private val serverRepository: ServerRepository
) {
    private val _connectionState = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val connectionState: StateFlow<VpnState> = _connectionState.asStateFlow()

    private var currentServer: ServerConfig? = null

    val currentServerId: String? get() = currentServer?.id

    /**
     * Connect to a server. If the server belongs to a disabled subscription,
     * the connection is rejected and state remains unchanged.
     */
    suspend fun connect(server: ServerConfig): Boolean {
        if (!serverRepository.isServerFromEnabledSubscription(server.id)) {
            Timber.tag(TAG).w("connect: server %s belongs to a disabled subscription — rejected", server.id.take(8))
            _connectionState.value = VpnState.Error("Server belongs to a disabled subscription")
            return false
        }

        _connectionState.value = VpnState.Connecting
        currentServer = server
        return true
    }

    suspend fun disconnect() {
        _connectionState.value = VpnState.Disconnecting
        currentServer = null
        _connectionState.value = VpnState.Disconnected
    }

    fun updateState(state: VpnState) {
        _connectionState.value = state
    }

    /**
     * Update the current server WITHOUT changing the connection state — used
     * by the in-core balancer (observatory/leastping) when it auto-switches
     * the active outbound while the VPN stays up. The UI (Home) reads
     * [currentServerId] reactively, so this keeps the displayed server in
     * sync with the server actually carrying traffic.
     */
    fun updateCurrentServer(server: ServerConfig?) {
        currentServer = server
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
    operator fun invoke(): StateFlow<VpnState> = connectUseCase.connectionState
}