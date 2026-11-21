package dev.lusalhi.lumynoid.app

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lusalhi.lumynoid.core.ApprovalDecision
import dev.lusalhi.lumynoid.core.ApprovalPort
import dev.lusalhi.lumynoid.core.Conversation
import dev.lusalhi.lumynoid.core.LiveAnswer
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Screen { LOADING, CHAT, PROVIDER, CONVERSATIONS }

/** Each notification tap requests a fresh scroll, even when its target has not changed. */
class ApprovalFocus(val eventId: String)

data class ChatState(
    val screen: Screen = Screen.LOADING,
    val providerConfigured: Boolean = false,
    val events: List<TaskEvent> = emptyList(),
    val live: LiveAnswer? = null,
    val conversations: List<Conversation> = emptyList(),
    val conversationId: String? = null,
    /** Title of the open conversation; empty for a new or untitled one. */
    val title: String = "",
    /** Conversations with pending tasks cannot be deleted. Null until the runner is ready. */
    val pendingConversationIds: Set<String>? = null,
    /** The runner's active task when it belongs to the open conversation; the chat shows Stop while set. */
    val runningTaskId: String? = null,
    val approvalDeadlines: Map<String, Long> = emptyMap(),
    val approvalFocus: ApprovalFocus? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as LumynoidApp).graph
    private val agent = AgentConnection(application)

    private val screen = MutableStateFlow(Screen.LOADING)
    private val providerConfigured = MutableStateFlow(false)
    private val conversationId = MutableStateFlow<String?>(null)
    private val approvalFocus = MutableStateFlow<ApprovalFocus?>(null)

    private val events = conversationId.flatMapLatest { id ->
        // Tagged with its conversation so a stale list is never shown under another conversation.
        if (id == null) flowOf(null to emptyList()) else graph.data.events.observe(id).map { id to it }
    }
    private val live = agent.runner.flatMapLatest { it?.live ?: flowOf(null) }
    private val active = agent.runner.flatMapLatest { it?.active ?: flowOf(null) }
    private val pending = agent.runner.flatMapLatest { it?.pendingConversations ?: flowOf(null) }

    private val conversations = graph.data.conversations.observeAll()
    // Serialize conversation changes and durable task handoffs with unbinding from the service.
    private val conversationLock = Mutex()
    private var activityStarted = false

    private val selection = combine(conversationId, approvalFocus, ::Pair)
    private val header = combine(
        screen, providerConfigured, selection, conversations, graph.runner.pendingApprovals,
    ) { screen, configured, (id, focus), all, approvals ->
        ChatState(
            screen = screen,
            providerConfigured = configured,
            conversations = all,
            conversationId = id,
            title = all.firstOrNull { it.id == id }?.title.orEmpty(),
            approvalDeadlines = buildMap {
                for ((eventId, approval) in approvals) {
                    if (approval.conversationId == id) put(eventId, approval.elapsedDeadline)
                }
            },
            approvalFocus = focus,
        )
    }

    val state: StateFlow<ChatState> =
        combine(header, events, live, active, pending) { header, tagged, live, active, pending ->
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
                pendingConversationIds = pending,
                runningTaskId = active?.takeIf { it.conversationId == header.conversationId }?.taskId,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    private val initialized = viewModelScope.launch {
        conversationId.value = graph.data.conversations.latest()?.id
        screen.value = if (refreshConfigured()) Screen.CHAT else Screen.PROVIDER
    }

    internal fun refreshNotificationPermission() = graph.approvalNotifications.refreshPermission()

    internal fun connectAgent() {
        activityStarted = true
        agent.connect()
    }

    internal fun disconnectAgent() {
        activityStarted = false
        viewModelScope.launch {
            conversationLock.withLock {
                if (!activityStarted) agent.disconnect()
            }
        }
    }

    internal fun openApprovalNotification() {
        val notifications = graph.approvalNotifications
        val target = notifications.openedTarget ?: return
        notifications.openedTarget = null
        viewModelScope.launch {
            initialized.join()
            conversationLock.withLock {
                val approval = graph.runner.pendingApprovals.value[target.eventId] ?: return@withLock
                if (!target.matches(approval)) return@withLock
                openConversation(approval.conversationId)
                approvalFocus.value = ApprovalFocus(target.eventId)
            }
        }
    }

    internal fun resolveApproval(eventId: String, decision: ApprovalDecision) {
        viewModelScope.launch {
            conversationLock.withLock {
                val runner = agent.runner.filterNotNull().first()
                val approval = runner.pendingApprovals.value[eventId] ?: return@withLock
                if (screen.value != Screen.CHAT || approval.conversationId != conversationId.value) return@withLock
                val port: ApprovalPort = runner
                try {
                    if (port.resolve(approval.event.approval.id, decision) && approvalFocus.value?.eventId == eventId) {
                        approvalFocus.value = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(getApplication<Application>(), R.string.approval_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openProvider() { screen.value = Screen.PROVIDER }

    /** Leaves the provider screen for the chat, once the app has a provider and model to chat with. */
    fun closeProvider() {
        viewModelScope.launch {
            if (refreshConfigured()) screen.value = Screen.CHAT
        }
    }

    /** Re-reads whether the app has an active provider and model, and returns it. */
    private suspend fun refreshConfigured(): Boolean {
        val configured = graph.providerAdmin.overview().active != null
        providerConfigured.value = configured
        return configured
    }

    /** First run finished: the app has its first provider, so chat starts in an empty new conversation, not the latest old one. */
    fun providerReady() {
        providerConfigured.value = true
        newConversation()
    }

    fun openConversations() { screen.value = Screen.CONVERSATIONS }

    fun closeConversations() { screen.value = Screen.CHAT }

    fun openConversation(id: String) {
        approvalFocus.value = null
        conversationId.value = id
        screen.value = Screen.CHAT
    }

    /** The conversation itself is created on the first message, so nothing is written here. */
    fun newConversation() {
        approvalFocus.value = null
        conversationId.value = null
        screen.value = Screen.CHAT
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { graph.data.conversations.rename(id, title) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationLock.withLock {
                val runner = agent.runner.filterNotNull().first()
                val pending = runner.pendingConversations.filterNotNull().first()
                if (id in pending) return@withLock
                graph.data.conversations.delete(id)
                if (conversationId.value == id) conversationId.value = null
            }
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
            conversationLock.withLock {
                val id = conversationId.value ?: graph.data.conversations.create(input).id.also { conversationId.value = it }
                val runner = agent.runner.filterNotNull().first()
                approvalFocus.value = null
                runner.submit(TaskRequest(Origin.User, id, workspaceId = id, input = input))
            }
        }
    }

    override fun onCleared() {
        agent.disconnect()
        super.onCleared()
    }
}
