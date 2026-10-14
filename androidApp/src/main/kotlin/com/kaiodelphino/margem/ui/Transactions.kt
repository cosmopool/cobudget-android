package com.kaiodelphino.margem.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.widgets.Amount
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.ListRow
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.dateShort

/** Lançamentos (spec §7.4). The list, the edit screen and the manual one come in blocks 3 and 4. */
@Composable
fun Transactions(db: Db, go: (Screen) -> Unit) {
    val rows = query(db) { it.transactions() }
    Column {
        TopBar("Lançamentos")
        LazyColumn(contentPadding = PaddingValues(horizontal = Margem.space.page)) {
            if (rows == null) return@LazyColumn
            if (rows.isEmpty()) item {
                EmptyState(
                    R.drawable.i_lancamento, "Nenhum lançamento ainda.",
                    "Cada recado que você lança da Pauta vira uma linha aqui. Compras sem recado entram como lançamento manual.",
                )
            }
            items(rows, key = { it.id.v }) { t ->
                ListRow(t.name, sub = "${t.budgetName} · ${t.tags.joinToString(", ")} · ${dateShort(t.date)}") { Amount(t.total, size = 17, struck = t.refunded) }
            }
        }
    }
}
