package com.kaiodelphino.margem.ui

import androidx.compose.runtime.Composable
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.NoticeId
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.dateLong
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * Test tags of the Pauta and of Registrar estorno (D35): the contract of their UI tests. Same setup and
 * rules as [BudgetTags]: `App(db, null) {}` in `MargemTheme(dark = false)` with `LocalToday` fixed, a Db on
 * AndroidSQLiteDriver (D28), elements found only by tags. Notices enter by `capture(db, raw)` from a
 * registered source. Dates and times of notices are read in the device's time zone
 * (TimeZone.currentSystemDefault()).
 *
 * The Pauta tab is reached by the tab bar or by BudgetTags.PAUTA_STRIP. The app-wide snackbar is
 * [com.kaiodelphino.margem.widgets.SnackTags]: a snack's action works even after the screen that showed
 * it is gone (it runs on the App's scope).
 *
 * "Este recado não está mais na Pauta." ([GONE_TEXT]) is the one answer whenever a notice stops being
 * pending under an open screen (launched, dismissed or refunded by another path): opening, submitting or
 * dismissing from Lançar or Registrar estorno shows it as a snack and goes back to the Pauta, writing nothing.
 */
object InboxTags {
    /** The snack text when the notice is no longer pending (see the object's KDoc). */
    const val GONE_TEXT = "Este recado não está mais na Pauta."

    // ---- Pauta (spec §7.2, book 10.2) ----

    /** The scrollable list: the paused notice, day headers and cards. Plain container. */
    const val LIST = "inbox.list"

    /** "A anotação automática está pausada.", first in the list, only while notification access is off. */
    const val PAUSED = "inbox.paused"

    /** "Pauta limpa.", instead of the cards when nothing is pending. */
    const val EMPTY = "inbox.empty"

    /**
     * Prefix: DAY + the date ("inbox.day.2026-10-09"), one per day with pending notices, newest first:
     * "Hoje · sexta, 9 de outubro", "Ontem · quinta, 8 de outubro", "Quarta, 7 de outubro" ([dayTitle]).
     */
    const val DAY = "inbox.day."

    /**
     * Prefix: CARD + notice id, one per pending notice (Db.pending order: newest posted first), under its
     * day. Shows the source (app label), the time ("07:42"), the bank text in quotes, and — unless it
     * looks like an ad — the merchant (alias when there is one, else the parsed merchant) and the value
     * read (money), with "ESTORNO" on an estorno notice. "▲ Parece propaganda" when NoticeRow.looksLikeAd.
     */
    const val CARD = "inbox.card."

    /**
     * Prefix: NEW + notice id: present exactly while the card is shown as new (NoticeRow.isNew, spec
     * §2.11 — the argila double rule). Notices stay new while the Pauta is open; leaving the Pauta marks
     * everything posted up to when it was opened as seen (Db.markPautaSeen).
     */
    const val NEW = "inbox.new."

    /**
     * Prefix: ACCEPT + notice id: "Lançar"; "Registrar estorno" on an estorno notice (Parsed.isRefund);
     * "Lançar mesmo assim" when it looks like an ad (an ad that reads as estorno is still an ad). Click opens
     * Registrar estorno for an estorno notice, the Lançar form ([TransactionTags]) for any other.
     */
    const val ACCEPT = "inbox.accept."

    /**
     * Prefix: DISMISS + notice id: "Riscar". Click dismisses it (Db.dismiss): the card leaves and the snack
     * "Recado riscado." with "Desfazer" (Db.restore) appears.
     */
    const val DISMISS = "inbox.dismiss."

    // ---- Registrar estorno (spec §6, §7.3, book 10.4), Screen.Refund ----

    /** The top bar: "Registrar estorno". */
    const val REFUND_TITLE = "refund.title"

    /** The estorno notice: "Cartão Ipê · qui, 8 out, 21:03" (source · dateShort · time) and the bank text in quotes. */
    const val REFUND_NOTICE = "refund.notice"

    /**
     * Search input (lupa). Filters the candidates as the person types: a candidate stays when its name
     * contains the query ignoring case, or when moneyPlain of its total contains it ("59,9" keeps R$ 59,90).
     * Blank = no filter. The selection stays even when the selected candidate is filtered out.
     */
    const val REFUND_SEARCH = "refund.search"

    /**
     * "Sugerida a mais provável": present when the first candidate of Db.refundCandidates is likely; it
     * heads that candidate. Absent when no candidate is likely or the search hides the first one.
     */
    const val REFUND_LIKELY = "refund.likely"

    /**
     * Prefix: CANDIDATE_TX + transaction id, or CANDIDATE_NOTICE + notice id: one per Db.refundCandidates
     * entry that passes the search, in that order. A transaction shows its name, "Casa · sex, 2 out ·
     * lançamento" (budget · dateShort · "lançamento") and its total (money); a notice shows its name,
     * "hoje, 07:15 · recado na pauta" ("hoje", "ontem" or dateShort of its date, then time) and its value.
     * Click selects it (`selected`); exactly one or none is selected. On open the first candidate is
     * selected when it is likely; otherwise none.
     */
    const val CANDIDATE_TX = "refund.candidate.t."
    const val CANDIDATE_NOTICE = "refund.candidate.n."

    /**
     * Instead of the candidates: "Nenhuma compra para estornar." when there are none, "Nada encontrado."
     * when the search hides all of them.
     */
    const val REFUND_EMPTY = "refund.empty"

    /**
     * "Casa: margem R$ 738,70 → R$ 798,60" (Db.previewRefund of the selected transaction; a negative margin
     * is written "R$ 12,00 além"). Present only while a transaction is selected.
     */
    const val REFUND_PREVIEW = "refund.preview"

    /**
     * "Registrar estorno": disabled until a candidate is selected. Click registers it (Db.registerRefund
     * with the transaction or the notice) and goes back to the Pauta with the snack "Estorno registrado: a
     * compra de 2 out não conta mais." (day and monthShort of the candidate's date) and "Desfazer"
     * (Db.undoRefund). Refused: [GONE_TEXT], back to the Pauta.
     */
    const val REFUND_SUBMIT = "refund.submit"

    /** "Riscar": dismisses the estorno notice; back to the Pauta with "Recado riscado." and "Desfazer". */
    const val REFUND_DISMISS = "refund.dismiss"

    /** "Não é estorno? Lançar como compra": replaces this screen with the Lançar form of the same notice. */
    const val REFUND_AS_PURCHASE = "refund.asPurchase"
}

/**
 * Pauta (spec §7.2, book 10.2): the anotação pausada notice when access is off, the pending notices
 * grouped by day, newest first, or "Pauta limpa.". Tags: [InboxTags].
 */
@Composable
fun Inbox(db: Db, granted: Boolean, snack: SnackHost, go: (Screen) -> Unit): Unit = TODO()

/**
 * Registrar estorno (book 10.4) for the pending estorno notice [estorno]: pick the cancelled purchase
 * among Db.refundCandidates. [replace] swaps this screen for another (Lançar como compra); [back] returns
 * to the Pauta. Tags: [InboxTags].
 */
@Composable
fun RefundScreen(db: Db, estorno: NoticeId, snack: SnackHost, replace: (Screen) -> Unit, back: () -> Unit): Unit = TODO()

/** "Hoje · sexta, 9 de outubro", "Ontem · quinta, 8 de outubro", "Quarta, 7 de outubro". */
fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Hoje · ${dateLong(day)}"
    today.minus(DatePeriod(days = 1)) -> "Ontem · ${dateLong(day)}"
    else -> dateLong(day).replaceFirstChar { it.uppercase() }
}
