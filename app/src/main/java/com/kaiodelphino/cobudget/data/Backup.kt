package com.kaiodelphino.cobudget.data

import com.kaiodelphino.cobudget.CobudgetApp
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies the live database file out. Checkpoints the write-ahead log first so
 * the main file alone is a complete backup.
 */
suspend fun exportBackup(db: AppDatabase, output: OutputStream) {
    withContext(Dispatchers.IO) {
        // Force the checkpoint to run: the cursor is lazy, so it must be consumed and closed.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.count }
        val path = db.openHelper.writableDatabase.path ?: error("database has no file")
        File(path).inputStream().use { input -> output.use { input.copyTo(it) } }
    }
}

/**
 * Replaces the live database with a backup. Closes the open database first;
 * the next [CobudgetApp.db] access rebuilds it from the restored file.
 * Callers must drop existing DAO references (e.g. recreate the activity).
 */
suspend fun importBackup(app: CobudgetApp, input: InputStream) {
    withContext(Dispatchers.IO) {
        app.resetDb()
        val file = app.getDatabasePath(AppDatabase.DB_NAME)
        input.use { input.copyTo(file.outputStream()) }
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }
}
