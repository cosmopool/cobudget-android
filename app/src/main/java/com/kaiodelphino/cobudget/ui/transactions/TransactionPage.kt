package com.kaiodelphino.cobudget.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaiodelphino.cobudget.ui.messages.Details

/** Accept a pending notification, or edit an accepted transaction. Calls [onDone] when there's nothing left to do here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionPage(vm: TransactionPageViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    val notification = vm.notification ?: return
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val nicknames by vm.nicknames.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(notification.appLabel, style = MaterialTheme.typography.labelLarge)
                notification.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                notification.body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        val refundOf = vm.refundOf
        if (refundOf != null) {
            Text("Estorno of", style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text(displayName(refundOf.merchant, nicknames, notification.appLabel)) },
                supportingContent = { Text("${refundOf.date} ${refundOf.time}") },
                trailingContent = { Text(formatBrl(refundOf.cents)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::acceptRefund) { Text("Accept refund") }
                OutlinedButton(onClick = vm::dismiss) { Text("Dismiss") }
            }
        } else {
            OutlinedTextField(vm.merchant, { vm.merchant = it }, Modifier.fillMaxWidth(), label = { Text("Merchant") }, singleLine = true)
            OutlinedTextField(
                vm.nickname, { vm.nickname = it }, Modifier.fillMaxWidth(),
                label = { Text("Nickname (optional)") }, singleLine = true, enabled = vm.merchant.isNotBlank(),
                supportingText = { Text("Shown instead of the merchant on every transaction") },
            )
            OutlinedTextField(
                vm.value, { vm.value = it }, Modifier.fillMaxWidth(),
                label = { Text("Value (R$)") }, singleLine = true, isError = vm.value.isNotEmpty() && vm.cents <= 0,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    vm.date, { vm.date = it }, Modifier.weight(1f),
                    label = { Text("Date (dd/MM/yyyy)") }, singleLine = true, isError = vm.parsedDate == null,
                )
                OutlinedTextField(
                    vm.time, { vm.time = it }, Modifier.weight(1f),
                    label = { Text("Time (HH:mm)") }, singleLine = true, isError = vm.parsedTime == null,
                )
            }
            if (vm.refunded) Text("Estornado", color = MaterialTheme.colorScheme.error)

            Text("Tags (at least one)", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Saved tags plus ones typed on this page and not saved yet.
                val names = allTags.map { it.name } + vm.tags.filter { t -> allTags.none { it.name.equals(t, ignoreCase = true) } }
                names.forEach { name ->
                    FilterChip(
                        selected = vm.tags.any { it.equals(name, ignoreCase = true) },
                        onClick = { vm.toggleTag(name) },
                        label = { Text(name) },
                    )
                }
            }
            OutlinedTextField(
                vm.newTag, { vm.newTag = it }, Modifier.fillMaxWidth(),
                label = { Text("New tag") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.addTag() }),
                trailingIcon = { IconButton(onClick = vm::addTag) { Icon(Icons.Default.Add, contentDescription = "Add tag") } },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save, enabled = vm.canSave) { Text(if (vm.isEdit) "Save" else "Accept") }
                if (vm.isEdit) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                } else {
                    OutlinedButton(onClick = vm::dismiss) { Text("Dismiss") }
                }
            }
        }

        Details(notification)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete transaction?") },
            text = { Text("Its notification goes back to Messages as pending.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
