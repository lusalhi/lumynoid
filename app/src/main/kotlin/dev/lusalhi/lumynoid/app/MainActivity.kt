package dev.lusalhi.lumynoid.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

    when (state.screen) {
        Screen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        Screen.PROVIDER -> {
            LaunchedEffect(Unit) { providers.refresh() }
            ProvidersRoute(providers, canLeave = state.providerConfigured, onLeave = viewModel::closeProvider)
        }
        Screen.CHAT -> ChatScreen(
            state = state,
            onSend = viewModel::send,
            onOpenProvider = viewModel::openProvider,
            onOpenConversations = viewModel::openConversations,
            onNewConversation = viewModel::newConversation,
        )
        Screen.CONVERSATIONS -> ConversationListScreen(
            conversations = state.conversations,
            openId = state.conversationId,
            streamingId = state.streamingConversationId,
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
