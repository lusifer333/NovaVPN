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
    val logCounts: Map<LogLevel, Int> = emptyMap()
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val novaLogger: NovaLogger
) : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    init {
        // Collect live logs from NovaLogger (in-memory circular buffer)
        viewModelScope.launch {
            novaLogger.logFlow.collect { entry ->
                _state.update { current ->
                    val newList = (current.entries + entry).takeLast(500)
                    current.copy(entries = newList)
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

    fun refreshLogCounts() {
        viewModelScope.launch {
            val counts = logRepository.countByLevel()
            _state.update { it.copy(logCounts = counts) }
        }
    }
}
