package com.kaiodelphino.cobudget.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.kaiodelphino.cobudget.capture.NotificationCaptureService
import com.kaiodelphino.cobudget.ui.access.AccessBanner
import com.kaiodelphino.cobudget.ui.apps.AppsScreen
import com.kaiodelphino.cobudget.ui.apps.AppsViewModel
import com.kaiodelphino.cobudget.ui.messages.MessagesScreen
import com.kaiodelphino.cobudget.ui.messages.MessagesViewModel
import com.kaiodelphino.cobudget.ui.settings.SettingsScreen
import com.kaiodelphino.cobudget.ui.settings.SettingsViewModel
import com.kaiodelphino.cobudget.ui.theme.CobudgetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = (application as CobudgetApp).db.dao()
        val factory = viewModelFactory {
            initializer { MessagesViewModel(dao) }
            initializer { AppsViewModel(dao, application) }
            initializer { SettingsViewModel(application as CobudgetApp) }
        }
        setContent {
            CobudgetTheme {
                MainScreen(
                    messagesViewModel = viewModel(factory = factory),
                    appsViewModel = viewModel(factory = factory),
                    settingsViewModel = viewModel(factory = factory),
                )
            }
        }
    }
}

private enum class Tab(val label: String) { Messages("Messages"), Apps("Apps"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    messagesViewModel: MessagesViewModel,
    appsViewModel: AppsViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.Messages) }
    var accessGranted by rememberSaveable { mutableStateOf(NotificationCaptureService.isAccessGranted(context)) }

    // The user grants access in system settings, so re-check whenever we come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessGranted = NotificationCaptureService.isAccessGranted(context)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Cobudget") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Messages,
                    onClick = { tab = Tab.Messages },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                    label = { Text(Tab.Messages.label) },
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
            when (tab) {
                Tab.Messages -> {
                    val state by messagesViewModel.state.collectAsStateWithLifecycle()
                    MessagesScreen(state, onSelectApp = messagesViewModel::select, onDismiss = messagesViewModel::dismiss)
                }
                Tab.Apps -> {
                    val state by appsViewModel.state.collectAsStateWithLifecycle()
                    AppsScreen(state, onQueryChange = appsViewModel::onQueryChange, onToggle = appsViewModel::setMonitored)
                }
                Tab.Settings -> {
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
                    )
                }
            }
        }
    }
}
