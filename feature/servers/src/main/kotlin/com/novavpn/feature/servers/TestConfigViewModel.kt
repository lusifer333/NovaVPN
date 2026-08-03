package com.novavpn.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.data.usecase.probe.FillMineUseCase
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import com.novavpn.domain.probe.ProfileServers
import com.novavpn.domain.probe.ProbeOptions
import com.novavpn.domain.repository.MineRepository
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.SettingsRepository
import com.novavpn.domain.repository.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the config-test results: server + live verdict. */
data class TestResultRow(
    val server: ServerConfig,
    val ok: Boolean,
    val e2eMs: Long?
)

/** UI state for the config-test screen (Karing-style URL test). */
data class TestConfigUiState(
    val selectedUrl: String = "https://www.gstatic.com/generate_204",
    val timeoutSec: Int = 15,
    val isTesting: Boolean = false,
    /** Server → probe result, updated live as the wave completes. */
    val results: Map<String, TestResultRow> = emptyMap(),
    val testedCount: Int = 0,
    val totalCount: Int = 0
)

/**
 * Karing-style config-test screen state holder.
 *
 * Reuses the proven mine-fill wave (one shared probe engine session, bounded
 * parallelism) but with the USER-SELECTED test URL + timeout from the Karing
 * settings, and streams results live. "Test All" tests every selectable
 * server (not just the mine) so the user can see which configs work with the
 * chosen URL before connecting.
 */
@HiltViewModel
class TestConfigViewModel @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val fillMineUseCase: FillMineUseCase,
    private val mineRepository: MineRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TestConfigUiState())
    val state: StateFlow<TestConfigUiState> = _state.asStateFlow()

    private var testJob: Job? = null

    init {
        // Load the persisted URL + timeout once.
        viewModelScope.launch {
            val s = settingsRepository.get()
            _state.update {
                it.copy(selectedUrl = s.urlTestUrl, timeoutSec = s.urlTestTimeoutSec)
            }
        }
    }

    fun selectUrl(url: String) {
        _state.update { it.copy(selectedUrl = url) }
        viewModelScope.launch { settingsRepository.setUrlTestUrl(url) }
    }

    fun setTimeout(seconds: Int) {
        _state.update { it.copy(timeoutSec = seconds) }
        viewModelScope.launch { settingsRepository.setUrlTestTimeout(seconds) }
    }

    /** Test ALL selectable servers with the chosen URL + timeout. */
    fun testAll() {
        if (_state.value.isTesting) return
        viewModelScope.launch {
            val subscriptions = subscriptionRepository.observeAll().first()
            val servers = serverRepository.observeSelectable().first()
            if (subscriptions.isEmpty() || servers.isEmpty()) return@launch

            val bySubscription = servers.groupBy { it.subscriptionId }
            val profiles = subscriptions
                .filter { it.isEnabled && bySubscription.containsKey(it.id) }
                .map { ProfileServers(it.id, it.name, bySubscription[it.id].orEmpty()) }
            if (profiles.isEmpty() || profiles.sumOf { it.servers.size } == 0) return@launch

            val settings = settingsRepository.get()
            val options = ProbeOptions(
                fragmentTls = settings.enableTlsFragment,
                keepAlive = settings.enableTcpKeepAlive,
                url = _state.value.selectedUrl,
                timeoutMs = _state.value.timeoutSec * 1000
            )
            val total = profiles.sumOf { it.servers.size }

            _state.update {
                it.copy(isTesting = true, results = emptyMap(), testedCount = 0, totalCount = total)
            }
            testJob?.cancel()
            testJob = viewModelScope.launch(Dispatchers.Default) {
                try {
                    fillMineUseCase(
                        profiles = profiles,
                        options = options,
                        previousMine = mineRepository.get(),
                        onResult = { res: ServerProbeResult ->
                            _state.update { st ->
                                val server = servers.firstOrNull { it.id == res.serverId }
                                val row = server?.let {
                                    TestResultRow(it, res.e2eOk, res.e2eMs)
                                }
                                val results = if (server != null && row != null) {
                                    st.results + (res.serverId to row)
                                } else st.results
                                st.copy(
                                    results = results,
                                    testedCount = st.testedCount + 1
                                )
                            }
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // a failed fill must never crash the screen
                } finally {
                    _state.update { it.copy(isTesting = false) }
                }
            }
        }
    }

    fun stopTest() {
        testJob?.cancel()
        testJob = null
    }
}
