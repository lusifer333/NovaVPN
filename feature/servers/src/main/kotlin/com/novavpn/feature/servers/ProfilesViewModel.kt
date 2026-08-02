package com.novavpn.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import com.novavpn.domain.model.Subscription
import com.novavpn.domain.probe.MineCapacity
import com.novavpn.domain.probe.MineFiller
import com.novavpn.domain.probe.ProfileServers
import com.novavpn.domain.probe.RealDelayProber
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.domain.usecase.server.SelectServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One expandable subscription group on the profiles screen. */
data class ProfileUi(
    val subscription: Subscription,
    val servers: List<ServerConfig>,
    val isExpanded: Boolean = false
)

data class ProfilesUiState(
    val profiles: List<ProfileUi> = emptyList(),
    val mine: List<ServerConfig> = emptyList(),
    val mineCapacity: Int = 0,
    val results: Map<String, ServerProbeResult> = emptyMap(),
    val isFilling: Boolean = false,
    val selectedServerId: String? = null
)

/**
 * Profiles screen state holder.
 *
 * Deliberately has NO auto-test on open: the page shows the mine and the
 * per-subscription profile lists; the user explicitly triggers «Fill
 * Mine» (پر کردن معدن). Profile menus default to COLLAPSED.
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val serverRepository: ServerRepository,
    private val selectServerUseCase: SelectServerUseCase,
    private val realDelayProber: RealDelayProber
) : ViewModel() {

    private val _state = MutableStateFlow(ProfilesUiState())
    val state: StateFlow<ProfilesUiState> = _state.asStateFlow()

    init {
        // Profiles = enabled subscriptions that have servers. Expand state
        // survives data reloads (subscription refresh etc.).
        viewModelScope.launch {
            combine(
                subscriptionRepository.observeAll(),
                serverRepository.observeSelectable()
            ) { subscriptions, servers ->
                val bySubscription = servers.groupBy { it.subscriptionId }
                subscriptions
                    .filter { it.isEnabled && bySubscription.containsKey(it.id) }
                    .map { sub -> sub to (bySubscription[sub.id].orEmpty()) }
            }
                .distinctUntilChanged()
                .collect { grouped ->
                    _state.update { current ->
                        val newProfiles = grouped.map { (sub, subServers) ->
                            val old = current.profiles.firstOrNull { it.subscription.id == sub.id }
                            ProfileUi(sub, subServers, old?.isExpanded ?: false)
                        }
                        current.copy(
                            profiles = newProfiles,
                            mineCapacity = MineCapacity.capacityOf(newProfiles.sumOf { it.servers.size })
                        )
                    }
                }
        }

        // Track last connected server (selection highlight).
        viewModelScope.launch {
            serverRepository.observeLastConnected().collect { server ->
                _state.update { it.copy(selectedServerId = server?.id) }
            }
        }
    }

    fun toggleProfile(subscriptionId: String) {
        _state.update { current ->
            current.copy(
                profiles = current.profiles.map {
                    if (it.subscription.id == subscriptionId) it.copy(isExpanded = !it.isExpanded) else it
                }
            )
        }
    }

    fun selectServer(server: ServerConfig) {
        viewModelScope.launch {
            selectServerUseCase(server.id)
            _state.update { it.copy(selectedServerId = server.id) }
        }
    }

    /**
     * «پر کردن معدن» — single Karing-style urltest fill:
     * every server is probed with a real HTTP round-trip through the
     * shared engine; results stream in completion order (best-first) and
     * the fill stops the moment the mine is full. Per-profile shares are
     * respected.
     */
    fun fillMine() {
        val current = _state.value
        if (current.isFilling) return
        val profiles = current.profiles.map {
            ProfileServers(it.subscription.id, it.subscription.name, it.servers)
        }
        if (profiles.isEmpty() || profiles.sumOf { it.servers.size } == 0) return

        _state.update { it.copy(isFilling = true, mine = emptyList(), results = emptyMap()) }
        viewModelScope.launch {
            val result = MineFiller(realDelayProber).fill(
                profiles = profiles,
                onResult = { res ->
                    if (res.serverId.isNotBlank()) {
                        _state.update { it.copy(results = it.results + (res.serverId to res)) }
                    }
                },
                onMine = { mine -> _state.update { it.copy(mine = mine) } }
            )
            _state.update { it.copy(isFilling = false, mine = result.mine, results = result.results) }
        }
    }
}
