package com.kaiodelphino.margem

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The SAF side of Backup and Importar do cobudget (D14, D40): the shared code works on local paths, so
// the chosen Uri is copied to or from a temporary file in the cache.

/** Copies [uri] into a new temporary file and returns its path; "" when it can't be read. */
suspend fun copyIn(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val file = File.createTempFile("import", ".db", context.cacheDir)
    val ok = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: error("no stream")
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
    }.isSuccess
    if (ok) file.path else "".also { file.delete() }
}

/** Writes a copy of the database (Db.exportTo) into [uri]. False when it can't be written. */
suspend fun exportInto(context: Context, db: Db, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, "export.db")
    try {
        db.exportTo(file.path) && runCatching {
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("no stream")
            output.use { o -> file.inputStream().use { it.copyTo(o) } }
        }.isSuccess
    } finally {
        file.delete()
    }
}

/** Deletes a temporary database file and what SQLite may have left beside it. Blank: nothing. */
fun deleteTemp(path: String) {
    if (path.isBlank()) return
    for (suffix in listOf("", "-wal", "-shm", "-journal")) File(path + suffix).delete()
}
