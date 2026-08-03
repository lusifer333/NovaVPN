package com.novavpn.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.ConnectionStats
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.VpnState
import com.novavpn.domain.probe.ProbeOptions
import com.novavpn.domain.probe.ProfileServers
import com.novavpn.domain.repository.MineRepository
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.SettingsRepository
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.domain.usecase.connection.ObserveConnectionStateUseCase
import com.novavpn.domain.usecase.connection.VpnServiceStarter
import com.novavpn.data.usecase.probe.FillMineUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A single active config-parameter badge shown on Home.
 *
 * @param letter single-letter minimal label (F = TLS Fragment, Q = Block
 *   QUIC, K = TCP Keep-Alive, D = FakeDNS).
 * @param label full parameter name (accessibility/contentDescription).
 */
data class ConfigBadge(
    val letter: String,
    val label: String
)

/**
 * Single atomic UI state.
 *
 * [vpnState] is a [VpnState] sealed interface that carries error
 * messages internally via [VpnState.Error.message] — no separate
 * [errorMessage] field that can fall out of sync.
 */
data class HomeUiState(
    val vpnState: VpnState = VpnState.Disconnected,
    val currentServer: ServerConfig? = null,
    val selectedServer: ServerConfig? = null,
    val serverList: List<ServerConfig> = emptyList(),
    val stats: ConnectionStats = ConnectionStats(),
    val isLoading: Boolean = false,
    /** Minimal letter badges for the ACTIVE connection-shaping params
     *  (only populated while Connected). */
    val activeBadges: List<ConfigBadge> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectUseCase: ConnectUseCase,
    private val observeConnectionState: ObserveConnectionStateUseCase,
    private val serverRepository: ServerRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: SettingsRepository,
    private val fillMineUseCase: FillMineUseCase,
    private val vpnServiceStarter: VpnServiceStarter,
    private val mineRepository: MineRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // Observe connection state — single source of truth from the service —
        // combined with the settings so the ACTIVE config-parameter badges stay
        // in sync with what the engine is actually running (read from the same
        // SettingsRepository the service consumes at connect time).
        viewModelScope.launch {
            combine(
                observeConnectionState(),
                settingsRepository.observe()
            ) { vpnState, settings ->
                val currentServer = if (vpnState is VpnState.Connected) {
                    connectUseCase.currentServerId?.let { id -> serverRepository.getById(id) }
                } else null
                val badges = if (vpnState == VpnState.Connected) {
                    buildList {
                        if (settings.enableBlockQuic) add(ConfigBadge("Q", "Block QUIC"))
                        if (settings.enableTlsFragment) add(ConfigBadge("F", "TLS Fragment"))
                        if (settings.enableTcpKeepAlive) add(ConfigBadge("K", "TCP Keep-Alive"))
                        if (settings.enableFakeDns) add(ConfigBadge("D", "FakeDNS"))
                    }
                } else emptyList()
                _state.update {
                    it.copy(
                        vpnState = vpnState,
                        currentServer = currentServer,
                        activeBadges = badges
                    )
                }
            }.collect {}
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
            _state.update { it.copy(isLoading = true) }
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
     * Force disconnect — works from any state
     * (Connected, Error, Connecting).
     */
    fun disconnect() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                vpnServiceStarter.stopVpn()
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Auto-connect, Karing-style: when Auto Connect is enabled, run a real
     * mine fill (chunked, off the main thread) and connect to the fastest
     * healthy relay instead of blindly grabbing the first server in the
     * catalog (which was effectively random and offered no speed benefit).
     *
     * Honors the "Auto Connect" setting: when it is OFF we fall back to the
     * last connected server (or the first selectable one) as before.
     */
    fun autoConnectToBest() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val settings = settingsRepository.get()
                val best = if (settings.enableAutoConnect) {
                    fastestHealthyRelay()
                } else {
                    _state.value.selectedServer ?: _state.value.serverList.firstOrNull()
                }
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

    /**
     * The reservoir of the mine fill, reduced to its fastest healthy
     * relay. Mirrors the engine-session settings (TLS fragment, TCP
     * keepalive) so the verdict matches the real connect path. Returns
     * null when nothing is healthy.
     */
    private suspend fun fastestHealthyRelay(): ServerConfig? {
        val subscriptions = subscriptionRepository.observeAll().first()
        val servers = serverRepository.observeSelectable().first()
        if (subscriptions.isEmpty() || servers.isEmpty()) return null
        val bySubscription = servers.groupBy { it.subscriptionId }
        val profiles = subscriptions
            .filter { it.isEnabled && bySubscription.containsKey(it.id) }
            .map { ProfileServers(it.id, it.name, bySubscription[it.id].orEmpty()) }
        if (profiles.isEmpty() || profiles.sumOf { it.servers.size } == 0) return null

        // Probe with the SAME connection-shaping settings the real connect will
        // apply — a server probed plain can be marked healthy and then fail the
        // real connect when TLS Fragment / TCP Keep-Alive are ON (v0.16.30).
        val settings = settingsRepository.get()
        val options = ProbeOptions(
            fragmentTls = settings.enableTlsFragment,
            keepAlive = settings.enableTcpKeepAlive
        )
        val result = fillMineUseCase(profiles, options, previousMine = mineRepository.get())
        return result.mine.firstOrNull()
    }

    /** Called after VPN permission is granted — retry the last connect. */
    fun retryConnect() {
        val server = _state.value.selectedServer
        if (server != null) connect(server)
    }

    /** Called when VPN permission is denied by user. */
    fun onVpnPermissionDenied() {
        connectUseCase.updateState(VpnState.Error("VPN permission denied"))
    }
}
