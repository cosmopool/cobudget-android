package com.kaiodelphino.cobudget.ui.apps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiodelphino.cobudget.data.CobudgetDao
import com.kaiodelphino.cobudget.data.InstalledApp
import com.kaiodelphino.cobudget.data.MonitoredApp
import com.kaiodelphino.cobudget.data.loadLauncherApps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppRow(val app: InstalledApp, val monitored: Boolean)

data class AppsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val rows: List<AppRow> = emptyList(),
)

class AppsViewModel(
    private val dao: CobudgetDao,
    /** Application context. */
    private val context: Context,
) : ViewModel() {

    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val query = MutableStateFlow("")

    val state: StateFlow<AppsUiState> =
        combine(installed, dao.observeMonitoredApps(), query) { installed, monitored, query ->
            val monitoredPackages = monitored.mapTo(HashSet()) { it.packageName }
            val rows = installed.orEmpty()
                .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
                .map { AppRow(it, it.packageName in monitoredPackages) }
                .sortedByDescending { it.monitored } // stable sort keeps alphabetical order within groups
            AppsUiState(loading = installed == null, query = query, rows = rows)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { installed.value = loadLauncherApps(context) }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun setMonitored(app: InstalledApp, monitored: Boolean) {
        viewModelScope.launch {
            if (monitored) {
                dao.upsertMonitoredApp(MonitoredApp(app.packageName, app.label, System.currentTimeMillis()))
            } else {
                dao.deleteMonitoredApp(app.packageName)
            }
        }
    }
}
