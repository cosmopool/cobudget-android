package com.kaiodelphino.cobudget.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    status: String?,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onReparse: () -> Unit,
    gendaUrl: String,
    gendaToken: String,
    gendaPort: String,
    onGendaChange: (url: String, token: String, port: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(onExport)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { exportLauncher.launch("cobudget-backup.db") }) {
            Text("Export backup")
        }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
            Text("Import backup")
        }
        Text("Transactions", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onReparse) {
            Text("Re-parse notifications")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text("genda", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(gendaUrl, { onGendaChange(it, gendaToken, gendaPort) }, Modifier.fillMaxWidth(), label = { Text("URL") }, singleLine = true)
        OutlinedTextField(
            gendaPort,
            { onGendaChange(gendaUrl, gendaToken, it.filter(Char::isDigit).take(5)) },
            Modifier.fillMaxWidth(),
            label = { Text("Port (empty = the URL's)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(gendaToken, { onGendaChange(gendaUrl, it, gendaPort) }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true)
    }
}
