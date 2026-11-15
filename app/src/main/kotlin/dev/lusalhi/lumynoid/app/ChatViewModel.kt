package dev.lusalhi.lumynoid.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lusalhi.lumynoid.core.Conversation
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Screen { LOADING, CHAT, PROVIDER, CONVERSATIONS }

data class ChatState(
    val screen: Screen = Screen.LOADING,
    val providerConfigured: Boolean = false,
    val events: List<TaskEvent> = emptyList(),
    val live: LiveAnswer? = null,
    val sending: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val conversationId: String? = null,
    /** Title of the open conversation; empty for a new or untitled one. */
    val title: String = "",
    /** The conversation whose answer is streaming right now; it cannot be deleted. */
    val streamingConversationId: String? = null,
    /** The runner's active task when it belongs to the open conversation; the chat shows Stop while set. */
    val runningTaskId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as LumynoidApp).graph
    private val agent = AgentConnection(application)

    private val screen = MutableStateFlow(Screen.LOADING)
    private val providerConfigured = MutableStateFlow(false)
    private val conversationId = MutableStateFlow<String?>(null)

    private val events = conversationId.flatMapLatest { id ->
        // Tagged with its conversation so a stale list is never shown under another conversation.
        if (id == null) flowOf(null to emptyList()) else graph.data.events.observe(id).map { id to it }
    }
    private val live = agent.runner.flatMapLatest { it?.live ?: flowOf(null) }
    private val active = agent.runner.flatMapLatest { it?.active ?: flowOf(null) }

    private val conversations = graph.data.conversations.observeAll()
    private val createLock = Mutex()

    private val header = combine(screen, providerConfigured, conversationId, conversations) { screen, configured, id, all ->
        ChatState(
            screen = screen,
            providerConfigured = configured,
            conversations = all,
            conversationId = id,
            title = all.firstOrNull { it.id == id }?.title.orEmpty(),
        )
    }

    val state: StateFlow<ChatState> =
        combine(header, events, live, active) { header, tagged, live, active ->
            val events = if (tagged.first == header.conversationId) tagged.second else emptyList()
            // The saved AssistantMessage can be observed a moment before the runner clears its live
            // copy; hide the live bubble then so the answer never shows twice. A live answer of
            // another conversation must never show here either.
            val visibleLive = live?.takeIf { l ->
                l.conversationId == header.conversationId &&
                    events.none { it is TaskEvent.AssistantMessage && it.taskId == l.taskId }
            }
            header.copy(
                events = events,
                live = visibleLive,
                streamingConversationId = active?.conversationId,
                runningTaskId = active?.takeIf { it.conversationId == header.conversationId }?.taskId,
            )
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

    fun openConversations() { screen.value = Screen.CONVERSATIONS }

    fun closeConversations() { screen.value = Screen.CHAT }

    fun openConversation(id: String) {
        conversationId.value = id
        screen.value = Screen.CHAT
    }

    /** The conversation itself is created on the first message, so nothing is written here. */
    fun newConversation() {
        conversationId.value = null
        screen.value = Screen.CHAT
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { graph.data.conversations.rename(id, title) }
    }

    fun deleteConversation(id: String) {
        // A cancel would append TaskCancelled after the delete and leave orphan events.
        if (state.value.streamingConversationId == id) return
        viewModelScope.launch {
            graph.data.conversations.delete(id)
            if (conversationId.value == id) conversationId.value = null
        }
    }

    /** Stops the answer of the open conversation; does nothing when it already finished. */
    fun stop() {
        val taskId = state.value.runningTaskId ?: return
        agent.runner.value?.cancel(taskId)
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty() || !providerConfigured.value) return
        viewModelScope.launch {
            val id = createLock.withLock {
                conversationId.value ?: graph.data.conversations.create(input).id.also { conversationId.value = it }
            }
            val runner = agent.runner.filterNotNull().first()
            runner.submit(TaskRequest(Origin.User, id, workspaceId = UuidV7.next(), input = input))
        }
    }

    override fun onCleared() {
        agent.disconnect()
        super.onCleared()
    }
}
