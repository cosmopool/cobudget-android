package com.kaiodelphino.margem.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kaiodelphino.margem.BudgetId
import com.kaiodelphino.margem.Db
import com.kaiodelphino.margem.MargemApp
import com.kaiodelphino.margem.NoticeId
import com.kaiodelphino.margem.Theme
import com.kaiodelphino.margem.TransactionId
import com.kaiodelphino.margem.isAccessGranted
import com.kaiodelphino.margem.widgets.Margem
import com.kaiodelphino.margem.widgets.MargemTheme
import com.kaiodelphino.margem.widgets.SnackHost
import com.kaiodelphino.margem.widgets.SnackbarHost
import com.kaiodelphino.margem.widgets.Tab
import com.kaiodelphino.margem.widgets.TabBar
import com.kaiodelphino.margem.widgets.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Intent extra with the tab to open ("budgets", "inbox", …): how the widgets open the app (§7). */
const val EXTRA_TAB = "tab"

/** Navigation (D18): a stack of these, a `when` and BackHandler. */
sealed interface Screen {
    data object Budgets : Screen
    data object Inbox : Screen
    data object Transactions : Screen
    data object Insights : Screen
    data object Settings : Screen
    data class Budget(val id: BudgetId, val month: YearMonth) : Screen
    data class Post(val notice: NoticeId) : Screen
    data class Edit(val id: TransactionId) : Screen
    data object NewTransaction : Screen
    data object Dismissed : Screen
    data object Sources : Screen
}

private fun encode(s: Screen): String = when (s) {
    Screen.Budgets -> "budgets"
    Screen.Inbox -> "inbox"
    Screen.Transactions -> "transactions"
    Screen.Insights -> "insights"
    Screen.Settings -> "settings"
    is Screen.Budget -> "budget:${s.id.v}:${s.month}"
    is Screen.Post -> "post:${s.notice.v}"
    is Screen.Edit -> "edit:${s.id.v}"
    Screen.NewTransaction -> "new"
    Screen.Dismissed -> "dismissed"
    Screen.Sources -> "sources"
}

/** The inverse of [encode]; anything unknown is Verbas. */
private fun decode(s: String): Screen {
    val p = s.split(':')
    return when (p[0]) {
        "inbox" -> Screen.Inbox
        "transactions" -> Screen.Transactions
        "insights" -> Screen.Insights
        "settings" -> Screen.Settings
        "budget" -> Screen.Budget(BudgetId(p[1].toLong()), YearMonth.parse(p[2]))
        "post" -> Screen.Post(NoticeId(p[1].toLong()))
        "edit" -> Screen.Edit(TransactionId(p[1].toLong()))
        "new" -> Screen.NewTransaction
        "dismissed" -> Screen.Dismissed
        "sources" -> Screen.Sources
        else -> Screen.Budgets
    }
}

private val StackSaver = Saver<List<Screen>, ArrayList<String>>(
    save = { stack -> ArrayList(stack.map(::encode)) },
    restore = { saved -> saved.map(::decode) },
)

private fun tabOf(s: Screen): Tab = when (s) {
    Screen.Inbox, is Screen.Post -> Tab.INBOX
    Screen.Transactions, is Screen.Edit, Screen.NewTransaction -> Tab.TRANSACTIONS
    Screen.Insights -> Tab.INSIGHTS
    Screen.Settings, Screen.Dismissed, Screen.Sources -> Tab.SETTINGS
    Screen.Budgets, is Screen.Budget -> Tab.BUDGETS
}

private fun screenOf(t: Tab): Screen = when (t) {
    Tab.BUDGETS -> Screen.Budgets
    Tab.INBOX -> Screen.Inbox
    Tab.TRANSACTIONS -> Screen.Transactions
    Tab.INSIGHTS -> Screen.Insights
    Tab.SETTINGS -> Screen.Settings
}

/**
 * Data on screen (D19): runs [block] on IO now and again whenever `db.version` changes (or [keys]).
 * Null until the first result; afterwards the previous result stays while the next one loads.
 */
@Composable
fun <T> query(db: Db, vararg keys: Any?, block: suspend (Db) -> T): T? {
    val version by db.version.collectAsState()
    return produceState<T?>(null, version, *keys) { value = withContext(Dispatchers.IO) { block(db) } }.value
}

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

class MainActivity : ComponentActivity() {
    private var tabRequest by mutableStateOf<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = (application as MargemApp).db
        if (savedInstanceState == null) tabRequest = intent.getStringExtra(EXTRA_TAB)?.let(::decode)
        // One small read on start so the first frame already has the chosen theme.
        val initialTheme = runBlocking { db.settings().theme }
        setContent {
            val theme = query(db) { it.settings().theme } ?: initialTheme
            val dark = when (theme) {
                Theme.SYSTEM -> isSystemInDarkTheme()
                Theme.LIGHT -> false
                Theme.DARK -> true
            }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            MargemTheme(dark) { App(db, tabRequest) { tabRequest = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabRequest = intent.getStringExtra(EXTRA_TAB)?.let(::decode)
    }
}

@Composable
fun App(db: Db, tabRequest: Screen?, onTabOpened: () -> Unit) {
    val context = LocalContext.current
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Budgets)) }
    var granted by remember { mutableStateOf(isAccessGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = isAccessGranted(context) }
    LaunchedEffect(tabRequest) {
        if (tabRequest == null) return@LaunchedEffect
        stack = listOf(tabRequest)
        onTabOpened()
    }
    val snack = remember { SnackHost() }
    val pending = query(db) { it.pendingCount() } ?: 0

    val screen = stack.last()
    val go: (Screen) -> Unit = { stack = stack + it }
    val back: () -> Unit = { stack = if (stack.size > 1) stack.dropLast(1) else listOf(Screen.Budgets) }
    // Back from another tab's root goes to Verbas; from Verbas it leaves the app.
    BackHandler(stack.size > 1 || screen != Screen.Budgets, back)
    // Forms (lançar, editar, novo) take the whole page; everything else keeps the tab bar.
    val tabs = screen !is Screen.Post && screen !is Screen.Edit && screen != Screen.NewTransaction

    Column(Modifier.fillMaxSize().background(Margem.colors.paper).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.Budgets -> Budgets(db, go)
                Screen.Inbox -> Inbox(db, granted, snack, go)
                Screen.Transactions -> Transactions(db, go)
                Screen.Insights -> Insights(db)
                Screen.Settings -> Settings(db, granted, go)
                Screen.Sources -> Sources(db, back)
                // Next blocks: 3 (Post, Dismissed), 4 (Budget, Edit, NewTransaction).
                is Screen.Budget, is Screen.Post, is Screen.Edit, Screen.NewTransaction, Screen.Dismissed ->
                    TopBar("", onBack = back)
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
        if (tabs) TabBar(tabOf(screen), { stack = listOf(screenOf(it)) }, badge = pending)
        else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
