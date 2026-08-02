package com.novavpn.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.data.usecase.probe.FillMineUseCase
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import com.novavpn.domain.model.Subscription
import com.novavpn.domain.probe.MineCapacity
import com.novavpn.domain.probe.ProfileServers
import com.novavpn.domain.probe.ProbeOptions
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.SettingsRepository
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.domain.repository.MineRepository
import com.novavpn.domain.usecase.server.SelectServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    private val settingsRepository: SettingsRepository,
    private val fillMineUseCase: FillMineUseCase,
    private val mineRepository: MineRepository
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

        // Restore the persisted mine so a freshly-opened app doesn't show
        // an empty reservoir (the mine survives process death).
        viewModelScope.launch {
            mineRepository.observe().collect { mine ->
                _state.update { it.copy(mine = mine) }
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
     *
     * Runs entirely off the main thread:
     * - the engine sessions are chunked (≤100 outbounds per session, see
     *   [MineFiller.CHUNK_SIZE]) so Android's ~1024 RLIMIT_NOFILE is never
     *   blown, which was the O(N²) freeze with ~2000 servers;
     * - probe results are batched into the StateFlow at most every 100 ms
     *   instead of one emission per server (no recomposition storm).
     */
    fun fillMine() {
        val current = _state.value
        if (current.isFilling) return
        val profiles = current.profiles.map {
            ProfileServers(it.subscription.id, it.subscription.name, it.servers)
        }
        if (profiles.isEmpty() || profiles.sumOf { it.servers.size } == 0) return

        _state.update { it.copy(isFilling = true, mine = emptyList(), results = emptyMap()) }
        viewModelScope.launch(Dispatchers.Default) {
            val settings = settingsRepository.get()
            val options = ProbeOptions(
                fragmentTls = settings.enableTlsFragment,
                keepAlive = settings.enableTcpKeepAlive
            )
            val pending = LinkedHashMap<String, ServerProbeResult>()
            var lastEmitMs = 0L
            fun flushBatch() {
                if (pending.isEmpty()) return
                val batch = pending.toMap()
                pending.clear()
                lastEmitMs = System.currentTimeMillis()
                _state.update { it.copy(results = it.results + batch) }
            }

            val result = fillMineUseCase(
                profiles = profiles,
                options = options,
                onResult = { res ->
                    if (res.serverId.isNotBlank()) {
                        pending[res.serverId] = res
                        if (System.currentTimeMillis() - lastEmitMs >= 100L) flushBatch()
                    }
                },
                onMine = { mine -> _state.update { it.copy(mine = mine) } }
            )
            flushBatch()
            mineRepository.save(result.mine)
            _state.update { it.copy(isFilling = false, mine = result.mine, results = result.results) }
        }
    }
}
