package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** The Db through its public API, on a real SQLite file: capture → Pauta → lançar → verba, and every rule of §5. */
class DbTest {

    private val path = Path(SystemTemporaryDirectory, "margem-${Random.nextLong().toULong()}.db").toString()
    private var writes = 0
    private val db = Db(BundledSQLiteDriver(), path, onWrite = { writes++ }, timeZone = TimeZone.of("America/Sao_Paulo"))

    @AfterTest
    fun tearDown() {
        db.close()
        for (p in listOf(path, "$path-wal", "$path-shm", "$path.copy")) SystemFileSystem.delete(Path(p), mustExist = false)
    }

    private fun nu(text: String, at: Long = T, key: String = "nu|$at") =
        RawNotice(packageName = NUBANK, key = key, postedAt = at, whenMs = at, title = "Nubank", text = text, bigText = text)

    /** Captures and returns the new notice's id. */
    private suspend fun post(raw: RawNotice): NoticeId {
        assertTrue(capture(db, raw))
        return db.pending().first().id
    }

    /** Lança with exactly what was read from the notice. */
    private suspend fun acceptAsRead(id: NoticeId, budget: BudgetId, tags: List<String> = listOf("mercado"), installments: Int = 1): TransactionId {
        val p = db.notice(id).parsed
        return db.accept(id, TransactionForm(budget, tags, p.cents, installments, p.merchant, p.date, p.time))
    }

    private suspend fun setUp(): BudgetId {
        db.addSource(NUBANK, "Nu", 0)
        return db.createBudget("Mercado", Cents(60000), OCT_6)
    }

    @Test
    fun `a captured purchase goes from the Pauta to its budget month`() = runTest {
        val budget = setUp()
        val id = post(nu(PURCHASE))

        val notice = db.pending().single()
        assertEquals(Parsed(Cents(3150), "GIPL COMERCIO DE ALIME", OCT_6, LocalTime(11, 43)), notice.parsed)
        assertEquals("Nu", notice.raw.appLabel)
        assertTrue(notice.isNew)
        assertFalse(notice.looksLikeAd)
        db.markPautaSeen(T)
        assertFalse(db.pending().single().isNew)

        val tx = db.accept(id, TransactionForm(budget, listOf(" Feira ", "MERCADO", "feira", " "), Cents(3150), 1, "GIPL COMERCIO DE ALIME", OCT_6, LocalTime(11, 43)))
        assertNotEquals(TransactionId(0), tx)
        assertEquals(0, db.pendingCount())
        assertEquals(NoticeStatus.PROCESSED, db.notice(id).status)
        val row = db.transactions().single()
        assertEquals(listOf("feira", "mercado"), row.tags)
        assertEquals("Mercado" to Cents(3150), row.budgetName to row.total)
        assertFalse(row.corrected)
        assertEquals(TransactionId(0), acceptAsRead(id, budget))

        db.ensureMonths(YearMonth(2026, 10))
        val month = db.budgetsOverview(YearMonth(2026, 10), OCT_6, BudgetOrder.RISK).single().month
        assertEquals(Cents(3150) to Cents(56850), month.spent to month.remaining)
        assertEquals(Suggestion(budget, listOf("feira", "mercado")), db.suggestion("gipl  comercio de ALIME"))
        assertEquals(Suggestion(), db.suggestion("outra loja"))
    }

    @Test
    fun `lançar is refused without a tag a value a budget or valid installments`() = runTest {
        val budget = setUp()
        val id = post(nu(PURCHASE))
        val ok = TransactionForm(budget, listOf("mercado"), Cents(3150), 1, "GIPL", OCT_6, LocalTime(11, 43))
        for (bad in listOf(
            ok.copy(tags = listOf(" ", "")), ok.copy(total = Cents(0)), ok.copy(total = Cents(-5)), ok.copy(budget = BudgetId(99)),
            ok.copy(installments = 0), ok.copy(installments = 401),
        )) assertEquals(TransactionId(0), db.accept(id, bad), bad.toString())
        assertTrue(db.transactions().isEmpty())
        assertEquals(listOf(id), db.pending().map { it.id })

        assertNotEquals(TransactionId(0), db.accept(id, ok.copy(installments = 400)))
    }

    @Test
    fun `emendas mark the transaction corrected and an alias names the merchant everywhere`() = runTest {
        val budget = setUp()
        val first = post(nu(UBER, T))
        val tx = db.accept(first, TransactionForm(budget, listOf("uber"), Cents(994), 1, "DL*UberRides", OCT_6, LocalTime(11, 43), alias = " Uber "))
        assertTrue(db.transactions().single().corrected) // the merchant was read as "DL *UberRides"
        assertEquals("Uber", db.transactions().single().name)

        val second = post(nu(UBER, T + MIN))
        assertEquals("Uber", db.pending().single { it.id == second }.alias)

        val p = db.notice(first).parsed
        assertTrue(db.edit(tx, TransactionForm(budget, listOf("uber"), p.cents, 1, p.merchant, p.date, p.time)))
        assertFalse(db.transaction(tx).row.corrected) // back to what was read
        assertEquals(first, db.transaction(tx).notice.id)

        val manual = db.addManual(TransactionForm(budget, listOf("feira"), Cents(2000), 1, "Feira", OCT_6, LocalTime(9, 0)))
        assertFalse(db.transaction(manual).row.corrected)
        assertEquals(NoticeRow(), db.transaction(manual).notice)

        db.setAlias("dl *uberrides", "")
        assertEquals("DL *UberRides", db.transactions().single { it.id == tx }.name)
    }

    @Test
    fun `re-posts are deduped and real repeats kept`() = runTest {
        db.addSource(BANK, "My Bank", 0)
        fun bank(postedAt: Long, whenMs: Long = postedAt, text: String = "You spent \$4.50 at Coffee") =
            RawNotice(packageName = BANK, key = "bank|1", postedAt = postedAt, whenMs = whenMs, title = "Card purchase", text = text)

        // Within the window, then a genuine repeat after it.
        assertTrue(capture(db, bank(1_000_000)))
        assertFalse(capture(db, bank(1_000_000 + 5_000, whenMs = 0)))
        assertTrue(capture(db, bank(1_000_000 + DUPLICATE_WINDOW_MS, whenMs = 0)))
        // Catch-up after a reconnect: the shade still holds an already saved post.
        assertFalse(capture(db, bank(1_000_000)))
        // Same `when` long after (re-post after a reboot); same text with a new `when` is a new purchase.
        assertFalse(capture(db, bank(1_000_000 + 10 * DUPLICATE_WINDOW_MS, whenMs = 1_000_000)))
        assertTrue(capture(db, bank(1_000_000 + 20 * DUPLICATE_WINDOW_MS)))
        // when = 0 never matches by itself.
        assertTrue(capture(db, bank(1_000_000 + 40 * DUPLICATE_WINDOW_MS, whenMs = 0)))
        assertTrue(capture(db, bank(1_000_000 + 60 * DUPLICATE_WINDOW_MS, whenMs = 0)))
        assertTrue(capture(db, bank(1_000_000 + 60 * DUPLICATE_WINDOW_MS + 1, text = "You spent \$12.00 at Grocer")))
        assertEquals(6, db.pendingCount())

        // Noise: not a source, or no text at all.
        assertFalse(capture(db, bank(9_000_000).copy(packageName = "com.chat")))
        assertFalse(capture(db, RawNotice(packageName = BANK, key = "bank|2", postedAt = 9_000_000)))
        assertEquals(6, db.pendingCount())
    }

    @Test
    fun `riscar and trazer de volta`() = runTest {
        val budget = setUp()
        val ad = post(nu("Conheça as novidades do seu cartão Nu Empresas.", T))
        val purchase = post(nu(PURCHASE, T + MIN))
        assertTrue(db.pending().single { it.id == ad }.looksLikeAd)

        assertTrue(db.dismiss(ad))
        assertFalse(db.dismiss(ad))
        assertEquals(listOf(purchase), db.pending().map { it.id })
        assertEquals(listOf(ad), db.dismissed().map { it.id })
        assertEquals(NoticeStatus.DISMISSED, db.notice(ad).status)

        assertTrue(db.restore(ad))
        assertFalse(db.restore(ad))
        assertEquals(setOf(ad, purchase), db.pending().map { it.id }.toSet())

        acceptAsRead(purchase, budget)
        assertFalse(db.dismiss(purchase))
        assertEquals(TransactionId(0), acceptAsRead(ad, budget)) // read no value: total 0 is refused
        assertEquals(NoticeRow(), db.notice(NoticeId(999)))
    }

    @Test
    fun `an estorno of a transaction takes all its installments out and can be undone`() = runTest {
        val budget = setUp()
        val older = acceptAsRead(post(nu(UBER, T)), budget)
        val purchase = acceptAsRead(post(nu(UBER, T + 3 * MIN)), budget, installments = 2)
        val estorno = post(nu(UBER_REFUND, T + 6 * MIN))
        assertTrue(db.notice(estorno).parsed.isRefund)

        val candidates = db.refundCandidates(estorno)
        assertEquals(RefundCandidate(transaction = purchase, name = "DL *UberRides", total = Cents(994), date = OCT_6, likely = true), candidates.first())
        assertTrue(candidates.single { it.transaction == older }.likely)

        assertTrue(db.registerRefund(estorno, purchase))
        assertFalse(db.registerRefund(estorno, older)) // the estorno is no longer pending
        assertEquals(0, db.pendingCount())
        assertEquals(estorno, db.transactions().single { it.id == purchase }.estorno)
        db.ensureMonths(YearMonth(2026, 11))
        assertEquals(Cents(994), db.budgetDetail(budget, YearMonth(2026, 10), OCT_6).month.spent) // only the older one
        assertEquals(Cents(0), db.budgetDetail(budget, YearMonth(2026, 11), OCT_6).month.spent) // its 2nd installment too
        assertTrue(db.refundCandidates(estorno).none { it.transaction == purchase })

        assertTrue(db.undoRefund(estorno))
        assertFalse(db.undoRefund(estorno))
        assertEquals(listOf(estorno), db.pending().map { it.id })
        assertEquals(Cents(497), db.budgetDetail(budget, YearMonth(2026, 11), OCT_6).month.spent)
    }

    @Test
    fun `an estorno of a notice takes both out of the Pauta and nothing counts`() = runTest {
        setUp()
        val purchase = post(nu(UBER, T))
        val other = post(nu(PURCHASE, T + MIN))
        val estorno = post(nu(UBER_REFUND, T + 6 * MIN))

        val candidates = db.refundCandidates(estorno)
        assertEquals(RefundCandidate(notice = purchase, name = "DL *UberRides", total = Cents(994), date = OCT_6, likely = true), candidates.first())
        assertFalse(candidates.single { it.notice == other }.likely)
        assertTrue(candidates.none { it.notice == estorno })

        assertFalse(db.registerRefund(estorno, estorno))
        assertTrue(db.registerRefund(estorno, purchase))
        assertEquals(listOf(other), db.pending().map { it.id })
        assertTrue(db.transactions().isEmpty())

        assertTrue(db.undoRefund(estorno))
        assertEquals(setOf(purchase, other, estorno), db.pending().map { it.id }.toSet())
    }

    @Test
    fun `deleting a refunded transaction brings back both notices`() = runTest {
        val budget = setUp()
        val notice = post(nu(UBER, T))
        val tx = acceptAsRead(notice, budget)
        val estorno = post(nu(UBER_REFUND, T + 3 * MIN))
        assertTrue(db.registerRefund(estorno, tx))

        db.deleteTransaction(tx)

        assertEquals(setOf(notice, estorno), db.pending().map { it.id }.toSet())
        assertFalse(db.undoRefund(estorno))
        assertEquals(TransactionDetail(), db.transaction(tx))
        assertEquals(listOf("mercado"), db.tags().map { it.name }) // tags outlive their transactions
    }

    @Test
    fun `installments count each in its own month and the last takes the cents`() = runTest {
        val budget = setUp()
        val tx = db.addManual(TransactionForm(budget, listOf("casa"), Cents(100001), 10, "Loja", OCT_6, LocalTime(10, 0)))

        val installments = db.transaction(tx).installments
        assertEquals((1..10).toList(), installments.map { it.k })
        assertEquals(List(9) { 10000L } + 10001L, installments.map { it.cents.v })
        assertEquals(LocalDate(2027, 7, 6), installments.last().date)
        assertEquals(10, db.transactions().single().installments)

        db.ensureMonths(YearMonth(2027, 8))
        val history = db.history(YearMonth(2026, 10), YearMonth(2027, 8))
        assertEquals(List(9) { 10000L } + 10001L + 0L, history.map { it.spent.v })
        assertEquals(YearMonth(2026, 10), history.first().month)
        assertEquals(Cents(60000), history.last().limit)

        // Leitura: by installment date.
        assertEquals(RankRow("casa", Cents(20000), 1), db.tagRanking(LocalDate(2026, 10, 1), LocalDate(2026, 11, 30)).single())
        assertEquals(RankRow("Loja", Cents(10000), 1), db.merchantRanking(LocalDate(2026, 11, 1), LocalDate(2026, 11, 30)).single())
        assertEquals(mapOf(OCT_6 to Cents(10000), LocalDate(2026, 11, 6) to Cents(10000)), db.spentByDay(LocalDate(2026, 10, 1), LocalDate(2026, 11, 30)))

        // The preview of another purchase in October sees this one's first installment.
        assertEquals(Preview(YearMonth(2026, 10), Cents(50000), Cents(45000)), db.previewAccept(budget, OCT_6, Cents(15000), 3, OCT_6))
        assertEquals(Preview(YearMonth(2026, 10), Cents(50000), Cents(55000)), db.previewAccept(budget, OCT_6, Cents(5000), 1, OCT_6, replacing = tx))
    }

    @Test
    fun `with a late month start day an installment lands inside its own month`() = runTest {
        val budget = setUp()
        assertTrue(db.setMonthStartDay(30, OCT_6))
        // Feb 2026 with day 30 starts on Feb 28 (no Feb 30) and ends on Mar 29; Mar 28 would still be February's.
        val tx = db.addManual(TransactionForm(budget, listOf("casa"), Cents(200), 2, "Loja", LocalDate(2026, 2, 28), LocalTime(10, 0)))
        assertEquals(listOf(LocalDate(2026, 2, 28), LocalDate(2026, 3, 30)), db.transaction(tx).installments.map { it.date })
        assertEquals(YearMonth(2026, 3), db.monthOf(LocalDate(2026, 3, 30)))
    }

    @Test
    fun `ensureMonths fills skipped months without bumping version, and a new limit holds from now on`() = runTest {
        val budget = setUp()
        val version = db.version.value
        val calls = writes

        db.ensureMonths(YearMonth(2027, 1))
        db.ensureMonths(YearMonth(2027, 1))
        assertEquals(version, db.version.value)
        assertEquals(calls, writes)
        assertEquals(listOf(60000L, 60000, 60000, 60000), db.history(YearMonth(2026, 1), YearMonth(2027, 1)).map { it.limit.v })
        assertTrue(db.budgetsOverview(YearMonth(2026, 9), OCT_6, BudgetOrder.RISK).isEmpty()) // before it existed

        assertTrue(db.setLimit(budget, Cents(80000), LocalDate(2026, 12, 1)))
        assertEquals(version + 1, db.version.value)
        assertEquals(calls + 1, writes)
        assertEquals(listOf(60000L, 60000, 80000, 80000), db.history(YearMonth(2026, 10), YearMonth(2027, 1)).map { it.limit.v })
        assertFalse(db.setLimit(budget, Cents(0), OCT_6))
        assertFalse(db.setLimit(BudgetId(99), Cents(100), OCT_6))
        assertEquals(version + 1, db.version.value) // refused writes change nothing
    }

    @Test
    fun `steps are validated, and a lower limit drops the edits it can't hold`() = runTest {
        val budget = setUp()
        val oct = YearMonth(2026, 10)
        db.ensureMonths(oct)
        // Oct 2026 from Monday: 1–4, 5–11, 12–18, 19–25, 26–31.
        val weeks = db.budgetDetail(budget, oct, OCT_6).month.weeks.map { it.start }
        assertEquals(listOf(1, 5, 12, 19, 26).map { LocalDate(2026, 10, it) }, weeks)

        assertFalse(db.setStep(budget, oct, LocalDate(2026, 10, 6), Cents(100))) // not a week start
        assertFalse(db.setStep(budget, oct, weeks[1], Cents(-1)))
        assertFalse(db.setStep(budget, oct, weeks[1], Cents(60001)))
        assertTrue(db.setStep(budget, oct, weeks[1], Cents(20000)))
        assertTrue(db.setStep(budget, oct, weeks[2], Cents(40000))) // max = 60000 − 20000
        assertTrue(db.setStep(budget, oct, weeks[3], Cents(0)))
        assertTrue(db.setStep(budget, oct, weeks[0], Cents(0)))
        assertFalse(db.setStep(budget, oct, weeks[4], Cents(0))) // the only automatic week left
        assertTrue(db.setStep(budget, oct, weeks[0], Cents(0))) // re-editing is fine
        assertEquals(listOf(0L, 20000, 40000, 0, 0), db.budgetDetail(budget, oct, OCT_6).month.weeks.map { it.step.v })

        assertTrue(db.setLimit(budget, Cents(60000), OCT_6)) // still fits
        assertEquals(4, db.budgetDetail(budget, oct, OCT_6).month.weeks.count { it.edited })
        assertTrue(db.setLimit(budget, Cents(59999), OCT_6))
        assertTrue(db.budgetDetail(budget, oct, OCT_6).month.weeks.none { it.edited })
    }

    @Test
    fun `changing the start days drops step edits from the current month on`() = runTest {
        db.addSource(NUBANK, "Nu", 0)
        val budget = db.createBudget("Mercado", Cents(60000), LocalDate(2026, 8, 1))
        db.ensureMonths(YearMonth(2026, 12))
        assertTrue(db.setStep(budget, YearMonth(2026, 10), LocalDate(2026, 10, 5), Cents(1000)))
        assertTrue(db.setStep(budget, YearMonth(2026, 11), LocalDate(2026, 11, 16), Cents(1000)))

        assertFalse(db.setMonthStartDay(0, NOV_20))
        assertTrue(db.setMonthStartDay(15, NOV_20))

        // September is now Sep 15 – Oct 14: the Oct 5 edit still matches a week and stays.
        val sep = db.budgetDetail(budget, YearMonth(2026, 9), NOV_20).month
        assertEquals(LocalDate(2026, 9, 15) to LocalDate(2026, 10, 14), sep.start to sep.end)
        assertTrue(sep.weeks.single { it.start == LocalDate(2026, 10, 5) }.edited)
        // November is now Nov 15 – Dec 14 and has a week on Nov 16, but that edit was dropped.
        assertTrue(db.budgetDetail(budget, YearMonth(2026, 11), NOV_20).month.weeks.none { it.edited })

        assertTrue(db.setStep(budget, YearMonth(2026, 11), LocalDate(2026, 11, 16), Cents(1000)))
        db.setWeekStart(DayOfWeek.SUNDAY, NOV_20)
        assertEquals(DayOfWeek.SUNDAY, db.settings().weekStart)
        assertTrue(db.budgetDetail(budget, YearMonth(2026, 11), NOV_20).month.weeks.none { it.edited })
    }

    @Test
    fun `deleting a budget or a tag sends their notices back to the Pauta`() = runTest {
        db.addSource(NUBANK, "Nu", 0)
        val a = db.createBudget("Casa", Cents(10000), OCT_6)
        val b = db.createBudget("casa ", Cents(10000), OCT_6)
        assertEquals(BudgetId(0), b) // names are unique ignoring case
        val c = db.createBudget("Lazer", Cents(10000), OCT_6)
        val n1 = post(nu(PURCHASE, T))
        val n2 = post(nu(UBER, T + MIN))
        acceptAsRead(n1, a, listOf("x"))
        val t2 = acceptAsRead(n2, c, listOf("x", "y"))

        db.deleteBudget(a)
        assertEquals(listOf(n1), db.pending().map { it.id })
        assertEquals(listOf(t2), db.transactions().map { it.id })

        val tags = db.tags().associateBy { it.name }
        assertEquals(TagRow(tags.getValue("x").id, "x", 1, 0), tags.getValue("x"))
        assertFalse(db.renameTag(tags.getValue("y").id, " X "))
        assertTrue(db.renameTag(tags.getValue("y").id, " Lazer "))
        assertTrue(db.mergeTags(tags.getValue("y").id, tags.getValue("x").id))
        assertEquals(listOf("x"), db.transactions().single().tags)
        assertEquals(1, db.tags().single().soleUses)

        db.deleteTag(tags.getValue("x").id)
        assertTrue(db.transactions().isEmpty())
        assertEquals(setOf(n1, n2), db.pending().map { it.id }.toSet())
    }

    @Test
    fun `export then import restores the database, and junk is refused`() = runTest {
        val budget = setUp()
        val notice = post(nu(PURCHASE, T))
        acceptAsRead(notice, budget)
        val copy = "$path.copy"
        assertTrue(db.exportTo(copy))

        db.deleteBudget(budget)
        db.setTheme(Theme.DARK)
        assertTrue(db.transactions().isEmpty())

        val version = db.version.value
        assertTrue(db.importFrom(copy))
        assertEquals(version + 1, db.version.value)
        assertEquals(listOf("Mercado"), db.transactions().map { it.budgetName })
        assertEquals(Theme.SYSTEM, db.settings().theme)
        assertNotEquals(TransactionId(0), db.addManual(TransactionForm(budget, listOf("x"), Cents(1), 1, "", OCT_6, LocalTime(0, 0))))

        SystemFileSystem.sink(Path(copy)).buffered().use { it.write(ByteArray(4096) { i -> i.toByte() }) }
        assertFalse(db.importFrom(copy))
        assertFalse(db.importFrom("$path.missing"))
        assertEquals(2, db.transactions().size)
    }

    private companion object {
        const val NUBANK = "com.nu.production"
        const val BANK = "com.bank"
        const val MIN = 60_000L
        /** 2026-10-06 11:43:59 in São Paulo. */
        const val T = 1_791_297_839_102L
        val OCT_6 = LocalDate(2026, 10, 6)
        val NOV_20 = LocalDate(2026, 11, 20)
        const val PURCHASE = "Compra de R$ 31,50 APROVADA em GIPL COMERCIO DE ALIME para o cartão com final 1234."
        const val UBER = "Compra de R$ 9,94 APROVADA em DL          *UberRides no seu cartão Nu Empresas."
        const val UBER_REFUND = "A compra em DL          *UberRides no valor de R$ 9,94 foi estornada no seu cartão Nu Empresas."
    }
}
