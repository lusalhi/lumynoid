package dev.lusalhi.lumynoid.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.TaskEvent

@Composable
fun ChatScreen(
    state: ChatState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onOpenProvider: () -> Unit,
    onOpenConversations: () -> Unit,
    onNewConversation: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val liveText = state.live?.text.orEmpty()
    val visibleEvents = remember(state.events) { state.events.filter { it.isVisible() } }
    val itemCount = visibleEvents.size + if (state.live != null) 1 else 0

    LaunchedEffect(itemCount, liveText.length) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                state.title.ifBlank { stringResource(R.string.conversation_untitled) },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenConversations) { Text(stringResource(R.string.chat_conversations)) }
            TextButton(onClick = onNewConversation) { Text(stringResource(R.string.chat_new)) }
            TextButton(onClick = onOpenProvider) { Text(stringResource(R.string.chat_settings)) }
        }

        Box(Modifier.weight(1f)) {
            if (itemCount == 0) {
                if (state.providerConfigured) {
                    Text(
                        stringResource(R.string.chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                } else {
                    SetupPrompt(R.string.setup_first, onOpenProvider, Modifier.align(Alignment.Center).padding(24.dp))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleEvents, key = { it.id }) { event -> EventRow(event) }
                    state.live?.let { item(key = "live") { Bubble(it.text, fromUser = false) } }
                }
            }
        }

        if (!state.providerConfigured) {
            // An empty chat already shows the prompt and its button above.
            if (itemCount > 0) SetupBanner(onOpenProvider, Modifier.padding(16.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )
                if (state.runningTaskId != null) {
                    Button(onClick = onStop) { Text(stringResource(R.string.chat_stop)) }
                } else {
                    Button(
                        onClick = { onSend(draft); draft = "" },
                        enabled = draft.isNotBlank(),
                    ) { Text(stringResource(R.string.chat_send)) }
                }
            }
        }
    }
}

private fun TaskEvent.isVisible() =
    this is TaskEvent.UserMessage || this is TaskEvent.AssistantMessage ||
        this is TaskEvent.TaskFailed || this is TaskEvent.TaskCancelled

@Composable
private fun EventRow(event: TaskEvent) {
    when (event) {
        is TaskEvent.UserMessage -> Bubble(event.text, fromUser = true)
        is TaskEvent.AssistantMessage -> Bubble(event.text, fromUser = false)
        is TaskEvent.TaskFailed -> Text(
            stringResource(event.reason.messageRes()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        is TaskEvent.TaskCancelled -> Text(
            stringResource(R.string.task_cancelled),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        else -> Unit
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            val padding = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            if (fromUser) {
                Text(text, padding, style = MaterialTheme.typography.bodyLarge)
            } else {
                AssistantMarkdown(text, padding)
            }
        }
    }
}

private fun FailureReason.messageRes(): Int = when (this) {
    FailureReason.AUTH_FAILED -> R.string.error_auth_failed
    FailureReason.RATE_LIMITED -> R.string.error_rate_limited
    FailureReason.QUOTA_EXHAUSTED -> R.string.error_quota_exhausted
    FailureReason.NETWORK -> R.string.error_network
    FailureReason.SERVER -> R.string.error_server
    FailureReason.INTERRUPTED -> R.string.error_interrupted
    FailureReason.TOOL_DENIED -> R.string.error_tool_denied
    FailureReason.APPROVAL_DENIED -> R.string.error_approval_denied
    FailureReason.APPROVAL_EXPIRED -> R.string.error_approval_expired
    FailureReason.APPROVAL_CHANGED -> R.string.error_approval_changed
    FailureReason.MALFORMED_TOOL_CALL -> R.string.error_malformed_tool_call
    FailureReason.BUDGET_EXHAUSTED -> R.string.error_budget_exhausted
}

/** Offers the saved crash report. Shown above every screen, so a crash during first-run setup can be shared too. */
@Composable
fun CrashBanner(onShare: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.crash_banner_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                stringResource(R.string.crash_banner_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.crash_dismiss)) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.crash_share)) }
            }
        }
    }
}

/** Shown in place of an empty list or chat while no provider is set up: what to do, and the button that does it. */
@Composable
fun SetupPrompt(@StringRes message: Int, onOpenProvider: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onOpenProvider) { Text(stringResource(R.string.chat_open_provider)) }
    }
}

/** A one-line reminder with the setup button, for screens that already show content. */
@Composable
fun SetupBanner(onOpenProvider: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.chat_needs_provider), Modifier.weight(1f))
        Button(onClick = onOpenProvider) { Text(stringResource(R.string.chat_open_provider)) }
    }
}
