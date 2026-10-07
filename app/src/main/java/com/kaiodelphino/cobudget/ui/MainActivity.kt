package com.kaiodelphino.cobudget.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import com.kaiodelphino.cobudget.CobudgetApp
import com.kaiodelphino.cobudget.data.CobudgetDao
import com.kaiodelphino.cobudget.capture.NotificationCaptureService
import com.kaiodelphino.cobudget.ui.access.AccessBanner
import com.kaiodelphino.cobudget.ui.apps.AppsScreen
import com.kaiodelphino.cobudget.ui.apps.AppsViewModel
import com.kaiodelphino.cobudget.ui.messages.MessagesScreen
import com.kaiodelphino.cobudget.ui.messages.MessagesViewModel
import com.kaiodelphino.cobudget.ui.settings.SettingsScreen
import com.kaiodelphino.cobudget.ui.settings.SettingsViewModel
import com.kaiodelphino.cobudget.ui.theme.CobudgetTheme
import com.kaiodelphino.cobudget.ui.transactions.TransactionPage
import com.kaiodelphino.cobudget.ui.transactions.TransactionPageViewModel
import com.kaiodelphino.cobudget.ui.transactions.TransactionsScreen
import com.kaiodelphino.cobudget.ui.transactions.TransactionsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = (application as CobudgetApp).db.dao()
        val factory = viewModelFactory {
            initializer { MessagesViewModel(dao) }
            initializer { TransactionsViewModel(dao) }
            initializer { AppsViewModel(dao, application) }
            initializer { SettingsViewModel(application as CobudgetApp) }
        }
        setContent {
            CobudgetTheme {
                MainScreen(
                    dao = dao,
                    messagesViewModel = viewModel(factory = factory),
                    transactionsViewModel = viewModel(factory = factory),
                    appsViewModel = viewModel(factory = factory),
                    settingsViewModel = viewModel(factory = factory),
                )
            }
        }
    }
}

private enum class Tab(val label: String) { Messages("Messages"), Transactions("Transactions"), Apps("Apps"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    dao: CobudgetDao,
    messagesViewModel: MessagesViewModel,
    transactionsViewModel: TransactionsViewModel,
    appsViewModel: AppsViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.Messages) }
    // The full-screen page over the tabs: accept a pending notification, or edit a transaction. 0 = none.
    var acceptId by rememberSaveable { mutableStateOf(0L) }
    var editId by rememberSaveable { mutableStateOf(0L) }
    // Bumped on every open so each visit gets a fresh page ViewModel; reusing one by id would bring
    // back its finished state (done = true) and close the page as soon as it opens.
    var pageVisit by rememberSaveable { mutableStateOf(0) }
    val onPage = acceptId != 0L || editId != 0L
    fun closePage() {
        acceptId = 0
        editId = 0
    }
    BackHandler(enabled = onPage) { closePage() }
    var accessGranted by rememberSaveable { mutableStateOf(NotificationCaptureService.isAccessGranted(context)) }

    // The user grants access in system settings, so re-check whenever we come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessGranted = NotificationCaptureService.isAccessGranted(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (acceptId != 0L) "Accept" else if (editId != 0L) "Transaction" else "Cobudget") },
                navigationIcon = {
                    if (onPage) IconButton(onClick = ::closePage) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            if (!onPage) NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Messages,
                    onClick = { tab = Tab.Messages },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    label = { Text(Tab.Messages.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Transactions,
                    onClick = { tab = Tab.Transactions },
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                    label = { Text(Tab.Transactions.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Apps,
                    onClick = { tab = Tab.Apps },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(Tab.Apps.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(Tab.Settings.label) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (!accessGranted) {
                AccessBanner(onGrant = { context.startActivity(NotificationCaptureService.accessSettingsIntent(context)) })
            }
            when {
                onPage -> {
                    val vm: TransactionPageViewModel = viewModel(
                        key = "page-$pageVisit",
                        factory = viewModelFactory { initializer { TransactionPageViewModel(dao, acceptId, editId) } },
                    )
                    TransactionPage(vm, onDone = ::closePage)
                }
                tab == Tab.Messages -> {
                    val state by messagesViewModel.state.collectAsStateWithLifecycle()
                    MessagesScreen(
                        state,
                        onSelectApp = messagesViewModel::select,
                        onDismiss = messagesViewModel::dismiss,
                        onOpen = { acceptId = it; pageVisit++ },
                    )
                }
                tab == Tab.Transactions -> {
                    val rows by transactionsViewModel.rows.collectAsStateWithLifecycle()
                    val nicknames by transactionsViewModel.nicknames.collectAsStateWithLifecycle()
                    TransactionsScreen(rows, nicknames, onOpen = { editId = it; pageVisit++ })
                }
                tab == Tab.Apps -> {
                    val state by appsViewModel.state.collectAsStateWithLifecycle()
                    AppsScreen(state, onQueryChange = appsViewModel::onQueryChange, onToggle = appsViewModel::setMonitored)
                }
                else -> {
                    SettingsScreen(
                        status = settingsViewModel.status,
                        onExport = settingsViewModel::export,
                        onImport = { uri ->
                            scope.launch {
                                if (settingsViewModel.import(uri)) {
                                    (context as? Activity)?.recreate()
                                }
                            }
                        },
                        gendaUrl = settingsViewModel.gendaUrl,
                        gendaToken = settingsViewModel.gendaToken,
                        gendaPort = settingsViewModel.gendaPort,
                        onGendaChange = settingsViewModel::setGenda,
                        tags = settingsViewModel.tags.collectAsStateWithLifecycle().value,
                        onRenameTag = settingsViewModel::renameTag,
                        soleTagCount = settingsViewModel::soleTagCount,
                        onDeleteTag = settingsViewModel::deleteTag,
                    )
                }
            }
        }
    }
}
