package com.kaiodelphino.margem

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Re-posts of the same notification usually arrive seconds apart; real repeat purchases rarely do. */
const val DUPLICATE_WINDOW_MS = 2 * 60 * 1000L

/**
 * Anotação automática (D25): saves [raw] as a notice unless it is noise or a re-post. True if saved.
 * - Noise: no title, text, big text, sub text nor lines; or a package that is not a source.
 * - Re-post: a notice with the same key, content and `when` (≠ 0), however much later; or the
 *   latest notice with the same key has the same content and was posted less than
 *   [DUPLICATE_WINDOW_MS] apart.
 * Content = title, text, big text, sub text and lines (content_hash, D32).
 */
suspend fun capture(db: Db, raw: RawNotice): Boolean {
    val parts = listOf(raw.title, raw.text, raw.bigText, raw.subText, raw.textLines)
    if (parts.all(String::isBlank)) return false
    val hash = contentHash(parts)
    return db.write { c ->
        if (c.long("SELECT COUNT(*) FROM sources WHERE package = ?", raw.packageName) == 0L) return@write false
        if (isRepost(c, raw, hash)) return@write false
        insertNotice(c, raw, hash, dismissed = false)
        true
    }
}

/** The dedupe of [capture] (D4): same key, content and `when`; or the latest of the key with the same content, posted close. */
private fun isRepost(c: SQLiteConnection, raw: RawNotice, hash: String): Boolean {
    // when = 0 means "not set": matching on it would merge unrelated posts forever.
    if (raw.whenMs != 0L &&
        c.long("SELECT EXISTS(SELECT 1 FROM notices WHERE key = ? AND content_hash = ? AND when_ms = ?)", raw.key, hash, raw.whenMs) == 1L
    ) return true
    val latest = c.query("SELECT content_hash, posted_at FROM notices WHERE key = ? ORDER BY posted_at DESC, id DESC LIMIT 1", raw.key) {
        it.getText(0) to it.getLong(1)
    }.firstOrNull()
    return latest != null && latest.first == hash && abs(raw.postedAt - latest.second) < DUPLICATE_WINDOW_MS
}

private fun insertNotice(c: SQLiteConnection, raw: RawNotice, hash: String, dismissed: Boolean) = c.exec(
    "INSERT INTO notices(package, app_label, key, posted_at, when_ms, title, text, big_text, sub_text, text_lines, " +
        "category, channel_id, extras_json, content_hash, dismissed) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    raw.packageName, raw.appLabel, raw.key, raw.postedAt, raw.whenMs, raw.title, raw.text, raw.bigText,
    raw.subText, raw.textLines, raw.category, raw.channelId, raw.extrasJson, hash, dismissed,
)

/**
 * Importar do cobudget (spec §7.6, D3, D4, D40): adds the notices of the cobudget backup at [path] to
 * [db]. Only notices: sources, tags, nicknames, transactions and the genda outbox are not read.
 *
 * Refused (`CobudgetImport(opened = false)`, nothing written, [db] untouched) unless [path] is an SQLite
 * file with `PRAGMA user_version` = 7 and a `captured_notifications` table with the columns below. The
 * file is only read.
 *
 * Each row, in postedAt order (then id), becomes a [RawNotice]: packageName, appLabel, notificationKey →
 * key, postedAt, notificationWhen → whenMs, title, text, bigText, subText, textLines, category,
 * channelId, extrasJson; NULL text is "". Its `contentHash` and `id` are ignored: the hash is recomputed
 * (D32). Then the rules of [capture], except the source one (notices of apps that are not sources enter
 * too): no text at all, or a re-post of a notice already in [db] or imported before it in this same
 * call, is skipped. A notice that enters keeps the cobudget's `dismissed` (1 = Riscados, 0 = pending;
 * a transaction it had in the cobudget doesn't matter).
 *
 * One write for the whole file: bumps `version` once when something entered. Importing the same file
 * again adds nothing (every notice is a re-post).
 *
 * The cobudget schema v7 table read (Room; the other tables of the file are ignored):
 * ```sql
 * CREATE TABLE `captured_notifications` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 *   `packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `notificationKey` TEXT NOT NULL,
 *   `postedAt` INTEGER NOT NULL, `notificationWhen` INTEGER NOT NULL DEFAULT 0, `title` TEXT, `text` TEXT,
 *   `bigText` TEXT, `subText` TEXT, `textLines` TEXT, `category` TEXT, `channelId` TEXT,
 *   `extrasJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `dismissed` INTEGER NOT NULL DEFAULT 0)
 * ```
 */
suspend fun importCobudget(db: Db, path: String): CobudgetImport {
    val rows = withContext(Dispatchers.IO) { readCobudget(db.driver, path) } ?: return CobudgetImport()
    return db.write { c ->
        var pending = 0
        var dismissed = 0
        var skipped = 0
        for ((raw, gone) in rows) {
            val parts = listOf(raw.title, raw.text, raw.bigText, raw.subText, raw.textLines)
            val hash = contentHash(parts)
            if (parts.all(String::isBlank) || isRepost(c, raw, hash)) {
                skipped++
                continue
            }
            insertNotice(c, raw, hash, gone)
            if (gone) dismissed++ else pending++
        }
        CobudgetImport(opened = true, pending = pending, dismissed = dismissed, skipped = skipped)
    }
}

private val COBUDGET_COLUMNS = listOf(
    "packageName", "appLabel", "notificationKey", "postedAt", "notificationWhen", "title", "text", "bigText",
    "subText", "textLines", "category", "channelId", "extrasJson", "dismissed",
)

/** The notices of a cobudget v7 file with their dismissed flag, by postedAt then id; null when it isn't one. */
private fun readCobudget(driver: SQLiteDriver, path: String): List<Pair<RawNotice, Boolean>>? {
    if (!SystemFileSystem.exists(Path(path))) return null
    return runCatching {
        val c = driver.open(path)
        try {
            if (c.long("PRAGMA user_version") != 7L) return null
            val columns = c.query("PRAGMA table_info(captured_notifications)") { it.getText(1) }
            if (!columns.containsAll(COBUDGET_COLUMNS)) return null
            c.query("SELECT ${COBUDGET_COLUMNS.joinToString()} FROM captured_notifications ORDER BY postedAt, id") {
                fun text(i: Int) = if (it.isNull(i)) "" else it.getText(i)
                RawNotice(
                    packageName = text(0), appLabel = text(1), key = text(2), postedAt = it.getLong(3), whenMs = it.getLong(4),
                    title = text(5), text = text(6), bigText = text(7), subText = text(8), textLines = text(9),
                    category = text(10), channelId = text(11), extrasJson = text(12),
                ) to (it.getLong(13) != 0L)
            }
        } finally {
            c.close()
        }
    }.getOrNull()
}


/** SHA-256 of the parts, each as its byte length (4 bytes, big-endian) then its UTF-8 bytes, as lowercase hex (D32). */
internal fun contentHash(parts: List<String>): String {
    var size = 0
    val encoded = parts.map { it.encodeToByteArray().also { b -> size += 4 + b.size } }
    val bytes = ByteArray(size)
    var at = 0
    for (b in encoded) {
        for (i in 0 until 4) bytes[at + i] = (b.size ushr (24 - 8 * i)).toByte()
        b.copyInto(bytes, at + 4)
        at += 4 + b.size
    }
    return sha256(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

private val K = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)

/** FIPS 180-4 SHA-256. */
internal fun sha256(message: ByteArray): ByteArray {
    val h = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
    // Padding: 0x80, zeros, then the bit length as a 64-bit big-endian number; total a multiple of 64.
    val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    val bits = message.size.toLong() * 8
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()

    val w = IntArray(64)
    for (block in 0 until padded.size / 64) {
        for (t in 0 until 16) {
            val o = block * 64 + t * 4
            w[t] = (padded[o].toInt() and 0xff shl 24) or (padded[o + 1].toInt() and 0xff shl 16) or
                (padded[o + 2].toInt() and 0xff shl 8) or (padded[o + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
    return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
}
