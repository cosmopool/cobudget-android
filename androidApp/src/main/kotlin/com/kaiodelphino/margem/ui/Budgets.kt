package com.kaiodelphino.margem.ui

import androidx.compose.runtime.Composable
import com.kaiodelphino.margem.BudgetId
import com.kaiodelphino.margem.Db
import kotlinx.datetime.YearMonth

/**
 * Test tags of the Verbas screens (D35): the contract of their UI tests. Tests run
 * `App(db, tabRequest = null, onTabOpened = {})` inside `MargemTheme(dark = false)` and
 * `CompositionLocalProvider(LocalToday provides { day })`, on a Db with AndroidSQLiteDriver (D28),
 * and find elements only by these tags. The app opens on Verbas.
 *
 * - A tagged node's text (SemanticsProperties.Text, assertTextContains) is everything it shows,
 *   except other tagged nodes inside it, which are nodes of their own. [LIST] and [STEP_DIALOG] are
 *   plain containers: only their presence and children matter.
 * - Inputs ([FORM_NAME], [FORM_LIMIT], [STEP_FIELD]) are text fields: performTextInput /
 *   performTextReplacement on them. Money inputs take digits as cents ("31000" = R$ 310,00); how they
 *   display the value is not part of the contract.
 * - Money in texts is formatted by `money()` ("R$ 1.234,56", U+202F after "R$") or `moneyPlain()`
 *   ("1.234,56") from widgets/Theme.kt; a week's step by `stepValue()` ("105", "33,33").
 * - "Absent" = no node has the tag. "Disabled" = the node is not enabled (assertIsNotEnabled).
 */
object BudgetTags {
    // ---- Verbas, the home tab (book 10.1) ----

    /**
     * The month of today: "Mês de 1 a 31 de outubro" (or "Mês de 5 de outubro a 4 de novembro"),
     * "Dia 9 de 31." and "Faltam 23 dias, contando hoje." ("Falta 1 dia, contando hoje." on the last day).
     */
    const val HEADER = "budgets.header"

    /**
     * "4 recados na pauta" ("1 recado na pauta"), plus "3 novos desde a última vez" ("1 novo desde a
     * última vez") when some are new (spec §2.11). Click opens the Pauta. Absent when the Pauta is empty.
     */
    const val PAUTA_STRIP = "budgets.pauta"

    /**
     * "Ordenar" options: risco · manual · A–Z (spec §7.1). Each is clickable and `selected` when it is
     * the chosen order; the choice is saved in Settings.budgetOrder and the list follows it.
     */
    const val ORDER_RISK = "budgets.order.risk"
    const val ORDER_MANUAL = "budgets.order.manual"
    const val ORDER_AZ = "budgets.order.az"

    /** "+": opens Nova verba. */
    const val ADD = "budgets.add"

    /** The scrollable list: one [ROW] per budget with a limit in the current month, in the chosen order. */
    const val LIST = "budgets.list"

    /**
     * Prefix: ROW + budget id ("budgets.row.3"). Name; spent (money) and "de 465,00" (moneyPlain of the
     * limit); the meter; the state ("No ritmo", "Acima do ritmo", "Fora da margem"; "Sem gasto" when
     * nothing was spent); and "margem R$ 388,90", or "R$ 12,00 além" when Fora da margem.
     * Click opens the detail on the current month. In manual order, long press and drag moves the row;
     * the new order is saved (reorderBudgets) on release.
     */
    const val ROW = "budgets.row."

    /** Instead of the list when there are no budgets: what a verba is and how to create the first. */
    const val EMPTY = "budgets.empty"

    // ---- Nova verba (book 10.7) and Editar verba: the same form ----

    /** Name input. Empty in Nova verba; the budget's name in Editar verba. */
    const val FORM_NAME = "budgetForm.name"

    /**
     * After a refused submit: "Dê um nome à verba." (blank) or "Já existe uma verba com esse nome."
     * (used by another budget, ignoring case). Absent otherwise.
     */
    const val FORM_NAME_ERROR = "budgetForm.name.error"

    /** Cota mensal input. Zero in Nova verba; the current month's limit in Editar verba. */
    const val FORM_LIMIT = "budgetForm.limit"

    /** After a refused submit: "A cota precisa ser maior que zero." Absent otherwise. */
    const val FORM_LIMIT_ERROR = "budgetForm.limit.error"

    /**
     * Nova verba only, while the limit is > 0: the steps of previewBudget(limit, today) — "Passos
     * semanais de outubro", "R$ 10,00 por dia · semana cheia R$ 70,00" (limit ÷ days of the month and
     * limit × 7 ÷ days, rounded down to the cent), and each week's range ("1–4") and step. Absent while
     * the limit is zero.
     */
    const val FORM_PREVIEW = "budgetForm.preview"

    /**
     * "Criar verba" in Nova verba, "Salvar" in Editar verba. A blank or used name, or a zero limit, shows
     * the errors above and stays. Nova verba creates the budget (createBudget, today's month) and goes
     * back to Verbas. Editar verba renames it and, when the limit changed, sets it from today's month on
     * (setLimit) — first asking with [LIMIT_DIALOG] when limitDropsEdits — then goes back to the detail.
     */
    const val FORM_SUBMIT = "budgetForm.submit"

    /** Editar verba only: "Apagar verba", opens [DELETE_DIALOG]. */
    const val FORM_DELETE = "budgetForm.delete"

    /**
     * The new limit deletes step edits (spec §4): says so. Confirm saves (rename + setLimit) and goes back
     * to the detail; cancel closes the dialog, saves nothing and stays on the form.
     */
    const val LIMIT_DIALOG = "budgetForm.limitDialog"
    const val LIMIT_DIALOG_CONFIRM = "budgetForm.limitDialog.confirm"
    const val LIMIT_DIALOG_CANCEL = "budgetForm.limitDialog.cancel"

    /**
     * "Apagar Lazer?": says the budget's N lançamentos are deleted too and their recados go back to the
     * Pauta (the number in digits). Confirm deletes (deleteBudget) and returns to Verbas; cancel closes it.
     */
    const val DELETE_DIALOG = "budgetForm.deleteDialog"
    const val DELETE_DIALOG_CONFIRM = "budgetForm.deleteDialog.confirm"
    const val DELETE_DIALOG_CANCEL = "budgetForm.deleteDialog.cancel"

    // ---- Detalhe da verba (book 10.8) ----

    /** The top bar: the budget's name. */
    const val DETAIL_TITLE = "budget.title"

    /** "Editar": opens Editar verba. */
    const val DETAIL_EDIT = "budget.edit"

    /** "outubro de 2026", with "1 a 31 · mês atual" (uppercase) on the current month. */
    const val MONTH_LABEL = "budget.month.label"

    /** One month back; disabled on the budget's first month (BudgetDetail.firstMonth). */
    const val MONTH_PREV = "budget.month.prev"

    /** One month forward; disabled on the current month. */
    const val MONTH_NEXT = "budget.month.next"

    /**
     * The headline: "R$ 12,00 além da cota." when Fora da margem; otherwise it contains the margin
     * (money of BudgetMonth.remaining). Followed by a sentence about the month.
     */
    const val DETAIL_HEADLINE = "budget.headline"

    /** The tall meter; content description "R$ 477,00 de R$ 465,00" (spent de limit). */
    const val DETAIL_METER = "budget.meter"

    /**
     * Prefix: WEEK + Week.start ("budget.week.2026-10-05"): range ("5–11") and step. Clickable and
     * enabled unless Week.locked; click opens [STEP_DIALOG] for that week.
     */
    const val WEEK = "budget.week."

    /** The step dialog of the tapped week. */
    const val STEP_DIALOG = "budget.step"

    /** Step input, starting with the week's step. */
    const val STEP_FIELD = "budget.step.field"

    /** "no máximo R$ 465,00": money of Week.max. */
    const val STEP_MAX = "budget.step.max"

    /** "Salvar": a value in 0..Week.max is saved (setStep) and the dialog closes; above it the dialog stays and nothing changes. */
    const val STEP_SAVE = "budget.step.save"

    /** Edited weeks only: "Voltar ao automático" (clearStep), then the dialog closes. Absent on an automatic week. */
    const val STEP_AUTO = "budget.step.auto"

    /** "Cancelar": closes the dialog; nothing changes. */
    const val STEP_CANCEL = "budget.step.cancel"

    /**
     * Prefix: LINE + transaction id, one per BudgetDetail.lines, oldest first. Name (alias, else bank
     * text); "qui, 1" (weekday and day of MonthLine.date) and the tags; "Parcela 3 de 10" when in
     * installments; "estornada" when refunded; the installment's value (money). Not clickable in this block.
     */
    const val LINE = "budget.line."

    /** Prefix: LINE_SUM + transaction id: the running sum up to that line (moneyPlain); refunded lines don't add. */
    const val LINE_SUM = "budget.lineSum."

    /** "cota atingida aqui": right after the first line whose running sum reaches the limit (≥). Absent if it never does. */
    const val CUT = "budget.cut"

    /** Instead of the lines when the month has none. */
    const val LINES_EMPTY = "budget.lines.empty"
}

/**
 * Verbas (spec §7.1, book 10.1): header of today's month, the Pauta strip, "Ordenar", the budgets of
 * the current month (after ensureMonths, D30) and "+". Tags: [BudgetTags].
 */
@Composable
fun Budgets(db: Db, go: (Screen) -> Unit): Unit = TODO()

/**
 * Detalhe da verba (spec §7.1, book 10.8) in [month]: month picker ([onMonth] switches the month),
 * headline, tall meter, weekly steps with their edit dialog, the month's lines with the running sum,
 * and "Editar".
 */
@Composable
fun BudgetDetailScreen(db: Db, id: BudgetId, month: YearMonth, go: (Screen) -> Unit, onMonth: (YearMonth) -> Unit, back: () -> Unit): Unit = TODO()

/** Nova verba (book 10.7): name, cota mensal and the preview of the weekly steps; [back] after creating. */
@Composable
fun NewBudget(db: Db, back: () -> Unit): Unit = TODO()

/** Editar verba: name, cota mensal and "Apagar verba"; [back] after saving, [home] after deleting. */
@Composable
fun EditBudget(db: Db, id: BudgetId, back: () -> Unit, home: () -> Unit): Unit = TODO()
