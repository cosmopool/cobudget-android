package com.kaiodelphino.cobudget.ui.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiodelphino.cobudget.CobudgetApp
import com.kaiodelphino.cobudget.data.exportBackup
import com.kaiodelphino.cobudget.data.importBackup
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: CobudgetApp) : ViewModel() {

    var status: String? by mutableStateOf(null)
        private set

    fun export(uri: Uri) {
        viewModelScope.launch {
            status = runCatching {
                app.contentResolver.openOutputStream(uri)!!.use { exportBackup(app.db, it) }
                "Backup saved"
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }

    /** Returns true when the import succeeded and the caller should restart the UI. */
    suspend fun import(uri: Uri): Boolean {
        val ok = runCatching {
            app.contentResolver.openInputStream(uri)!!.use { importBackup(app, it) }
        }.onFailure { status = "Import failed: ${it.message}" }.isSuccess
        if (ok) status = "Backup restored"
        return ok
    }
}
