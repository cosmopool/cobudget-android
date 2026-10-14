package com.kaiodelphino.margem

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray

// Block 5 (Ajustes e importação) fuzz, shared side: random and hostile cobudget v7 files through
// importCobudget, and random sequences of start-day changes and step edits checked against
// startDropsEdits. Expected values come from spec §4 and §7.6, architecture D3, D4, D10, D28, D40,
// D41 and the KDoc of Capture.kt / Db.kt / Types.kt, never from an implementation.
//
// The oracle for "the rules of [capture]" is capture itself (public API) on a second Db: the
// importCobudget KDoc defines the import as those rules (minus the source one) applied row by row in
// postedAt order (then id), so a Db that captures the same rows in that order (with every package as
// a source) must end up with the same notices. Fixed seeds, bounded sizes, a real SQLite file per Db,
// wall-clock timeouts; every failure message carries the seed, the step and the input.

private const val AF_W = DUPLICATE_WINDOW_MS
private const val AF_PKG = "com.nu.production"
private const val AF_T0 = 1_791_000_000_000L
private val AF_TZ = TimeZone.of("America/Sao_Paulo")
private val AF_TODAY = LocalDate(2026, 10, 14)

/** The cobudget schema v7 table, verbatim from the importCobudget KDoc. */
private const val AF_V7 = "CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
    "`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, " +
    "`postedAt` INTEGER NOT NULL, `notificationWhen` INTEGER NOT NULL DEFAULT 0, `title` TEXT, `text` TEXT, " +
    "`bigText` TEXT, `subText` TEXT, `textLines` TEXT, `category` TEXT, `channelId` TEXT, " +
    "`extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL DEFAULT 0)"

/** The same columns, one definition each, to build files that differ from v7 in one column. */
private val AF_DEFS = linkedMapOf(
    "id" to "INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL",
    "packageName" to "TEXT NOT NULL",
    "appLabel" to "TEXT NOT NULL",
    "notificationKey" to "TEXT NOT NULL",
    "postedAt" to "INTEGER NOT NULL",
    "notificationWhen" to "INTEGER NOT NULL DEFAULT 0",
    "title" to "TEXT",
    "text" to "TEXT",
    "bigText" to "TEXT",
    "subText" to "TEXT",
    "textLines" to "TEXT",
    "category" to "TEXT",
    "channelId" to "TEXT",
    "extrasJson" to "TEXT NOT NULL",
    "contentHash" to "TEXT NOT NULL",
    "dismissed" to "INTEGER NOT NULL DEFAULT 0",
)
private val AF_COLUMNS = AF_DEFS.keys.toList()

private fun afDdl(columns: List<String>, rename: Map<String, String> = emptyMap(), table: String = "captured_notifications"): String =
    "CREATE TABLE `$table` (" + columns.joinToString { "`${rename[it] ?: it}` ${AF_DEFS.getValue(it)}" } + ")"

/** One row of `captured_notifications`; null = SQL NULL. */
private data class AfRow(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val key: String,
    val postedAt: Long,
    val whenMs: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val textLines: String?,
    val category: String?,
    val channelId: String?,
    val extrasJson: String,
    val contentHash: String,
    val dismissed: Long,
) {
    fun cell(column: String): Any? = when (column) {
        "id" -> id
        "packageName" -> packageName
        "appLabel" -> appLabel
        "notificationKey" -> key
        "postedAt" -> postedAt
        "notificationWhen" -> whenMs
        "title" -> title
        "text" -> text
        "bigText" -> bigText
        "subText" -> subText
        "textLines" -> textLines
        "category" -> category
        "channelId" -> channelId
        "extrasJson" -> extrasJson
        "contentHash" -> contentHash
        else -> dismissed
    }

    /** The RawNotice the importCobudget KDoc maps this row to: NULL text is "". */
    fun raw() = RawNotice(
        packageName = packageName, appLabel = appLabel, key = key, postedAt = postedAt, whenMs = whenMs,
        title = title ?: "", text = text ?: "", bigText = bigText ?: "", subText = subText ?: "", textLines = textLines ?: "",
        category = category ?: "", channelId = channelId ?: "", extrasJson = extrasJson,
    )

    val texts: List<String?> get() = listOf(title, text, bigText, subText, textLines)

    fun show() = "#$id pkg=${afShow(packageName)} key=${afShow(key)} at=$postedAt when=$whenMs " +
        "texts=${texts.joinToString(",", "[", "]") { afShow(it) }} d=$dismissed"
}

/** The five texts that make a notice's content (capture KDoc). */
private data class AfContent(val title: String?, val text: String?, val bigText: String?, val subText: String?, val textLines: String?)

// ---- capture-side fixtures ----

private val AF_PRE = listOf(
    RawNotice(AF_PKG, "Nu", "k0", AF_T0, AF_T0, "Nubank", "Compra de R$ 31,50 APROVADA em GIPL COMERCIO", "Compra de R$ 31,50 APROVADA em GIPL COMERCIO"),
    RawNotice(AF_PKG, "Nu", "k1", AF_T0 + 1_000, 0, "Nubank", "Pix recebido de R$ 10,00"),
    RawNotice(AF_PKG, "Nu", "k0", AF_T0 + 10 * AF_W, 0, "Nubank", "Compra de R$ 5,00 APROVADA em PADARIA"),
)
private val AF_PRE_CONTENT = AF_PRE.map { AfContent(it.title, it.text, it.bigText, it.subText, it.textLines) }

/** A Db with history: a source, three captured notices (one dismissed, one accepted), a budget and a tag. */
private suspend fun afPopulate(db: Db) {
    assertTrue(db.addSource(AF_PKG, "Nu", 0), "populate: addSource")
    for (r in AF_PRE) assertTrue(capture(db, r), "populate: capture ${r.key} at ${r.postedAt}")
    val rows = db.pending()
    assertTrue(db.dismiss(rows.last().id), "populate: dismiss")
    val budget = db.createBudget("Mercado", Cents(60_000), AF_TODAY)
    assertNotEquals(BudgetId(0), budget, "populate: createBudget")
    val tx = db.accept(rows.first().id, TransactionForm(budget, listOf("feira"), Cents(500), 1, "PADARIA", AF_TODAY, LocalTime(9, 0)))
    assertNotEquals(TransactionId(0), tx, "populate: accept")
}

// ---- files ----

private fun afTemp(tag: String) = Path(SystemTemporaryDirectory, "margem-afuzz-$tag-${Random.Default.nextLong().toULong()}.db").toString()

private fun afDelete(path: String) {
    for (s in listOf("", "-wal", "-shm", "-journal")) runCatching { SystemFileSystem.delete(Path(path + s), mustExist = false) }
}

/** Writes a cobudget-like SQLite file with the SQLite driver (no java.*). */
private fun afWrite(
    path: String,
    rows: List<AfRow>,
    version: Long = 7,
    ddl: String = AF_V7,
    columns: List<String> = AF_COLUMNS,
    rename: Map<String, String> = emptyMap(),
    table: String = "captured_notifications",
    extra: List<String> = emptyList(),
    wal: Boolean = false,
    cell: (AfRow, String) -> Any? = { r, c -> r.cell(c) },
) {
    val c = BundledSQLiteDriver().open(path)
    try {
        if (wal) c.prepare("PRAGMA journal_mode = WAL").use { it.step() }
        c.execSQL(ddl)
        for (sql in extra) c.execSQL(sql)
        val names = columns.map { rename[it] ?: it }
        val insert = "INSERT INTO `$table`(${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})"
        for (r in rows) c.prepare(insert).use { st ->
            columns.forEachIndexed { i, col ->
                when (val v = cell(r, col)) {
                    null -> st.bindNull(i + 1)
                    is Long -> st.bindLong(i + 1, v)
                    is Int -> st.bindLong(i + 1, v.toLong())
                    is String -> st.bindText(i + 1, v)
                    is Double -> st.bindDouble(i + 1, v)
                    is ByteArray -> st.bindBlob(i + 1, v)
                    else -> error("cell $v")
                }
            }
            st.step()
        }
        c.execSQL("PRAGMA user_version = $version")
    } finally {
        c.close()
    }
}

/**
 * The rows as the file holds them, read through the same driver the import uses. SQLite's UTF-16 ↔ UTF-8
 * conversion (D28) may already have changed hostile text on the way in; the import can only see this.
 */
private fun afReadBack(path: String): List<AfRow> {
    val c = BundledSQLiteDriver().open(path)
    try {
        return c.prepare("SELECT ${AF_COLUMNS.joinToString { "`$it`" }} FROM captured_notifications ORDER BY id").use { st ->
            fun t(i: Int) = if (st.isNull(i)) null else st.getText(i)
            val out = ArrayList<AfRow>()
            while (st.step()) out += AfRow(
                st.getLong(0), st.getText(1), st.getText(2), st.getText(3), st.getLong(4), st.getLong(5),
                t(6), t(7), t(8), t(9), t(10), t(11), t(12), st.getText(13), st.getText(14), st.getLong(15),
            )
            out
        }
    } finally {
        c.close()
    }
}

private fun afBytes(path: String): ByteArray? =
    if (SystemFileSystem.metadataOrNull(Path(path))?.isRegularFile != true) null
    else SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

private fun afWriteBytes(path: String, bytes: ByteArray) {
    SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }
}

// ---- Db probes ----

private class AfProbe {
    val path = afTemp("db")
    var writes = 0
    val db = Db(BundledSQLiteDriver(), path, onWrite = { writes++ }, timeZone = AF_TZ)
    fun close() {
        runCatching { db.close() }
        afDelete(path)
    }
}

private suspend fun <T> afWithProbes(n: Int, block: suspend (List<AfProbe>) -> T): T {
    val probes = ArrayList<AfProbe>()
    try {
        repeat(n) { probes += AfProbe() }
        return block(probes)
    } finally {
        probes.forEach { it.close() }
    }
}

/** Everything a screen can see, plus version and onWrite calls: "Db untouched" means this is equal. */
private data class AfSnap(
    val version: Long,
    val writes: Int,
    val settings: Settings,
    val sources: List<Source>,
    val pending: List<NoticeRow>,
    val dismissed: List<NoticeRow>,
    val tags: List<TagRow>,
    val transactions: List<TransactionRow>,
)

private suspend fun AfProbe.snap() = AfSnap(db.version.value, writes, db.settings(), db.sources(), db.pending(), db.dismissed(), db.tags(), db.transactions())

// ---- helpers ----

/** Real threads under a wall-clock timeout (withTimeout inside Dispatchers.Default), so a deadlock fails. */
private fun afRealTime(block: suspend () -> Unit) = runTest(timeout = 240.seconds) {
    withContext(Dispatchers.Default) { withTimeout(200.seconds) { block() } }
}

private suspend fun <T> afNoThrow(where: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    fail("$where threw $e", e)
}

private fun afShow(s: String?): String = if (s == null) "NULL" else buildString {
    append('"')
    for (c in s.take(60)) {
        if (c.code in 0x20..0x7E && c != '"' && c != '\\') append(c)
        else append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }
    append('"')
    if (s.length > 60) append("…(${s.length})")
}

private fun afShowRows(rows: List<AfRow>) = if (rows.isEmpty()) " (no rows)" else rows.joinToString("\n    ", "\n    ") { it.show() }

private fun afShowRaw(r: RawNotice) = "pkg=${afShow(r.packageName)} key=${afShow(r.key)} at=${r.postedAt} when=${r.whenMs} " +
    "texts=[${listOf(r.title, r.text, r.bigText, r.subText, r.textLines).joinToString(",") { afShow(it) }}] " +
    "label=${afShow(r.appLabel)} cat=${afShow(r.category)} ch=${afShow(r.channelId)} extras=${afShow(r.extrasJson)}"

private fun afRaws(rows: List<NoticeRow>): List<RawNotice> =
    rows.map { it.raw }.sortedWith(compareBy<RawNotice>({ it.postedAt }, { it.key }, { it.whenMs }).thenBy { it.toString() })

private fun afSameRaws(expected: List<RawNotice>, actual: List<RawNotice>, where: String) {
    if (expected == actual) return
    val i = (0 until minOf(expected.size, actual.size)).firstOrNull { expected[it] != actual[it] } ?: minOf(expected.size, actual.size)
    fail(
        "$where: sizes ${expected.size} vs ${actual.size}; first difference at $i:\n  expected ${expected.getOrNull(i)?.let(::afShowRaw)}" +
            "\n  actual   ${actual.getOrNull(i)?.let(::afShowRaw)}",
    )
}

/**
 * The outcome rules that hold for any file (importCobudget KDoc, CobudgetImport KDoc, Db.version KDoc):
 * refused ⇒ zero result and the Db untouched; opened ⇒ every row is counted once (when [rows] is known),
 * pending()/dismissed() gain exactly the counts, nothing else changes, and version/onWrite bump once
 * when something entered, never otherwise.
 */
private suspend fun afCheckOutcome(where: String, p: AfProbe, before: AfSnap, r: CobudgetImport, rows: Int?) {
    val after = p.snap()
    if (!r.opened) {
        assertEquals(CobudgetImport(), r, "$where: a refused file has every count 0")
        assertEquals(before, after, "$where: a refused file leaves the Db untouched")
        return
    }
    assertTrue(r.pending >= 0 && r.dismissed >= 0 && r.skipped >= 0, "$where: negative count in $r")
    if (rows != null) assertEquals(rows, r.pending + r.dismissed + r.skipped, "$where: pending + dismissed + skipped = rows ($r)")
    assertEquals(before.pending.size + r.pending, after.pending.size, "$where: pending() gains result.pending ($r)")
    assertEquals(before.dismissed.size + r.dismissed, after.dismissed.size, "$where: dismissed() gains result.dismissed ($r)")
    assertTrue(after.pending.containsAll(before.pending), "$where: notices pending before stay pending")
    assertTrue(after.dismissed.containsAll(before.dismissed), "$where: notices dismissed before stay dismissed")
    val bump = if (r.pending + r.dismissed > 0) 1 else 0
    assertEquals(before.version + bump, after.version, "$where: version bumps once iff something entered ($r)")
    assertEquals(before.writes + bump, after.writes, "$where: onWrite runs once iff something entered ($r)")
    assertEquals(
        before.copy(version = 0, writes = 0, pending = emptyList(), dismissed = emptyList()),
        after.copy(version = 0, writes = 0, pending = emptyList(), dismissed = emptyList()),
        "$where: only notices are imported (settings, sources, tags, transactions unchanged)",
    )
}

// ---- generators ----

private fun afSupplementary(rnd: Random): String {
    val v = rnd.nextInt(0x10000, 0x110000) - 0x10000
    return "" + Char(0xD800 + (v shr 10)) + Char(0xDC00 + (v and 0x3FF))
}

/** One hostile piece: NUL, U+FEFF, lone surrogates, U+FFFE/U+FFFF, emoji, inner whitespace, SQL, very long. */
private fun afPiece(rnd: Random): String = when (rnd.nextInt(16)) {
    0 -> "\u0000"
    1 -> "\uFEFF"
    2 -> Char(rnd.nextInt(0xD800, 0xDC00)).toString()
    3 -> Char(rnd.nextInt(0xDC00, 0xE000)).toString()
    4 -> listOf("\uFFFE", "\uFFFF", "\uFFFD").random(rnd)
    5 -> listOf("😀", "🇧🇷", "👩‍👩‍👧", "💳").random(rnd)
    6 -> afSupplementary(rnd)
    7 -> listOf(" ", "\t", "\n", "\r\n", "\u00A0", "\u3000").random(rnd)
    8 -> listOf("Compra de R$ 31,50 APROVADA em GIPL", "Compra aprovada", "PAG*JOSEDASILVA 0231", "Pix recebido").random(rnd)
    9 -> listOf("'; DROP TABLE notices;--", "%", "_", "\\", "\"", "?1", ":name").random(rnd)
    10 -> Char(rnd.nextInt(0x300, 0x370)).toString()
    11 -> Char(rnd.nextInt(0x4E00, 0xA000)).toString()
    12 -> Char(rnd.nextInt(1, 0x20)).toString()
    13 -> if (rnd.nextInt(5) == 0) "x".repeat(rnd.nextInt(1_000, 5_000)) else "ab"
    else -> Char(rnd.nextInt(0x21, 0x7F)).toString()
}

private fun afBoxed(rnd: Random, maxPieces: Int = 6) = "<" + buildString { repeat(rnd.nextInt(0, maxPieces + 1)) { append(afPiece(rnd)) } } + ">"

/**
 * A hostile text with no whitespace at its ends, so trimming can't change it: RawNotice says texts
 * "arrive trimmed" while the import KDoc maps the columns without trimming (see the trim test).
 */
private fun afText(rnd: Random): String = when (rnd.nextInt(10)) {
    0 -> "\uFEFF" + afBoxed(rnd)
    1 -> Char(rnd.nextInt(0xD800, 0xE000)).toString() + afBoxed(rnd)
    2 -> afBoxed(rnd) + Char(rnd.nextInt(0xD800, 0xE000))
    else -> afBoxed(rnd)
}

private fun afField(rnd: Random): String? = when (rnd.nextInt(10)) {
    0, 1 -> null
    2 -> ""
    else -> afText(rnd)
}

private fun afContent(rnd: Random): AfContent = when (rnd.nextInt(12)) {
    0 -> AfContent(null, null, null, null, null)
    1 -> AfContent("", null, "", null, "")
    2 -> AF_PRE_CONTENT.random(rnd)
    else -> AfContent(afField(rnd), afField(rnd), afField(rnd), afField(rnd), afField(rnd))
}

private val AF_KEYS = listOf("k0", "k1", "", "<\u0000>", "<😀>", "\uD800<k>", "\uFEFF<k>", "0|com.nu.production|7|null|10001")
private val AF_PKGS = listOf(AF_PKG, "br.com.aurora", "com.whatsapp", "x")

/** postedAt: 0, equal, inside/at/outside DUPLICATE_WINDOW_MS of another row, negative; all within ±2^61 + 3W. */
private fun afPosted(rnd: Random, base: Long, prev: List<Long>): Long {
    val near = if (prev.isEmpty()) base else prev[rnd.nextInt(prev.size)]
    return when (rnd.nextInt(9)) {
        0 -> 0L
        1 -> near
        2 -> near + rnd.nextLong(-AF_W + 1, AF_W)
        3 -> near + listOf(AF_W, -AF_W, AF_W - 1, 1 - AF_W, AF_W + 1, -AF_W - 1).random(rnd)
        4 -> -rnd.nextLong(1, 1L shl 40)
        5 -> base + rnd.nextLong(0, 20 * AF_W)
        6 -> rnd.nextLong(-(1L shl 61), 1L shl 61)
        else -> base + rnd.nextLong(0, 3 * AF_W)
    }
}

/** when: mostly 0 (not set), else equal to postedAt, repeated, random, negative or a Long extreme. */
private fun afWhen(rnd: Random, posted: Long, prev: List<Long>): Long = when (rnd.nextInt(11)) {
    0, 1, 2, 3 -> 0L
    4 -> posted
    5, 6 -> if (prev.isEmpty()) 0L else prev[rnd.nextInt(prev.size)]
    7 -> rnd.nextLong()
    8 -> -rnd.nextLong(1, 1L shl 40)
    9 -> Long.MIN_VALUE
    else -> Long.MAX_VALUE
}

private fun afHash(rnd: Random): String = when (rnd.nextInt(5)) {
    0 -> ""
    1 -> "0".repeat(64)
    2 -> buildString { repeat(64) { append("0123456789abcdef"[rnd.nextInt(16)]) } }
    3 -> "deadbeef"
    else -> afText(rnd)
}

private fun afIds(rnd: Random, n: Int): List<Long> =
    if (rnd.nextBoolean()) (1L..n.toLong()).toList()
    else generateSequence { rnd.nextLong(-1_000, 1_000_000) }.distinct().take(n).toList()

/** A random v7 file: few keys and contents, so re-posts are frequent. */
private fun afRandomRows(rnd: Random, n: Int): List<AfRow> {
    val keys = AF_KEYS.shuffled(rnd).take(rnd.nextInt(1, 4))
    val contents = List(rnd.nextInt(1, 5)) { afContent(rnd) }
    val base = rnd.nextLong(-(1L shl 40), 1L shl 41)
    val ids = afIds(rnd, n)
    val posted = ArrayList<Long>()
    val whens = ArrayList<Long>()
    return List(n) { i ->
        val at = afPosted(rnd, base, posted).also { posted += it }
        val w = afWhen(rnd, at, whens).also { whens += it }
        val c = contents.random(rnd)
        AfRow(
            id = ids[i], packageName = AF_PKGS.random(rnd), appLabel = listOf("", "Nu", afText(rnd)).random(rnd),
            key = keys.random(rnd), postedAt = at, whenMs = w,
            title = c.title, text = c.text, bigText = c.bigText, subText = c.subText, textLines = c.textLines,
            category = listOf(null, "", "msg", afText(rnd)).random(rnd), channelId = listOf(null, "", "compras", afText(rnd)).random(rnd),
            extrasJson = listOf("{}", "", afText(rnd)).random(rnd), contentHash = afHash(rnd), dismissed = rnd.nextInt(2).toLong(),
        )
    }
}

/** A row that surely enters: its own key and content (no other notice has that key). */
private fun afPlainRow(i: Int, dismissed: Long, key: String = "o$i", at: Long = AF_T0 + i * 7_919L) = AfRow(
    id = i + 1L, packageName = AF_PKG, appLabel = "Nu", key = key, postedAt = at, whenMs = at,
    title = "Nubank", text = "<Compra de R$ $i,00 APROVADA em LOJA $i>", bigText = null, subText = null, textLines = null,
    category = "msg", channelId = null, extrasJson = "{}", contentHash = "h$i", dismissed = dismissed,
)

// ---- start-day helpers ----

private val AF_TODAYS = listOf(
    LocalDate(2026, 10, 14), LocalDate(2026, 1, 31), LocalDate(2026, 2, 28), LocalDate(2028, 2, 29),
    LocalDate(2026, 12, 31), LocalDate(2026, 3, 1), LocalDate(2026, 10, 30), LocalDate(2027, 5, 29),
)

private fun YearMonth.afShift(n: Int): YearMonth = if (n >= 0) plus(n, DateTimeUnit.MONTH) else minus(-n, DateTimeUnit.MONTH)

class AjustesFuzzTest {

    /**
     * Differential against capture: importing a random file into a Db with history must leave the same
     * notices as capturing each row (packages made sources, NULL text = "") in postedAt order, then id
     * (importCobudget KDoc; D4), with the cobudget's dismissed kept for the ones that entered.
     */
    @Test
    fun `random cobudget files import exactly what capture would keep`() = afRealTime {
        for (seed in listOf(1, 7, 42, 2026, 5150)) {
            val rnd = Random(seed)
            for (iter in 0 until 8) {
                val n = if (iter == 0) 0 else rnd.nextInt(1, 31)
                afDifferential("seed=$seed iter=$iter", afRandomRows(rnd, n))
            }
        }
    }

    private suspend fun afDifferential(tag: String, rows: List<AfRow>) {
        val file = afTemp("cb")
        try {
            afWrite(file, rows)
            val held = afReadBack(file)
            val where = "$tag rows=${afShowRows(held)}\n "
            for (r in held) for (t in r.texts) if (t != null) check(t == t.trim()) { "generator: untrimmed text ${afShow(t)} in $where" }
            val bytes = afBytes(file)
            afWithProbes(2) { (a, b) ->
                afPopulate(a.db)
                afPopulate(b.db)
                for (pkg in held.map { it.packageName }.distinct()) assertTrue(b.db.addSource(pkg, "x", 0), "$where: oracle source $pkg")

                // Oracle: capture in postedAt order, then id (importCobudget KDoc), dismissing the ones the cobudget had dismissed.
                var p = 0
                var d = 0
                for (r in held.sortedWith(compareBy({ it.postedAt }, { it.id }))) {
                    val ids = b.db.pending().map { it.id }.toSet()
                    if (!capture(b.db, r.raw())) continue
                    if (r.dismissed == 1L) {
                        assertTrue(b.db.dismiss(b.db.pending().first { it.id !in ids }.id), "$where: oracle dismiss")
                        d++
                    } else {
                        p++
                    }
                }

                val before = a.snap()
                val result = afNoThrow("$where importCobudget") { importCobudget(a.db, file) }
                // CobudgetImport KDoc: pending = entered not dismissed, dismissed = entered dismissed, skipped = the rest.
                assertEquals(CobudgetImport(true, p, d, held.size - p - d), result, "$where: counts")
                afCheckOutcome(where, a, before, result, held.size)
                afSameRaws(afRaws(b.db.pending()), afRaws(a.db.pending()), "$where: pending() vs capture")
                afSameRaws(afRaws(b.db.dismissed()), afRaws(a.db.dismissed()), "$where: dismissed() vs capture")
                assertTrue(bytes.contentEquals(afBytes(file)), "$where: the file is only read")

                // Again: whatever the second pass does, it obeys the outcome rules (see the twice test for the exact claim).
                val again = a.snap()
                val second = afNoThrow("$where second importCobudget") { importCobudget(a.db, file) }
                assertTrue(second.opened, "$where: the same file opens again")
                afCheckOutcome("$where (second import)", a, again, second, held.size)
            }
        } finally {
            afDelete(file)
        }
    }

    /**
     * "Importing the same file again adds nothing (every notice is a re-post)" (importCobudget KDoc, spec
     * §7.6). Taken on files where the capture rules themselves guarantee it: every `when` ≠ 0 and equal for
     * equal key and content, so each row of the second pass finds itself (or the notice it repeated) by
     * key + content + when. See the report for files outside this class.
     */
    @Test
    fun `importing the same file twice adds nothing the second time`() = afRealTime {
        for (seed in listOf(11, 23, 404, 8080)) {
            val rnd = Random(seed)
            for (iter in 0 until 6) {
                val n = rnd.nextInt(1, 31)
                val keys = List(rnd.nextInt(1, 4)) { "t$it" }
                val contents = List(rnd.nextInt(1, 5)) { j ->
                    if (rnd.nextInt(6) == 0) AfContent(null, "", null, null, null)
                    else AfContent("Banco $j", "<Compra de R$ ${j + 1},00 em LOJA $j 😀>", listOf(null, "", "<linha $j>").random(rnd), null, null)
                }
                val whenOf = HashMap<Pair<String, Int>, Long>()
                val base = rnd.nextLong(-(1L shl 40), 1L shl 41)
                val posted = ArrayList<Long>()
                val ids = afIds(rnd, n)
                val rows = List(n) { i ->
                    val k = keys.random(rnd)
                    val ci = rnd.nextInt(contents.size)
                    val c = contents[ci]
                    val w = whenOf.getOrPut(k to ci) {
                        generateSequence { listOf(rnd.nextLong(), -rnd.nextLong(1, 1L shl 40), Long.MIN_VALUE, Long.MAX_VALUE).random(rnd) }
                            .first { it != 0L && it !in whenOf.values }
                    }
                    val at = afPosted(rnd, base, posted).also { posted += it }
                    AfRow(ids[i], AF_PKGS.random(rnd), "Nu", k, at, w, c.title, c.text, c.bigText, c.subText, c.textLines, null, null, "{}", afHash(rnd), rnd.nextInt(2).toLong())
                }
                val where = "seed=$seed iter=$iter rows=${afShowRows(rows)}\n "
                val file = afTemp("twice")
                try {
                    afWrite(file, rows)
                    afWithProbes(1) { (a) ->
                        afPopulate(a.db)
                        val s0 = a.snap()
                        val first = afNoThrow("$where first import") { importCobudget(a.db, file) }
                        assertTrue(first.opened, "$where: a v7 file opens")
                        afCheckOutcome("$where first", a, s0, first, n)
                        for (pass in 2..3) {
                            val s = a.snap()
                            val again = afNoThrow("$where import #$pass") { importCobudget(a.db, file) }
                            assertEquals(CobudgetImport(true, 0, 0, n), again, "$where: import #$pass adds nothing, skipped = rows")
                            assertEquals(s, a.snap(), "$where: import #$pass leaves the Db untouched (no version bump either)")
                        }
                    }
                } finally {
                    afDelete(file)
                }
            }
        }
    }

    /**
     * "Each row, in postedAt order (then id)": with distinct postedAt the ids can't matter. The same rows
     * under three id orders (ascending with postedAt, descending, random with negatives) give the same
     * result and the same notices.
     */
    @Test
    fun `row ids do not change the outcome when postedAt orders the rows`() = afRealTime {
        for (seed in listOf(5, 55, 555)) {
            val rnd = Random(seed)
            for (iter in 0 until 5) {
                val n = rnd.nextInt(2, 26)
                val base = rnd.nextLong(-(1L shl 40), 1L shl 41)
                val times = generateSequence(base) { it + listOf(1L, AF_W - 1, AF_W, AF_W + 1, rnd.nextLong(1, 5 * AF_W)).random(rnd) }
                    .take(n).toList().shuffled(rnd)
                val template = afRandomRows(rnd, n).mapIndexed { i, r -> r.copy(postedAt = times[i]) }
                val byTime = template.sortedBy { it.postedAt }
                val randomIds = afIds(Random(seed * 31 + iter), n).let { if (it == (1L..n.toLong()).toList()) it.reversed().map { v -> -v } else it }
                val variants = listOf(
                    "ascending" to byTime.mapIndexed { i, r -> r.copy(id = i + 1L) },
                    "descending" to byTime.mapIndexed { i, r -> r.copy(id = (n - i).toLong()) },
                    "random" to template.mapIndexed { i, r -> r.copy(id = randomIds[i]) },
                )
                var reference: Triple<CobudgetImport, List<RawNotice>, List<RawNotice>>? = null
                for ((name, rows) in variants) {
                    val where = "seed=$seed iter=$iter ids=$name rows=${afShowRows(rows)}\n "
                    val file = afTemp("order")
                    try {
                        afWrite(file, rows)
                        afWithProbes(1) { (a) ->
                            afPopulate(a.db)
                            val s0 = a.snap()
                            val r = afNoThrow("$where import") { importCobudget(a.db, file) }
                            afCheckOutcome(where, a, s0, r, n)
                            val got = Triple(r, afRaws(a.db.pending()), afRaws(a.db.dismissed()))
                            val ref = reference
                            if (ref == null) {
                                reference = got
                            } else {
                                assertEquals(ref.first, got.first, "$where: same counts as ascending ids")
                                afSameRaws(ref.second, got.second, "$where: same pending() as ascending ids")
                                afSameRaws(ref.third, got.third, "$where: same dismissed() as ascending ids")
                            }
                        }
                    } finally {
                        afDelete(file)
                    }
                }
            }
        }
    }

    /**
     * "No text at all ... is skipped" (importCobudget KDoc; NULL text is ""), whatever the row's other
     * columns hold. Rows with text get their own key and content, so each one enters:
     * expected = (text rows not dismissed, text rows dismissed, rows without text).
     */
    @Test
    fun `rows without any text are skipped whatever else they carry`() = afRealTime {
        for (seed in listOf(2, 13, 77)) {
            val rnd = Random(seed)
            for (iter in 0 until 4) {
                val n = rnd.nextInt(1, 25)
                val rows = List(n) { i ->
                    if (rnd.nextInt(3) == 0) afPlainRow(i, rnd.nextInt(2).toLong())
                    else {
                        val k = if (rnd.nextBoolean()) "o$i" else AF_KEYS.random(rnd)
                        val at = afPosted(rnd, AF_T0, emptyList())
                        AfRow(
                            i + 1L, AF_PKGS.random(rnd), afText(rnd), k, at, afWhen(rnd, at, emptyList()),
                            listOf(null, "").random(rnd), listOf(null, "").random(rnd), listOf(null, "").random(rnd),
                            listOf(null, "").random(rnd), listOf(null, "").random(rnd),
                            afText(rnd), afText(rnd), afText(rnd), afHash(rnd), rnd.nextInt(2).toLong(),
                        )
                    }
                }
                val texted = rows.filter { r -> r.texts.any { !it.isNullOrEmpty() } }
                val expected = CobudgetImport(true, texted.count { it.dismissed == 0L }, texted.count { it.dismissed == 1L }, n - texted.size)
                afImportExpecting("seed=$seed iter=$iter", rows, expected)
            }
        }
    }

    /**
     * Blank texts: the import applies "the rules of [capture]", and capture's noise rule treats a notice whose
     * texts are all blank as having no text (RawNotice: "Texts arrive trimmed"). Reading chosen: a row whose
     * five texts are NULL, "" or only whitespace is skipped (see the report).
     */
    @Test
    fun `rows with only blank texts are skipped like capture skips them`() = afRealTime {
        val blanks = listOf(null, "", " ", "   ", "\t", "\n", "\r\n", "\u00A0", "\u3000", "\u2003", " \t\n ")
        for (seed in listOf(4, 44)) {
            val rnd = Random(seed)
            for (iter in 0 until 4) {
                val n = rnd.nextInt(1, 20)
                val rows = List(n) { i ->
                    if (rnd.nextInt(3) == 0) afPlainRow(i, rnd.nextInt(2).toLong())
                    else afPlainRow(i, rnd.nextInt(2).toLong()).copy(
                        title = blanks.random(rnd), text = blanks.random(rnd), bigText = blanks.random(rnd),
                        subText = blanks.random(rnd), textLines = " ",
                    )
                }
                val texted = rows.filter { r -> r.texts.any { !it.isNullOrBlank() } }
                val expected = CobudgetImport(true, texted.count { it.dismissed == 0L }, texted.count { it.dismissed == 1L }, n - texted.size)
                afImportExpecting("seed=$seed iter=$iter", rows, expected)
            }
        }
    }

    /**
     * Texts with whitespace at their ends: the import KDoc maps columns as they are, RawNotice says texts
     * arrive trimmed. Either reading is accepted here; what's asserted holds under both: every notice that
     * enters carries each of its texts as the file had it or trimmed, and with one key per row the counts are
     * exact (a row enters iff some text is not blank).
     */
    @Test
    fun `texts with spaces at the ends enter as written or trimmed`() = afRealTime {
        val edges = listOf("", " ", "  ", "\t", "\n", "\u00A0", " \r\n")
        val cores = listOf("", "Nubank", "Compra de R$ 12,34 APROVADA em PADARIA", "a  b", "😀 ok", "çãé", "x".repeat(3_000))
        for (seed in listOf(6, 66)) {
            val rnd = Random(seed)
            for (iter in 0 until 4) {
                val n = rnd.nextInt(1, 20)
                fun t(): String? = if (rnd.nextInt(5) == 0) null else edges.random(rnd) + cores.random(rnd) + edges.random(rnd)
                val rows = List(n) { i -> afPlainRow(i, rnd.nextInt(2).toLong()).copy(title = t(), text = t(), bigText = t(), subText = t(), textLines = t()) }
                val texted = rows.filter { r -> r.texts.any { !it.isNullOrBlank() } }
                val expected = CobudgetImport(true, texted.count { it.dismissed == 0L }, texted.count { it.dismissed == 1L }, n - texted.size)
                afImportExpecting("seed=$seed iter=$iter", rows, expected) { where, entered ->
                    for (e in entered) {
                        val row = rows.firstOrNull { it.key == e.key && it.postedAt == e.postedAt }
                        if (row == null) fail("$where: notice not from the file: ${afShowRaw(e)}")
                        val got = listOf(e.title, e.text, e.bigText, e.subText, e.textLines)
                        row.texts.zip(got).forEach { (file, stored) ->
                            val f = file ?: ""
                            assertTrue(stored == f || stored == f.trim(), "$where: ${afShow(stored)} is neither ${afShow(f)} nor its trim")
                        }
                    }
                }
            }
        }
    }

    /** Imports [rows] into a Db with history, expecting exactly [expected]; [more] gets the notices that entered. */
    private suspend fun afImportExpecting(
        tag: String,
        rows: List<AfRow>,
        expected: CobudgetImport,
        more: (String, List<RawNotice>) -> Unit = { _, _ -> },
    ) {
        val where = "$tag rows=${afShowRows(rows)}\n "
        val file = afTemp("exp")
        try {
            afWrite(file, rows)
            val bytes = afBytes(file)
            afWithProbes(1) { (a) ->
                afPopulate(a.db)
                val s0 = a.snap()
                val r = afNoThrow("$where import") { importCobudget(a.db, file) }
                assertEquals(expected, r, "$where: counts")
                afCheckOutcome(where, a, s0, r, rows.size)
                val old = (s0.pending + s0.dismissed).map { it.id }.toSet()
                more(where, (a.db.pending() + a.db.dismissed()).filter { it.id !in old }.map { it.raw })
                assertTrue(bytes.contentEquals(afBytes(file)), "$where: the file is only read")
            }
        } finally {
            afDelete(file)
        }
    }

    /**
     * postedAt extremes against the window (capture KDoc: same key and content "posted less than
     * DUPLICATE_WINDOW_MS apart"; same key, content and when ≠ 0 "however much later"). Two rows, same key
     * and content: the second (in postedAt order) enters iff it is not a re-post. The gap is the true
     * difference: Long.MIN_VALUE and Long.MAX_VALUE are 2^64 − 1 ms apart, far outside the window.
     */
    @Test
    fun `postedAt and when extremes follow the window arithmetic`() = afRealTime {
        val min = Long.MIN_VALUE
        val max = Long.MAX_VALUE
        // (a, b, when of a, when of b, second enters?)
        val cases = mutableListOf(
            listOf(min, max, 0L, 0L) to true, // gap 2^64 − 1 ≥ W
            listOf(min, 0L, 0L, 0L) to true, // gap 2^63 ≥ W
            listOf(0L, max, 0L, 0L) to true, // gap 2^63 − 1 ≥ W
            listOf(min, min, 0L, 0L) to false, // gap 0 < W
            listOf(max, max, 0L, 0L) to false,
            listOf(max - 1, max, 0L, 0L) to false, // gap 1 < W
            listOf(min, min + AF_W - 1, 0L, 0L) to false, // gap W − 1 < W
            listOf(min, min + AF_W, 0L, 0L) to true, // gap W, not less than W
            listOf(max - AF_W + 1, max, 0L, 0L) to false,
            listOf(max - AF_W, max, 0L, 0L) to true,
            listOf(-1L, AF_W - 1, 0L, 0L) to true, // gap W
            listOf(-1L, AF_W - 2, 0L, 0L) to false, // gap W − 1
            listOf(-AF_W, 0L, 0L, 0L) to true, // gap W, across zero
            listOf(min, max, 5L, 5L) to false, // same when ≠ 0: re-post however much later
            listOf(min, max, min, min) to false,
            listOf(0L, 10 * AF_W, -3L, -3L) to false,
            listOf(0L, 1_000L, 5L, 6L) to false, // different when, inside the window: re-post by the window
            listOf(0L, 10 * AF_W, 5L, 6L) to true, // different when, outside the window
            listOf(0L, 10 * AF_W, 0L, 0L) to true, // when 0 never matches
        )
        val rnd = Random(9_001)
        repeat(16) {
            val a = rnd.nextLong(-(1L shl 60), 1L shl 60)
            val d = rnd.nextLong(0, 3 * AF_W)
            when (rnd.nextInt(3)) {
                0 -> cases += listOf(a, a + d, 0L, 0L) to (d >= AF_W)
                1 -> rnd.nextLong(1, 1L shl 50).let { w -> cases += listOf(a, a + d, w, w) to false }
                else -> cases += listOf(a, a + d, 7L, 8L) to (d >= AF_W)
            }
        }
        for ((i, case) in cases.withIndex()) {
            val (v, enters) = case
            val rows = listOf(
                afPlainRow(0, 0, key = "x", at = v[0]).copy(id = 1, whenMs = v[2]),
                afPlainRow(0, 0, key = "x", at = v[1]).copy(id = 2, whenMs = v[3]),
            )
            // Both enter: (2, 0, 0); the second is a re-post: (1, 0, 1).
            val expected = if (enters) CobudgetImport(true, 2, 0, 0) else CobudgetImport(true, 1, 0, 1)
            afImportExpecting("seed=9001 case=$i at=${v[0]},${v[1]} when=${v[2]},${v[3]}", rows, expected)
        }
    }

    /**
     * Hostile files never throw. Files that are clearly not a cobudget v7 backup are refused with a zero
     * result and the Db untouched (importCobudget KDoc, spec §7.6, D3); files whose fate the KDoc doesn't
     * settle (truncated, garbage after the SQLite header, extra column, odd storage classes) only have to obey
     * the outcome rules. Every existing file is left byte for byte as it was ("The file is only read").
     */
    @Test
    fun `hostile files never throw and refused ones change nothing`() = afRealTime {
        for (seed in listOf(31, 62)) {
            val rnd = Random(seed)
            val good = List(12) { afPlainRow(it, rnd.nextInt(2).toLong()) }
            val goodExpected = CobudgetImport(true, good.count { it.dismissed == 0L }, good.count { it.dismissed == 1L }, 0)
            afWithProbes(1) { (a) ->
                afPopulate(a.db)

                /** [refused]: true = must refuse, false = must open with [exact] (if given), null = either. */
                suspend fun case(name: String, refused: Boolean?, rows: Int?, exact: CobudgetImport? = null, checkBytes: Boolean = true, make: (String) -> Unit) {
                    val where = "seed=$seed case=$name"
                    val file = afTemp("hostile")
                    try {
                        make(file)
                        val bytes = afBytes(file)
                        val s0 = a.snap()
                        val r = afNoThrow("$where importCobudget") { importCobudget(a.db, file) }
                        when (refused) {
                            true -> assertEquals(CobudgetImport(), r, "$where: refused")
                            false -> assertTrue(r.opened, "$where: a cobudget v7 file opens ($r)")
                            null -> {}
                        }
                        if (exact != null && r.opened) assertEquals(exact, r, "$where: counts")
                        afCheckOutcome(where, a, s0, r, rows)
                        if (checkBytes && bytes != null) assertTrue(bytes.contentEquals(afBytes(file)), "$where: the file is only read")
                    } finally {
                        afDelete(file)
                        runCatching { SystemFileSystem.delete(Path(file), mustExist = false) }
                    }
                }

                // Not SQLite at all.
                case("empty file", true, null) { afWriteBytes(it, ByteArray(0)) }
                for (size in listOf(1, 15, 16, 100, 512, 4096, 10_000)) {
                    case("random bytes $size", true, null) { afWriteBytes(it, ByteArray(size) { i -> if (i == 0) 'X'.code.toByte() else rnd.nextInt(256).toByte() }) }
                }
                case("text file", true, null) { afWriteBytes(it, "id,packageName,postedAt\n1,com.nu.production,0\n".repeat(50).encodeToByteArray()) }
                case("missing path", true, null) { }
                case("directory", true, null) { SystemFileSystem.createDirectories(Path(it)) }

                // SQLite header, then garbage; truncated v7 files.
                for (size in listOf(100, 1024, 4096, 8192)) {
                    case("sqlite magic then $size random bytes", null, null) {
                        afWriteBytes(it, "SQLite format 3\u0000".encodeToByteArray() + ByteArray(size) { rnd.nextInt(256).toByte() })
                    }
                }
                val full = afTemp("full")
                try {
                    // Rows of their own (100..139): a truncated file that still opens may import them into the shared Db,
                    // and rows equal to [good] would turn the exact "other cobudget tables" case below into repeats.
                    afWrite(full, List(40) { afPlainRow(it + 100, rnd.nextInt(2).toLong()) })
                    val whole = afBytes(full)!!
                    val cuts = listOf(1, 50, 99, 100, 101, 512, 4095, 4096, 4097, whole.size / 2, whole.size - 1) + List(4) { rnd.nextInt(1, whole.size) }
                    for (cut in cuts.filter { it in 1 until whole.size }.distinct()) {
                        case("v7 truncated to $cut of ${whole.size} bytes", null, null) { afWriteBytes(it, whole.copyOf(cut)) }
                    }
                    case("v7 with garbage over page 2", null, null) {
                        val bytes = whole.copyOf()
                        for (i in 4096 until minOf(bytes.size, 8192)) bytes[i] = rnd.nextInt(256).toByte()
                        afWriteBytes(it, bytes)
                    }
                } finally {
                    afDelete(full)
                }

                // Valid table, wrong user_version (D3: only the v7 schema).
                for (v in listOf(0L, 1L, 6L, 8L, 70L, -7L, Int.MAX_VALUE.toLong())) {
                    case("user_version $v", true, null) { afWrite(it, good, version = v) }
                }
                // Version 7 without the table, or with it under another name.
                case("v7 without captured_notifications", true, null) {
                    afWrite(it, good, ddl = afDdl(AF_COLUMNS, table = "notices"), table = "notices")
                }
                case("v7 with captured_notification (singular)", true, null) {
                    afWrite(it, good, ddl = afDdl(AF_COLUMNS, table = "captured_notification"), table = "captured_notification")
                }
                // "a captured_notifications table with the columns below": each column missing, a few renamed.
                for (col in AF_COLUMNS) {
                    case("v7 without column $col", true, null) { f -> afWrite(f, good, ddl = afDdl(AF_COLUMNS - col), columns = AF_COLUMNS - col) }
                }
                for ((col, to) in listOf("notificationKey" to "notification_key", "postedAt" to "posted_at", "title" to "Title2", "dismissed" to "isDismissed")) {
                    val rename = mapOf(col to to)
                    case("v7 with $col renamed to $to", true, null) { f -> afWrite(f, good, ddl = afDdl(AF_COLUMNS, rename), rename = rename) }
                }
                // A Margem backup: see its own test (exportTo is suspend).

                // Other tables of the file are ignored (KDoc): the notices still come in, exactly.
                case("v7 with the other cobudget tables", false, good.size, goodExpected) {
                    afWrite(
                        it, good,
                        extra = listOf(
                            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                            "INSERT INTO room_master_table VALUES (42, 'abc')",
                            "CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
                            "INSERT INTO tags VALUES (1, 'mercado')",
                            "CREATE TABLE transactions (id INTEGER PRIMARY KEY, notificationId INTEGER, amountCents INTEGER)",
                            "INSERT INTO transactions VALUES (1, 1, 3150)",
                            "CREATE TABLE genda_outbox (id INTEGER PRIMARY KEY, payload BLOB)",
                            "INSERT INTO genda_outbox VALUES (1, x'00ff00')",
                            "CREATE TABLE sources (packageName TEXT PRIMARY KEY)",
                            "INSERT INTO sources VALUES ('br.com.aurora')",
                        ),
                    )
                }
                // A cobudget file left in WAL mode (Room's default) is still a v7 file.
                // Keys of its own: the case above already imported [good] into the same Db, so the same rows would all be repeats.
                case("v7 in WAL mode", false, good.size, goodExpected, checkBytes = false) { afWrite(it, good.map { r -> r.copy(key = "wal-${r.key}") }, wal = true) }
                // Not settled by the KDoc: extra column, other column order, odd storage classes.
                case("v7 with an extra column", null, good.size) { f ->
                    afWrite(f, good, ddl = AF_V7.dropLast(1) + ", `extra` TEXT)")
                }
                case("v7 with the columns in another order", null, good.size, goodExpected) { f ->
                    val order = AF_COLUMNS.shuffled(rnd)
                    // Keys of its own, like the WAL case: [good] is already in the Db.
                    afWrite(f, good.map { r -> r.copy(key = "order-${r.key}") }, ddl = afDdl(order), columns = order)
                }
                case("v7 with odd storage classes", null, good.size) { f ->
                    afWrite(f, good) { r, c ->
                        when {
                            c == "postedAt" && r.id % 3 == 0L -> "not a number"
                            c == "postedAt" && r.id % 3 == 1L -> 1.5e12
                            c == "notificationWhen" && r.id % 2 == 0L -> byteArrayOf(0, 1, 2)
                            c == "title" && r.id % 4 == 0L -> 42L
                            c == "text" && r.id % 5 == 0L -> byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())
                            c == "dismissed" && r.id % 3 == 2L -> listOf(2L, -1L, "1").random(rnd)
                            else -> r.cell(c)
                        }
                    }
                }
            }
        }
    }

    /** A Margem backup (exportTo, user_version 1, no captured_notifications) is not a cobudget backup: refused. */
    @Test
    fun `a Margem backup is refused by the cobudget import`() = afRealTime {
        afWithProbes(2) { (a, other) ->
            afPopulate(a.db)
            afPopulate(other.db)
            val file = afTemp("margem")
            try {
                assertTrue(other.db.exportTo(file), "exportTo")
                val bytes = afBytes(file)
                val s0 = a.snap()
                val r = afNoThrow("importCobudget of a Margem backup") { importCobudget(a.db, file) }
                assertEquals(CobudgetImport(), r, "a Margem backup is refused")
                afCheckOutcome("a Margem backup", a, s0, r, null)
                assertTrue(bytes.contentEquals(afBytes(file)), "the file is only read")
            } finally {
                afDelete(file)
            }
        }
    }

    /**
     * Random sequences of setStep / clearStep / start-day changes. Before each change startDropsEdits is asked
     * (and must write nothing); after it, the prediction is checked against what really went away:
     * - the cut (D10, setMonthStartDay KDoc) is the start of today's month by the old or the new day,
     *   whichever is earlier; setWeekStart cuts at today's month start by the day then current, which is
     *   never earlier; so the edits deleted are those with week start ≥ min(old start, new start);
     * - startDropsEdits = something changes and some edit falls at or after that cut (D41 KDoc);
     * - the model of edits (from setStep/clearStep that returned true) must match every edited week the
     *   screens show, before and after: no deleted edit shows again, no edit from today's month on survives.
     */
    @Test
    fun `startDropsEdits predicts exactly when a start change deletes step edits`() = afRealTime {
        for (seed in listOf(3, 17, 99, 2024, 31337, 4242)) afWithProbes(1) { (p) -> afStartRun(seed, p) }
    }

    private suspend fun afStartRun(seed: Int, p: AfProbe) {
        val db = p.db
        val rnd = Random(seed)
        val today = AF_TODAYS.random(rnd)
        val budgets = listOf(
            db.createBudget("Mercado", Cents(rnd.nextLong(10_000, 500_000)), today.minus(120, DateTimeUnit.DAY)),
            db.createBudget("Casa", Cents(rnd.nextLong(10_000, 500_000)), today.minus(40, DateTimeUnit.DAY)),
        )
        budgets.forEach { assertNotEquals(BudgetId(0), it, "seed=$seed: createBudget") }
        val model = HashMap<Pair<BudgetId, LocalDate>, Long>()
        val log = ArrayList<String>()
        fun where(step: Int) = "seed=$seed today=$today step=$step model=${model.entries.sortedBy { it.key.second }.map { "${it.key.first.v}@${it.key.second}=${it.value}" }} log=${log.takeLast(8)}"

        suspend fun startOfToday() = db.previewBudget(Cents(10_000), today).start

        suspend fun visible(): Map<Pair<BudgetId, LocalDate>, Long> {
            val m = db.monthOf(today)
            db.ensureMonths(m.afShift(3))
            val out = HashMap<Pair<BudgetId, LocalDate>, Long>()
            for (b in budgets) for (k in -3..3) for (w in db.budgetDetail(b, m.afShift(k), today).month.weeks) if (w.edited) out[b to w.start] = w.step.v
            return out
        }

        suspend fun checkVisible(step: Int, what: String) {
            for ((k, v) in visible()) assertEquals(model[k], v, "${where(step)}: $what: edited week ${k.first.v}@${k.second} shows $v, model says ${model[k]}")
        }

        for (step in 0 until 70) {
            val s = db.settings()
            when (rnd.nextInt(10)) {
                in 0..4 -> {
                    val b = budgets.random(rnd)
                    val m = db.monthOf(today).afShift(rnd.nextInt(-2, 3))
                    db.ensureMonths(db.monthOf(today).afShift(3))
                    val weeks = db.budgetDetail(b, m, today).month.weeks.filter { !it.locked }
                    if (weeks.isEmpty()) continue
                    val w = weeks.random(rnd)
                    val cents = when (rnd.nextInt(10)) {
                        0 -> w.max.v + 1
                        1 -> -1L
                        2 -> 0L
                        3 -> w.max.v
                        else -> if (w.max.v >= 0) rnd.nextLong(0, w.max.v + 1) else 0L
                    }
                    log += "setStep(${b.v}, $m, ${w.start}, $cents) max=${w.max.v}"
                    val ok = db.setStep(b, m, w.start, Cents(cents))
                    // Week.max KDoc: any value in 0..max is accepted, max + 1 is refused; setStep refuses < 0.
                    assertEquals(cents in 0..w.max.v, ok, "${where(step)}: setStep acceptance")
                    if (ok) model[b to w.start] = cents
                    checkVisible(step, "after setStep")
                }
                5 -> {
                    val vis = visible()
                    if (vis.isEmpty()) continue
                    val k = vis.keys.toList()[rnd.nextInt(vis.size)]
                    log += "clearStep(${k.first.v}, ${k.second})"
                    assertTrue(db.clearStep(k.first, db.monthOf(k.second), k.second), "${where(step)}: clearStep of a shown edit")
                    model.remove(k)
                    checkVisible(step, "after clearStep")
                }
                6, 7 -> {
                    // Dry prediction: writes nothing; checked where the answer doesn't need the new day's month start.
                    val day = listOf(0, 32, 33, -1, s.monthStartDay, rnd.nextInt(1, 32), rnd.nextInt(28, 32)).random(rnd)
                    val ws = if (rnd.nextBoolean()) s.weekStart else DayOfWeek.entries.random(rnd)
                    val v0 = db.version.value
                    val w0 = p.writes
                    val predicted = db.startDropsEdits(day, ws, today)
                    log += "dry startDropsEdits($day, $ws) = $predicted"
                    assertEquals(v0 to w0, db.version.value to p.writes, "${where(step)}: startDropsEdits reads only")
                    val oldStart = startOfToday()
                    when {
                        day !in 1..31 -> assertFalse(predicted, "${where(step)}: a day outside 1..31 predicts false")
                        day == s.monthStartDay && ws == s.weekStart -> assertFalse(predicted, "${where(step)}: nothing changes, nothing is deleted")
                        // Day unchanged: the cut is today's month start as it is now.
                        day == s.monthStartDay -> assertEquals(model.keys.any { it.second >= oldStart }, predicted, "${where(step)}: week-only change, cut $oldStart")
                        // The cut is never after today's month start as it is now.
                        model.keys.any { it.second >= oldStart } -> assertTrue(predicted, "${where(step)}: an edit at or after $oldStart is always deleted")
                        model.isEmpty() -> assertFalse(predicted, "${where(step)}: no edit, nothing to delete")
                    }
                }
                else -> {
                    val day = listOf(1, 1, 2, 15, 28, 29, 30, 31, s.monthStartDay, rnd.nextInt(1, 32), 0, 32).random(rnd)
                    val ws = if (rnd.nextInt(3) == 0) s.weekStart else DayOfWeek.entries.random(rnd)
                    val v0 = db.version.value
                    val predicted = db.startDropsEdits(day, ws, today)
                    log += "startDropsEdits($day, $ws) = $predicted, then save"
                    assertEquals(v0, db.version.value, "${where(step)}: startDropsEdits reads only")
                    if (day !in 1..31) {
                        // The screen's stepper never offers it; the KDoc says false.
                        assertFalse(predicted, "${where(step)}: a day outside 1..31 predicts false")
                        continue
                    }
                    val oldStart = startOfToday()
                    val before = visible()
                    assertTrue(db.setMonthStartDay(day, today), "${where(step)}: setMonthStartDay($day)")
                    db.setWeekStart(ws, today)
                    val newStart = startOfToday()
                    val changed = day != s.monthStartDay || ws != s.weekStart
                    val cut = minOf(oldStart, newStart)
                    val doomed = if (changed) model.keys.filter { it.second >= cut } else emptyList()
                    assertEquals(
                        doomed.isNotEmpty(), predicted,
                        "${where(step)}: startDropsEdits($day, $ws) from (${s.monthStartDay}, ${s.weekStart}); cut $cut (old $oldStart, new $newStart); deleted $doomed",
                    )
                    doomed.forEach { model.remove(it) }
                    val after = visible()
                    checkVisible(step, "after the change")
                    if (changed) {
                        assertTrue(after.keys.none { it.second >= newStart }, "${where(step)}: no edit survives from today's month on: $after")
                    } else {
                        assertEquals(before, after, "${where(step)}: same days, same edits")
                        assertEquals(v0, db.version.value, "${where(step)}: same days write nothing")
                    }
                }
            }
        }
    }
}
