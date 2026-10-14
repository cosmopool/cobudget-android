package com.kaiodelphino.margem.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.widgets.EmptyState
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.TopBar

/** Leitura (spec §7.5). The four charts come in block 6. */
@Composable
fun Insights(@Suppress("UNUSED_PARAMETER") db: Db) {
    Column {
        TopBar("Leitura")
        EmptyState(
            R.drawable.i_leitura, "Nada para ler ainda.",
            "A Leitura mostra para onde o dinheiro vai: verbas mês a mês, o ritmo do mês, marcadores e estabelecimentos.",
            Modifier.padding(horizontal = Margem.space.page),
        )
    }
}
