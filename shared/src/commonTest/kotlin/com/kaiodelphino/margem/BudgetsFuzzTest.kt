package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

// Block 3 (Verbas) fuzz: hostile names, limits, steps and dates on the budget writes and on the
// new reads (clearStep, limitDropsEdits, previewBudget, budgetDetail). Fixed seeds, bounded
// iterations, a real SQLite file per Db, real-time timeout. Expected values come from the spec
// (§3, §4), the glossary and the KDoc of Db.kt / Types.kt, never from the implementation.
// Every failure message carries the seed, the iteration and the input.

private val B_TODAY = LocalDate(2026, 10, 14)

/**
 * Limits up to R$ 10 milhões must be accepted: the KDoc refuses only limit ≤ 0 and "above the
 * maximum", without saying what the maximum is. Above this, refusing is allowed, crashing is not.
 */
private const val B_SANE = 1_000_000_000L

/** Real threads under a wall-clock timeout (withTimeout inside Dispatchers.Default), so a deadlock fails. */
private fun realTime(block: suspend () -> Unit) = runTest(timeout = 90.seconds) {
    withContext(Dispatchers.Default) { withTimeout(60.seconds) { block() } }
}

/** A fresh Db on a temporary file, closed and deleted (with -wal/-shm) afterwards. */
private suspend fun withDb(block: suspend (Db) -> Unit) {
    val path = Path(SystemTemporaryDirectory, "margem-bfuzz-${Random.Default.nextLong().toULong()}.db").toString()
    try {
        val db = Db(BundledSQLiteDriver(), path)
        try {
            block(db)
        } finally {
            db.close()
        }
    } finally {
        for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
    }
}

/** Runs one call of the API; any exception (but cancellation/timeout) fails with [where]. */
private suspend fun <T> noThrow(where: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fail("$where threw $e", e)
}

/** Escaped, truncated rendering of [s] for failure messages. */
private fun bShow(s: String): String = buildString {
    append('"')
    for (c in s.take(120)) {
        if (c.code in 0x20..0x7E && c != '"' && c != '\\') append(c)
        else append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }
    append('"')
    if (s.length > 120) append("…(${s.length} chars)")
}

/**
 * A text as the Db stores and reads it back (architecture D28): U+FEFF, U+FFFE, U+FFFF and lone
 * surrogates become U+FFFD; everything else is exact.
 */
private fun bStored(s: String): String = buildString {
    for (i in s.indices) {
        val c = s[i]
        val lone = (c.isHighSurrogate() && (i + 1 >= s.length || !s[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !s[i - 1].isHighSurrogate()))
        append(if (c == '﻿' || c == '￾' || c == '￿' || lone) '�' else c)
    }
}

// ---- hostile names ----

/** One hostile piece of a name: lone surrogates, the D28 characters, controls, combining marks, scripts. */
private fun nameChar(rnd: Random): String = when (rnd.nextInt(14)) {
    0 -> Char(rnd.nextInt(0xD800, 0xDC00)).toString() // lone high surrogate (unless a low one follows)
    1 -> Char(rnd.nextInt(0xDC00, 0xE000)).toString() // lone low surrogate
    2 -> listOf("﻿", "￾", "￿", "�").random(rnd)
    3 -> {
        val v = rnd.nextInt(0x10000, 0x110000) - 0x10000
        "" + Char(0xD800 + (v shr 10)) + Char(0xDC00 + (v and 0x3FF))
    }
    4 -> Char(rnd.nextInt(0xC0, 0x250)).toString() // Latin-1 and Latin Extended: é, É, ç, ø, ß…
    5 -> Char(rnd.nextInt(0x400, 0x500)).toString() // Cyrillic
    6 -> Char(rnd.nextInt(0x4E00, 0xA000)).toString() // CJK
    7 -> Char(rnd.nextInt(0, 0x20)).toString() // controls, \u0000 included
    8 -> Char(rnd.nextInt(0x300, 0x370)).toString() // combining marks
    9 -> listOf("​", "‎", "‮", "⁦", " ", "　", " ", "\t").random(rnd)
    10 -> listOf("'", "\"", "%", "_", "\\", ";", "--", "'; DROP TABLE budgets;--", "?", ":name").random(rnd)
    else -> Char(rnd.nextInt(0x21, 0x7F)).toString()
}

/** A name with no whitespace at its ends (so trimming can't matter): "<" + hostile + ">". */
private fun boxedName(rnd: Random, maxPieces: Int = 10): String =
    "<" + buildString { repeat(rnd.nextInt(0, maxPieces + 1)) { append(nameChar(rnd)) } } + ">"

/** Names the KDoc calls blank: only whitespace (Unicode White_Space, as Kotlin's isBlank). */
private val BLANKS = listOf("", " ", "   ", "\t", "\n", "\r\n", " \t \n ", " ", " ", "　", " ", "  　 ")

class BudgetsFuzzTest {

    // ---- names ----

    @Test
    fun `budget names survive hostile Unicode and are refused only when blank or used ignoring case`() = realTime {
        withDb { db ->
            val seed = 0xB3A1L
            val rnd = Random(seed)
            val month = db.monthOf(B_TODAY)
            val names = HashMap<BudgetId, String>() // id → stored name

            /** Whether [stored] collides with another budget's name ignoring case; null when lower/upper folding disagree. */
            fun collides(stored: String, except: BudgetId): Boolean? {
                val others = names.filterKeys { it != except }.values
                val byLower = others.any { it.lowercase() == stored.lowercase() }
                val byUpper = others.any { it.uppercase() == stored.uppercase() }
                return if (byLower == byUpper) byLower else null
            }

            suspend fun create(i: Int, name: String, exactName: Boolean = true) {
                val where = "seed=$seed i=$i createBudget(${bShow(name)})"
                val stored = bStored(name)
                val version = db.version.value
                val id = noThrow(where) { db.createBudget(name, Cents(10_000L + i), B_TODAY) }
                when {
                    name.isBlank() -> assertEquals(BudgetId(0), id, "$where: blank name refused (createBudget KDoc)")
                    exactName -> when (collides(stored, BudgetId(0))) {
                        true -> assertEquals(BudgetId(0), id, "$where: name already used ignoring case (createBudget KDoc)")
                        false -> assertNotEquals(0L, id.v, "$where: refused a new non-blank name")
                        null -> {}
                    }
                }
                if (id.v == 0L) {
                    assertEquals(version, db.version.value, "$where: a refused write doesn't bump version (Db.version KDoc)")
                    return
                }
                assertFalse(id in names, "$where: returned an existing id ${id.v}")
                val detail = noThrow("$where budgetDetail") { db.budgetDetail(id, month, B_TODAY) }
                assertEquals(id, detail.id, "$where: budgetDetail id")
                if (exactName) {
                    assertEquals(stored, detail.name, "$where: name read back is what was stored (D28)")
                } else {
                    // Spaces at the ends: whether they are trimmed is not in the KDoc.
                    assertTrue(detail.name == stored || detail.name == stored.trim(), "$where: name read back ${bShow(detail.name)}")
                }
                assertEquals(Cents(10_000L + i), detail.month.limit, "$where: limit of today's month")
                names[id] = detail.name
            }

            suspend fun rename(i: Int, id: BudgetId, name: String) {
                val where = "seed=$seed i=$i renameBudget(${id.v}, ${bShow(name)}) from ${bShow(names[id] ?: "?")}"
                val stored = bStored(name)
                val version = db.version.value
                val ok = noThrow(where) { db.renameBudget(id, name) }
                when {
                    name.isBlank() -> assertFalse(ok, "$where: blank name refused (renameBudget KDoc)")
                    id !in names -> assertFalse(ok, "$where: unknown budget refused (renameBudget KDoc)")
                    else -> when (collides(stored, id)) {
                        true -> assertFalse(ok, "$where: name used by another budget ignoring case (renameBudget KDoc)")
                        false -> assertTrue(ok, "$where: refused a free non-blank name")
                        null -> {}
                    }
                }
                if (!ok) {
                    assertEquals(version, db.version.value, "$where: a refused write doesn't bump version (Db.version KDoc)")
                    if (id in names) assertEquals(names[id], db.budgetDetail(id, month, B_TODAY).name, "$where: refused rename changed the name")
                    return
                }
                assertEquals(stored, db.budgetDetail(id, month, B_TODAY).name, "$where: renamed name read back (D28)")
                names[id] = stored
            }

            // Case pairs, accents included (architecture: "Zé" = "ZÉ"); accents themselves are not case.
            var i = 0
            for (n in listOf("Zé", "ZÉ", "zé", "zÉ", "Ze", "ZE", "Ação", "AÇÃO", "ação", "Acao", "Ñandú", "ÑANDÚ", "Еда", "ЕДА", "еда", "Øre", "øRE", "ＡＢＣ", "ａｂｃ")) {
                create(i++, n)
            }
            val ze = names.entries.single { it.value == "Zé" }.key
            val zePlain = names.entries.single { it.value == "Ze" }.key
            assertTrue(names.values.containsAll(listOf("Zé", "Ze", "Ação", "Acao", "Ñandú", "Еда", "Øre", "ＡＢＣ")), "seed=$seed accent-different names are distinct: ${names.values.map(::bShow)}")
            assertEquals(8, names.size, "seed=$seed only case duplicates refused: ${names.values.map(::bShow)}")
            rename(i++, zePlain, "zÉ") // taken by "Zé"
            rename(i++, ze, "ZÉ") // its own name in another case: Editar verba saves the name unchanged or recased
            rename(i++, ze, "ZÉ") // the same name again
            rename(i++, ze, "Zé")

            // The D28 characters: normalized to U+FFFD, so names that differ only there are the same name.
            for (n in listOf("﻿", "﻿Mercado", "Mercado﻿", "a￾b", "a￿b", "a�b", "x\uD800", "x\uDC00", "x\uDBFF\uDBFF", "\uDC00\uD800", "\uD83D", "y😀", "y\uDE00\uD83D")) {
                create(i++, n)
            }

            // Blanks are refused, by create and by rename.
            for (n in BLANKS) {
                create(i++, n)
                rename(i++, ze, n)
            }

            // Very long names, with pairs and lone surrogates inside.
            for (len in listOf(10_000, 100_000)) {
                val long = buildString { append('<'); while (length < len) append(nameChar(rnd)); append('>') }
                create(i++, long)
                create(i++, long.uppercase()) // same name ignoring case, unless folding disagrees
            }

            // Random hostile names and renames.
            repeat(150) {
                when (rnd.nextInt(6)) {
                    0, 1, 2 -> create(i++, boxedName(rnd))
                    3 -> {
                        val raw = buildString { repeat(rnd.nextInt(0, 6)) { append(nameChar(rnd)) } }
                        create(i++, raw, exactName = raw == raw.trim())
                    }
                    4 -> {
                        val (id, current) = names.entries.toList().random(rnd).toPair()
                        val target = when (rnd.nextInt(4)) {
                            0 -> current.uppercase()
                            1 -> current.lowercase()
                            2 -> names.values.random(rnd).uppercase() // likely another budget's name
                            else -> boxedName(rnd)
                        }
                        if (target == target.trim()) rename(i++, id, target)
                    }
                    else -> rename(i++, listOf(BudgetId(0), BudgetId(-1), BudgetId(Long.MAX_VALUE), BudgetId(Long.MIN_VALUE)).random(rnd), boxedName(rnd))
                }
            }

            // The overview of the month has exactly these budgets with exactly these names.
            val overview = noThrow("seed=$seed budgetsOverview") { db.budgetsOverview(month, B_TODAY, BudgetOrder.AZ) }
            assertEquals(names.toMap(), overview.associate { it.id to it.name }, "seed=$seed budgetsOverview names")
            for (order in BudgetOrder.entries) {
                assertEquals(names.keys, noThrow("budgetsOverview($order)") { db.budgetsOverview(month, B_TODAY, order) }.map { it.id }.toSet(), "seed=$seed budgetsOverview($order) ids")
            }
        }
    }

    // ---- limits and steps ----

    /** What this test set and expects: the limit of each month and the edited steps of each month. */
    private class Plan(val months: List<YearMonth>) {
        val limit = HashMap<YearMonth, Long>()
        val edits = HashMap<YearMonth, HashMap<LocalDate, Long>>()
        fun sumEdits(m: YearMonth): Long = edits.getValue(m).values.sum()
        override fun toString() = months.joinToString("; ") { "$it limit=${limit[it]} edits=${edits[it]}" }
    }

    @Test
    fun `steps and limits under hostile values keep every month whole`() = realTime {
        for (cfg in 0 until 5) withDb { db -> stepsSession(db, 0xB3B0L + cfg) }
    }

    private suspend fun stepsSession(db: Db, seed: Long) {
        val rnd = Random(seed)
        val startDay = listOf(1, 1, 5, 15, 28, 29, 30, 31).random(rnd)
        val weekStart = DayOfWeek.entries.random(rnd)
        val created = LocalDate(rnd.nextInt(2020, 2031), rnd.nextInt(1, 13), 1).plus(rnd.nextInt(0, 31), DateTimeUnit.DAY)
        db.setMonthStartDay(startDay, created)
        db.setWeekStart(weekStart, created)
        val l0 = if (rnd.nextInt(4) == 0) rnd.nextLong(1, 10) else rnd.nextLong(1, 2_000_000)
        val ctx = "seed=$seed startDay=$startDay weekStart=$weekStart created=$created l0=$l0"

        val id = noThrow("$ctx createBudget") { db.createBudget("Verba", Cents(l0), created) }
        assertNotEquals(0L, id.v, "$ctx createBudget refused")
        val m0 = db.monthOf(created)
        val months = listOf(m0, m0.plusMonth(), m0.plusMonth().plusMonth())
        db.ensureMonths(months.last())
        val plan = Plan(months)
        for (m in months) {
            plan.limit[m] = l0
            plan.edits[m] = HashMap()
        }
        // The windows don't change in this session (settings stay): used only to pick dates inside a month.
        val window = months.associateWith { m ->
            val bm = noThrow("$ctx budgetDetail($m)") { db.budgetDetail(id, m, created) }.month
            assertTrue(bm.start <= bm.end && bm.start != NO_DATE, "$ctx budgetDetail($m) window ${bm.start}..${bm.end}")
            bm.start to bm.end
        }
        fun dayIn(m: YearMonth): LocalDate {
            val (s, e) = window.getValue(m)
            return s.plus(rnd.nextInt(0, s.daysUntil(e) + 1), DateTimeUnit.DAY)
        }

        suspend fun snapshot(view: LocalDate) = months.map { db.budgetDetail(id, it, view) }

        suspend fun verify(where: String, view: LocalDate) {
            for (m in months) {
                val w = "$where month=$m view=$view plan=[$plan]"
                val d = noThrow("$w budgetDetail") { db.budgetDetail(id, m, view) }
                assertEquals(id, d.id, "$w: id")
                assertEquals("Verba", d.name, "$w: name")
                assertEquals(m, d.month.month, "$w: month")
                assertEquals(m0, d.firstMonth, "$w: firstMonth is the month it was created in (BudgetDetail KDoc)")
                assertTrue(d.lines.isEmpty(), "$w: no transactions, no lines")
                assertEquals(Cents(plan.limit.getValue(m)), d.month.limit, "$w: limit (spec §3: from today's month on)")
                checkWeeks(d.month, weekStart, w)
                val edited = d.month.weeks.filter { it.edited }.associate { it.start to it.step.v }
                assertEquals(plan.edits.getValue(m).toMap(), edited, "$w: edited weeks keep their steps (spec §4)")
                val inOverview = noThrow("$w budgetsOverview") { db.budgetsOverview(m, view, BudgetOrder.RISK) }.firstOrNull { it.id == id }
                assertNotNull(inOverview, "$w: budgetsOverview lists the budget")
                assertEquals(d.month, inOverview.month, "$w: budgetsOverview and budgetDetail agree (D12)")
            }
        }

        verify("$ctx start", created)
        for (i in 0 until 120) {
            val m = months.random(rnd)
            val view = dayIn(months.random(rnd))
            val bm = db.budgetDetail(id, m, view).month
            val roll = rnd.nextInt(100)
            val where: String
            when {
                roll < 35 -> { // setStep on a real week of the month
                    val w = bm.weeks.random(rnd)
                    val v = stepChoice(rnd, w.max.v)
                    where = "$ctx i=$i setStep(month=$m week=${w.start} step=$v) max=${w.max.v} locked=${w.locked} edited=${w.edited}"
                    val before = snapshot(view)
                    val version = db.version.value
                    val ok = noThrow(where) { db.setStep(id, m, w.start, Cents(v)) }
                    assertEquals(!w.locked && v in 0..w.max.v, ok, "$where: accepts exactly 0..max of a week not locked (setStep, Week.max KDoc)")
                    if (ok) {
                        plan.edits.getValue(m)[w.start] = v
                    } else {
                        assertEquals(before, snapshot(view), "$where: refused setStep changed something")
                        assertEquals(version, db.version.value, "$where: refused setStep bumped version")
                    }
                }
                roll < 60 -> { // clearStep
                    val allStarts = months.flatMap { mm -> db.budgetDetail(id, mm, view).month.weeks.map { it.start } }.toSet()
                    val edited = bm.weeks.filter { it.edited }
                    val kind = rnd.nextInt(6)
                    var target = id
                    var month = m
                    val ws: LocalDate
                    val expect: Boolean
                    when {
                        kind <= 1 && edited.isNotEmpty() -> { ws = edited.random(rnd).start; expect = true }
                        kind == 2 && edited.isNotEmpty() -> { // the edited week, but asked in another month
                            ws = edited.random(rnd).start
                            month = (months - m).random(rnd)
                            expect = false
                        }
                        kind == 3 -> { // not a week start of any month here
                            var d = dayIn(m)
                            while (d in allStarts) d = d.plus(1, DateTimeUnit.DAY)
                            ws = if (rnd.nextBoolean()) d else listOf(NO_DATE, LocalDate(1, 1, 1), LocalDate(9999, 12, 31), LocalDate(2024, 2, 29)).random(rnd)
                            expect = false
                        }
                        kind == 4 -> { // an unknown budget, a real week
                            ws = bm.weeks.random(rnd).start
                            target = listOf(BudgetId(0), BudgetId(-1), BudgetId(id.v + 1000), BudgetId(Long.MAX_VALUE), BudgetId(Long.MIN_VALUE)).random(rnd)
                            expect = false
                        }
                        else -> { // a week with no edit
                            val auto = bm.weeks.filter { !it.edited }
                            ws = auto.random(rnd).start
                            expect = false
                        }
                    }
                    where = "$ctx i=$i clearStep(budget=${target.v} month=$month week=$ws) asked for $m"
                    val before = snapshot(view)
                    val version = db.version.value
                    val ok = noThrow(where) { db.clearStep(target, month, ws) }
                    assertEquals(expect, ok, "$where: true only for an edited week of that month of a known budget (clearStep KDoc)")
                    if (ok) {
                        plan.edits.getValue(m).remove(ws)
                    } else {
                        assertEquals(before, snapshot(view), "$where: refused clearStep changed something")
                        assertEquals(version, db.version.value, "$where: refused clearStep bumped version")
                    }
                }
                roll < 85 -> { // limitDropsEdits, then setLimit with the same arguments
                    val tm = months.random(rnd)
                    val today = dayIn(tm)
                    val limit = limitChoice(rnd, plan.months.map(plan::sumEdits))
                    where = "$ctx i=$i setLimit(limit=$limit today=$today)"
                    val before = snapshot(view)
                    val version = db.version.value
                    val drops = noThrow("$where limitDropsEdits") { db.limitDropsEdits(id, Cents(limit), today) }
                    assertEquals(before, snapshot(view), "$where: limitDropsEdits writes nothing (KDoc)")
                    assertEquals(version, db.version.value, "$where: limitDropsEdits bumped version")
                    val ok = noThrow(where) { db.setLimit(id, Cents(limit), today) }
                    if (limit <= 0) assertFalse(ok, "$where: limit ≤ 0 refused (setLimit KDoc)")
                    if (limit in 1..B_SANE) assertTrue(ok, "$where: a sane limit refused")
                    if (ok) {
                        val from = months.filter { it >= tm }
                        val expectDrops = from.any { plan.sumEdits(it) > limit }
                        assertEquals(expectDrops, drops, "$where: limitDropsEdits = some month from today's on has edits summing more than the limit (spec §4) plan=[$plan]")
                        for (mm in from) {
                            plan.limit[mm] = limit
                            if (plan.sumEdits(mm) > limit) plan.edits.getValue(mm).clear()
                        }
                    } else {
                        assertFalse(drops, "$where: limitDropsEdits is false when setLimit refuses (KDoc)")
                        assertEquals(before, snapshot(view), "$where: refused setLimit changed something")
                        assertEquals(version, db.version.value, "$where: refused setLimit bumped version")
                    }
                }
                roll < 93 -> { // limitDropsEdits alone, with any date as today
                    val today = if (rnd.nextBoolean()) anyDate(rnd) else dayIn(months.random(rnd))
                    val limit = limitChoice(rnd, plan.months.map(plan::sumEdits))
                    where = "$ctx i=$i limitDropsEdits(limit=$limit today=$today)"
                    val before = snapshot(view)
                    val version = db.version.value
                    val drops = noThrow(where) { db.limitDropsEdits(id, Cents(limit), today) }
                    assertEquals(before, snapshot(view), "$where: writes nothing (KDoc)")
                    assertEquals(version, db.version.value, "$where: bumped version")
                    val from = db.monthOf(today)
                    val expect = months.filter { it >= from }.any { plan.sumEdits(it) > limit }
                    when {
                        limit <= 0 -> assertFalse(drops, "$where: false for limit ≤ 0 (KDoc)")
                        limit <= B_SANE -> assertEquals(expect, drops, "$where: some month from today's ($from) on has edits summing more than the limit (KDoc) plan=[$plan]")
                        !expect -> assertFalse(drops, "$where: no month from $from on has edits above the limit plan=[$plan]")
                    }
                }
                else -> { // every week not locked accepts max and refuses max + 1; the locked one refuses all
                    where = "$ctx i=$i probe month=$m"
                    probe(db, id, m, view, weekStart, where)
                }
            }
            verify(where, view)
        }
    }

    /**
     * For each week of [month]: not locked → setStep(max + 1) refused, setStep(max) accepted, then
     * restored (setStep(old) or clearStep); locked → refused with any value. Ends as it started.
     */
    private suspend fun probe(db: Db, id: BudgetId, month: YearMonth, view: LocalDate, weekStart: DayOfWeek, where: String) {
        val start = db.budgetDetail(id, month, view)
        for (w in start.month.weeks) {
            val at = "$where week=${w.start} max=${w.max.v} step=${w.step.v} edited=${w.edited} locked=${w.locked}"
            if (w.locked) {
                for (v in listOf(0L, w.step.v, 1L, w.max.v)) {
                    assertFalse(noThrow("$at setStep($v)") { db.setStep(id, month, w.start, Cents(v)) }, "$at: the locked week refuses $v (Week.locked KDoc)")
                }
                assertFalse(noThrow("$at clearStep") { db.clearStep(id, month, w.start) }, "$at: the locked week has no edit to clear")
                continue
            }
            if (w.max.v < Long.MAX_VALUE) {
                assertFalse(noThrow("$at setStep(max+1)") { db.setStep(id, month, w.start, Cents(w.max.v + 1)) }, "$at: max + 1 refused (Week.max KDoc)")
            }
            assertFalse(noThrow("$at setStep(-1)") { db.setStep(id, month, w.start, Cents(-1)) }, "$at: -1 refused (setStep KDoc)")
            assertEquals(start, db.budgetDetail(id, month, view), "$at: refusals changed the month")
            assertTrue(noThrow("$at setStep(max)") { db.setStep(id, month, w.start, w.max) }, "$at: max accepted (Week.max KDoc)")
            val atMax = db.budgetDetail(id, month, view).month
            checkWeeks(atMax, weekStart, "$at after setStep(max)")
            assertEquals(w.max, atMax.weeks.single { it.start == w.start }.step, "$at: the step is max after setStep(max)")
            val restored = if (w.edited) db.setStep(id, month, w.start, w.step) else db.clearStep(id, month, w.start)
            assertTrue(restored, "$at: restoring the week after setStep(max) refused")
            assertEquals(start, db.budgetDetail(id, month, view), "$at: setStep(max) then restore didn't give the month back")
        }
    }

    @Test
    fun `setStep on dates that are not week starts never throws and keeps months whole`() = realTime {
        for (cfg in 0 until 3) withDb { db ->
            val seed = 0xB3C0L + cfg
            val rnd = Random(seed)
            val startDay = listOf(1, 10, 29, 31).random(rnd)
            val weekStart = DayOfWeek.entries.random(rnd)
            db.setMonthStartDay(startDay, B_TODAY)
            db.setWeekStart(weekStart, B_TODAY)
            val limit = rnd.nextLong(1, 1_000_000)
            val ctx = "seed=$seed startDay=$startDay weekStart=$weekStart limit=$limit"
            val id = db.createBudget("Bogus", Cents(limit), B_TODAY)
            assertNotEquals(0L, id.v, "$ctx createBudget")
            val m0 = db.monthOf(B_TODAY)
            val months = listOf(m0, m0.plusMonth())
            db.ensureMonths(months.last())
            for (i in 0 until 120) {
                val m = months.random(rnd)
                val bm = db.budgetDetail(id, m, B_TODAY).month
                val other = db.budgetDetail(id, (months - m).single(), B_TODAY).month
                val starts = bm.weeks.map { it.start }.toSet()
                val date = when (rnd.nextInt(6)) {
                    0 -> other.weeks.random(rnd).start // a week start of the other month
                    1 -> anyDate(rnd)
                    2 -> bm.start.plus(rnd.nextInt(-40, 0), DateTimeUnit.DAY) // before the month
                    3 -> bm.end.plus(rnd.nextInt(1, 40), DateTimeUnit.DAY) // after the month
                    else -> { // inside the month, not a week start
                        var d = bm.start.plus(rnd.nextInt(0, bm.start.daysUntil(bm.end) + 1), DateTimeUnit.DAY)
                        while (d in starts) d = d.plus(1, DateTimeUnit.DAY)
                        d
                    }
                }
                val v = when (rnd.nextInt(4)) {
                    0 -> 0L
                    1 -> rnd.nextLong(0, limit + 1)
                    2 -> listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE, limit, limit + 1).random(rnd)
                    else -> rnd.nextLong(0, 1000)
                }
                val where = "$ctx i=$i month=$m date=$date step=$v"
                // What happens to such a date is not in the KDoc ("a Week.start of that month"): no crash, months stay whole.
                noThrow("$where setStep") { db.setStep(id, m, date, Cents(v)) }
                if (rnd.nextInt(3) == 0) noThrow("$where clearStep") { db.clearStep(id, m, date) }
                // Real edits in between, so the bogus calls meet edited months too.
                if (rnd.nextInt(3) == 0) {
                    val w = bm.weeks.random(rnd)
                    if (!w.locked) db.setStep(id, m, w.start, Cents(rnd.nextLong(0, w.max.v + 1)))
                }
                if (rnd.nextInt(5) == 0) bm.weeks.filter { it.edited }.randomOrNull(rnd)?.let { db.clearStep(id, m, it.start) }
                for (mm in months) {
                    val d = noThrow("$where budgetDetail($mm)") { db.budgetDetail(id, mm, B_TODAY) }
                    assertEquals(Cents(limit), d.month.limit, "$where month=$mm: limit")
                    checkWeeks(d.month, weekStart, "$where month=$mm")
                }
            }
        }
    }

    // ---- unknown budgets, months without a limit, hostile dates ----

    @Test
    fun `unknown budgets and hostile dates read as zero and never throw`() = realTime {
        withDb { db ->
            val seed = 0xB3D0L
            val rnd = Random(seed)
            val startDay = listOf(1, 15, 30, 31).random(rnd)
            val weekStart = DayOfWeek.entries.random(rnd)
            db.setMonthStartDay(startDay, B_TODAY)
            db.setWeekStart(weekStart, B_TODAY)
            val ctx = "seed=$seed startDay=$startDay weekStart=$weekStart"

            // A: a budget without edits. B: edited, then deleted.
            val a = db.createBudget("A", Cents(46_500), B_TODAY)
            val b = db.createBudget("B", Cents(60_000), B_TODAY)
            assertNotEquals(0L, a.v, "$ctx createBudget A")
            assertNotEquals(0L, b.v, "$ctx createBudget B")
            val m0 = db.monthOf(B_TODAY)
            val withLimit = listOf(m0, m0.plusMonth(), m0.plusMonth().plusMonth())
            db.ensureMonths(withLimit.last())
            val bWeek = db.budgetDetail(b, m0, B_TODAY).month.weeks.first { !it.locked }
            assertTrue(db.setStep(b, m0, bWeek.start, Cents(100)), "$ctx setStep on B")
            db.deleteBudget(b)
            assertFalse(db.clearStep(b, m0, bWeek.start), "$ctx clearStep on a deleted budget (KDoc: unknown budget)")
            val unknown = listOf(BudgetId(0), BudgetId(-1), BudgetId(Long.MIN_VALUE), BudgetId(Long.MAX_VALUE), BudgetId(a.v + 1000), b)

            for (i in 0 until 250) {
                val month = anyMonth(rnd)
                val date = anyDate(rnd)
                val limit = limitChoice(rnd, emptyList())
                val where = "$ctx i=$i month=$month date=$date limit=$limit"
                val version = db.version.value

                val u = unknown.random(rnd)
                assertEquals(BudgetDetail(), noThrow("$where budgetDetail(${u.v})") { db.budgetDetail(u, month, date) }, "$where: budgetDetail of unknown ${u.v} is zero (KDoc)")
                assertFalse(noThrow("$where limitDropsEdits(${u.v})") { db.limitDropsEdits(u, Cents(limit), date) }, "$where: limitDropsEdits of unknown ${u.v} is false (KDoc)")
                assertFalse(noThrow("$where clearStep(${u.v})") { db.clearStep(u, month, date) }, "$where: clearStep of unknown ${u.v} is false (KDoc)")

                // A has no edits anywhere: nothing to drop, nothing to clear, whatever the date or month.
                val d = noThrow("$where budgetDetail(A)") { db.budgetDetail(a, month, date) }
                if (month in withLimit) {
                    assertEquals(a, d.id, "$where: budgetDetail(A) id")
                    assertEquals(Cents(46_500), d.month.limit, "$where: budgetDetail(A) limit")
                    checkWeeks(d.month, weekStart, "$where budgetDetail(A)")
                    assertTrue(d.month.weeks.none { it.edited }, "$where: A has no edits")
                }
                assertFalse(noThrow("$where limitDropsEdits(A)") { db.limitDropsEdits(a, Cents(limit), date) }, "$where: A has no edits to drop")
                val ws = if (month in withLimit && rnd.nextBoolean()) d.month.weeks.random(rnd).start else date
                assertFalse(noThrow("$where clearStep(A, $ws)") { db.clearStep(a, month, ws) }, "$where: A has no edit to clear")

                val p = noThrow("$where previewBudget") { db.previewBudget(Cents(limit), date) }
                if (limit <= 0) assertEquals(BudgetMonth(), p, "$where: previewBudget of limit ≤ 0 is zero (KDoc)")

                assertEquals(version, db.version.value, "$where: reads and refusals bumped version")
            }
            assertEquals(listOf(a), db.budgetsOverview(m0, B_TODAY, BudgetOrder.MANUAL).map { it.id }, "$ctx: only A remains")
        }
    }

    // ---- previewBudget ----

    @Test
    fun `previewBudget equals the month of the budget it previews and is zero when the limit is refused`() = realTime {
        for (cfg in 0 until 3) withDb { db ->
            val seed = 0xB3E0L + cfg
            val rnd = Random(seed)
            val startDay = listOf(1, 7, 29, 30, 31).random(rnd)
            val weekStart = DayOfWeek.entries.random(rnd)
            db.setMonthStartDay(startDay, B_TODAY)
            db.setWeekStart(weekStart, B_TODAY)
            val ctx = "seed=$seed startDay=$startDay weekStart=$weekStart"
            for (i in 0 until 80) {
                val limit = limitChoice(rnd, emptyList())
                val today = anyDate(rnd)
                val where = "$ctx i=$i previewBudget(limit=$limit today=$today)"
                val month = db.monthOf(today)
                val version = db.version.value
                val p = noThrow(where) { db.previewBudget(Cents(limit), today) }
                assertEquals(version, db.version.value, "$where: writes nothing (KDoc)")
                assertTrue(noThrow("$where budgetsOverview") { db.budgetsOverview(month, today, BudgetOrder.MANUAL) }.isEmpty(), "$where: preview created a budget")
                assertEquals(p, noThrow("$where again") { db.previewBudget(Cents(limit), today) }, "$where: same inputs, same preview")

                val id = noThrow("$where createBudget") { db.createBudget("p$i", Cents(limit), today) }
                if (limit <= 0) {
                    assertEquals(BudgetId(0), id, "$where: createBudget refuses limit ≤ 0")
                    assertEquals(BudgetMonth(), p, "$where: zero when createBudget refuses the limit (KDoc)")
                    continue
                }
                if (id.v == 0L) {
                    assertTrue(limit > B_SANE, "$where: createBudget refused a sane limit")
                    assertEquals(BudgetMonth(), p, "$where: zero when createBudget refuses the limit (KDoc)")
                    continue
                }
                val d = noThrow("$where budgetDetail") { db.budgetDetail(id, month, today) }
                assertEquals(d.month, p, "$where: equals budgetDetail's month right after createBudget (KDoc)")
                assertEquals(month, p.month, "$where: today's month")
                assertEquals(Cents(limit), p.limit, "$where: limit")
                assertEquals(Cents(0), p.spent, "$where: nothing spent")
                assertEquals(Cents(limit), p.remaining, "$where: margem = cota − gasto (spec §3)")
                assertTrue(p.weeks.none { it.edited }, "$where: default steps")
                checkWeeks(p, weekStart, where)
                assertEquals(month, d.firstMonth, "$where: firstMonth is the month it was created in")
                db.deleteBudget(id)
                assertEquals(BudgetDetail(), db.budgetDetail(id, month, today), "$where: deleted budget reads as zero")
            }
        }
    }
}

// ---- shared checks and generators ----

/**
 * What every [BudgetMonth] with a limit must satisfy (spec §4, Week KDoc): calendar weeks starting on
 * [weekStart], cut to the month, contiguous; steps ≥ 0 summing to the limit; at least one week not
 * edited; locked = the only week not edited; max = limit − Σ other edited weeks (0 when locked).
 */
private fun checkWeeks(bm: BudgetMonth, weekStart: DayOfWeek, where: String) {
    val weeks = bm.weeks
    val shown = weeks.joinToString { "${it.start}..${it.end}=${it.step.v}${if (it.edited) "e" else ""}${if (it.locked) "L" else ""}/max${it.max.v}" }
    assertTrue(weeks.isNotEmpty(), "$where: no weeks")
    assertEquals(bm.start, weeks.first().start, "$where: the first week starts with the month (spec §4) [$shown]")
    assertEquals(bm.end, weeks.last().end, "$where: the last week ends with the month (spec §4) [$shown]")
    var sum = 0L
    var editedSum = 0L
    for ((i, w) in weeks.withIndex()) {
        assertTrue(w.start <= w.end && w.start.daysUntil(w.end) < 7, "$where: week ${w.start}..${w.end} [$shown]")
        if (i > 0) {
            assertEquals(weeks[i - 1].end.plus(1, DateTimeUnit.DAY), w.start, "$where: weeks are contiguous [$shown]")
            assertEquals(weekStart, w.start.dayOfWeek, "$where: weeks start on the week start day (spec §4) [$shown]")
        }
        assertTrue(w.step.v >= 0, "$where: week ${w.start} step ${w.step.v} < 0 (spec §4: never below R$ 0,00) [$shown]")
        assertTrue(sum <= Long.MAX_VALUE - w.step.v, "$where: steps sum past Long.MAX_VALUE [$shown]")
        sum += w.step.v
        if (w.edited) editedSum += w.step.v
    }
    assertEquals(bm.limit.v, sum, "$where: Σ steps = limit (spec §4, D11) [$shown]")
    val notEdited = weeks.count { !it.edited }
    assertTrue(notEdited >= 1, "$where: one week always stays automatic (spec §4) [$shown]")
    for (w in weeks) {
        assertEquals(!w.edited && notEdited == 1, w.locked, "$where: week ${w.start} locked iff the only one not edited (Week.locked KDoc) [$shown]")
        val max = if (w.locked) 0L else bm.limit.v - (editedSum - if (w.edited) w.step.v else 0L)
        assertEquals(max, w.max.v, "$where: week ${w.start} max = limit − Σ other edited (Week.max KDoc) [$shown]")
    }
}

/** A step around [max]: 0, max, max + 1, inside, negative, huge. */
private fun stepChoice(rnd: Random, max: Long): Long = when (rnd.nextInt(10)) {
    0 -> 0L
    1, 2 -> max
    3 -> if (max < Long.MAX_VALUE) max + 1 else Long.MAX_VALUE
    4, 5 -> if (max <= 0) 0L else rnd.nextLong(0, if (max < Long.MAX_VALUE) max + 1 else max)
    6 -> -1L
    7 -> listOf(Long.MIN_VALUE, Long.MAX_VALUE, -rnd.nextLong(1, Long.MAX_VALUE)).random(rnd)
    8 -> if (max < Long.MAX_VALUE - 1000) max + rnd.nextLong(1, 1000) else Long.MAX_VALUE
    else -> rnd.nextLong(0, 100_000)
}

/** A limit: refused ones, fewer cents than weeks, around a month's edited sum ([sums]), typical, absurd. */
private fun limitChoice(rnd: Random, sums: List<Long>): Long = when (rnd.nextInt(12)) {
    0 -> listOf(0L, -1L, Long.MIN_VALUE, -rnd.nextLong(1, 1_000_000)).random(rnd)
    1 -> rnd.nextLong(1, 10)
    2, 3 -> if (sums.isEmpty()) rnd.nextLong(1, 1000) else sums.random(rnd) + rnd.nextLong(-1, 2)
    4, 5, 6 -> rnd.nextLong(1, 10_000_000)
    7 -> rnd.nextLong(10_000_000, B_SANE + 1)
    8 -> listOf(1_000_000_000_000L, 10_000_000_000_000L, 99_999_999_999_999_999L, 100_000_000_000_000_000L, Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, Long.MAX_VALUE).random(rnd)
    else -> rnd.nextLong(1, 200_000)
}

/** Any date: plain ones, month ends, leap days, and extremes. */
private fun anyDate(rnd: Random): LocalDate = when (rnd.nextInt(8)) {
    0 -> listOf(
        NO_DATE, LocalDate(1, 1, 1), LocalDate(9999, 12, 31), LocalDate(2024, 2, 29), LocalDate(2025, 2, 28),
        LocalDate(2026, 12, 31), LocalDate(2027, 1, 1), LocalDate(1900, 3, 1), LocalDate(2100, 2, 28), LocalDate(2026, 1, 31),
    ).random(rnd)
    else -> LocalDate(rnd.nextInt(1990, 2100), rnd.nextInt(1, 13), 1).plus(rnd.nextInt(0, 31), DateTimeUnit.DAY)
}

/** Any month: plain ones and extremes. */
private fun anyMonth(rnd: Random): YearMonth = when (rnd.nextInt(8)) {
    0 -> listOf(NO_MONTH, YearMonth(1, 1), YearMonth(9999, 12), YearMonth(2024, 2), YearMonth(2026, 10)).random(rnd)
    else -> YearMonth(rnd.nextInt(2020, 2032), rnd.nextInt(1, 13))
}
