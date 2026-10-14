package com.kaiodelphino.margem.ui

import androidx.compose.runtime.Composable
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.NoticeId
import com.kaiodelphino.margem.TransactionId
import com.kaiodelphino.margem.widgets.SnackHost

/**
 * Test tags of Lançamentos and of the one form of Lançar / editar / lançamento manual (D35). Same setup
 * and rules as [BudgetTags] and [InboxTags]; the date and time dialog is [com.kaiodelphino.margem.widgets.DateTimeTags].
 *
 * Margins in texts: `money(c)` when c ≥ 0, `"${money(-c)} além"` when negative ("R$ 12,00 além").
 *
 * The form opens three ways:
 * - **Lançar** (Screen.Post, from InboxTags.ACCEPT): prefilled from the notice — total = Parsed.cents,
 *   bank text = Parsed.merchant, date and time = Parsed.date/time, alias = NoticeRow.alias — plus the
 *   suggestion (Db.suggestion of the parsed merchant, spec §2.5): its budget selected and its tags selected
 *   and suggested. A notice that looks like an ad opens with zero total and blank merchant.
 * - **Editar** (Screen.Edit, from [ROW] or BudgetTags.LINE): the transaction as it is (Db.transaction):
 *   total, merchant, date, time, budget, installments, its tags selected (not suggested), alias of its
 *   merchant. A transaction from a notice shows the notice and the emendas against its Parsed.
 * - **Novo lançamento** (Screen.NewTransaction, from [NEW]): empty; today (LocalToday) and the current
 *   time of the system clock to the minute; no notice, no suggestion.
 *
 * Every input survives Activity recreation and process death (rememberSaveable): total, bank text,
 * alias, name, date, time, budget, installments, tags and their suggested/confirmed state.
 */
object TransactionTags {
    // ---- Lançamentos (spec §7.4, book 10.5) ----

    /** "Novo lançamento": opens the empty form. */
    const val NEW = "transactions.new"

    /** The scrollable list. Plain container. */
    const val LIST = "transactions.list"

    /** Instead of the rows when there are no transactions. */
    const val EMPTY = "transactions.empty"

    /** Prefix: DAY + date ("transactions.day.2026-10-08"): [dayTitle] of each purchase date with rows, newest first. */
    const val DAY = "transactions.day."

    /**
     * Prefix: ROW + transaction id, one per Db.transactions(today), in that order, under the day of its
     * purchase date. Name (alias, else bank text); "Lazer · bar, amigos" (budget · tags joined by ", "),
     * then "‸ emendado" when corrected, "Parcela 3 de 10" when in installments with one in today's month
     * (TransactionRow.now) or "10×" when it has none there, and "estornada" when refunded. The value is the
     * installment of today's month (now.cents) for an installment purchase with one there, else the total;
     * struck through when refunded. Click opens the edit form.
     */
    const val ROW = "transactions.row."

    /**
     * Prefix: UNDO_REFUND + transaction id, on refunded rows only: "Desfazer estorno" (Db.undoRefund with
     * row.estorno). The row stops being refunded; the snack "Estorno desfeito: a compra volta a contar." appears.
     */
    const val UNDO_REFUND = "transactions.undoRefund."

    // ---- The form (spec §7.3, book 10.3) ----

    /** The top bar: "Lançar recado", "Editar lançamento" or "Novo lançamento". */
    const val FORM_TITLE = "txForm.title"

    /** Post and Edit of a transaction from a notice: "Cartão Ipê · qui, 8 out, 23:10" and the bank text in quotes. Absent otherwise. */
    const val FORM_NOTICE = "txForm.notice"

    /** Valor total: money input (digits as cents, like BudgetTags.FORM_LIMIT). */
    const val FORM_TOTAL = "txForm.total"

    /**
     * Present while the total differs from the notice's Parsed.cents (form from a notice): "‸ emendado",
     * "o recado dizia" and money of Parsed.cents. "$FORM_TOTAL_EMENDA.undo" ("Desfazer") puts it back.
     */
    const val FORM_TOTAL_EMENDA = "txForm.total.emenda"

    /** Form from a notice: the alias input (Apelido), prefilled with the merchant's alias ("" when none). */
    const val FORM_ALIAS = "txForm.alias"

    /**
     * Form from a notice: "No banco: BAR E REST PONTO CERTO" (the current bank text). Click turns it into
     * [FORM_BANK_INPUT], prefilled with that text; FORM_BANK is then absent.
     */
    const val FORM_BANK = "txForm.bank"
    const val FORM_BANK_INPUT = "txForm.bank.input"

    /** Present while the bank text differs from Parsed.merchant; "$FORM_BANK_EMENDA.undo" puts it back (and closes the input). */
    const val FORM_BANK_EMENDA = "txForm.bank.emenda"

    /** Manual (new, or editing one with no notice): the one name input, saved as the merchant. Absent in forms from a notice. */
    const val FORM_NAME = "txForm.name"

    /** "quinta, 8 de outubro · 23:10" (dateLong · time). Click opens the date and time dialog (DateTimeTags). */
    const val FORM_DATE = "txForm.date"

    /** Present while date or time differ from the notice's Parsed; "$FORM_DATE_EMENDA.undo" puts both back. */
    const val FORM_DATE_EMENDA = "txForm.date.emenda"

    /**
     * Prefix: BUDGET + budget id: one option per budget (manual order), `selected` when chosen. Click
     * selects it; exactly one or none is selected.
     */
    const val BUDGET = "txForm.budget."

    /** No budgets at all: "Crie uma verba antes de lançar." with "Nova verba" ([BUDGETS_NEW] opens it). */
    const val BUDGETS_EMPTY = "txForm.budgets.empty"
    const val BUDGETS_NEW = "txForm.budgets.new"

    /** Parcelas: the number (1..400) as text; [INSTALLMENTS_MINUS] / [INSTALLMENTS_PLUS] change it by one and are disabled at the ends. */
    const val INSTALLMENTS = "txForm.installments"
    const val INSTALLMENTS_MINUS = "txForm.installments.minus"
    const val INSTALLMENTS_PLUS = "txForm.installments.plus"

    /**
     * With 2 or more installments and total > 0: "10 × R$ 120,00" (n × money(total / n rounded down)),
     * plus " · última R$ 333,34" when the last one absorbs cents (spec §6). Absent otherwise.
     */
    const val INSTALLMENTS_PREVIEW = "txForm.installments.preview"

    /**
     * Prefix: TAG + tag name ("txForm.tag.padaria"): one chip per existing tag (Db.tags) and per name
     * suggested or added here, by name. `selected` when it counts. A suggested one (spec §2.5) is selected
     * with stateDescription "sugerido" (dashed); click confirms it (selected, no stateDescription); click
     * on a confirmed one unselects it; click on an unselected one selects it.
     */
    const val TAG = "txForm.tag."

    /**
     * "+ novo": shows [TAG_INPUT]. Text typed there and confirmed with [TAG_ADD] (or the keyboard's done)
     * becomes a selected chip, trimmed and lowercased; an existing name selects that chip; blank does
     * nothing. The tag is written only when the form is saved. The input closes after adding.
     */
    const val TAG_NEW = "txForm.tag.new"
    const val TAG_INPUT = "txForm.tag.input"
    const val TAG_ADD = "txForm.tag.add"

    /**
     * "Lazer: margem R$ 34,00 → R$ 12,00 além" (Db.previewAccept for the chosen budget, date, total and
     * installments; in Editar, replacing the transaction). Present while a budget is chosen and total > 0.
     */
    const val FORM_PREVIEW = "txForm.preview"

    /**
     * What is missing, while something is: "Falta " + the missing ones among "o valor" (total 0), "a
     * verba", "um marcador", joined with ", " and a final " e ", + "." — e.g. "Falta a verba e um
     * marcador.". Absent when nothing is missing.
     */
    const val FORM_MISSING = "txForm.missing"

    /**
     * Lançar: "Lançar R$ 46,00" (money of the total); Novo lançamento the same; Editar: "Salvar".
     * Disabled while [FORM_MISSING] is shown; a second click while saving does nothing.
     * - Lançar: Db.accept; back to the Pauta with the snack "Lançado em Lazer. Margem: R$ 12,00 além." (the
     *   preview's after) and "Desfazer" (deletes it: the notice is pending again). Refused: InboxTags.GONE_TEXT, back.
     * - Novo lançamento: Db.addManual; back to Lançamentos with the same snack and "Desfazer".
     * - Editar: Db.edit; back. Refused (the transaction is gone): "Este lançamento não existe mais.", back.
     * A non-blank alias is saved with it (TransactionForm.alias); blank leaves the alias as it is.
     */
    const val FORM_SUBMIT = "txForm.submit"

    /** Lançar only: "Riscar" — dismisses the notice; back to the Pauta with "Recado riscado." and "Desfazer". */
    const val FORM_DISMISS = "txForm.dismiss"

    /** Lançar only: "É um estorno? Registrar estorno" — replaces the form with Registrar estorno of the same notice. */
    const val FORM_AS_REFUND = "txForm.asRefund"

    /** Editar of a refunded transaction: "estornada" and [FORM_UNDO_REFUND] "Desfazer estorno" (Db.undoRefund; the line goes away). */
    const val FORM_REFUNDED = "txForm.refunded"
    const val FORM_UNDO_REFUND = "txForm.undoRefund"

    /** Editar only: "Apagar lançamento", opens [DELETE_DIALOG]. */
    const val FORM_DELETE = "txForm.delete"

    /**
     * "Apagar o lançamento?" saying what happens (spec §2.8): from a notice, "O recado volta à Pauta." (and,
     * when refunded, that the estorno notice goes back too); manual, that it simply goes away. No undo.
     * Confirm deletes it (Db.deleteTransaction) and goes back; cancel closes the dialog.
     */
    const val DELETE_DIALOG = "txForm.deleteDialog"
    const val DELETE_DIALOG_CONFIRM = "txForm.deleteDialog.confirm"
    const val DELETE_DIALOG_CANCEL = "txForm.deleteDialog.cancel"
}

/** Lançamentos (spec §7.4, book 10.5): every transaction grouped by day, with Desfazer estorno. Tags: [TransactionTags]. */
@Composable
fun Transactions(db: Db, snack: SnackHost, go: (Screen) -> Unit): Unit = TODO()

/** Lançar recado (book 10.3) for the pending notice [notice]. [replace] swaps this screen (Registrar estorno). */
@Composable
fun PostScreen(db: Db, notice: NoticeId, snack: SnackHost, go: (Screen) -> Unit, replace: (Screen) -> Unit, back: () -> Unit): Unit = TODO()

/** Editar lançamento: the same form, with Apagar and Desfazer estorno. */
@Composable
fun EditTransaction(db: Db, id: TransactionId, snack: SnackHost, go: (Screen) -> Unit, back: () -> Unit): Unit = TODO()

/** Novo lançamento: the manual form. */
@Composable
fun NewTransaction(db: Db, snack: SnackHost, go: (Screen) -> Unit, back: () -> Unit): Unit = TODO()
