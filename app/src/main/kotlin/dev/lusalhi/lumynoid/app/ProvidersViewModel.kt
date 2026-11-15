package dev.lusalhi.lumynoid.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ConnectionTestResult
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.ModelListResult
import dev.lusalhi.lumynoid.core.ProviderDraft
import dev.lusalhi.lumynoid.core.ProviderPreset
import dev.lusalhi.lumynoid.core.ProviderPresets
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.ProviderSummary
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

    fun toDraft() = ProviderDraft(id, presetId, name, baseUrl, apiKey, model, makeActive)
}

data class ProvidersState(
    val loaded: Boolean = false,
    val providers: List<ProviderSummary> = emptyList(),
    val active: ActiveModel? = null,
    val form: ProviderFormState? = null,
)

class ProvidersViewModel(application: Application) : AndroidViewModel(application) {

    private val admin = (application as LumynoidApp).graph.providerAdmin

    private val _state = MutableStateFlow(ProvidersState())
    val state: StateFlow<ProvidersState> = _state

    init { refresh() }

    /** Re-reads the saved providers; with none saved, goes straight to the form for the first one. */
    fun refresh() {
        viewModelScope.launch {
            val overview = admin.overview()
            _state.update { current ->
                val form = current.form ?: if (overview.providers.isEmpty()) ProviderFormState(makeActive = true) else null
                current.copy(loaded = true, providers = overview.providers, active = overview.active, form = form)
            }
        }
    }

    fun startAdd() = _state.update {
        it.copy(form = ProviderFormState(makeActive = it.active == null, alreadyActive = false))
    }

    fun startEdit(provider: ProviderSummary) = _state.update {
        val isActive = it.active?.providerId == provider.id
        val model = if (isActive) it.active.modelId else provider.models.lastOrNull().orEmpty()
        it.copy(
            form = ProviderFormState(
                id = provider.id,
                presetId = provider.presetId,
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
        val previous = ProviderPresets.all.firstOrNull { it.id == f.presetId }
        val nameWasAutomatic = f.name.isBlank() || f.name == previous?.name
        val urlWasAutomatic = f.baseUrl.isBlank() || f.baseUrl == previous?.baseUrl
        f.copy(
            presetId = preset?.id,
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
                // Only the form that asked still wants the answer; an edit of URL or key, or another provider, makes it stale.
                val fresh = current.id == sent.id && current.baseUrl == sent.baseUrl && current.apiKey == sent.apiKey
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
                val fresh = current.toDraft().copy(makeActive = sent.makeActive, name = sent.name) == sent
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
                    val overview = admin.overview()
                    _state.update { it.copy(providers = overview.providers, active = overview.active, form = null) }
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
