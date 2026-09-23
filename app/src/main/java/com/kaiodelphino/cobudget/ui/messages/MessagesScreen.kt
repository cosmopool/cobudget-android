package com.kaiodelphino.cobudget.ui.messages

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kaiodelphino.cobudget.data.CapturedNotification
import com.kaiodelphino.cobudget.ui.AppIcon
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
fun MessagesScreen(state: MessagesUiState, onSelectApp: (String?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        if (state.apps.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = state.selectedPackage == null, onClick = { onSelectApp(null) }, label = { Text("All") })
                state.apps.forEach { app ->
                    FilterChip(
                        selected = state.selectedPackage == app.packageName,
                        onClick = { onSelectApp(app.packageName) },
                        label = { Text(app.label) },
                    )
                }
            }
        }
        if (state.messages.isEmpty()) {
            EmptyState(if (state.apps.isEmpty()) "Pick apps to monitor in the Apps tab." else "No messages captured yet.")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { MessageCard(it) }
            }
        }
    }
}

@Composable
private fun MessageCard(message: CapturedNotification) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(message.packageName)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(message.appLabel, style = MaterialTheme.typography.labelLarge)
                    Text(
                        timeFormat.format(Instant.ofEpochMilli(message.postedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            message.title?.let {
                Spacer(Modifier.padding(top = 8.dp))
                Text(it, style = MaterialTheme.typography.titleSmall)
            }
            message.body?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3)
            }
            if (expanded) Details(message)
        }
    }
}

@Composable
private fun Details(message: CapturedNotification) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    SelectionContainer {
        Column {
            listOf(
                "Package" to message.packageName,
                "Sub text" to message.subText,
                "Category" to message.category,
                "Channel" to message.channelId,
                "Key" to message.notificationKey,
            ).forEach { (label, value) ->
                if (value != null) Text("$label: $value", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.padding(top = 8.dp))
            Text("Extras", style = MaterialTheme.typography.labelMedium)
            Text(
                runCatching { JSONObject(message.extrasJson).toString(2) }.getOrDefault(message.extrasJson),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
