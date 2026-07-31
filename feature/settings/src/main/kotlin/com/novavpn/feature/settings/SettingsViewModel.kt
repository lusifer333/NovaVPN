package com.novavpn.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.AppSettings
import com.novavpn.domain.model.EngineType
import com.novavpn.domain.model.ThemeMode
import com.novavpn.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoading: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.observe().collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
    }

    fun setEngine(engine: EngineType) {
        viewModelScope.launch { settingsRepository.setEngine(engine) }
    }

    fun setCustomDns(dns: String) {
        viewModelScope.launch { settingsRepository.setCustomDns(dns) }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoConnect(enabled) }
    }

    fun setTheme(theme: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.update(_state.value.settings.copy(theme = theme))
        }
    }

    fun setIPv6(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update(_state.value.settings.copy(enableIPv6 = enabled))
        }
    }

    fun setFakeDns(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update(_state.value.settings.copy(enableFakeDns = enabled))
        }
    }

    fun setBlockQuic(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update(_state.value.settings.copy(enableBlockQuic = enabled))
        }
    }

    fun setPerAppVpn(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update(_state.value.settings.copy(enablePerAppVpn = enabled))
        }
    }

    fun setAlwaysOn(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update(_state.value.settings.copy(enableAlwaysOnVpn = enabled))
        }
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update(_state.value.settings.copy(enableNotifications = enabled))
        }
    }
}
