package com.kaiodelphino.margem.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.R
import com.kaiodelphino.margem.accessSettingsIntent
import com.kaiodelphino.margem.widgets.CapturePaused
import com.kaiodelphino.margem.widgets.Check
import com.kaiodelphino.margem.widgets.Icon
import com.kaiodelphino.margem.widgets.ListRow
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.SectionLine
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.sans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Ajustes (spec §7.6). This block has Fontes; the rest of the items comes in block 5. */
@Composable
fun Settings(db: Db, granted: Boolean, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val sources = query(db) { it.sources() }
    Column {
        TopBar("Ajustes")
        Column(Modifier.padding(horizontal = Margem.space.page)) {
            if (!granted) CapturePaused({ context.startActivity(accessSettingsIntent(context)) }, Modifier.padding(top = 8.dp, bottom = 8.dp))
            SectionLine("Anotação", Modifier.padding(top = 0.dp))
            ListRow(
                "Fontes", sub = sources?.joinToString(", ") { it.label }?.ifEmpty { "nenhuma fonte escolhida" }.orEmpty(),
                onClick = { go(Screen.Sources) },
            ) { Icon(R.drawable.i_avancar, tint = Margem.colors.inkTertiary, size = 20.dp) }
        }
    }
}

/** An app with a launcher icon (Ajustes › Fontes). */
class LauncherApp(val packageName: String, val label: String)

/**
 * Apps with a launcher icon, by label. Visibility comes from the LAUNCHER `<queries>` entry in the
 * manifest, so no QUERY_ALL_PACKAGES permission is needed.
 */
suspend fun launcherApps(context: Context): List<LauncherApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { LauncherApp(it.activityInfo.packageName, it.activityInfo.applicationInfo.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
}

/**
 * Ajustes › Fontes: the apps whose notifications become notices. Chosen ones first; a source whose
 * app is gone still shows, so it can be unchecked.
 */
@Composable
fun Sources(db: Db, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = produceState<List<LauncherApp>?>(null) { value = launcherApps(context) }.value
    val sources = query(db) { it.sources() }
    Column {
        TopBar("Fontes", onBack = back)
        LazyColumn(contentPadding = PaddingValues(start = Margem.space.page, end = Margem.space.page, bottom = 24.dp)) {
            item {
                Text(
                    "Bancos, cartões e carteiras cujas notificações viram recados na Pauta. As outras notificações não são lidas.",
                    sans(15f, 400, 22f), Modifier.padding(bottom = 8.dp), Margem.colors.inkSecondary,
                )
            }
            if (apps == null || sources == null) return@LazyColumn
            val chosen = sources.map { it.packageName }.toSet()
            val gone = sources.filter { s -> apps.none { it.packageName == s.packageName } }.map { LauncherApp(it.packageName, it.label) }
            val all = (apps + gone).sortedWith(compareBy({ it.packageName !in chosen }, { it.label.lowercase() }))
            items(all, key = { it.packageName }) { app ->
                val on = app.packageName in chosen
                ListRow(app.label, sub = app.packageName, onClick = {
                    scope.launch {
                        if (on) db.removeSource(app.packageName)
                        else db.addSource(app.packageName, app.label, Clock.System.now().toEpochMilliseconds())
                    }
                }) { Check(on) }
            }
        }
    }
}
