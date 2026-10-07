package com.kaiodelphino.cobudget.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kaiodelphino.cobudget.data.TransactionRow
import com.kaiodelphino.cobudget.ui.AppIcon
import com.kaiodelphino.cobudget.ui.messages.EmptyState
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

private val brl = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
private val dayTime = DateTimeFormatter.ofPattern("dd/MM HH:mm")

/** Centavos as "R$ 1.234,56". */
fun formatBrl(cents: Long): String = brl.format(cents / 100.0)

@Composable
fun TransactionsScreen(rows: List<TransactionRow>, onOpen: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        EmptyState("No transactions yet. Accept a notification in Messages.")
        return
    }
    LazyColumn(modifier) {
        items(rows, key = { it.transaction.id }) { row ->
            val t = row.transaction
            ListItem(
                modifier = Modifier.clickable { onOpen(t.id) },
                leadingContent = { AppIcon(row.packageName) },
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.merchant.ifEmpty { row.appLabel })
                        if (t.ratified) {
                            Icon(Icons.Default.Edit, contentDescription = "Corrected", Modifier.padding(start = 6.dp).size(14.dp))
                        }
                    }
                },
                supportingContent = {
                    Text(listOf(t.date.atTime(t.time).format(dayTime), row.tags.joinToString(", ")).joinToString(" · "))
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatBrl(t.cents),
                            style = MaterialTheme.typography.titleSmall,
                            textDecoration = if (t.refunded) TextDecoration.LineThrough else null,
                        )
                        if (t.refunded) {
                            Text("Estornado", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
    }
}
