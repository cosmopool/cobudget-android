package com.kaiodelphino.cobudget

import android.app.Application
import com.kaiodelphino.cobudget.data.AppDatabase

class CoBudgetApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.build(this) }
}
