package com.kaiodelphino.margem

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
        val label = c.query("SELECT label FROM sources WHERE package = ?", raw.packageName) { it.getText(0) }.firstOrNull()
            ?: return@write false
        // when = 0 means "not set": matching on it would merge unrelated posts forever.
        if (raw.whenMs != 0L &&
            c.long("SELECT EXISTS(SELECT 1 FROM notices WHERE key = ? AND content_hash = ? AND when_ms = ?)", raw.key, hash, raw.whenMs) == 1L
        ) return@write false
        val latest = c.query("SELECT content_hash, posted_at FROM notices WHERE key = ? ORDER BY posted_at DESC, id DESC LIMIT 1", raw.key) {
            it.getText(0) to it.getLong(1)
        }.firstOrNull()
        if (latest != null && latest.first == hash && kotlin.math.abs(raw.postedAt - latest.second) < DUPLICATE_WINDOW_MS) return@write false
        c.exec(
            "INSERT INTO notices(package, app_label, key, posted_at, when_ms, title, text, big_text, sub_text, text_lines, " +
                "category, channel_id, extras_json, content_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            raw.packageName, raw.appLabel.ifBlank { label }, raw.key, raw.postedAt, raw.whenMs, raw.title, raw.text, raw.bigText,
            raw.subText, raw.textLines, raw.category, raw.channelId, raw.extrasJson, hash,
        )
        true
    }
}

/** SHA-256 of the parts, each followed by a 0 byte, as lowercase hex: the same hash as cobudget's (D32). */
internal fun contentHash(parts: List<String>): String {
    var size = 0
    val encoded = parts.map { it.encodeToByteArray().also { b -> size += b.size + 1 } }
    val bytes = ByteArray(size)
    var at = 0
    for (b in encoded) {
        b.copyInto(bytes, at)
        at += b.size + 1
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
