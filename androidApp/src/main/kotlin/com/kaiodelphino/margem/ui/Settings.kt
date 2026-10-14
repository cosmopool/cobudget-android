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
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.SectionLine
import com.kaiodelphino.margem.widgets.Text
import com.kaiodelphino.margem.widgets.TopBar
import com.kaiodelphino.margem.widgets.sans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Test tags of Ajustes and its sub-screens (spec §7.6, book 10.9, D35): the contract of their UI tests.
 * Same setup and rules as [BudgetTags]: `App(db, null) {}` in `MargemTheme(dark = false)` with
 * `LocalToday` fixed, a Db on AndroidSQLiteDriver (D28), elements found only by tags. Ajustes is reached
 * by the tab bar (Tab.SETTINGS) or by `App`'s public `tabRequest` (Screen.Settings); each sub-screen by
 * its row here, and back by the top bar's back arrow or the system back, to Ajustes.
 *
 * Files (Backup, cobudget) are picked through the Storage Access Framework with
 * `rememberLauncherForActivityResult`: `ActivityResultContracts.CreateDocument("application/octet-stream")`
 * to export, `ActivityResultContracts.OpenDocument()` (any MIME type) to import. Tests answer
 * the picker with their own registry, `CompositionLocalProvider(LocalActivityResultRegistryOwner provides …)`,
 * returning a `file://` Uri of a temporary file (`Uri.fromFile`), or null for a cancelled pick (nothing
 * happens). The app reads and writes that Uri through `ContentResolver`.
 *
 * Snacks go through the app-wide snackbar ([com.kaiodelphino.margem.widgets.SnackTags]).
 */
object SettingsTags {
    // ---- Ajustes (book 10.9) ----

    /** The scrollable page. Plain container. */
    const val LIST = "settings.list"

    /** "A anotação automática está pausada.", first on the page, only while notification access is off. */
    const val PAUSED = "settings.paused"

    /**
     * "Fontes", with the labels of the sources (Db.sources order) joined by ", ", or "nenhuma fonte
     * escolhida". Click opens Fontes.
     */
    const val SOURCES = "settings.sources"

    /** "Riscados" · "trazer recados de volta à pauta". Click opens Riscados. */
    const val DISMISSED = "settings.dismissed"

    /** "Marcadores" · "renomear, juntar, apagar". Click opens Marcadores. */
    const val TAGS = "settings.tags"

    /**
     * "Início do mês e da semana" · "dia 5 · segunda" (Settings.monthStartDay and the weekStart in
     * lowercase, `weekdayLong`: segunda, terça, quarta, quinta, sexta, sábado, domingo). Click opens it.
     */
    const val START_DAYS = "settings.start"

    /**
     * Prefix: THEME + "system" / "light" / "dark": "sistema" · "claro" · "escuro". The one `selected` is
     * Settings.theme; click saves it (Db.setTheme) and the app follows.
     */
    const val THEME = "settings.theme."

    /**
     * Prefix: WIDGET_ORDER + "risk" / "manual" / "az": "risco" · "manual" · "A–Z". The one `selected` is
     * Settings.widgetOrder; click saves it (Db.setWidgetOrder).
     */
    const val WIDGET_ORDER = "settings.widget."

    /** "Backup" · "exportar · importar". Click opens Backup. */
    const val BACKUP = "settings.backup"

    /** "Importar do cobudget" · "recados de um backup do app antigo". Click opens Importar do cobudget. */
    const val COBUDGET = "settings.cobudget"

    // ---- Riscados (spec §2.3, §7.6) ----

    /** The scrollable list of dismissed notices. Plain container. */
    const val DISMISSED_LIST = "dismissed.list"

    /** "Nenhum recado riscado.", instead of the cards when nothing is dismissed. */
    const val DISMISSED_EMPTY = "dismissed.empty"

    /**
     * Prefix: DISMISSED_DAY + the date ("dismissed.day.2026-10-09"), one per day with dismissed notices,
     * newest first, titled like the Pauta's (InboxTags.DAY, [dayTitle]).
     */
    const val DISMISSED_DAY = "dismissed.day."

    /**
     * Prefix: DISMISSED_CARD + notice id, one per dismissed notice (Db.dismissed order: newest posted
     * first), under its day. Shows what the Pauta card shows (InboxTags.CARD): source, time, bank text
     * and, unless it looks like an ad, merchant and value.
     */
    const val DISMISSED_CARD = "dismissed.card."

    /**
     * Prefix: RESTORE + notice id: "Trazer de volta". Click restores it (Db.restore): the card leaves the
     * list and the notice is pending in the Pauta. No snack, no Desfazer.
     */
    const val RESTORE = "dismissed.restore."

    // ---- Marcadores (spec §2.9, §7.6) ----

    /** The scrollable list of tags. Plain container. */
    const val TAGS_LIST = "tags.list"

    /** "Nenhum marcador." with "Marcadores nascem ao lançar.", instead of the rows when there is no tag. */
    const val TAGS_EMPTY = "tags.empty"

    /**
     * Prefix: TAG_ROW + tag id, one per tag in Db.tags order (by name): the name and "1 lançamento",
     * "3 lançamentos" or "nenhum lançamento" (TagRow.uses). Click opens [TAG_DIALOG] for it.
     */
    const val TAG_ROW = "tags.row."

    /** The tag's dialog. Plain container: [TAG_NAME], [TAG_SAVE], [TAG_DELETE], [TAG_CANCEL]. */
    const val TAG_DIALOG = "tags.dialog"

    /** The name, a text field filled with the tag's name. */
    const val TAG_NAME = "tags.name"

    /**
     * "Salvar", or "Juntar com mercado" when the typed name (trimmed, lowercased) is another tag's name.
     * Disabled while the typed name is blank or the tag's own name. Click renames (Db.renameTag) or merges
     * this tag into the other (Db.mergeTags: its transactions get the other tag, it disappears) and closes
     * the dialog.
     */
    const val TAG_SAVE = "tags.save"

    /** "Apagar": closes the tag's dialog and opens [TAG_DELETE_DIALOG]. */
    const val TAG_DELETE = "tags.delete"

    /** "Cancelar": closes the dialog, nothing written. */
    const val TAG_CANCEL = "tags.cancel"

    /**
     * MargemDialog "Apagar o marcador padaria?", with "3 lançamentos ficam sem marcador e são apagados; os
     * recados deles voltam à Pauta." ("1 lançamento fica sem marcador e é apagado; o recado dele volta à
     * Pauta.") when TagRow.soleUses > 0, else "Nenhum lançamento é apagado.". "$TAG_DELETE_DIALOG.confirm"
     * ("Apagar") deletes it (Db.deleteTag); "$TAG_DELETE_DIALOG.cancel" closes, nothing written.
     */
    const val TAG_DELETE_DIALOG = "tags.deleteDialog"

    // ---- Início do mês e da semana (spec §4, §7.6, book 8.6, D41) ----

    /**
     * Stepper "Mês começa no dia" (1..31): the number; "$START_DAY.minus" and "$START_DAY.plus" change it
     * by one (disabled at 1 and 31). Starts at Settings.monthStartDay. A draft until [START_SAVE].
     */
    const val START_DAY = "start.day"

    /**
     * Prefix: START_WEEK + DayOfWeek name in lowercase ("start.week.monday"): "Semana começa na"; the
     * `selected` one is the draft, starting at Settings.weekStart. Click changes the draft only.
     */
    const val START_WEEK = "start.week."

    /**
     * "Salvar". Disabled while the draft equals the saved settings. Click: when Db.startDropsEdits is
     * true, opens [START_DIALOG]; otherwise saves (Db.setMonthStartDay, then Db.setWeekStart) and goes
     * back to Ajustes.
     */
    const val START_SAVE = "start.save"

    /**
     * MargemDialog "Mudar o início do mês?" ("…da semana?", "…do mês e da semana?", by what the draft
     * changes), with "As edições de passo de outubro em diante são apagadas, em todas as verbas, e os
     * passos voltam ao padrão. Meses passados são recalculados com o dia novo." (the month of today by
     * the saved settings, `monthLong` in lowercase). "$START_DIALOG.confirm" ("Mudar") saves like
     * [START_SAVE] and goes back to Ajustes; "$START_DIALOG.cancel" closes it, nothing written, the draft kept.
     */
    const val START_DIALOG = "start.dialog"

    // ---- Backup (spec §7.6, D14) ----

    /**
     * "Exportar": opens the picker to create a file named "margem-2026-10-14.db" (LocalToday). Then the
     * file gets a copy of the database (Db.exportTo) and the snack "Backup exportado." appears, or
     * "Não deu para exportar o backup." when it can't be written.
     */
    const val BACKUP_EXPORT = "backup.export"

    /**
     * "Importar": opens the picker to choose a file, then [BACKUP_DIALOG]. Nothing changes before the
     * dialog is confirmed.
     */
    const val BACKUP_IMPORT = "backup.import"

    /**
     * MargemDialog "Restaurar este backup?" with "Verbas, lançamentos, recados e ajustes atuais dão lugar
     * aos do backup. Não dá para desfazer.". "$BACKUP_DIALOG.confirm" ("Restaurar") replaces the database
     * (Db.importFrom) and shows the snack "Backup restaurado.", or "Este arquivo não é um backup do Margem."
     * with the database untouched; "$BACKUP_DIALOG.cancel" closes it, nothing written.
     */
    const val BACKUP_DIALOG = "backup.dialog"

    // ---- Importar do cobudget (spec §7.6, D3, D40) ----

    /** "Escolher arquivo": opens the picker; the chosen file goes through importCobudget. */
    const val COBUDGET_PICK = "cobudget.pick"

    /**
     * Shown after an import, until the screen is left: for a refused file, "Este arquivo não é um backup
     * do cobudget."; otherwise "312 recados importados: 280 na Pauta, 32 em Riscados." ("1 recado
     * importado: 1 na Pauta, 0 em Riscados.", "Nenhum recado importado." when both are 0), followed by
     * " 40 não entraram (repetidos ou sem texto)." ("1 não entrou (repetido ou sem texto).") when
     * CobudgetImport.skipped > 0. Absent before the first import.
     */
    const val COBUDGET_RESULT = "cobudget.result"
}

/** Ajustes (spec §7.6, book 10.9): [SettingsTags]. */
@Composable
fun Settings(db: Db, granted: Boolean, go: (Screen) -> Unit): Unit = TODO()

/** Ajustes › Riscados: [SettingsTags.DISMISSED_LIST] and the rest of that section. */
@Composable
fun Dismissed(db: Db, back: () -> Unit): Unit = TODO()

/** Ajustes › Marcadores: [SettingsTags.TAGS_LIST] and the rest of that section. */
@Composable
fun Tags(db: Db, back: () -> Unit): Unit = TODO()

/** Ajustes › Início do mês e da semana: [SettingsTags.START_DAY] and the rest of that section. */
@Composable
fun StartDays(db: Db, back: () -> Unit): Unit = TODO()

/** Ajustes › Backup: [SettingsTags.BACKUP_EXPORT] and the rest of that section. */
@Composable
fun BackupScreen(db: Db, snack: SnackHost, back: () -> Unit): Unit = TODO()

/** Ajustes › Importar do cobudget: [SettingsTags.COBUDGET_PICK] and [SettingsTags.COBUDGET_RESULT]. */
@Composable
fun CobudgetScreen(db: Db, back: () -> Unit): Unit = TODO()

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
