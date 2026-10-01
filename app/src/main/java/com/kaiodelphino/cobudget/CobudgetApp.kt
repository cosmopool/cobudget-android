package com.kaiodelphino.cobudget

import android.app.Application
import com.kaiodelphino.cobudget.data.AppDatabase

class CobudgetApp : Application() {
    private var dbRef: AppDatabase? = null

    val db: AppDatabase
        @Synchronized get() = dbRef ?: AppDatabase.build(this).also { dbRef = it }

    /** Closes the database so the next [db] access rebuilds it (used after a backup import). */
    @Synchronized fun resetDb() {
        dbRef?.close()
        dbRef = null
    }
}
