package com.kaiodelphino.margem.ui

import androidx.compose.runtime.Composable
import com.kaiodelphino.margem.Db

/**
 * Leitura's elements for UI tests (D35, spec §7.5, book 10.6). Money: `money` ("R$ 231,10") and
 * `moneyPlain` ("231,10") of widgets/Theme.kt; "R$ 796" = whole reais, rounded half up, thousands dot.
 * The month of the page is "the month" below; today comes from LocalToday.
 */
object InsightsTags {
    /** Instead of everything else when there are no budgets: "Nada para ler ainda." */
    const val EMPTY = "insights.empty"

    // ---- The month (spec §7.5: one month for the whole page) ----

    /**
     * "outubro de 2026", with "1 a 31 · até sex, 9" (uppercase) on the current month and "1 a 31" on a
     * past one (the month window, like the budget detail). Opens on the current month.
     */
    const val MONTH_LABEL = "insights.month.label"

    /** One month back; disabled on the earliest month with a limit row of any existing budget. */
    const val MONTH_PREV = "insights.month.prev"

    /** One month forward; disabled on the current month. */
    const val MONTH_NEXT = "insights.month.next"

    // ---- Ritmo do mês (book Fig. 9.2) ----
    // Ritmo and Verbas por mês are absent when no budget has a limit row in the month.

    /**
     * The chosen budget's name next to "Ritmo do mês", with its series mark; clickable, opens
     * [BUDGET_CHOICE]. Default: the riskiest budget of the month (spec §8.1 order). The choice stays
     * while the screen is open; on a month where that budget has no limit row, the default again.
     */
    const val PACE_BUDGET = "insights.pace.budget"

    /** Dialog listing the budgets with a limit row in the month, in the risk order. */
    const val BUDGET_CHOICE = "insights.budgetChoice"

    /** Prefix: BUDGET_OPTION + budget id ("insights.budgetChoice.3"): its name; click chooses it and closes. */
    const val BUDGET_OPTION = "insights.budgetChoice."

    /**
     * The conclusion. Current month, by the chosen budget's BudgetMonth:
     * - spent > limit: "R$ 12,00 além da cota." (spent − limit);
     * - else spent > ideal: "+ R$ 51,10 sobre o ritmo ideal." (spent − ideal);
     * - else spent < ideal: "R$ 20,00 abaixo do ritmo ideal." (ideal − spent);
     * - else: "No ritmo ideal.";
     * then, when ideal > 0, " No passo atual, o mês fecharia em R$ 796." (projection, whole reais).
     * Past month: "Fechou em R$ 1.180,00, R$ 60,00 dentro da cota." (spent, limit − spent),
     * "Fechou em R$ 477,00, R$ 12,00 além da cota." (spent, spent − limit), or
     * "Fechou em R$ 620,00, exatamente a cota." when spent == limit.
     */
    const val PACE_SENTENCE = "insights.pace.sentence"

    /**
     * The chart: cumulative spent by day (installments on their date, refunds out), BudgetMonth.idealByDay
     * dashed, projection dotted (current month only), the rest of the month shaded (current month only).
     * Content description, current month: "Ritmo do mês de Restaurantes: R$ 231,10 até o dia 9, contra
     * ritmo ideal de R$ 180,00; no passo atual, R$ 796 no fim do mês, para uma cota de R$ 620,00"
     * (the "; no passo atual, … no fim do mês" part only when ideal > 0). Past month: "Ritmo do mês de
     * Restaurantes: R$ 1.180,00 no mês, para uma cota de R$ 1.240,00".
     */
    const val PACE_CHART = "insights.pace.chart"

    // ---- Verbas por mês (book Fig. 9.6) ----

    /**
     * Months of the 6 ending at the month in which the chosen budget spent more than that month's
     * limit: "Nenhum mês fora da margem.", "Um mês fora da margem.", "Dois meses fora da margem.",
     * "Três …", "Quatro …", "Cinco …", "Seis meses fora da margem."
     */
    const val HISTORY_SENTENCE = "insights.history.sentence"

    /**
     * The bars of the chosen budget, 6 slots, oldest first, ending at the month. Content description:
     * "Verbas por mês de Lazer: mai sem cota; jun 431,00 de 400,00; …; out 477,00 de 465,00" — one
     * "<monthShort> <moneyPlain spent> de <moneyPlain limit>" per month with a limit row, "<monthShort>
     * sem cota" for a month without one (before the budget's first month), joined by "; ".
     */
    const val HISTORY_CHART = "insights.history.chart"

    // ---- Marcadores e estabelecimentos (book Fig. 9.4) ----

    /** Options "Marcadores" (default) · "Estabelecimentos"; the chosen one is `selected`. Kept while the screen is open. */
    const val RANK_TAGS = "insights.rank.tags"
    const val RANK_MERCHANTS = "insights.rank.merchants"

    /** "feira puxa a fila." — the first row's name. Absent when the ranking is empty. */
    const val RANK_SENTENCE = "insights.rank.sentence"

    /**
     * Prefix: RANK_ROW + index from 0 ("insights.rank.row.0"), in Db.tagRanking / Db.merchantRanking
     * order for the month window: name, "3 lançamentos" ("1 lançamento") and moneyPlain of the total.
     * Rows 0–5 only until [RANK_MORE] is clicked; then all.
     */
    const val RANK_ROW = "insights.rank.row."

    /** "Ver todos (9)" (row count). Present only with more than 6 rows and until clicked. */
    const val RANK_MORE = "insights.rank.more"

    /**
     * Under Marcadores only, when there are rows: "Com vários marcadores, o lançamento conta inteiro
     * em cada um: a soma pode passar do total."
     */
    const val RANK_NOTE = "insights.rank.note"

    /** Instead of the rows when the ranking is empty: "Nada lançado em outubro." (monthLong of the month). */
    const val RANK_EMPTY = "insights.rank.empty"

    // ---- Calendário de gastos (book Fig. 9.5) ----

    /**
     * The weekday with the most spending in the month window (ties: the first in week order from the
     * Ajustes week start): "Os sábados pesam." / "As sextas pesam." (plural, lowercase weekday;
     * article "As" for segunda–sexta, "Os" for sábado and domingo). Absent when nothing was spent.
     */
    const val CAL_SENTENCE = "insights.cal.sentence"

    /**
     * "31% do mês em quatro sábados": that weekday's share of the month's spending (rounded half up)
     * and how many of that weekday the month window has, in words ("um sábado", "uma sexta",
     * "dois sábados", "duas sextas", "três", "quatro", "cinco"). Absent when nothing was spent.
     */
    const val CAL_SHARE = "insights.cal.share"

    /**
     * Prefix: CAL_DAY + ISO date ("insights.cal.day.2026-10-04"), one per day of the month window,
     * in a grid whose columns start at the Ajustes week start. Content description "4: R$ 286,40"
     * or "4: sem gasto"; state description "degrau 0".."degrau 4" (spec §7.5: step = highest day ÷ 4
     * rounded up to R$ 10; level = ⌈spent ÷ step⌉; 0 = no spending).
     */
    const val CAL_DAY = "insights.cal.day."

    /** "cada degrau ≈ R$ 60" (the step, whole reais). Absent when nothing was spent. */
    const val CAL_LEGEND = "insights.cal.legend"

    /** Instead of the grid when nothing was spent in the month: "Nada lançado em outubro." */
    const val CAL_EMPTY = "insights.cal.empty"
}

/** Leitura (spec §7.5, book 10.6). */
@Composable
fun Insights(db: Db): Unit = TODO("block 6")
