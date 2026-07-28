package com.novavpn.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.ConnectionState
import com.novavpn.domain.model.ConnectionStats
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.domain.usecase.connection.ObserveConnectionStateUseCase
import com.novavpn.domain.usecase.connection.VpnServiceStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val currentServer: ServerConfig? = null,
    val selectedServer: ServerConfig? = null,
    val serverList: List<ServerConfig> = emptyList(),
    val stats: ConnectionStats = ConnectionStats(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectUseCase: ConnectUseCase,
    private val observeConnectionState: ObserveConnectionStateUseCase,
    private val serverRepository: ServerRepository,
    private val vpnServiceStarter: VpnServiceStarter
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // Observe connection state
        viewModelScope.launch {
            combine(
                observeConnectionState(),
                connectUseCase.lastError
            ) { connState, error ->
                val currentServer = if (connState == ConnectionState.Connected) {
                    connectUseCase.currentServerId?.let { id -> serverRepository.getById(id) }
                } else null
                HomeUiState(
                    connectionState = connState,
                    currentServer = currentServer,
                    selectedServer = _state.value.selectedServer,
                    serverList = _state.value.serverList,
                    errorMessage = if (connState == ConnectionState.Error) error else null
                )
            }.collect { newState ->
                _state.value = newState
            }
        }

        // Observe selectable servers
        viewModelScope.launch {
            serverRepository.observeSelectable().collect { servers ->
                _state.update { it.copy(serverList = servers) }
            }
        }

        // Observe last connected server reactively
        viewModelScope.launch {
            serverRepository.observeLastConnected().collect { server ->
                _state.update { it.copy(selectedServer = server) }
            }
        }
    }

    fun connect(server: ServerConfig) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val accepted = connectUseCase.connect(server)
                if (accepted) {
                    vpnServiceStarter.startVpn(server)
                }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun connectToSelected() {
        val server = _state.value.selectedServer ?: return
        connect(server)
    }

    /**
     * Force disconnect — stops service + engine + resets state.
     * Works from any state (Connected, Error, Connecting).
     */
    fun disconnect() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                vpnServiceStarter.stopVpn()
                connectUseCase.disconnect()
            } finally {
                _state.update {
                    it.copy(isLoading = false, errorMessage = null)
                }
            }
        }
    }

    fun autoConnectToBest() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val best = _state.value.serverList.firstOrNull()
                if (best != null) {
                    val accepted = connectUseCase.connect(best)
                    if (accepted) {
                        vpnServiceStarter.startVpn(best)
                    }
                }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Called after VPN permission is granted — retry the last connect. */
    fun retryConnect() {
        val server = _state.value.selectedServer
        if (server != null) connect(server)
    }

    /** Called when VPN permission is denied by user. */
    fun onVpnPermissionDenied() {
        connectUseCase.updateState(ConnectionState.Error)
        _state.update { it.copy(connectionState = ConnectionState.Error, errorMessage = "VPN permission denied") }
    }
}