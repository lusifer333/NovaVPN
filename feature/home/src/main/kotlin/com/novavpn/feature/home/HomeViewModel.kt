package com.novavpn.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.ConnectionState
import com.novavpn.domain.model.ConnectionStats
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.domain.usecase.connection.ObserveConnectionStateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val currentServer: ServerConfig? = null,
    val serverList: List<ServerConfig> = emptyList(),
    val stats: ConnectionStats = ConnectionStats(),
    val isLoading: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectUseCase: ConnectUseCase,
    private val observeConnectionState: ObserveConnectionStateUseCase,
    private val serverRepository: ServerRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // Observe connection state
        viewModelScope.launch {
            observeConnectionState().collect { connState ->
                _state.update { it.copy(connectionState = connState) }
            }
        }

        // Observe servers
        viewModelScope.launch {
            serverRepository.observeAll().collect { servers ->
                _state.update { it.copy(serverList = servers) }
            }
        }
    }

    fun connect(server: ServerConfig) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                connectUseCase.connect(server)
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectUseCase.disconnect()
        }
    }

    fun autoConnectToBest() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val best = _state.value.serverList.firstOrNull()
                if (best != null) {
                    connectUseCase.connect(best)
                }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
