package dev.lusalhi.lumynoid.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lusalhi.lumynoid.core.LiveAnswer
import dev.lusalhi.lumynoid.core.Origin
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
            val configured = graph.providerAdmin.overview().active != null
            providerConfigured.value = configured
            screen.value = if (configured) Screen.CHAT else Screen.PROVIDER
        }
    }

    fun openProvider() { screen.value = Screen.PROVIDER }

    /** Leaves the provider screen for the chat, once the app has a provider and model to chat with. */
    fun closeProvider() {
        viewModelScope.launch {
            val configured = graph.providerAdmin.overview().active != null
            providerConfigured.value = configured
            if (configured) screen.value = Screen.CHAT
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
