package dev.lusalhi.lumynoid.app

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    private val providers: ProvidersViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LumynoidTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(viewModel, providers)
                }
            }
        }
    }
}

@Composable
private fun Root(viewModel: ChatViewModel, providers: ProvidersViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(state.screen, state.providerConfigured) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            state.screen == Screen.CHAT && state.providerConfigured &&
            context.checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("requested", false)) {
                prefs.edit().putBoolean("requested", true).apply()
                notificationPermission.launch(POST_NOTIFICATIONS)
            }
        }
    }
    val crashStore = remember { CrashHandler.store(context) }
    // One directory listing on first composition; Dismiss and a successful relaunch are the only ways it changes.
    var crashReport by remember { mutableStateOf(crashStore.newest()) }
    val report = crashReport

    Column(Modifier.fillMaxSize()) {
        if (report != null) {
            // The banner takes the status bar inset; the screen below consumes it so it is neither doubled nor lost.
            CrashBanner(
                onShare = {
                    try {
                        context.startActivity(CrashHandler.shareIntent(context, report))
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.crash_share_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = {
                    crashStore.clear()
                    crashReport = null
                },
                modifier = Modifier.statusBarsPadding(),
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (report != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
        ) {
            Screens(state, viewModel, providers)
        }
    }
}

@Composable
private fun Screens(state: ChatState, viewModel: ChatViewModel, providers: ProvidersViewModel) {
    when (state.screen) {
        Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        Screen.PROVIDER -> {
            LaunchedEffect(Unit) { providers.refresh() }
            ProvidersRoute(
                providers,
                canLeave = state.providerConfigured,
                onLeave = viewModel::closeProvider,
                onFirstRunDone = viewModel::providerReady,
            )
        }
        Screen.CHAT -> ChatScreen(
            state = state,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onOpenProvider = viewModel::openProvider,
            onOpenConversations = viewModel::openConversations,
            onNewConversation = viewModel::newConversation,
        )
        Screen.CONVERSATIONS -> ConversationListScreen(
            conversations = state.conversations,
            openId = state.conversationId,
            pendingIds = state.pendingConversationIds,
            providerConfigured = state.providerConfigured,
            onOpenProvider = viewModel::openProvider,
            onOpen = viewModel::openConversation,
            onNew = viewModel::newConversation,
            onRename = viewModel::renameConversation,
            onDelete = viewModel::deleteConversation,
            onBack = viewModel::closeConversations,
        )
    }
}

@Composable
private fun LumynoidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
