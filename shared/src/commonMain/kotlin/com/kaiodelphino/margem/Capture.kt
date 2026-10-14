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
suspend fun capture(db: Db, raw: RawNotice): Boolean = TODO()
