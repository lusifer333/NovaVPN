package com.novavpn.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import com.novavpn.domain.model.VpnState
import com.novavpn.domain.probe.ServerProber
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.StatisticsRepository
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.domain.usecase.connection.ObserveConnectionStateUseCase
import com.novavpn.domain.usecase.server.SelectServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServersUiState(
    val servers: List<ServerConfig> = emptyList(),
    val selectedServerId: String? = null,
    val connectedServerId: String? = null,
    val favoriteIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isConnected: Boolean = false,
    val testResults: Map<String, ServerProbeResult> = emptyMap(),
    val isTesting: Boolean = false
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val connectUseCase: ConnectUseCase,
    private val observeConnectionState: ObserveConnectionStateUseCase,
    private val statisticsRepository: StatisticsRepository,
    private val selectServerUseCase: SelectServerUseCase,
    private val serverProber: ServerProber
) : ViewModel() {

    private val _state = MutableStateFlow(ServersUiState())
    val state: StateFlow<ServersUiState> = _state.asStateFlow()

    private var hasAutoTested = false

    init {
        // Observe selectable servers (from enabled subscriptions only)
        viewModelScope.launch {
            serverRepository.observeSelectable().collect { servers ->
                _state.update { it.copy(servers = servers) }
                if (servers.isNotEmpty() && !hasAutoTested) {
                    hasAutoTested = true
                    refreshTests()
                }
            }
        }

        // Observe connection state
        viewModelScope.launch {
            observeConnectionState().collect { vpnState ->
                val connectedId = if (vpnState is VpnState.Connected)
                    connectUseCase.currentServerId else null
                _state.update {
                    it.copy(
                        isConnected = vpnState is VpnState.Connected,
                        connectedServerId = connectedId
                    )
                }
            }
        }

        // Observe last connected server reactively (tracks selection)
        viewModelScope.launch {
            serverRepository.observeLastConnected().collect { server ->
                _state.update { it.copy(selectedServerId = server?.id) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    /**
     * Select a server for later connection.
     * This persists the choice and updates UI — does NOT start the engine.
     */
    fun selectServer(server: ServerConfig) {
        viewModelScope.launch {
            // Persist the selection
            selectServerUseCase(server.id)
            // Update UI state immediately
            _state.update { it.copy(selectedServerId = server.id) }
        }
    }

    /**
     * Connect to a server immediately.
     * Used from HomeScreen; on ServersScreen we use selectServer() instead.
     */
    fun connectTo(server: ServerConfig) {
        viewModelScope.launch {
            connectUseCase.connect(server)
        }
    }

    fun toggleFavorite(serverId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            serverRepository.setFavourite(serverId, isFavorite)
        }
    }

    /**
     * Two-stage fast server test:
     * 1) TCP RTT for all servers (parallel) — results appear immediately.
     * 2) TLS handshake only for the ones that passed stage 1 — then merge.
     */
    fun refreshTests() {
        val servers = _state.value.servers
        if (servers.isEmpty() || _state.value.isTesting) return
        _state.update { it.copy(isTesting = true) }
        viewModelScope.launch {
            val stage1 = serverProber.fastProbeAll(servers)
            _state.update { it.copy(testResults = stage1) }
            val merged = serverProber.tlsProbeAll(servers, stage1)
            _state.update { it.copy(testResults = merged, isTesting = false) }
        }
    }
}