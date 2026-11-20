package dev.lusalhi.lumynoid.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ConnectOutcome
import dev.lusalhi.lumynoid.core.ConnectionTestResult
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderKind
import dev.lusalhi.lumynoid.core.ProviderOverview
import dev.lusalhi.lumynoid.core.ProviderPreset
import dev.lusalhi.lumynoid.core.ProviderPresets
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.ProviderSummary
import dev.lusalhi.lumynoid.core.connectAndSave
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Success : TestState
    data class Failed(val error: LlmError) : TestState
}

sealed interface ModelsState {
    data object Idle : ModelsState
    data object Loading : ModelsState
    data class Loaded(val ids: List<String>) : ModelsState

    /** The provider gave no usable list; the user types the model name instead. */
    data class Unavailable(val error: LlmError?) : ModelsState
}

/** The add/edit form. [id] is null while adding a new provider. */
data class ProviderFormState(
    val id: String? = null,
    val presetId: String? = null,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val keySaved: Boolean = false,
    val makeActive: Boolean = false,
    val alreadyActive: Boolean = false,
    val models: ModelsState = ModelsState.Idle,
    val test: TestState = TestState.Idle,
    val showMissing: Boolean = false,
    val busy: Boolean = false,
) {
    val baseUrlMissing get() = baseUrl.isBlank()
    val apiKeyMissing get() = apiKey.isBlank() && !keySaved
    val modelMissing get() = model.isBlank()
    val complete get() = !baseUrlMissing && !apiKeyMissing && !modelMissing
    val canListModels get() = !baseUrlMissing && !apiKeyMissing

    fun toDraft() = ProviderDraft(id, presetId, name, baseUrl, apiKey, model, makeActive, kind)
}

data class ProvidersState(
    val loaded: Boolean = false,
    val providers: List<ProviderSummary> = emptyList(),
    val active: ActiveModel? = null,
    val form: ProviderFormState? = null,
) {
    /** The form of the very first provider: nothing is saved yet, so setup is guided. */
    val isFirstRun get() = form != null && form.id == null && providers.isEmpty()
}

/** The first provider starts with OpenRouter chosen, so only the key and a model are left to fill in. */
internal fun firstRunForm(): ProviderFormState {
    val preset = ProviderPresets.all.first()
    return ProviderFormState(
        presetId = preset.id,
        kind = preset.kind,
        name = preset.name,
        baseUrl = preset.baseUrl,
        makeActive = true,
    )
}

class ProvidersViewModel(application: Application) : AndroidViewModel(application) {

    private val admin = (application as LumynoidApp).graph.providerAdmin

    private val _state = MutableStateFlow(ProvidersState())
    val state: StateFlow<ProvidersState> = _state

    init { refresh() }

    /** Re-reads the saved providers; with none saved, goes straight to the form for the first one. */
    fun refresh() {
        viewModelScope.launch { reloadOverview(closeForm = false) }
    }

    /** Re-reads the saved providers into the state; [closeForm] also drops the form that was just saved. */
    private suspend fun reloadOverview(closeForm: Boolean): ProviderOverview {
        val overview = admin.overview()
        _state.update { current ->
            val form = when {
                closeForm -> null
                else -> current.form ?: if (overview.providers.isEmpty()) firstRunForm() else null
            }
            // Only a refresh marks the list as loaded; a save or connect keeps the flag as it was.
            current.copy(loaded = current.loaded || !closeForm, providers = overview.providers, active = overview.active, form = form)
        }
        return overview
    }

    fun startAdd() = _state.update {
        it.copy(form = ProviderFormState(makeActive = it.active == null))
    }

    fun startEdit(provider: ProviderSummary) = _state.update {
        val isActive = it.active?.providerId == provider.id
        val model = if (isActive) it.active.modelId else provider.models.lastOrNull().orEmpty()
        it.copy(
            form = ProviderFormState(
                id = provider.id,
                presetId = provider.presetId,
                kind = provider.kind,
                name = provider.name,
                baseUrl = provider.baseUrl,
                model = model,
                keySaved = true,
                makeActive = isActive,
                alreadyActive = isActive,
            ),
        )
    }

    fun closeForm() = _state.update { it.copy(form = null) }

    /** A user edit: it makes an earlier test result stale. */
    fun edit(transform: (ProviderFormState) -> ProviderFormState) = patch { transform(it).copy(test = TestState.Idle) }

    private fun patch(transform: (ProviderFormState) -> ProviderFormState) =
        _state.update { s -> s.copy(form = s.form?.let(transform)) }

    fun choosePreset(preset: ProviderPreset?) = edit { f ->
        val previous = ProviderPresets.byId(f.presetId)
        val nameWasAutomatic = f.name.isBlank() || f.name == previous?.name
        val urlWasAutomatic = f.baseUrl.isBlank() || f.baseUrl == previous?.baseUrl
        f.copy(
            presetId = preset?.id,
            kind = preset?.kind ?: ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = if (urlWasAutomatic) preset?.baseUrl.orEmpty() else f.baseUrl,
            name = if (nameWasAutomatic) preset?.name.orEmpty() else f.name,
            models = ModelsState.Idle,
        )
    }

    fun loadModels() {
        val form = _state.value.form ?: return
        if (!form.canListModels) {
            patch { it.copy(showMissing = true) }
            return
        }
        val sent = form.toDraft()
        patch { it.copy(models = ModelsState.Loading) }
        viewModelScope.launch {
            val result = admin.listModels(sent)
            _state.update { s ->
                val current = s.form ?: return@update s
                // Only the form that asked still wants the answer; an edit of URL or key, a switch of kind, or another provider makes it stale.
                val fresh = current.id == sent.id && current.kind == sent.kind &&
                    current.baseUrl == sent.baseUrl && current.apiKey == sent.apiKey
                s.copy(
                    form = when {
                        fresh -> current.copy(
                            models = when (result) {
                                is ModelListResult.Models -> ModelsState.Loaded(result.ids)
                                is ModelListResult.Unavailable -> ModelsState.Unavailable(result.error)
                            },
                        )
                        current.models == ModelsState.Loading -> current.copy(models = ModelsState.Idle)
                        else -> current
                    },
                )
            }
        }
    }

    fun testConnection() {
        val form = _state.value.form ?: return
        if (!form.complete) {
            patch { it.copy(showMissing = true) }
            return
        }
        val sent = form.toDraft()
        patch { it.copy(test = TestState.Running) }
        viewModelScope.launch {
            val result = admin.testConnection(sent)
            _state.update { s ->
                val current = s.form ?: return@update s
                // A result belongs to the settings that were sent: never show "Connected" for edited ones.
                val fresh = isFresh(current, sent)
                s.copy(
                    form = when {
                        !fresh -> if (current.test == TestState.Running) current.copy(test = TestState.Idle) else current
                        else -> when (result) {
                            ConnectionTestResult.Success -> current.copy(test = TestState.Success)
                            is ConnectionTestResult.Failure -> current.copy(test = TestState.Failed(result.error))
                            // The stored key is gone: let the key field ask for a new one.
                            ConnectionTestResult.Incomplete ->
                                current.copy(test = TestState.Idle, showMissing = true, keySaved = false)
                        }
                    },
                )
            }
        }
    }

    /** A result belongs to the settings that were sent; a name or "use for chat" edit does not change what was tested. */
    private fun isFresh(current: ProviderFormState, sent: ProviderDraft) =
        current.toDraft().copy(makeActive = sent.makeActive, name = sent.name) == sent

    /**
     * First run: tests the connection and saves only when it works, then [onStarted] runs. With a key but no model yet,
     * it loads the model list instead and sends nothing.
     */
    fun connect(onStarted: () -> Unit) {
        val form = _state.value.form ?: return
        if (form.busy || form.test == TestState.Running) return
        if (!form.complete) {
            patch { it.copy(showMissing = true) }
            if (form.modelMissing && form.canListModels && form.models == ModelsState.Idle) loadModels()
            return
        }
        val sent = form.toDraft()
        patch { it.copy(test = TestState.Running, busy = true) }
        viewModelScope.launch {
            when (val outcome = connectAndSave(admin, sent)) {
                ConnectOutcome.Started -> {
                    if (reloadOverview(closeForm = true).active != null) onStarted()
                }
                is ConnectOutcome.TestFailed -> patch { current ->
                    // A failure for settings the user has since edited is stale: drop it.
                    when {
                        isFresh(current, sent) -> current.copy(test = TestState.Failed(outcome.error), busy = false)
                        current.test == TestState.Running -> current.copy(test = TestState.Idle, busy = false)
                        else -> current.copy(busy = false)
                    }
                }
                // The stored key is gone or a field is blank: let the form ask again.
                ConnectOutcome.Incomplete -> patch {
                    it.copy(test = TestState.Idle, showMissing = true, keySaved = false, busy = false)
                }
            }
        }
    }

    /** Saves the form. [onFirstProviderActive] runs when this save gave the app its first usable provider. */
    fun save(onFirstProviderActive: () -> Unit) {
        val before = _state.value
        val form = before.form ?: return
        if (!form.complete) {
            patch { it.copy(showMissing = true) }
            return
        }
        patch { it.copy(busy = true) }
        viewModelScope.launch {
            when (admin.save(form.toDraft())) {
                ProviderSaveResult.SAVED -> {
                    val overview = reloadOverview(closeForm = true)
                    if (before.active == null && overview.active != null) onFirstProviderActive()
                }
                // A saved provider whose key went missing also lands here: the key field must ask again.
                ProviderSaveResult.INCOMPLETE -> patch { it.copy(showMissing = true, busy = false, keySaved = false) }
            }
        }
    }

    /** "Use for chat" on a card: the provider's most recently saved model becomes the app-wide choice. */
    fun use(provider: ProviderSummary) {
        val model = provider.models.lastOrNull() ?: return
        viewModelScope.launch {
            admin.select(provider.id, model)
            val overview = admin.overview()
            _state.update { it.copy(active = overview.active) }
        }
    }
}
