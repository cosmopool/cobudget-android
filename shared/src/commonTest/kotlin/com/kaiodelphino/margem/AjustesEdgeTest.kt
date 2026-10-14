package com.kaiodelphino.margem

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
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

/**
 * Edge cases of the block 5 additions (Ajustes e importação) in the shared module, through the public
 * API only: [importCobudget] (spec §7.6, D3, D4, D40) and [Db.startDropsEdits] (spec §4, §7.6, D10, D41).
 *
 * Cobudget backups are built here: a fresh SQLite file opened with the same driver, the v7
 * `captured_notifications` table copied from the importCobudget KDoc, rows inserted by hand and
 * `PRAGMA user_version` set, then closed.
 *
 * Calendar facts used by hand below (Jan 1 2026 is a Thursday; Jan 1 2027 a Friday):
 * - 2026: Sep 1 Tue; Mondays Sep 7, 14, 21, 28, Oct 5, 12, 19, 26, Nov 2, 9, 16; Oct 1 Thu, Oct 14 Wed, Oct 15 Thu.
 * - 2027: Dec 31 2026 Thu; Mondays Jan 4, 11, 18, 25, Feb 1, 8, 15, 22, Mar 1; Jan 31 Sun, Feb 28 Sun (Feb has 28 days).
 * Month windows (spec §3: day D to the eve of D next month; a missing D is that month's last day):
 * - D = 1: the calendar month.
 * - D = 15: 2026-09 = Sep 15..Oct 14 · 2026-10 = Oct 15..Nov 14.
 * - D = 31: 2026-12 = Dec 31..Jan 30 · 2027-01 = Jan 31..Feb 27 · 2027-02 = Feb 28..Mar 30.
 * - D = 30: 2027-01 = Jan 30..Feb 27 · 2027-02 = Feb 28..Mar 29.
 * - D = 29: 2027-01 = Jan 29..Feb 27 · 2027-02 = Feb 28..Mar 28.
 * Weeks (spec §4) are calendar weeks from the week start day, cut to the month window.
 */
class AjustesEdgeTest {

    private class Env(val db: Db, val path: String, private val counter: IntArray) {
        val files = mutableListOf<String>()
        val writes: Int get() = counter[0]

        fun temp(name: String): String =
            Path(SystemTemporaryDirectory, "margem-ajustes-edge-$name-${Random.nextLong().toULong()}.db").toString().also { files += it }
    }

    private fun withDb(block: suspend Env.() -> Unit): TestResult = runTest(timeout = 180.seconds) {
        val path = Path(SystemTemporaryDirectory, "margem-ajustes-edge-${Random.nextLong().toULong()}.db").toString()
        val counter = intArrayOf(0)
        val db = Db(BundledSQLiteDriver(), path, onWrite = { counter[0]++ }, timeZone = TZ)
        val env = Env(db, path, counter)
        try {
            env.block()
        } finally {
            db.close()
            for (p in env.files + path) for (suffix in listOf("", "-wal", "-shm", "-journal", ".import")) {
                SystemFileSystem.delete(Path(p + suffix), mustExist = false)
            }
        }
    }

    // ------------------------------------------------------------------ cobudget fixture

    /** One `captured_notifications` row. null = SQL NULL (only where the v7 schema allows it). */
    private data class Row(
        val key: String,
        val postedAt: Long,
        val whenMs: Long = 0,
        val title: String? = "Banco Aurora",
        val text: String? = null,
        val bigText: String? = null,
        val subText: String? = null,
        val textLines: String? = null,
        val category: String? = null,
        val channelId: String? = null,
        val pkg: String = OTHER,
        val label: String = "Banco Aurora",
        val extrasJson: String = "{}",
        val contentHash: String = "cobudget-hash",
        val dismissed: Int = 0,
        val id: Long = 0,
    ) {
        /** The KDoc mapping: column → RawNotice field, NULL text is "". */
        fun raw() = RawNotice(
            packageName = pkg, appLabel = label, key = key, postedAt = postedAt, whenMs = whenMs,
            title = title ?: "", text = text ?: "", bigText = bigText ?: "", subText = subText ?: "",
            textLines = textLines ?: "", category = category ?: "", channelId = channelId ?: "", extrasJson = extrasJson,
        )
    }

    private fun insert(c: SQLiteConnection, r: Row, id: Long) {
        val st = c.prepare(
            "INSERT INTO captured_notifications(id, packageName, appLabel, notificationKey, postedAt, notificationWhen, title, text, " +
                "bigText, subText, textLines, category, channelId, extrasJson, contentHash, dismissed) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        try {
            st.bindLong(1, id)
            st.bindText(2, r.pkg)
            st.bindText(3, r.label)
            st.bindText(4, r.key)
            st.bindLong(5, r.postedAt)
            st.bindLong(6, r.whenMs)
            listOf(r.title, r.text, r.bigText, r.subText, r.textLines, r.category, r.channelId).forEachIndexed { i, v ->
                if (v == null) st.bindNull(7 + i) else st.bindText(7 + i, v)
            }
            st.bindText(14, r.extrasJson)
            st.bindText(15, r.contentHash)
            st.bindLong(16, r.dismissed.toLong())
            st.step()
        } finally {
            st.close()
        }
    }

    /** A cobudget backup: [table] (v7 by default), [extra] statements, [rows] (ids 1.. unless set) and user_version. */
    private fun Env.cobudget(
        rows: List<Row>,
        userVersion: Int = 7,
        table: String = V7_TABLE,
        extra: List<String> = emptyList(),
    ): String {
        val p = temp("cobudget")
        val c = BundledSQLiteDriver().open(p)
        try {
            if (table.isNotEmpty()) c.execSQL(table)
            for (sql in extra) c.execSQL(sql)
            rows.forEachIndexed { i, r -> insert(c, r, if (r.id != 0L) r.id else i + 1L) }
            c.execSQL("PRAGMA user_version = $userVersion")
        } finally {
            c.close()
        }
        return p
    }

    private fun Env.file(bytes: ByteArray): String {
        val p = temp("raw")
        SystemFileSystem.sink(Path(p)).buffered().use { it.write(bytes) }
        return p
    }

    private fun bytesOf(p: String): ByteArray = SystemFileSystem.source(Path(p)).buffered().use { it.readByteArray() }

    private data class Snap(
        val pending: List<NoticeRow>,
        val dismissed: List<NoticeRow>,
        val sources: List<Source>,
        val tags: List<TagRow>,
        val transactions: List<TransactionRow>,
        val settings: Settings,
    )

    private suspend fun Db.snap() = Snap(pending(), dismissed(), sources(), tags(), transactions(), settings())

    /** Imports [p] checking the version moved by [bumps], onWrite ran as often, and the file's bytes didn't change. */
    private suspend fun Env.importFile(p: String, bumps: Long): CobudgetImport {
        val v0 = db.version.value
        val w0 = writes
        val before = if (SystemFileSystem.exists(Path(p))) bytesOf(p) else null
        val r = importCobudget(db, p)
        assertEquals(bumps, db.version.value - v0, "version bumps importing $p: $r")
        assertEquals(bumps, (writes - w0).toLong(), "onWrite calls importing $p: $r")
        if (before != null) assertTrue(before.contentEquals(bytesOf(p)), "the file is only read: $p")
        return r
    }

    private fun purchase(cents: String = "31,50", merchant: String = "LOJA X") =
        "Compra de R$ $cents APROVADA em $merchant para o cartão com final 1234."

    // ------------------------------------------------------------------ importCobudget: refused files

    @Test
    fun `refused files leave the Db untouched and return the zero import`() = withDb {
        // A Db with something in every list.
        assertTrue(db.addSource(NU, "Nubank", 0))
        val kept = RawNotice(NU, "Nubank", "kept", T, T, "Nubank", purchase(), purchase())
        val gone = RawNotice(NU, "Nubank", "gone", T + MIN, T + MIN, "Nubank", "Promoção imperdível")
        assertTrue(capture(db, kept))
        assertTrue(capture(db, gone))
        assertTrue(db.dismiss(db.pending().single { it.raw.key == "gone" }.id))
        val budget = db.createBudget("Mercado", Cents(60000), TODAY)
        assertNotEquals(0L, budget.v)
        val t = db.accept(
            db.pending().single { it.raw.key == "kept" }.id,
            TransactionForm(budget, listOf("feira"), Cents(3150), 1, "LOJA X", TODAY, LocalTime(12, 0)),
        )
        assertNotEquals(0L, t.v)
        assertTrue(capture(db, RawNotice(NU, "Nubank", "left", T + 2 * MIN, T + 2 * MIN, "Nubank", purchase("9,90"))))
        val before = db.snap()

        val good = listOf(Row("c-1", T, T, text = purchase("12,00")), Row("c-2", T + MIN, T + MIN, text = purchase("13,00"), dismissed = 1))
        val margemBackup = temp("margem-backup").also { assertTrue(db.exportTo(it), "exportTo $it") }
        val refused = listOf(
            "missing" to temp("missing"),
            "empty file" to file(ByteArray(0)),
            "random bytes" to file(Random(5).nextBytes(4096)),
            "SQLite header then garbage" to file("SQLite format 3\u0000".encodeToByteArray() + Random(6).nextBytes(4096)),
            "SQLite without the table" to cobudget(
                emptyList(), table = "",
                extra = listOf("CREATE TABLE tags(id INTEGER PRIMARY KEY, name TEXT NOT NULL)", "INSERT INTO tags(name) VALUES ('padaria')"),
            ),
            // KDoc: "a captured_notifications table with the columns below": notificationWhen is missing here.
            "v7 table missing a column" to cobudget(
                emptyList(),
                table = "CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, " +
                    "`postedAt` INTEGER NOT NULL, `title` TEXT, `text` TEXT, `bigText` TEXT, `subText` TEXT, `textLines` TEXT, " +
                    "`category` TEXT, `channelId` TEXT, `extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, " +
                    "`dismissed` INTEGER NOT NULL DEFAULT 0)",
                extra = listOf(
                    "INSERT INTO captured_notifications(packageName, appLabel, notificationKey, postedAt, title, text, extrasJson, contentHash) " +
                        "VALUES ('$OTHER', 'Banco Aurora', 'c-x', $T, 'Banco Aurora', 'Compra de R$ 5,00 APROVADA em LOJA', '{}', 'h')",
                ),
            ),
            "user_version 0" to cobudget(good, userVersion = 0),
            "user_version 6" to cobudget(good, userVersion = 6),
            "user_version 8" to cobudget(good, userVersion = 8),
            "a Margem backup" to margemBackup,
        )
        for ((what, p) in refused) {
            assertEquals(CobudgetImport(), importFile(p, bumps = 0), "refused: $what")
            assertEquals(before, db.snap(), "Db untouched after refusing: $what")
        }

        // Still usable: the v7 version of the same rows opens and imports.
        assertEquals(CobudgetImport(opened = true, pending = 1, dismissed = 1), importFile(cobudget(good), bumps = 1))
    }

    @Test
    fun `an empty v7 table opens and adds nothing without bumping version`() = withDb {
        val before = db.snap()
        // KDoc CobudgetImport: opened = false only when refused; nothing entered = no bump ("bumps version once when something entered").
        assertEquals(CobudgetImport(opened = true), importFile(cobudget(emptyList()), bumps = 0))
        assertEquals(before, db.snap())
    }

    @Test
    fun `the other tables of the backup are ignored`() = withDb {
        val p = cobudget(
            listOf(Row("c-1", T, T, text = purchase())),
            extra = listOf(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
                "INSERT INTO room_master_table VALUES (42, 'abc')",
                "CREATE TABLE sources(packageName TEXT PRIMARY KEY, label TEXT NOT NULL)",
                "INSERT INTO sources VALUES ('$OTHER', 'Banco Aurora')",
                "CREATE TABLE tags(id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
                "INSERT INTO tags(name) VALUES ('padaria')",
                "CREATE TABLE transactions(id INTEGER PRIMARY KEY, notificationId INTEGER, amountCents INTEGER)",
                "INSERT INTO transactions VALUES (1, 1, 3150)",
                "CREATE TABLE genda_outbox(id INTEGER PRIMARY KEY, payload TEXT)",
                "INSERT INTO genda_outbox VALUES (1, 'x')",
            ),
        )
        assertEquals(CobudgetImport(opened = true, pending = 1), importFile(p, bumps = 1))
        // D3: sources, tags, nicknames, transactions and the genda outbox stay behind.
        assertEquals(emptyList(), db.sources())
        assertEquals(emptyList(), db.tags())
        assertEquals(emptyList(), db.transactions())
        assertEquals(listOf("c-1"), db.pending().map { it.raw.key })
    }

    // ------------------------------------------------------------------ importCobudget: rows

    @Test
    fun `NULL texts become empty strings and every column maps to the notice`() = withDb {
        val rows = listOf(
            // Everything nullable is NULL except text.
            Row("c-null", T, 0, title = null, text = purchase(), pkg = NU, label = "Nubank", extrasJson = "{\"a\":1}"),
            // Every column set.
            Row(
                "c-full", T + MIN, T - 5 * MIN, title = "Nubank", text = purchase("45,00"), bigText = purchase("45,00") + " Big",
                subText = "Cartão", textLines = "linha 1\nlinha 2", category = "msg", channelId = "purchases", pkg = NU, label = "Nubank",
                extrasJson = "{\"android.title\":\"Nubank\"}",
            ),
            // Only the lines carry text.
            Row("c-lines", T + 2 * MIN, title = null, textLines = "Compra aprovada\nR$ 10,00"),
            // Only the sub text carries text.
            Row("c-sub", T + 3 * MIN, title = null, subText = "Aviso"),
        )
        assertEquals(CobudgetImport(opened = true, pending = 4), importFile(cobudget(rows), bumps = 1))
        val byKey = db.pending().associateBy { it.raw.key }
        for (r in rows) assertEquals(r.raw(), byKey.getValue(r.key).raw, "row ${r.key}")
        assertEquals(0L, byKey.getValue("c-null").raw.whenMs, "notificationWhen 0 stays 0")
        assertEquals(T - 5 * MIN, byKey.getValue("c-full").raw.whenMs, "notificationWhen → whenMs")
    }

    @Test
    fun `a row whose texts are all blank or NULL is skipped`() = withDb {
        val rows = listOf(
            Row("blank-null", T, T, title = null),
            Row("blank-empty", T + 1, T + 1, title = "", text = "", bigText = "", subText = "", textLines = ""),
            Row("blank-spaces", T + 2, T + 2, title = " ", text = "\n", bigText = "\t", subText = "  ", textLines = null),
            // category, channel and extras are not texts (capture: title, text, big text, sub text and lines).
            Row("blank-meta", T + 3, T + 3, title = null, category = "msg", channelId = "promo", extrasJson = "{\"x\":\"Compra\"}", dismissed = 1),
            Row("with-text", T + 4, T + 4, title = null, text = purchase(), dismissed = 1),
        )
        assertEquals(CobudgetImport(opened = true, dismissed = 1, skipped = 4), importFile(cobudget(rows), bumps = 1))
        assertEquals(emptyList(), db.pending())
        assertEquals(listOf("with-text"), db.dismissed().map { it.raw.key })
    }

    @Test
    fun `a file whose rows are all skipped opens without bumping version`() = withDb {
        val rows = listOf(Row("b1", T, title = null), Row("b2", T + 1, title = "  "))
        val before = db.snap()
        assertEquals(CobudgetImport(opened = true, skipped = 2), importFile(cobudget(rows), bumps = 0))
        assertEquals(before, db.snap())
    }

    @Test
    fun `when 0 never matches while a set when matches however much later`() = withDb {
        // capture KDoc: same key, content and when (≠ 0), however much later; when = 0 never matches.
        val rows = listOf(
            Row("k-when0", T, 0, text = purchase()),
            Row("k-when0", T + DAY, 0, text = purchase()), // a day apart, when 0: enters
            Row("k-whenset", T, W, text = purchase()),
            Row("k-whenset", T + DAY, W, text = purchase(), dismissed = 1), // same when a day later: re-post
            Row("k-whendiff", T, W, text = purchase()),
            Row("k-whendiff", T + DAY, W + 1, text = purchase()), // another when, a day apart: enters
        )
        assertEquals(CobudgetImport(opened = true, pending = 5, skipped = 1), importFile(cobudget(rows), bumps = 1))
        val pending = db.pending()
        assertEquals(listOf(T, T + DAY), pending.filter { it.raw.key == "k-when0" }.map { it.raw.postedAt }.sorted())
        assertEquals(listOf(T), pending.filter { it.raw.key == "k-whenset" }.map { it.raw.postedAt })
        assertEquals(2, pending.count { it.raw.key == "k-whendiff" })
        assertEquals(emptyList(), db.dismissed(), "the skipped re-post was the dismissed one")
    }

    @Test
    fun `the window rule skips a re-post under DUPLICATE_WINDOW_MS and keeps one at the window`() = withDb {
        val rows = listOf(
            // Same key and content, when 0: 1 ms under the window → re-post (posted less than the window apart).
            Row("w-under", T, text = purchase()),
            Row("w-under", T + DUPLICATE_WINDOW_MS - 1, text = purchase()),
            // Exactly the window apart is not "less than": enters.
            Row("w-at", T, text = purchase()),
            Row("w-at", T + DUPLICATE_WINDOW_MS, text = purchase()),
            // Same key, other content, 1 s apart: enters.
            Row("w-content", T, text = purchase("10,00")),
            Row("w-content", T + 1000, text = purchase("11,00")),
            // Other key, same content, same instant: enters.
            Row("w-key-a", T, text = purchase()),
            Row("w-key-b", T, text = purchase()),
            // The window compares with the latest of the key only: X, Y, X 30 s apart each → the second X enters.
            Row("w-latest", T, text = purchase("1,00")),
            Row("w-latest", T + 30_000, text = purchase("2,00")),
            Row("w-latest", T + 60_000, text = purchase("1,00")),
            // Content differs only in the extras / category / channel / label: still the same content → re-post.
            Row("w-meta", T, text = purchase(), category = "a", channelId = "a", extrasJson = "{\"a\":1}"),
            Row("w-meta", T + 1000, text = purchase(), category = "b", channelId = "b", extrasJson = "{\"b\":2}", label = "Outro"),
        )
        // 13 rows: skipped = w-under #2 and w-meta #2 → 2; the other 11 enter.
        assertEquals(CobudgetImport(opened = true, pending = 11, skipped = 2), importFile(cobudget(rows), bumps = 1))
        val count = db.pending().groupingBy { it.raw.key }.eachCount()
        assertEquals(
            mapOf("w-under" to 1, "w-at" to 2, "w-content" to 2, "w-key-a" to 1, "w-key-b" to 1, "w-latest" to 3, "w-meta" to 1),
            count,
        )
        assertEquals(listOf(T), db.pending().filter { it.raw.key == "w-under" }.map { it.raw.postedAt })
        assertEquals("a", db.pending().single { it.raw.key == "w-meta" }.raw.category, "the first one stays")
    }

    @Test
    fun `rows are taken in postedAt order then id`() = withDb {
        val rows = listOf(
            // Inserted later-first: id 1 is posted a day after id 2. postedAt order makes id 2 the original.
            Row("o-time", T + DAY, W, text = purchase(), dismissed = 1, id = 1),
            Row("o-time", T, W, text = purchase(), dismissed = 0, id = 2),
            // Same postedAt: the lower id goes first and is the original; id 9 is its re-post (0 ms apart).
            Row("o-id", T, 0, text = purchase("7,00"), dismissed = 0, id = 9),
            Row("o-id", T, 0, text = purchase("7,00"), dismissed = 1, id = 5),
        )
        assertEquals(CobudgetImport(opened = true, pending = 1, dismissed = 1, skipped = 2), importFile(cobudget(rows), bumps = 1))
        assertEquals(listOf("o-time" to T), db.pending().map { it.raw.key to it.raw.postedAt })
        assertEquals(listOf("o-id"), db.dismissed().map { it.raw.key })
    }

    @Test
    fun `a row that repeats a notice already in the Db is skipped`() = withDb {
        assertTrue(db.addSource(NU, "Nubank", 0))
        fun nu(key: String, at: Long, whenMs: Long, text: String = purchase(), category: String = "msg") =
            RawNotice(NU, "Nubank", key, at, whenMs, "Nubank", text, category = category, extrasJson = "{}")
        for (n in listOf(nu("d-1", T, W), nu("d-2", T, 0), nu("d-3", T, 0), nu("d-4", T, W), nu("d-5", T, W))) assertTrue(capture(db, n), n.key)
        val before = db.pending().map { it.id to it.raw }.toSet()

        val rows = listOf(
            // Same key, content and when, a day later: re-post.
            Row("d-1", T + DAY, W, title = "Nubank", text = purchase(), pkg = NU, label = "Nubank", dismissed = 1),
            // when 0, same content, 60 s after the Db's: inside the window → re-post.
            Row("d-2", T + 60_000, 0, title = "Nubank", text = purchase(), pkg = NU, label = "Nubank"),
            // when 0, same content, exactly the window after: enters.
            Row("d-3", T + DUPLICATE_WINDOW_MS, 0, title = "Nubank", text = purchase(), pkg = NU, label = "Nubank"),
            // Same key and when, other content: enters.
            Row("d-4", T + 30_000, W, title = "Nubank", text = purchase("99,00"), pkg = NU, label = "Nubank"),
            // Same key, content and when; only category, channel and extras differ: re-post.
            Row("d-5", T + DAY, W, title = "Nubank", text = purchase(), pkg = NU, label = "Nubank", category = "promo", channelId = "x", extrasJson = "{\"y\":1}"),
        )
        assertEquals(CobudgetImport(opened = true, pending = 2, skipped = 3), importFile(cobudget(rows), bumps = 1))
        val after = db.pending()
        assertTrue(after.map { it.id to it.raw }.toSet().containsAll(before), "the notices already in the Db are kept as they were")
        assertEquals(listOf(T + DUPLICATE_WINDOW_MS, T), after.filter { it.raw.key == "d-3" }.map { it.raw.postedAt })
        assertEquals(2, after.count { it.raw.key == "d-4" })
        assertEquals(1, after.count { it.raw.key == "d-1" })
        assertEquals(emptyList(), db.dismissed())
    }

    @Test
    fun `notices of apps that are not sources enter and sources stay as they are`() = withDb {
        assertTrue(db.addSource(NU, "Nubank", 7))
        val sources = db.sources()
        val rows = listOf(
            Row("s-1", T, T, text = purchase(), pkg = "com.removed.bank", label = "Banco Antigo"),
            Row("s-2", T + 1, T + 1, text = purchase(), pkg = "", label = ""),
            Row("s-3", T + 2, T + 2, text = purchase(), pkg = NU, label = "Nubank", dismissed = 1),
        )
        assertEquals(CobudgetImport(opened = true, pending = 2, dismissed = 1), importFile(cobudget(rows), bumps = 1))
        assertEquals(sources, db.sources())
        assertEquals(setOf("com.removed.bank", ""), db.pending().map { it.raw.packageName }.toSet())
    }

    @Test
    fun `the file contentHash and id are ignored`() = withDb {
        assertTrue(db.addSource(NU, "Nubank", 0))
        // A notice in the Db with id 1, so the file's id 1 must not collide with it.
        val mine = RawNotice(NU, "Nubank", "mine", T, T, "Nubank", purchase("1,00"))
        assertTrue(capture(db, mine))
        val mineId = db.pending().single().id
        val rows = listOf(
            // Same stored hash, different content, same key, 1 s apart: the hash is recomputed → both enter.
            Row("h-1", T, text = purchase("10,00"), contentHash = "same", id = 1),
            Row("h-1", T + 1000, text = purchase("20,00"), contentHash = "same", id = 2),
            // Different stored hashes, same content and when: still a re-post.
            Row("h-2", T, W, text = purchase(), contentHash = "aaa", id = 3),
            Row("h-2", T + DAY, W, text = purchase(), contentHash = "bbb", id = 4),
            // The Db's notice again, with a garbage hash and the Db's own id: a re-post by content.
            Row("mine", T + DAY, T, title = "Nubank", text = purchase("1,00"), pkg = NU, label = "Nubank", contentHash = "", id = mineId.v + 100),
        )
        assertEquals(CobudgetImport(opened = true, pending = 3, skipped = 2), importFile(cobudget(rows), bumps = 1))
        val pending = db.pending()
        assertEquals(mine, db.notice(mineId).raw, "the Db's notice keeps its id and content")
        assertEquals(pending.size, pending.map { it.id }.toSet().size, "ids are unique")
        assertEquals(2, pending.count { it.raw.key == "h-1" })
        assertEquals(1, pending.count { it.raw.key == "h-2" })
        // The recomputed hash is the capture's: capturing the same notice afterwards is a re-post.
        assertTrue(db.addSource(OTHER, "Banco Aurora", 0))
        assertFalse(capture(db, rows[2].raw().copy(postedAt = T + 2 * DAY)), "capture after import dedupes against it")
    }

    @Test
    fun `counts are exact and one import is one write`() = withDb {
        // 500 rows, own keys, when set. i % 7 == 0 → no text (skipped); else i % 3 == 0 → dismissed; else pending.
        // skipped = |{0..499} ∩ 7ℤ| = ⌊499/7⌋ + 1 = 72.
        // dismissed = |3ℤ| − |21ℤ| = (⌊499/3⌋ + 1) − (⌊499/21⌋ + 1) = 167 − 24 = 143.
        // pending = 500 − 72 − 143 = 285.
        val rows = List(500) { i ->
            if (i % 7 == 0) Row("n-$i", T + i * 1000L, T + i, title = null)
            else Row("n-$i", T + i * 1000L, T + i, text = purchase("${i},00"), dismissed = if (i % 3 == 0) 1 else 0)
        }
        val p = cobudget(rows)
        assertEquals(CobudgetImport(opened = true, pending = 285, dismissed = 143, skipped = 72), importFile(p, bumps = 1))
        assertEquals(285, db.pending().size)
        assertEquals(285, db.pendingCount())
        assertEquals(143, db.dismissed().size)
        assertTrue(db.dismissed().all { it.raw.key.removePrefix("n-").toInt() % 3 == 0 })

        // Again: every notice is a re-post, nothing written, no bump (KDoc: "Importing the same file again adds nothing").
        val before = db.snap()
        assertEquals(CobudgetImport(opened = true, skipped = 500), importFile(p, bumps = 0))
        assertEquals(before, db.snap())
    }

    @Test
    fun `importing the same file again adds nothing even after deciding its notices`() = withDb {
        val rows = listOf(
            Row("r-1", T, W, text = purchase()),
            Row("r-2", T + MIN, 0, text = purchase("2,00")),
            Row("r-3", T + 2 * MIN, W + 2, text = purchase("3,00"), dismissed = 1),
            Row("r-4", T + 3 * MIN, W + 3, title = null),
        )
        val p = cobudget(rows)
        assertEquals(CobudgetImport(opened = true, pending = 2, dismissed = 1, skipped = 1), importFile(p, bumps = 1))
        // Decide them in the Margem: dismissed or restored, they are still notices the dedupe sees.
        assertTrue(db.dismiss(db.pending().single { it.raw.key == "r-1" }.id))
        assertTrue(db.restore(db.dismissed().single { it.raw.key == "r-3" }.id))
        val before = db.snap()
        assertEquals(CobudgetImport(opened = true, skipped = 4), importFile(p, bumps = 0))
        assertEquals(before, db.snap())
    }

    @Test
    fun `imported notices behave like captured ones`() = withDb {
        val budget = db.createBudget("Mercado", Cents(60000), TODAY)
        assertNotEquals(0L, budget.v)
        // A notice captured directly, between the two imported ones in time.
        assertTrue(db.addSource(NU, "Nubank", 0))
        val live = RawNotice(NU, "Nubank", "live", T + MIN, T + MIN, "Nubank", purchase("5,00"), purchase("5,00"))
        assertTrue(capture(db, live))
        val rows = listOf(
            Row("i-pending", T, T, title = "Nubank", text = purchase(), bigText = purchase(), pkg = NU, label = "Nubank"),
            Row("i-dismissed", T + 2 * MIN, T + 2 * MIN, title = "Nubank", text = purchase("80,00", "MERCADO Y"), pkg = NU, label = "Nubank", dismissed = 1),
            Row("i-ad", T + 3 * MIN, T + 3 * MIN, title = "Nubank", text = "Aproveite as ofertas da semana", pkg = NU, label = "Nubank"),
        )
        assertEquals(CobudgetImport(opened = true, pending = 2, dismissed = 1), importFile(cobudget(rows), bumps = 1))

        // Each one reads exactly as the same RawNotice captured into another Db (parsed, alias, status, new, looks like ad).
        val otherPath = temp("other")
        val other = Db(BundledSQLiteDriver(), otherPath, timeZone = TZ)
        try {
            assertTrue(other.addSource(NU, "Nubank", 0))
            for (r in rows) {
                assertTrue(capture(other, r.raw()), r.key)
                if (r.dismissed == 1) assertTrue(other.dismiss(other.pending().single { it.raw.key == r.key }.id))
            }
            for (r in rows) {
                val mine = (db.pending() + db.dismissed()).single { it.raw.key == r.key }
                val theirs = (other.pending() + other.dismissed()).single { it.raw.key == r.key }
                assertEquals(theirs.copy(id = NoticeId(0)), mine.copy(id = NoticeId(0)), "imported ${r.key} reads like a captured one")
            }
        } finally {
            other.close()
        }

        // pending(): newest posted first, imported and captured interleaved by postedAt.
        assertEquals(listOf("i-ad", "live", "i-pending"), db.pending().map { it.raw.key })
        assertEquals(listOf("i-dismissed"), db.dismissed().map { it.raw.key })
        assertTrue(db.dismissed().single().status == NoticeStatus.DISMISSED)
        assertTrue(db.pending().all { it.status == NoticeStatus.PENDING })

        // Trazer de volta, riscar and lançar work on them.
        val dismissedId = db.dismissed().single().id
        assertTrue(db.restore(dismissedId))
        assertEquals(NoticeStatus.PENDING, db.notice(dismissedId).status)
        val ad = db.pending().single { it.raw.key == "i-ad" }.id
        assertTrue(db.dismiss(ad))
        assertFalse(db.restore(db.pending().single { it.raw.key == "i-pending" }.id), "a pending notice isn't restored")
        val n = db.pending().single { it.raw.key == "i-pending" }
        val t = db.accept(n.id, TransactionForm(budget, listOf("feira"), n.parsed.cents, 1, n.parsed.merchant, n.parsed.date, n.parsed.time))
        assertNotEquals(0L, t.v, "accept an imported notice")
        assertEquals(NoticeStatus.PROCESSED, db.notice(n.id).status)
        assertEquals(n.id, db.transaction(t).row.notice)
        assertEquals(listOf("i-dismissed", "live"), db.pending().map { it.raw.key })
        assertEquals(listOf("i-ad"), db.dismissed().map { it.raw.key })
    }

    // ------------------------------------------------------------------ startDropsEdits

    private suspend fun Env.budget(name: String, created: LocalDate, through: YearMonth): BudgetId {
        val id = db.createBudget(name, Cents(60000), created)
        assertNotEquals(0L, id.v, "createBudget $name")
        db.ensureMonths(through)
        return id
    }

    private suspend fun Env.edit(b: BudgetId, m: YearMonth, weekStart: LocalDate, cents: Long = 1000) =
        assertTrue(db.setStep(b, m, weekStart, Cents(cents)), "setStep $m $weekStart (a Week.start of that month)")

    /** Every edited week of [b] in [months], as the current settings show them. */
    private suspend fun Env.edits(b: BudgetId, months: List<YearMonth>, today: LocalDate): List<Triple<YearMonth, LocalDate, Long>> =
        months.flatMap { m -> db.budgetDetail(b, m, today).month.weeks.filter { it.edited }.map { Triple(m, it.start, it.step.v) } }

    /** startDropsEdits must answer [expected] and only read: version, onWrite, settings and edits unchanged. */
    private suspend fun Env.drops(expected: Boolean, day: Int, week: DayOfWeek, today: LocalDate, b: BudgetId, months: List<YearMonth>, why: String) {
        val v0 = db.version.value
        val w0 = writes
        val s0 = db.settings()
        val e0 = edits(b, months, today)
        assertEquals(expected, db.startDropsEdits(day, week, today), "startDropsEdits($day, $week, $today): $why")
        assertEquals(v0, db.version.value, "reads only, version: $why")
        assertEquals(w0, writes, "reads only, onWrite: $why")
        assertEquals(s0, db.settings(), "reads only, settings: $why")
        assertEquals(e0, edits(b, months, today), "reads only, edits: $why")
    }

    @Test
    fun `startDropsEdits is false for a month start day outside 1 to 31`() = withDb {
        val oct14 = day(2026, 10, 14)
        val months = listOf(ym(2026, 10), ym(2026, 11))
        val b = budget("Mercado", oct14, ym(2026, 11))
        edit(b, ym(2026, 10), day(2026, 10, 19))
        edit(b, ym(2026, 11), day(2026, 11, 16))
        // Control: a valid call with the same week change does drop them (cut Oct 1, edits Oct 19 and Nov 16).
        drops(true, 1, DayOfWeek.SUNDAY, oct14, b, months, "control: only the week")
        for (bad in listOf(0, 32, -1, 100, Int.MIN_VALUE, Int.MAX_VALUE)) {
            drops(false, bad, DayOfWeek.SUNDAY, oct14, b, months, "day $bad is invalid, even with the week changing")
            drops(false, bad, DayOfWeek.MONDAY, oct14, b, months, "day $bad is invalid")
        }
    }

    @Test
    fun `startDropsEdits is false when neither day changes`() = withDb {
        val oct14 = day(2026, 10, 14)
        val months = listOf(ym(2026, 10), ym(2026, 11))
        val b = budget("Mercado", oct14, ym(2026, 11))
        edit(b, ym(2026, 10), day(2026, 10, 19))
        edit(b, ym(2026, 11), day(2026, 11, 2))
        drops(false, 1, DayOfWeek.MONDAY, oct14, b, months, "defaults: day 1, Monday")

        // Another current pair: day 15, Sunday.
        assertTrue(db.setMonthStartDay(15, oct14))
        db.setWeekStart(DayOfWeek.SUNDAY, oct14)
        // D = 15, Sunday: 2026-10 = Oct 15..Nov 14; Oct 18 is a Sunday inside it.
        edit(b, ym(2026, 10), day(2026, 10, 18))
        drops(false, 15, DayOfWeek.SUNDAY, day(2026, 10, 20), b, months, "day 15 Sunday is what is set")
    }

    @Test
    fun `only the week start cuts at the start of today's month`() = withDb {
        val oct20 = day(2026, 10, 20)
        assertTrue(db.setMonthStartDay(15, oct20))
        // D = 15: Sep 20 is in 2026-09 (Sep 15..Oct 14). Weeks (Monday): Sep 15–20 · 21–27 · Sep 28–Oct 4 · Oct 5–11 · Oct 12–14;
        // 2026-10 (Oct 15..Nov 14): Oct 15–18 · 19–25 · Oct 26–Nov 1 · Nov 2–8 · Nov 9–14.
        val months = listOf(ym(2026, 9), ym(2026, 10))
        val b = budget("Mercado", day(2026, 9, 20), ym(2026, 10))
        // Today Oct 20 is in 2026-10: the cut is Oct 15 (spec §4: "do mês atual em diante").
        edit(b, ym(2026, 9), day(2026, 9, 15))
        edit(b, ym(2026, 9), day(2026, 10, 12))
        drops(false, 15, DayOfWeek.SUNDAY, oct20, b, months, "Oct 12 is before the cut Oct 15")
        drops(false, 15, DayOfWeek.TUESDAY, oct20, b, months, "any other week start, same cut")
        edit(b, ym(2026, 10), day(2026, 10, 15))
        drops(true, 15, DayOfWeek.SUNDAY, oct20, b, months, "Oct 15 is at the cut")
        drops(false, 15, DayOfWeek.MONDAY, oct20, b, months, "Monday is what is set")

        // The same edits seen from Oct 14 (2026-09, cut Sep 15): the edit at Sep 15 is at the cut.
        drops(true, 15, DayOfWeek.SUNDAY, day(2026, 10, 14), b, months, "today Oct 14: cut Sep 15")
    }

    @Test
    fun `only the month start day cuts at the earlier of the old and the new month start`() = withDb {
        val oct14 = day(2026, 10, 14)
        // D = 1, Monday. Sep weeks: Sep 1–6 · 7–13 · 14–20 · 21–27 · 28–30. Oct: Oct 1–4 · 5–11 · 12–18 · 19–25 · 26–31.
        val months = listOf(ym(2026, 9), ym(2026, 10))
        val b = budget("Mercado", day(2026, 9, 2), ym(2026, 10))
        // 1 → 15 on Oct 14: today's month by day 1 starts Oct 1, by day 15 Sep 15 (Sep 15..Oct 14). Cut = Sep 15.
        edit(b, ym(2026, 9), day(2026, 9, 14))
        drops(false, 15, DayOfWeek.MONDAY, oct14, b, months, "Sep 14 is before the cut Sep 15")
        // 1 → 2 on Oct 14: by day 2, Oct 14 is in Oct 2..Nov 1. Cut = min(Oct 1, Oct 2) = Oct 1.
        edit(b, ym(2026, 9), day(2026, 9, 28))
        drops(false, 2, DayOfWeek.MONDAY, oct14, b, months, "Sep 28 is before the cut Oct 1")
        drops(true, 15, DayOfWeek.MONDAY, oct14, b, months, "Sep 28 is after the cut Sep 15 (the new day's start is earlier)")
        edit(b, ym(2026, 10), day(2026, 10, 1))
        drops(true, 2, DayOfWeek.MONDAY, oct14, b, months, "Oct 1 is at the cut Oct 1 (the old day's start is earlier)")
    }

    @Test
    fun `from day 15 back to day 1 the old start is later and the new one cuts`() = withDb {
        val oct20 = day(2026, 10, 20)
        assertTrue(db.setMonthStartDay(15, oct20))
        val months = listOf(ym(2026, 9), ym(2026, 10))
        val b = budget("Mercado", day(2026, 9, 20), ym(2026, 10))
        // 15 → 1 on Oct 20: by day 15 Oct 15..Nov 14 (start Oct 15), by day 1 Oct 1. Cut = Oct 1.
        edit(b, ym(2026, 9), day(2026, 9, 28))
        drops(false, 1, DayOfWeek.MONDAY, oct20, b, months, "Sep 28 is before the cut Oct 1")
        drops(false, 15, DayOfWeek.SUNDAY, oct20, b, months, "only the week: cut Oct 15")
        edit(b, ym(2026, 9), day(2026, 10, 5))
        drops(true, 1, DayOfWeek.MONDAY, oct20, b, months, "Oct 5 is after the cut Oct 1")
        drops(false, 15, DayOfWeek.SUNDAY, oct20, b, months, "only the week: Oct 5 is before the cut Oct 15")
        drops(true, 1, DayOfWeek.SUNDAY, oct20, b, months, "both: the earlier cut Oct 1")
    }

    @Test
    fun `both changes cut at the earlier month start`() = withDb {
        val oct14 = day(2026, 10, 14)
        val months = listOf(ym(2026, 9), ym(2026, 10), ym(2026, 11))
        val b = budget("Mercado", day(2026, 9, 2), ym(2026, 11))
        // 1 → 15 and Monday → Sunday on Oct 14: month cut min(Oct 1, Sep 15) = Sep 15; the week's cut by the new day is also Sep 15.
        edit(b, ym(2026, 9), day(2026, 9, 7))
        edit(b, ym(2026, 9), day(2026, 9, 14))
        drops(false, 15, DayOfWeek.SUNDAY, oct14, b, months, "Sep 7 and Sep 14 are before Sep 15")
        drops(false, 1, DayOfWeek.MONDAY, oct14, b, months, "nothing changes")
        edit(b, ym(2026, 11), day(2026, 11, 9))
        drops(true, 15, DayOfWeek.SUNDAY, oct14, b, months, "Nov 9 is after the cut")
        drops(true, 1, DayOfWeek.SATURDAY, oct14, b, months, "only the week: cut Oct 1, Nov 9 after it")
        drops(true, 31, DayOfWeek.MONDAY, oct14, b, months, "only the month: by day 31 Oct 14 is in Sep 30..Oct 30, cut Sep 30")
    }

    @Test
    fun `month start day 31 in February cuts at the earlier start`() = withDb {
        // D = 1 → 31 on Feb 28 2027: by day 1 Feb 1; by day 31 2027-02 = Feb 28..Mar 30 (31 missing → Feb 28). Cut = Feb 1.
        val feb28 = day(2027, 2, 28)
        val months = listOf(ym(2027, 1), ym(2027, 2), ym(2027, 3))
        val b = budget("Mercado", day(2027, 1, 5), ym(2027, 3))
        // D = 1 weeks: Jan 1–3 · 4–10 · 11–17 · 18–24 · 25–31; Feb 1–7 · 8–14 · 15–21 · 22–28.
        edit(b, ym(2027, 1), day(2027, 1, 25))
        drops(false, 31, DayOfWeek.MONDAY, feb28, b, months, "Jan 25 is before the cut Feb 1")
        edit(b, ym(2027, 2), day(2027, 2, 22))
        drops(true, 31, DayOfWeek.MONDAY, feb28, b, months, "Feb 22 is after Feb 1 (though before the new start Feb 28)")
        drops(true, 29, DayOfWeek.MONDAY, feb28, b, months, "day 29: also Feb 28 by the new day, cut Feb 1")
        drops(true, 30, DayOfWeek.MONDAY, feb28, b, months, "day 30: also Feb 28 by the new day, cut Feb 1")
    }

    @Test
    fun `from day 31 to day 1 in February the old start Jan 31 cuts`() = withDb {
        val jan5 = day(2027, 1, 5)
        assertTrue(db.setMonthStartDay(31, jan5))
        // D = 31: Jan 5 is in 2026-12 = Dec 31..Jan 30; 2027-01 = Jan 31..Feb 27.
        // Weeks (Monday): 2026-12: Dec 31–Jan 3 · Jan 4–10 · 11–17 · 18–24 · 25–30. 2027-01: Jan 31 · Feb 1–7 · 8–14 · 15–21 · 22–27.
        val months = listOf(ym(2026, 12), ym(2027, 1))
        val b = budget("Mercado", jan5, ym(2027, 1))
        val feb10 = day(2027, 2, 10)
        // 31 → 1 on Feb 10: by day 31 Jan 31, by day 1 Feb 1. Cut = Jan 31.
        edit(b, ym(2026, 12), day(2027, 1, 25))
        drops(false, 1, DayOfWeek.MONDAY, feb10, b, months, "Jan 25 is before the cut Jan 31")
        edit(b, ym(2027, 1), day(2027, 1, 31))
        drops(true, 1, DayOfWeek.MONDAY, feb10, b, months, "Jan 31 is at the cut (the new start Feb 1 alone would keep it)")
        drops(true, 31, DayOfWeek.SUNDAY, feb10, b, months, "only the week: cut Jan 31")
        drops(false, 31, DayOfWeek.MONDAY, feb10, b, months, "nothing changes")
    }

    @Test
    fun `from day 30 to day 29 on February 28 both starts are February 28`() = withDb {
        assertTrue(db.setMonthStartDay(30, day(2027, 2, 1)))
        // D = 30: Feb 1 is in 2027-01 = Jan 30..Feb 27; 2027-02 = Feb 28..Mar 29.
        // Weeks (Monday): 2027-01: Jan 30–31 · Feb 1–7 · 8–14 · 15–21 · 22–27. 2027-02: Feb 28 · Mar 1–7 · ... · Mar 29.
        val months = listOf(ym(2027, 1), ym(2027, 2))
        val b = budget("Mercado", day(2027, 2, 1), ym(2027, 2))
        val feb28 = day(2027, 2, 28)
        // 30 → 29 on Feb 28: by day 30 Feb 28; by day 29 2027-02 = Feb 28..Mar 28, also Feb 28. Cut = Feb 28.
        edit(b, ym(2027, 1), day(2027, 2, 22))
        drops(false, 29, DayOfWeek.MONDAY, feb28, b, months, "Feb 22 is before the cut Feb 28")
        // On Feb 27 the same change cuts at Jan 29 (day 29: 2027-01 = Jan 29..Feb 27; day 30: Jan 30): Feb 22 is after it.
        drops(true, 29, DayOfWeek.MONDAY, day(2027, 2, 27), b, months, "today Feb 27: cut Jan 29")
        edit(b, ym(2027, 2), day(2027, 2, 28))
        drops(true, 29, DayOfWeek.MONDAY, feb28, b, months, "Feb 28 is at the cut")
    }

    @Test
    fun `startDropsEdits agrees with what setMonthStartDay then setWeekStart delete`() = runTest(timeout = 300.seconds) {
        val seed = 5_2026_10_14L
        val rnd = Random(seed)
        val days = listOf(1, 2, 14, 15, 16, 28, 29, 30, 31)
        val todays = listOf(
            day(2026, 10, 14), day(2026, 10, 15), day(2026, 10, 20), day(2026, 12, 31), day(2027, 1, 31),
            day(2027, 2, 10), day(2027, 2, 27), day(2027, 2, 28), day(2027, 3, 1), day(2028, 2, 29),
        )
        repeat(40) { step ->
            val oldDay = days.random(rnd)
            val newDay = if (rnd.nextInt(4) == 0) oldDay else days.random(rnd)
            val oldWeek = DayOfWeek.entries.random(rnd)
            val newWeek = if (rnd.nextBoolean()) oldWeek else DayOfWeek.entries.random(rnd)
            val today = todays.random(rnd)
            val input = "seed $seed step $step: $oldDay/$oldWeek → $newDay/$newWeek on $today"
            val path = Path(SystemTemporaryDirectory, "margem-ajustes-edge-sweep-$step-${Random.nextLong().toULong()}.db").toString()
            val db = Db(BundledSQLiteDriver(), path, timeZone = TZ)
            try {
                assertTrue(db.setMonthStartDay(oldDay, today), input)
                db.setWeekStart(oldWeek, today)
                // The budget months from ~10 weeks before today to ~6 weeks after, by the old settings.
                val created = today.minus(70, DateTimeUnit.DAY)
                val months = (0..16).map { db.monthOf(created.plus(it * 7, DateTimeUnit.DAY)) }.distinct()
                val b = db.createBudget("Verba", Cents(60000), created)
                assertNotEquals(0L, b.v, input)
                db.ensureMonths(months.last())
                for (m in months) {
                    if (rnd.nextInt(10) < 4) continue
                    repeat(1 + rnd.nextInt(2)) {
                        val free = db.budgetDetail(b, m, today).month.weeks.filter { !it.edited && !it.locked }
                        if (free.size >= 2) {
                            val w = free.random(rnd)
                            assertTrue(db.setStep(b, m, w.start, Cents(100L + rnd.nextInt(500))), "$input: setStep $m ${w.start}")
                        }
                    }
                }
                suspend fun edits() = months.flatMap { m -> db.budgetDetail(b, m, today).month.weeks.filter { it.edited }.map { Triple(m, it.start, it.step.v) } }
                val e0 = edits()
                val v0 = db.version.value
                val s0 = db.settings()
                val predicted = db.startDropsEdits(newDay, newWeek, today)
                assertEquals(v0, db.version.value, "$input: reads only (version)")
                assertEquals(s0, db.settings(), "$input: reads only (settings)")
                assertEquals(e0, edits(), "$input: reads only (edits)")

                assertTrue(db.setMonthStartDay(newDay, today), input)
                db.setWeekStart(newWeek, today)
                // Back to the old settings to see the surviving edits on their own weeks. Going back cuts at the same
                // month start or later (setMonthStartDay: the earlier of old and new; setWeekStart: today's month by the
                // day then set), so it deletes nothing the forward change kept at or after its cut, and nothing before it.
                assertTrue(db.setMonthStartDay(oldDay, today), input)
                db.setWeekStart(oldWeek, today)
                val e1 = edits()
                assertTrue(e0.containsAll(e1), "$input: only deletions: $e0 → $e1")
                assertEquals(e1 != e0, predicted, "$input: predicted $predicted, edits $e0 → $e1")
            } finally {
                db.close()
                for (suffix in listOf("", "-wal", "-shm")) SystemFileSystem.delete(Path(path + suffix), mustExist = false)
            }
        }
    }

    private fun day(y: Int, m: Int, d: Int) = LocalDate(y, m, d)
    private fun ym(y: Int, m: Int) = YearMonth(y, m)

    private companion object {
        val TZ = TimeZone.of("America/Sao_Paulo")
        const val NU = "com.nu.production"
        const val OTHER = "br.com.aurora.banco"
        const val MIN = 60_000L
        const val DAY = 24 * 60 * MIN
        /** A fixed epoch ms (October 2026). */
        const val T = 1_791_297_839_102L
        /** A set Notification.when. */
        const val W = T - 5_000L
        val TODAY = LocalDate(2026, 10, 14)

        /** From the importCobudget KDoc (cobudget schema v7, Room). */
        const val V7_TABLE = "CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL, " +
            "`postedAt` INTEGER NOT NULL, `notificationWhen` INTEGER NOT NULL DEFAULT 0, `title` TEXT, `text` TEXT, " +
            "`bigText` TEXT, `subText` TEXT, `textLines` TEXT, `category` TEXT, `channelId` TEXT, " +
            "`extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL DEFAULT 0)"
    }
}
