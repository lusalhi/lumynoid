package dev.lusalhi.lumynoid.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.ProviderPreset
import dev.lusalhi.lumynoid.core.ProviderPresets
import dev.lusalhi.lumynoid.core.ProviderSummary

/** The provider screen: the saved providers, or the add/edit form while one is open. */
@Composable
fun ProvidersRoute(viewModel: ProvidersViewModel, canLeave: Boolean, onLeave: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val form = state.form
    // Back closes the form first; with no provider saved there is nowhere to go back to.
    BackHandler(enabled = form != null && state.providers.isNotEmpty()) { viewModel.closeForm() }
    BackHandler(enabled = form == null && canLeave, onBack = onLeave)

    if (form != null) {
        ProviderFormScreen(
            form = form,
            canCancel = state.providers.isNotEmpty(),
            onEdit = viewModel::edit,
            onPreset = viewModel::choosePreset,
            onLoadModels = viewModel::loadModels,
            onTest = viewModel::testConnection,
            onSave = { viewModel.save(onFirstProviderActive = onLeave) },
            onCancel = viewModel::closeForm,
        )
    } else {
        ProviderListScreen(
            state = state,
            canLeave = canLeave,
            onAdd = viewModel::startAdd,
            onEdit = viewModel::startEdit,
            onUse = viewModel::use,
            onLeave = onLeave,
        )
    }
}

@Composable
private fun ProviderListScreen(
    state: ProvidersState,
    canLeave: Boolean,
    onAdd: () -> Unit,
    onEdit: (ProviderSummary) -> Unit,
    onUse: (ProviderSummary) -> Unit,
    onLeave: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.provider_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.provider_intro), style = MaterialTheme.typography.bodyMedium)

        state.providers.forEach { provider ->
            val active = state.active?.takeIf { it.providerId == provider.id }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    Text(provider.baseUrl, style = MaterialTheme.typography.bodySmall)
                    if (active != null) {
                        Text(
                            stringResource(R.string.provider_active, active.modelId),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (active == null && provider.models.isNotEmpty()) {
                            Button(onClick = { onUse(provider) }) { Text(stringResource(R.string.provider_use)) }
                        }
                        TextButton(onClick = { onEdit(provider) }) { Text(stringResource(R.string.provider_edit)) }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd) { Text(stringResource(R.string.provider_add)) }
            if (canLeave) TextButton(onClick = onLeave) { Text(stringResource(R.string.provider_back)) }
        }
    }
}

@Composable
private fun ProviderFormScreen(
    form: ProviderFormState,
    canCancel: Boolean,
    onEdit: ((ProviderFormState) -> ProviderFormState) -> Unit,
    onPreset: (ProviderPreset?) -> Unit,
    onLoadModels: () -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
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
        Text(
            stringResource(if (form.id == null) R.string.provider_add_title else R.string.provider_edit_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        if (form.id == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderPresets.all.forEach { preset ->
                    FilterChip(
                        selected = form.presetId == preset.id,
                        onClick = { onPreset(preset) },
                        label = { Text(preset.name) },
                    )
                }
                FilterChip(
                    selected = form.presetId == null,
                    onClick = { onPreset(null) },
                    label = { Text(stringResource(R.string.provider_custom)) },
                )
            }
        }

        OutlinedTextField(
            value = form.name,
            onValueChange = { v -> onEdit { it.copy(name = v) } },
            label = { Text(stringResource(R.string.provider_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = { v -> onEdit { it.copy(baseUrl = v, models = ModelsState.Idle) } },
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
            onValueChange = { v -> onEdit { it.copy(apiKey = v, models = ModelsState.Idle) } },
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
            placeholder = {
                Text(
                    stringResource(
                        if (form.kind == ProviderKind.ANTHROPIC) R.string.provider_model_hint_anthropic else R.string.provider_model_hint,
                    ),
                )
            },
            singleLine = true,
            isError = form.showMissing && form.modelMissing,
            supportingText = {
                if (form.showMissing && form.modelMissing) Text(stringResource(R.string.provider_missing_model))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ModelChoices(form, onLoadModels) { id -> onEdit { it.copy(model = id) } }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = form.makeActive,
                onCheckedChange = { v -> onEdit { it.copy(makeActive = v) } },
                enabled = !form.alreadyActive,
            )
            Text(stringResource(R.string.provider_use_for_chat), style = MaterialTheme.typography.bodyLarge)
        }

        TestResult(form.test)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onTest, enabled = form.test != TestState.Running && !form.busy) {
                Text(stringResource(R.string.provider_test))
            }
            Button(onClick = onSave, enabled = !form.busy) { Text(stringResource(R.string.provider_save)) }
            if (canCancel) TextButton(onClick = onCancel) { Text(stringResource(R.string.provider_cancel)) }
        }
    }
}

/** Fetched model ids narrowed by what is typed; typing a name by hand always works. */
@Composable
private fun ModelChoices(form: ProviderFormState, onLoad: () -> Unit, onPick: (String) -> Unit) {
    when (val models = form.models) {
        ModelsState.Idle -> TextButton(onClick = onLoad) { Text(stringResource(R.string.provider_load_models)) }
        ModelsState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
        is ModelsState.Unavailable -> Column {
            Text(stringResource(R.string.provider_models_unavailable), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onLoad) { Text(stringResource(R.string.provider_load_models_again)) }
        }
        is ModelsState.Loaded -> {
            val query = form.model.trim()
            val matches = models.ids.filter { it.contains(query, ignoreCase = true) && it != query }.take(MAX_SUGGESTIONS)
            Column {
                Text(stringResource(R.string.provider_models_loaded, models.ids.size), style = MaterialTheme.typography.bodySmall)
                matches.forEach { id -> TextButton(onClick = { onPick(id) }) { Text(id) } }
            }
        }
    }
}

@Composable
private fun TestResult(test: TestState) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Text(stringResource(R.string.provider_testing))
        }
        TestState.Success -> Text(
            stringResource(R.string.provider_test_ok),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
        )
        is TestState.Failed -> Text(
            stringResource(R.string.provider_test_failed, stringResource(test.error.reasonRes())),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun LlmError.reasonRes(): Int = when (this) {
    LlmError.AuthFailed -> R.string.provider_reason_auth
    is LlmError.RateLimited -> R.string.provider_reason_rate_limited
    LlmError.QuotaExhausted -> R.string.provider_reason_quota
    LlmError.Network -> R.string.provider_reason_network
    LlmError.Server, LlmError.ToolsUnsupported, LlmError.MalformedToolCall -> R.string.provider_reason_server
}

private const val MAX_SUGGESTIONS = 6
