package com.kaiodelphino.cobudget.ui.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiodelphino.cobudget.CobudgetApp
import com.kaiodelphino.cobudget.capture.NotificationCaptureService
import com.kaiodelphino.cobudget.data.exportBackup
import com.kaiodelphino.cobudget.data.importBackup
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: CobudgetApp) : ViewModel() {

    var status: String? by mutableStateOf(null)
        private set

    private val genda = app.getSharedPreferences(NotificationCaptureService.GENDA_PREFS, Context.MODE_PRIVATE)

    var gendaUrl: String by mutableStateOf(genda.getString("url", "")!!)
        private set

    var gendaToken: String by mutableStateOf(genda.getString("token", "")!!)
        private set

    var gendaPort: String by mutableStateOf(genda.getString("port", "")!!)
        private set

    fun setGenda(url: String, token: String, port: String) {
        gendaUrl = url
        gendaToken = token
        gendaPort = port
        genda.edit { putString("url", url).putString("token", token).putString("port", port) }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            status = runCatching {
                app.contentResolver.openOutputStream(uri)!!.use { exportBackup(app.db, it) }
                "Backup saved"
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }

    /** Rebuilds the transactions table from every saved notification (after a parser fix, or for old rows). */
    fun reparse() {
        viewModelScope.launch {
            status = runCatching { "Re-parsed: ${app.db.dao().rebuildTransactions()} transactions" }
                .getOrElse { "Re-parse failed: ${it.message}" }
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
