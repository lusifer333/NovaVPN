package com.novavpn.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.ConnectionState
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.StatisticsRepository
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.domain.usecase.connection.ObserveConnectionStateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ServersUiState(
    val servers: List<ServerConfig> = emptyList(),
    val connectedServerId: String? = null,
    val favoriteIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isConnected: Boolean = false
)

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val connectUseCase: ConnectUseCase,
    private val observeConnectionState: ObserveConnectionStateUseCase,
    private val statisticsRepository: StatisticsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ServersUiState())
    val state: StateFlow<ServersUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            serverRepository.observeAll().collect { servers ->
                Timber.tag("ServersVM").w("[DEBUG-servers] observeAll emitted: %d servers", servers.size)
                servers.forEach { s ->
                    Timber.tag("ServersVM").d("[DEBUG-servers] server: id=%s name=%s subId=%s", s.id.take(8), s.name, s.subscriptionId.take(8))
                }
                _state.update { it.copy(servers = servers) }
            }
        }

        viewModelScope.launch {
            observeConnectionState().collect { connState ->
                _state.update {
                    it.copy(
                        isConnected = connState == ConnectionState.Connected,
                        connectedServerId = if (connState == ConnectionState.Connected)
                            connectUseCase.currentServerId else null
                    )
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

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

    val filteredServers: List<ServerConfig>
        get() {
            val query = _state.value.searchQuery.trim().lowercase()
            return if (query.isBlank()) _state.value.servers
            else _state.value.servers.filter {
                it.name.lowercase().contains(query) ||
                it.address.lowercase().contains(query) ||
                it.protocol.displayName.lowercase().contains(query)
            }
        }
}
