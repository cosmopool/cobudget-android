package com.kaiodelphino.margem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiodelphino.margem.BudgetSummary
import com.kaiodelphino.margem.Cents
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.PaceStatus
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.HomeBar
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.Meter
import com.kaiodelphino.margem.widgets.StatusLabel
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.money
import com.kaiodelphino.margem.widgets.moneyPlain
import com.kaiodelphino.margem.widgets.sans
import com.kaiodelphino.margem.widgets.serif

/** Verbas, the home tab (spec §7.1). The full screen (order, new budget, detail) is block 4. */
@Composable
fun Budgets(db: Db, go: (Screen) -> Unit) {
    val today = today()
    val budgets = query(db, today) {
        val month = it.monthOf(today)
        it.ensureMonths(month)
        it.budgetsOverview(month, today, it.settings().budgetOrder)
    }
    Column {
        HomeBar()
        LazyColumn(contentPadding = PaddingValues(horizontal = Margem.space.page)) {
            if (budgets == null) return@LazyColumn
            if (budgets.isEmpty()) item {
                EmptyState(
                    R.drawable.i_verba, "Nenhuma verba ainda.",
                    "Verba é um limite que você escolhe: um nome e uma cota por mês. Cada lançamento entra em uma verba. " +
                        "Comece por onde o dinheiro mais escorre — mercado, por exemplo.",
                )
            }
            items(budgets, key = { it.id.v }) { BudgetLine(it) }
        }
    }
}

/** Book `.vr`: name and "R$ 477,00 de 465,00", the ruler, the state and the margin. */
@Composable
private fun BudgetLine(b: BudgetSummary) {
    val c = Margem.colors
    val m = b.month
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(b.name, serif(19, 500, 24), Modifier.weight(1f), maxLines = 1)
            Text(money(m.spent), serif(17, 500, 22, tabular = true))
            Text(" de ${moneyPlain(m.limit)}", sans(12.5f, 400, 18f, tabular = true), color = c.inkTertiary)
        }
        Meter(m, Modifier.padding(top = 9.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatusLabel(m.status, empty = m.spent.v == 0L)
            if (m.status == PaceStatus.OVERRUN) Text("${money(Cents(-m.remaining.v))} além", sans(13f, 600, 16f, tabular = true), color = c.overrun)
            else Text("margem ${money(m.remaining)}", sans(13f, 400, 16f, tabular = true), color = c.inkSecondary)
        }
    }
}
