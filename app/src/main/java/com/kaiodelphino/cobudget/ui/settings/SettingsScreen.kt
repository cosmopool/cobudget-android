package com.kaiodelphino.cobudget.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.kaiodelphino.cobudget.data.Tag

@Composable
fun SettingsScreen(
    status: String?,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    gendaUrl: String,
    gendaToken: String,
    gendaPort: String,
    onGendaChange: (url: String, token: String, port: String) -> Unit,
    tags: List<Tag>,
    onRenameTag: (id: Long, name: String) -> Unit,
    soleTagCount: suspend (id: Long) -> Int,
    onDeleteTag: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(onExport)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { exportLauncher.launch("cobudget-backup.db") }) {
            Text("Export backup")
        }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
            Text("Import backup")
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
        Text("Tags", style = MaterialTheme.typography.titleMedium)
        if (tags.isEmpty()) Text("Tags are created when you accept a transaction.", style = MaterialTheme.typography.bodyMedium)
        tags.forEach { tag -> TagRow(tag, onRenameTag, soleTagCount, onDeleteTag) }
    }
}

@Composable
private fun TagRow(tag: Tag, onRename: (Long, String) -> Unit, soleTagCount: suspend (Long) -> Int, onDelete: (Long) -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(tag.name) },
        trailingContent = {
            Row {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        },
    )
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(tag.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename tag") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, singleLine = true)
                    Text("An existing name merges the two tags.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; onRename(tag.id, name) }, enabled = name.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        var orphans by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(tag.id) { orphans = soleTagCount(tag.id) }
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete \"${tag.name}\"?") },
            text = {
                Text(
                    when (orphans) {
                        null, 0 -> "It is removed from every transaction."
                        1 -> "1 transaction has only this tag; it goes back to Messages as pending."
                        else -> "$orphans transactions have only this tag; they go back to Messages as pending."
                    }
                )
            },
            confirmButton = { TextButton(onClick = { deleting = false; onDelete(tag.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}
