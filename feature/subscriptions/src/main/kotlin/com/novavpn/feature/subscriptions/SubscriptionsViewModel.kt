package com.novavpn.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novavpn.domain.model.Subscription
import com.novavpn.domain.usecase.subscription.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SubscriptionsUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val showAddDialog: Boolean = false,
    val editSubscription: Subscription? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val observeSubscriptions: ObserveSubscriptionsUseCase,
    private val addSubscription: AddSubscriptionUseCase,
    private val deleteSubscription: DeleteSubscriptionUseCase,
    private val updateSubscription: UpdateSubscriptionUseCase,
    private val toggleSubscription: ToggleSubscriptionUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(SubscriptionsUiState())
    val state: StateFlow<SubscriptionsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeSubscriptions().collect { subs ->
                _state.update { it.copy(subscriptions = subs) }
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
            } else {
                addSubscription(name, url)
            }
            hideDialog()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            deleteSubscription(id)
        }
    }

    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            toggleSubscription(id, enabled)
        }
    }

    fun copyUrl(subscription: Subscription) {
        // Handled at UI layer with clipboard
    }
}
