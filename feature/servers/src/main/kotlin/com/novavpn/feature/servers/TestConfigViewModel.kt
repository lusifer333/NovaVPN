package com.novavpn.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.data.usecase.probe.FillMineUseCase
import com.novavpn.domain.model.ServerConfig
import com.novavpn.domain.model.ServerProbeResult
import com.novavpn.domain.model.VpnState
import com.novavpn.domain.probe.ProfileServers
import com.novavpn.domain.probe.ProbeOptions
import com.novavpn.domain.repository.MineRepository
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.SettingsRepository
import com.novavpn.domain.repository.SubscriptionRepository
import com.novavpn.domain.repository.TestResultRepository
import com.novavpn.domain.probe.TestResultEntry
import com.novavpn.domain.usecase.connection.ConnectUseCase
import com.novavpn.domain.usecase.connection.VpnServiceStarter
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

/** One row of the config-test results: server + live verdict + selectable. */
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
    private val mineRepository: MineRepository,
    private val testResultRepository: TestResultRepository,
    private val connectUseCase: ConnectUseCase,
    private val vpnServiceStarter: VpnServiceStarter
) : ViewModel() {

    private val _state = MutableStateFlow(TestConfigUiState())
    val state: StateFlow<TestConfigUiState> = _state.asStateFlow()

    private var testJob: Job? = null

    init {
        // Load the persisted URL + timeout, and any previously persisted test
        // results (request: tested servers stay listed across restarts until a
        // NEW re-test of the same server fails).
        viewModelScope.launch {
            val s = settingsRepository.get()
            val persisted = testResultRepository.get()
            val servers = serverRepository.observeAll().first()
            val restored = persisted.mapNotNull { entry ->
                val server = servers.firstOrNull { it.id == entry.serverId } ?: return@mapNotNull null
                TestResultRow(server, entry.ok, entry.e2eMs)
            }
            _state.update {
                it.copy(
                    selectedUrl = s.urlTestUrl,
                    timeoutSec = s.urlTestTimeoutSec,
                    results = restored.associateBy { it.server.id }
                )
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

    /** Test ALL servers (every subscription, enabled or not) with the chosen URL + timeout. */
    fun testAll() {
        if (_state.value.isTesting) return
        viewModelScope.launch {
            val subscriptions = subscriptionRepository.observeAll().first()
            val servers = serverRepository.observeAll().first()
            if (subscriptions.isEmpty() || servers.isEmpty()) return@launch

            val bySubscription = servers.groupBy { it.subscriptionId }
            val profiles = subscriptions
                .filter { bySubscription.containsKey(it.id) }
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
                    // Live results are batched (100ms) so a large list never
                    // thrashes the UI thread with one recomposition per probe.
                    val pending = LinkedHashMap<String, TestResultRow>()
                    var lastEmitMs = 0L
                    var tested = 0
                    fun flushBatch() {
                        if (pending.isEmpty()) return
                        val batch = pending.toMap()
                        pending.clear()
                        lastEmitMs = System.currentTimeMillis()
                        _state.update { st ->
                            var results = st.results
                            // A failed re-test removes the server from the list
                            // entirely (request); only healthy pings keep a row.
                            batch.forEach { (id, row) ->
                                results = if (row.ok) results + (id to row) else results - id
                            }
                            st.copy(results = results, testedCount = tested)
                        }
                    }
                    fillMineUseCase(
                        profiles = profiles,
                        options = options,
                        previousMine = mineRepository.get(),
                        onResult = { res: ServerProbeResult ->
                            val server = servers.firstOrNull { it.id == res.serverId }
                            if (server == null) return@onResult
                            tested++
                            // Buffer every probe; the batch decides ok→add,
                            // fail→remove at flush time.
                            pending[res.serverId] = TestResultRow(server, res.e2eOk, res.e2eMs)
                            if (System.currentTimeMillis() - lastEmitMs >= 100L) flushBatch()
                        }
                    )
                    flushBatch()
                    persistResults()
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

    /**
     * Persist the current result rows to the TestResultRepository so the
     * tested servers stay listed (with their last ping) across app restarts.
     * A server is only removed later when a NEW re-test of it fails — the
     * persistence itself never prunes rows.
     */
    private suspend fun persistResults() {
        val rows = _state.value.results.values
        val entries = rows.map { r ->
            TestResultEntry(
                serverId = r.server.id,
                ok = r.ok,
                e2eMs = r.e2eMs,
                lastTestedAt = System.currentTimeMillis()
            )
        }
        runCatching { testResultRepository.save(entries) }
    }

    fun stopTest() {
        testJob?.cancel()
        testJob = null
    }

    /** Tap on a tested server → connect to it right away (Karing parity:
     *  the config-test list is directly actionable). The UI navigates back
     *  to Home after a successful select.
     *
     *  Crash-guard (device crash report, MIUI): [VpnServiceStarter.startVpn]
     *  calls `startForegroundService`, which can throw synchronously
     *  (e.g. `ForegroundServiceStartNotAllowedException`/`IllegalStateException`
     *  when FGS start is restricted). A tap must NEVER crash the app — on
     *  failure we surface VpnState.Error and stay on the screen instead.
     */
    fun selectServer(server: ServerConfig) {
        if (_state.value.isTesting) return
        viewModelScope.launch {
            try {
                val accepted = connectUseCase.connect(server)
                if (accepted) {
                    vpnServiceStarter.startVpn(server)
                    onServerSelected?.invoke()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectUseCase.updateState(
                    VpnState.Error("Failed to start VPN: ${e.message}")
                )
            }
        }
    }

    /** Set once by the screen so a successful select pops back to Home. */
    var onServerSelected: (() -> Unit)? = null
}
