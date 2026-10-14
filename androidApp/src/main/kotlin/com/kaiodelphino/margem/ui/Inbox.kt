package com.kaiodelphino.margem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.accessSettingsIntent
import com.kaiodelphino.margem.widgets.CapturePaused
import com.kaiodelphino.margem.widgets.DayHeader
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.NoticeCard
import com.kaiodelphino.margem.widgets.Snack
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.dateLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pauta (spec §7.2): the anotação pausada notice when access is off, the pending notices grouped by
 * day, newest first, or "Pauta limpa.". Lançar (the accept screen) comes in block 3.
 */
@Composable
fun Inbox(db: Db, granted: Boolean, snack: SnackHost, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = query(db) { it.pending() }
    // Notices posted since the last visit stay "new" while the person is here; leaving marks them seen.
    val openedAt = remember { Clock.System.now().toEpochMilliseconds() }
    LaunchedEffect(Unit) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { db.markPautaSeen(openedAt) }
        }
    }
    val today = LocalToday.current()
    val zone = TimeZone.currentSystemDefault()
    Column {
        TopBar("Pauta")
        LazyColumn(contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!granted) item { CapturePaused({ context.startActivity(accessSettingsIntent(context)) }, Modifier.padding(top = 8.dp)) }
            if (pending == null) return@LazyColumn
            if (pending.isEmpty()) item { EmptyState(R.drawable.i_pauta, "Pauta limpa.", "Nenhum recado esperando. Os próximos chegam sozinhos.") }
            val byDay = pending.groupBy { Instant.fromEpochMilliseconds(it.raw.postedAt).toLocalDateTime(zone).date }
            for ((day, notices) in byDay) {
                item(key = "day-$day") { DayHeader(dayTitle(day, today), Modifier.padding(top = 0.dp)) }
                items(notices, key = { it.id.v }) { n ->
                    NoticeCard(n, onAccept = { go(Screen.Post(n.id)) }, acceptEnabled = false, onDismiss = {
                        scope.launch {
                            if (db.dismiss(n.id)) snack.show(Snack("Recado riscado.", "Desfazer") { scope.launch { db.restore(n.id) } })
                        }
                    })
                }
            }
        }
    }
}

/** "Hoje · sexta, 9 de outubro", "Ontem · quinta, 8 de outubro", "Quarta, 7 de outubro". */
fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Hoje · ${dateLong(day)}"
    today.minus(DatePeriod(days = 1)) -> "Ontem · ${dateLong(day)}"
    else -> dateLong(day).replaceFirstChar { it.uppercase() }
}

