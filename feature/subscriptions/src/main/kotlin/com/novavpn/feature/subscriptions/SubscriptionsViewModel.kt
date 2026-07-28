package com.novavpn.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.data.usecase.RefreshSubscriptionUseCase
import com.novavpn.domain.model.Subscription
import com.novavpn.domain.repository.ServerRepository
import com.novavpn.domain.repository.StatisticsRepository
import com.novavpn.domain.usecase.subscription.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubscriptionsUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val serverCounts: Map<String, Int> = emptyMap(),
    val showAddDialog: Boolean = false,
    val editSubscription: Subscription? = null,
    val refreshingIds: Set<String> = emptySet(),
    val snackbarMessage: String? = null
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val observeSubscriptions: ObserveSubscriptionsUseCase,
    private val addSubscription: AddSubscriptionUseCase,
    private val deleteSubscription: DeleteSubscriptionUseCase,
    private val updateSubscription: UpdateSubscriptionUseCase,
    private val toggleSubscription: ToggleSubscriptionUseCase,
    private val refreshSubscription: RefreshSubscriptionUseCase,
    private val serverRepository: ServerRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SubscriptionsUiState())
    val state: StateFlow<SubscriptionsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeSubscriptions().collect { subs ->
                // Build server count map for each subscription
                val counts = mutableMapOf<String, Int>()
                for (sub in subs) {
                    val servers = serverRepository.observeBySubscription(sub.id).firstOrNull()
                    counts[sub.id] = servers?.size ?: 0
                }
                _state.update { it.copy(subscriptions = subs, serverCounts = counts) }
            }
        }
    }

    fun showAddDialog() {
        _state.update { it.copy(showAddDialog = true, editSubscription = null) }
    }

    fun showEditDialog(subscription: Subscription) {
        _state.update { it.copy(showAddDialog = true, editSubscription = subscription) }
    }

    fun hideDialog() {
        _state.update { it.copy(showAddDialog = false, editSubscription = null) }
    }

    fun addOrUpdate(name: String, url: String) {
        viewModelScope.launch {
            val existing = _state.value.editSubscription
            if (existing != null) {
                updateSubscription(existing.copy(name = name, url = url))
                _state.update { it.copy(snackbarMessage = "Subscription updated") }
            } else {
                val id = addSubscription(name, url)
                // Auto-fetch after adding
                refreshSubscription(id)
                _state.update { it.copy(snackbarMessage = "Subscription added") }
            }
            hideDialog()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            deleteSubscription(id)
            _state.update { it.copy(snackbarMessage = "Subscription deleted") }
        }
    }

    fun refresh(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(refreshingIds = _state.value.refreshingIds + id) }
            val result = refreshSubscription(id)
            result.onFailure { error ->
                _state.update { it.copy(
                    refreshingIds = it.refreshingIds - id,
                    snackbarMessage = "Refresh failed: ${error.message ?: "unknown error"}"
                )}
                return@launch
            }
            _state.update {
                it.copy(
                    refreshingIds = it.refreshingIds - id,
                    snackbarMessage = "Subscription updated (${result.getOrDefault(emptyList()).size} servers)"
                )
            }
        }
    }

    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            toggleSubscription(id, enabled)
        }
    }

    fun clearSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }
}
