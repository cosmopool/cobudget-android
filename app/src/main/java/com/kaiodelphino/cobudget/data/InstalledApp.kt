package com.kaiodelphino.cobudget.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

/**
 * Lists apps that have a launcher icon. Visibility comes from the LAUNCHER `<queries>` entry
 * in the manifest, so no QUERY_ALL_PACKAGES permission is needed.
 */
suspend fun loadLauncherApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    activities
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { InstalledApp(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
}
