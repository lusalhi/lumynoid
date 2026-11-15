package dev.lusalhi.lumynoid.app

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.TaskEvent

@Composable
fun ProviderScreen(
    form: ProviderFormState,
    canGoBack: Boolean,
    onEdit: ((ProviderFormState) -> ProviderFormState) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.provider_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.provider_intro), style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = { v -> onEdit { it.copy(baseUrl = v) } },
            label = { Text(stringResource(R.string.provider_base_url)) },
            placeholder = { Text(stringResource(R.string.provider_base_url_hint)) },
            singleLine = true,
            isError = form.showMissing && form.baseUrlMissing,
            supportingText = {
                if (form.showMissing && form.baseUrlMissing) Text(stringResource(R.string.provider_missing_base_url))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.apiKey,
            onValueChange = { v -> onEdit { it.copy(apiKey = v) } },
            label = { Text(stringResource(R.string.provider_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = form.showMissing && form.apiKeyMissing,
            supportingText = {
                when {
                    form.showMissing && form.apiKeyMissing -> Text(stringResource(R.string.provider_missing_api_key))
                    form.keySaved -> Text(stringResource(R.string.provider_api_key_saved))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.model,
            onValueChange = { v -> onEdit { it.copy(model = v) } },
            label = { Text(stringResource(R.string.provider_model)) },
            placeholder = { Text(stringResource(R.string.provider_model_hint)) },
            singleLine = true,
            isError = form.showMissing && form.modelMissing,
            supportingText = {
                if (form.showMissing && form.modelMissing) Text(stringResource(R.string.provider_missing_model))
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave) { Text(stringResource(R.string.provider_save)) }
            if (canGoBack) TextButton(onClick = onBack) { Text(stringResource(R.string.provider_back)) }
        }
    }
}

@Composable
fun ChatScreen(
    state: ChatState,
    onSend: (String) -> Unit,
    onOpenProvider: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val liveText = state.live?.text.orEmpty()
    val itemCount = state.events.size + if (state.live != null) 1 else 0

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
            Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onOpenProvider) { Text(stringResource(R.string.chat_settings)) }
        }

        Box(Modifier.weight(1f)) {
            if (itemCount == 0) {
                Text(
                    stringResource(R.string.chat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.events.filter { it.isVisible() }, key = { it.id }) { event -> EventRow(event) }
                state.live?.let { item(key = "live") { Bubble(it.text, fromUser = false) } }
            }
        }

        if (!state.providerConfigured) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.chat_needs_provider), Modifier.weight(1f))
                Button(onClick = onOpenProvider) { Text(stringResource(R.string.chat_open_provider)) }
            }
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
                Button(
                    onClick = { onSend(draft); draft = "" },
                    enabled = draft.isNotBlank(),
                ) { Text(stringResource(R.string.chat_send)) }
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
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
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
}
