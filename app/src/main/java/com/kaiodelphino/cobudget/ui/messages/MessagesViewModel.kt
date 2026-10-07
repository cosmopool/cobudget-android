package com.kaiodelphino.cobudget.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiodelphino.cobudget.data.CapturedNotification
import com.kaiodelphino.cobudget.data.MonitoredApp
import com.kaiodelphino.cobudget.data.CobudgetDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.kaiodelphino.cobudget.ui.transactions.nicknameMap

data class MessagesUiState(
    val messages: List<CapturedNotification> = emptyList(),
    val apps: List<MonitoredApp> = emptyList(),
    val selectedPackage: String? = null,
    /** Merchant key → nickname. */
    val nicknames: Map<String, String> = emptyMap(),
)

class MessagesViewModel(private val dao: CobudgetDao) : ViewModel() {

    private val selectedPackage = MutableStateFlow<String?>(null)

    val state: StateFlow<MessagesUiState> =
        combine(dao.observePending(), dao.observeMonitoredApps(), selectedPackage, dao.observeNicknames()) { messages, apps, selected, nicknames ->
            MessagesUiState(
                messages = if (selected == null) messages else messages.filter { it.packageName == selected },
                apps = apps,
                selectedPackage = selected,
                nicknames = nicknameMap(nicknames),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessagesUiState())

    fun select(packageName: String?) {
        selectedPackage.value = packageName
    }

    fun dismiss(id: Long) {
        viewModelScope.launch { dao.dismiss(id) }
    }
}
