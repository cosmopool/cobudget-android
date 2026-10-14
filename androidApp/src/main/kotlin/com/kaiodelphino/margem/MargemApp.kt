package com.kaiodelphino.margem

import android.app.Application
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Owns the one Db of the process (architecture §2), used by the Activity, the listener and the widgets. */
class MargemApp : Application() {
    lateinit var db: Db
        private set

    override fun onCreate() {
        super.onCreate()
        // onWrite → Glance updateAll comes with the widgets (block 7, D21).
        db = Db(BundledSQLiteDriver(), getDatabasePath("margem.db").path, onWrite = {})
    }
}
