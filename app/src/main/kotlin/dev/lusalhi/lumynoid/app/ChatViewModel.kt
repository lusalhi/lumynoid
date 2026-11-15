package dev.lusalhi.lumynoid.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lusalhi.lumynoid.core.LiveAnswer
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProviderInput
import dev.lusalhi.lumynoid.core.ProviderSaveResult
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.UuidV7
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { LOADING, CHAT, PROVIDER }

data class ProviderFormState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val keySaved: Boolean = false,
    val showMissing: Boolean = false,
) {
    val baseUrlMissing get() = baseUrl.isBlank()
    val apiKeyMissing get() = apiKey.isBlank() && !keySaved
    val modelMissing get() = model.isBlank()
    val complete get() = !baseUrlMissing && !apiKeyMissing && !modelMissing
}

data class ChatState(
    val screen: Screen = Screen.LOADING,
    val providerConfigured: Boolean = false,
    val events: List<TaskEvent> = emptyList(),
    val live: LiveAnswer? = null,
    val sending: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as LumynoidApp).graph
    private val agent = AgentConnection(application)

    private val screen = MutableStateFlow(Screen.LOADING)
    private val providerConfigured = MutableStateFlow(false)
    private val conversationId = MutableStateFlow<String?>(null)

    private val _form = MutableStateFlow(ProviderFormState())
    val form: StateFlow<ProviderFormState> = _form

    private val events = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else graph.data.events.observe(id)
    }
    private val live = agent.runner.flatMapLatest { it?.live ?: flowOf(null) }

    val state: StateFlow<ChatState> =
        combine(screen, providerConfigured, events, live) { screen, configured, events, live ->
            // The saved AssistantMessage can be observed a moment before the runner clears its live
            // copy; hide the live bubble then so the answer never shows twice.
            val visibleLive = live?.takeIf { l -> events.none { it is TaskEvent.AssistantMessage && it.taskId == l.taskId } }
            ChatState(screen, configured, events, visibleLive)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    init {
        agent.connect()
        viewModelScope.launch {
            conversationId.value = graph.data.conversations.latest()?.id
            val current = graph.providerAdmin.current()
            providerConfigured.value = current != null
            if (current != null) {
                _form.value = ProviderFormState(current.baseUrl, "", current.model, keySaved = true)
            }
            screen.value = if (current != null) Screen.CHAT else Screen.PROVIDER
        }
    }

    fun openProvider() { screen.value = Screen.PROVIDER }

    fun closeProvider() { if (providerConfigured.value) screen.value = Screen.CHAT }

    fun editForm(transform: (ProviderFormState) -> ProviderFormState) = _form.update(transform)

    fun saveProvider() {
        val form = _form.value
        if (!form.complete) {
            _form.update { it.copy(showMissing = true) }
            return
        }
        viewModelScope.launch {
            when (graph.providerAdmin.save(ProviderInput(form.baseUrl, form.apiKey, form.model))) {
                ProviderSaveResult.SAVED -> {
                    providerConfigured.value = true
                    _form.update { it.copy(apiKey = "", keySaved = true, showMissing = false) }
                    screen.value = Screen.CHAT
                }
                ProviderSaveResult.INCOMPLETE -> _form.update { it.copy(showMissing = true) }
            }
        }
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty() || !providerConfigured.value) return
        viewModelScope.launch {
            val id = conversationId.value ?: graph.data.conversations.create().id.also { conversationId.value = it }
            val runner = agent.runner.filterNotNull().first()
            runner.submit(TaskRequest(Origin.User, id, workspaceId = UuidV7.next(), input = input))
        }
    }

    override fun onCleared() {
        agent.disconnect()
        super.onCleared()
    }
}
