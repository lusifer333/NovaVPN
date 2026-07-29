package com.novavpn.feature.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.LogEntry
import com.novavpn.domain.model.LogLevel
import com.novavpn.domain.repository.LogRepository
import com.novavpn.logging.NovaLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogsUiState(
    val entries: List<LogEntry> = emptyList(),
    val filterLevel: LogLevel? = null,
    val searchQuery: String = "",
    val logCounts: Map<LogLevel, Int> = emptyMap(),
    val rawLogText: String = ""
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val novaLogger: NovaLogger
) : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    init {
        // 1. Load existing buffer in one batch (prevents flash from 1000 replay items)
        val existing = novaLogger.getRecent(500)
        val rawText = existing.joinToString("\n") { entry ->
            "[${entry.level.name.uppercase()}] ${entry.tag}: ${entry.message}"
        }
        _state.update { it.copy(entries = existing, rawLogText = rawText) }

        // 2. Collect new log entries as they arrive
        viewModelScope.launch {
            novaLogger.logFlow.collect { entry ->
                _state.update { current ->
                    val newList = (current.entries + entry).takeLast(500)
                    val newRaw = if (current.rawLogText.length < 500_000) {
                        val line = "[${entry.level.name.uppercase()}] ${entry.tag}: ${entry.message}"
                        (current.rawLogText + "\n" + line).takeLast(50_000)
                    } else current.rawLogText
                    current.copy(entries = newList, rawLogText = newRaw)
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setFilterLevel(level: LogLevel?) {
        _state.update { it.copy(filterLevel = level) }
    }

    suspend fun copyLogs(): String {
        return logRepository.export(_state.value.filterLevel)
    }

    suspend fun exportLogs(): String {
        return logRepository.export(_state.value.filterLevel)
    }

    fun clearLogs() {
        viewModelScope.launch {
            logRepository.clear()
        }
    }

    fun addTestLog() {
        novaLogger.d("TEST_LOG_WORKING", "If you see this, the logging pipeline is working ✅")
    }

    fun refreshLogCounts() {
        viewModelScope.launch {
            val counts = logRepository.countByLevel()
            _state.update { it.copy(logCounts = counts) }
        }
    }
}
