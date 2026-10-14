package com.kaiodelphino.margem

import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth

/**
 * The whole database (architecture D5, D6, D29): one writer connection under a Mutex, two
 * `query_only` readers, WAL. Every function runs on Dispatchers.IO. Nothing returns null: a lookup
 * by an unknown id returns the zero value, and a refused write returns false or a zero id.
 *
 * Months: a [YearMonth] names the budget month that starts on Settings.monthStartDay of that
 * calendar month (spec §3). Screens call [ensureMonths] before reading a month (D30): budgets
 * without a limit row for a month are absent from that month's reads.
 *
 * @param path the database file; created and migrated on open.
 * @param onWrite called after every write that bumped [version] (D21).
 * @param timeZone used to read a notice's date and time when its text has none.
 */
class Db(
    driver: SQLiteDriver,
    path: String,
    onWrite: () -> Unit = {},
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    /** Bumped once at the end of every write (except [ensureMonths]): screens re-query on change. */
    val version: StateFlow<Long> get() = TODO()

    /** Closes every connection. The Db can't be used afterwards. */
    fun close(): Unit = TODO()

    // ---- Backup (D14) ----

    /** Writes a consistent copy of the database to [path] (VACUUM INTO). False if it can't be written. */
    suspend fun exportTo(path: String): Boolean = TODO()

    /**
     * Replaces the database with the file at [path] (a copy made by [exportTo]): closes writer and
     * readers, swaps the file (dropping -wal/-shm), reopens, migrates and bumps [version].
     * False, with the current database untouched, if [path] isn't a Margem database.
     */
    suspend fun importFrom(path: String): Boolean = TODO()

    // ---- Months (D30) ----

    /**
     * Makes sure every budget has a limit row up to [month]: copies the latest earlier row and
     * fills skipped months. Months before a budget's first row stay without one. Does not bump [version].
     */
    suspend fun ensureMonths(month: YearMonth): Unit = TODO()

    /** The budget month [date] falls in, by the current monthStartDay. */
    suspend fun monthOf(date: LocalDate): YearMonth = TODO()

    // ---- Settings and sources ----

    suspend fun settings(): Settings = TODO()

    /**
     * Changes the month start day (1..31; false otherwise). Deletes the step edits from the start
     * of [today]'s month onward, by the old or the new day, whichever is earlier (spec §4, D10).
     */
    suspend fun setMonthStartDay(day: Int, today: LocalDate): Boolean = TODO()

    /** Changes the week start day; deletes step edits like [setMonthStartDay]. */
    suspend fun setWeekStart(day: DayOfWeek, today: LocalDate): Unit = TODO()

    suspend fun setTheme(theme: Theme): Unit = TODO()

    suspend fun setBudgetOrder(order: BudgetOrder): Unit = TODO()

    suspend fun setWidgetOrder(order: BudgetOrder): Unit = TODO()

    /** Opening the Pauta: notices posted up to [now] stop being new. */
    suspend fun markPautaSeen(now: Long): Unit = TODO()

    /** Sorted by label, ignoring case. */
    suspend fun sources(): List<Source> = TODO()

    /** Adds or relabels a source. Blank package or label: refused (false). */
    suspend fun addSource(packageName: String, label: String, now: Long): Boolean = TODO()

    /** Stops capturing from it; its notices stay. */
    suspend fun removeSource(packageName: String): Unit = TODO()

    // ---- Notices ----

    /** Pending notices, newest (posted_at) first. */
    suspend fun pending(): List<NoticeRow> = TODO()

    suspend fun pendingCount(): Int = TODO()

    /** Riscados, newest first. */
    suspend fun dismissed(): List<NoticeRow> = TODO()

    /** Zero row when unknown. */
    suspend fun notice(id: NoticeId): NoticeRow = TODO()

    /** Riscar: only a pending notice (false otherwise). */
    suspend fun dismiss(id: NoticeId): Boolean = TODO()

    /** Trazer de volta: a dismissed notice becomes pending (false if it wasn't dismissed). */
    suspend fun restore(id: NoticeId): Boolean = TODO()

    /** Budget and tags of the latest transaction whose merchant has the same key (spacing and case ignored). */
    suspend fun suggestion(merchant: String): Suggestion = TODO()

    // ---- Transactions ----

    /**
     * Lançar: turns a pending notice into a transaction. Refused (TransactionId(0), nothing written)
     * unless the notice is pending, [TransactionForm.budget] exists, at least one tag remains after
     * cleaning, total > 0 and installments in 1..400.
     *
     * Writes the transaction, its installments and its tags in one transaction: installment k
     * (1..n) is dated purchase date + (k−1) months, moved to the nearest day inside the k-th
     * budget month when that date falls outside it (start days 29–31); each is total / n rounded
     * down and the last absorbs the remainder. `corrected` when total, merchant, date or time
     * differ from the notice's [Parsed]. A non-blank alias names the merchant.
     */
    suspend fun accept(notice: NoticeId, form: TransactionForm): TransactionId = TODO()

    /** Lançamento manual: same rules as [accept], with no notice; never corrected. */
    suspend fun addManual(form: TransactionForm): TransactionId = TODO()

    /**
     * Replaces every field, installment and tag of a transaction; same rules as [accept]. corrected
     * is recomputed against the notice's [Parsed]. A registered estorno stays. False if refused or unknown.
     */
    suspend fun edit(id: TransactionId, form: TransactionForm): Boolean = TODO()

    /**
     * Deletes the transaction with its installments, tag links and the estorno registered on it:
     * its notice and that estorno notice become pending again (spec §2.8).
     */
    suspend fun deleteTransaction(id: TransactionId): Unit = TODO()

    /** All transactions, newest (date, time) first. */
    suspend fun transactions(): List<TransactionRow> = TODO()

    /** Zero detail when unknown. */
    suspend fun transaction(id: TransactionId): TransactionDetail = TODO()

    /**
     * Margin of [budget] in the month of [date] (the first installment's), before and after a
     * purchase of [total] in [installments]. For an edit, [replacing] is the transaction being
     * edited: "before" includes it as it is, "after" swaps it for the new values.
     * Zero when the budget or the inputs aren't valid.
     */
    suspend fun previewAccept(
        budget: BudgetId,
        date: LocalDate,
        total: Cents,
        installments: Int,
        today: LocalDate,
        replacing: TransactionId = TransactionId(0),
    ): Preview = TODO()

    // ---- Estornos (spec §6) ----

    /**
     * Registers [estorno] (a pending notice) as cancelling [purchase], a transaction not yet
     * refunded. Its installments stop counting, in every month. False otherwise.
     */
    suspend fun registerRefund(estorno: NoticeId, purchase: TransactionId): Boolean = TODO()

    /**
     * Registers [estorno] as cancelling [purchase], another pending notice: both leave the Pauta
     * and nothing counts. False unless both are pending and different.
     */
    suspend fun registerRefund(estorno: NoticeId, purchase: NoticeId): Boolean = TODO()

    /** Desfazer estorno: the purchase counts again; the estorno (and a purchase notice) are pending again. */
    suspend fun undoRefund(estorno: NoticeId): Boolean = TODO()

    /**
     * What [estorno] may cancel: transactions not refunded and pending notices (not the estorno
     * itself, nor notices parsed as estornos). Likely ones first, newest first within each group;
     * likely = same source, same value, same merchant key when the estorno names one, posted no later.
     */
    suspend fun refundCandidates(estorno: NoticeId): List<RefundCandidate> = TODO()

    // ---- Budgets ----

    /**
     * New budget at the end of the manual order, with [limit] for [today]'s month. Refused
     * (BudgetId(0)) for a blank name, a name already used (ignoring case) or limit ≤ 0.
     */
    suspend fun createBudget(name: String, limit: Cents, today: LocalDate): BudgetId = TODO()

    /** False for a blank or already used name, or an unknown budget. */
    suspend fun renameBudget(id: BudgetId, name: String): Boolean = TODO()

    /**
     * Changes the limit from [today]'s month onward; earlier months keep theirs. In each of those
     * months where the edited steps now sum more than the new limit, all edits are deleted (spec §4).
     * False for limit ≤ 0 or an unknown budget.
     */
    suspend fun setLimit(id: BudgetId, limit: Cents, today: LocalDate): Boolean = TODO()

    /** Deletes the budget, its transactions (notices back to the Pauta), limits and step edits. */
    suspend fun deleteBudget(id: BudgetId): Unit = TODO()

    /** Manual order: [ids] must be exactly the existing budgets, in the new order. False otherwise. */
    suspend fun reorderBudgets(ids: List<BudgetId>): Boolean = TODO()

    /**
     * Edits the step of the week starting on [weekStart] (a Week.start of that month). Refused
     * when the step is < 0 or > limit − Σ other edited weeks, or when it is the only week left
     * not edited (one week always stays automatic). Editing an already edited week is allowed.
     */
    suspend fun setStep(budget: BudgetId, month: YearMonth, weekStart: LocalDate, step: Cents): Boolean = TODO()

    /** Budgets with a limit row in [month], with their [BudgetMonth], in [order]. */
    suspend fun budgetsOverview(month: YearMonth, today: LocalDate, order: BudgetOrder): List<BudgetSummary> = TODO()

    /** One budget in [month] plus its transactions with an installment in it. Zero when unknown. */
    suspend fun budgetDetail(id: BudgetId, month: YearMonth, today: LocalDate): BudgetDetail = TODO()

    // ---- Tags ----

    /** With use counts, sorted by name. */
    suspend fun tags(): List<TagRow> = TODO()

    /** Trimmed and lowercased; returns the existing tag for a used name. TagId(0) for a blank one. */
    suspend fun createTag(name: String): TagId = TODO()

    /** Trimmed and lowercased. False for a blank name, a name of another tag, or an unknown tag. */
    suspend fun renameTag(id: TagId, name: String): Boolean = TODO()

    /** Moves every link of [from] to [into] and deletes [from]. False if either is unknown or they are the same. */
    suspend fun mergeTags(from: TagId, into: TagId): Boolean = TODO()

    /** Deletes the tag; transactions left without tags are deleted and their notices go back to the Pauta. */
    suspend fun deleteTag(id: TagId): Unit = TODO()

    // ---- Aliases ----

    /** Names the merchant (key: spacing and case ignored) everywhere; a blank alias removes it. Blank merchant: ignored. */
    suspend fun setAlias(merchant: String, alias: String): Unit = TODO()

    // ---- Leitura (spec §7.5) ----
    // Refunded transactions never count. Installments count on their own date.

    /** Spent × limit for every budget and month in [from]..[to] that has a limit row. */
    suspend fun history(from: YearMonth, to: YearMonth): List<HistoryRow> = TODO()

    /** Tags by value of installments dated in [from]..[to]; count = transactions with one there. */
    suspend fun tagRanking(from: LocalDate, to: LocalDate): List<RankRow> = TODO()

    /** Same, by merchant (grouped by key, named by alias). */
    suspend fun merchantRanking(from: LocalDate, to: LocalDate): List<RankRow> = TODO()

    /** Spent per day in [from]..[to]; days without spending are absent. */
    suspend fun spentByDay(from: LocalDate, to: LocalDate): Map<LocalDate, Cents> = TODO()
}
