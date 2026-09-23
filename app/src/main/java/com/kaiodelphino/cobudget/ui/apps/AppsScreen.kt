package com.kaiodelphino.cobudget.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiodelphino.cobudget.data.InstalledApp
import com.kaiodelphino.cobudget.ui.AppIcon
import com.kaiodelphino.cobudget.ui.messages.EmptyState

@Composable
fun AppsScreen(
    state: AppsUiState,
    onQueryChange: (String) -> Unit,
    onToggle: (InstalledApp, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.rows.isEmpty() -> EmptyState("No apps match \"${state.query}\".")
            else -> LazyColumn {
                items(state.rows, key = { it.app.packageName }) { row ->
                    ListItem(
                        modifier = Modifier.clickable { onToggle(row.app, !row.monitored) },
                        leadingContent = { AppIcon(row.app.packageName) },
                        headlineContent = { Text(row.app.label) },
                        supportingContent = { Text(row.app.packageName) },
                        trailingContent = { Switch(checked = row.monitored, onCheckedChange = { onToggle(row.app, it) }) },
                    )
                }
            }
        }
    }
}
