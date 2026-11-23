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
import dev.lusalhi.lumynoid.core.Project
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Screen { LOADING, CHAT, PROVIDER, CONVERSATIONS, PROJECTS, PROJECT, ACTION_LOG }

/** Each notification tap requests a fresh scroll, even when its target has not changed. */
class ApprovalFocus(val eventId: String)

data class ChatState(
    val screen: Screen = Screen.LOADING,
    val providerConfigured: Boolean = false,
    val events: List<TaskEvent> = emptyList(),
    val live: LiveAnswer? = null,
    val conversations: List<Conversation> = emptyList(),
    val projects: List<Project> = emptyList(),
    val projectPage: Project? = null,
    /** Projects whose cleanup call is running in this session; their Resume deletion action is disabled. */
    val deletingProjectIds: Set<String> = emptySet(),
    val conversationId: String? = null,
    val draftProjectId: String? = null,
    val chatProjectName: String? = null,
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
    private class ChatSelection(val id: String? = null, val draftProjectId: String? = null) {
        // Under conversationLock. Sends already queued for a draft share its eventual saved ID.
        var savedId: String? = id
    }

    private val conversation = MutableStateFlow(ChatSelection())
    private val projectPageId = MutableStateFlow<String?>(null)
    private val deletingProjects = MutableStateFlow<Set<String>>(emptySet())
    private val approvalFocus = MutableStateFlow<ApprovalFocus?>(null)

    private val events = conversation.map { it.id }.distinctUntilChanged().flatMapLatest { id ->
        // Tagged with its conversation so a stale list is never shown under another conversation.
        if (id == null) flowOf(null to emptyList()) else graph.data.events.observe(id).map { id to it }
    }
    private val live = agent.runner.flatMapLatest { it?.live ?: flowOf(null) }
    private val active = agent.runner.flatMapLatest { it?.active ?: flowOf(null) }
    private val pending = agent.runner.flatMapLatest { it?.pendingConversations ?: flowOf(null) }

    private val conversations = graph.data.conversations.observeAll()
    private val projects = graph.data.projects.observeAll()
    // Serialize conversation changes and durable task handoffs with unbinding from the service.
    private val conversationLock = Mutex()
    private var activityStarted = false

    private val selection = combine(conversation, approvalFocus, projectPageId, ::Triple)
    private val lists = combine(conversations, projects, deletingProjects) { all, projects, deleting -> Triple(all, projects, deleting) }
    private val header = combine(
        screen, providerConfigured, selection, lists, graph.runner.pendingApprovals,
    ) { screen, configured, (chat, focus, projectPageId), (all, projects, deleting), approvals ->
        val id = chat.id
        val current = all.firstOrNull { it.id == id }
        val chatProjectId = current?.projectId ?: chat.draftProjectId
        ChatState(
            screen = screen,
            providerConfigured = configured,
            conversations = all,
            projects = projects,
            projectPage = projects.firstOrNull { it.id == projectPageId },
            deletingProjectIds = deleting,
            conversationId = id,
            draftProjectId = chat.draftProjectId,
            chatProjectName = projects.firstOrNull { it.id == chatProjectId }?.name,
            title = current?.title.orEmpty(),
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
        conversation.value = ChatSelection(graph.data.conversations.latest()?.id)
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
                if (screen.value != Screen.CHAT || approval.conversationId != conversation.value.id) return@withLock
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

    fun openProjects() { screen.value = Screen.PROJECTS }

    fun openProject(id: String) {
        if (id in deletingProjects.value) return
        if (state.value.projects.firstOrNull { it.id == id }?.deleting == true) return
        projectPageId.value = id
        screen.value = Screen.PROJECT
    }

    fun createProject(name: String) {
        viewModelScope.launch {
            try {
                val project = graph.data.projects.create(name) ?: return@launch
                if (screen.value == Screen.PROJECTS) openProject(project.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(getApplication(), R.string.project_create_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Saves the open project's instructions verbatim; empty clears them. The editor stays open when saving fails. */
    fun saveProjectInstructions(id: String, instructions: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val saved = try {
                graph.data.projects.saveInstructions(id, instructions)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            onComplete(saved)
        }
    }

    /** The selection a deletion may stale, captured before the mark hides member rows. */
    private class DeletionCapture(val selection: ChatSelection, val projectId: String?)

    /** Deletes the project and its owned data, or resumes an interrupted deletion. The marked row
     * keeps its resume action until cleanup succeeds; busy and failed cleanups are reported separately. */
    fun deleteProject(id: String) {
        if (id in deletingProjects.value) return
        deletingProjects.value = deletingProjects.value + id
        viewModelScope.launch {
            var removed = false
            try {
                val captured = captureDeletion()
                var busy = false
                try {
                    removed = graph.data.projects.delete(id)
                    busy = !removed
                    if (busy) {
                        Toast.makeText(getApplication(), R.string.project_delete_busy, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(getApplication(), R.string.project_delete_failed, Toast.LENGTH_SHORT).show()
                }
                if (busy) return@launch
                // Reconcile against persisted state, not list emissions: Room can conflate the mark
                // and removal into one missing-project emission, so fast success never shows deleting=true.
                val intact = !removed &&
                    graph.data.projects.observeAll().first().any { it.id == id && !it.deleting }
                if (intact) return@launch
                conversationLock.withLock {
                    val current = conversation.value
                    val capturedIsOpen = current.id == captured.selection.id &&
                        current.draftProjectId == captured.selection.draftProjectId
                    if ((captured.projectId == id && capturedIsOpen) || current.draftProjectId == id) {
                        conversation.value = ChatSelection()
                    }
                    if (projectPageId.value == id) {
                        projectPageId.value = null
                        if (screen.value == Screen.PROJECT) screen.value = Screen.PROJECTS
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!removed) Toast.makeText(getApplication(), R.string.project_delete_failed, Toast.LENGTH_SHORT).show()
            } finally {
                deletingProjects.value = deletingProjects.value - id
            }
        }
    }

    /** Captures the open selection and its project under the lock, so a concurrent send settles first. */
    private suspend fun captureDeletion(): DeletionCapture = conversationLock.withLock {
        val selection = conversation.value
        val projectId = selection.draftProjectId
            ?: graph.data.conversations.observeAll().first().firstOrNull { it.id == selection.id }?.projectId
        DeletionCapture(selection, projectId)
    }

    /** Opens the read-only action log of the open conversation; a new unsaved conversation has none. */
    fun openActionLog() {
        if (conversation.value.id != null) screen.value = Screen.ACTION_LOG
    }

    fun closeActionLog() { screen.value = Screen.CHAT }

    fun openConversation(id: String) {
        approvalFocus.value = null
        conversation.value = ChatSelection(id)
        screen.value = Screen.CHAT
    }

    /** The conversation itself is created on the first message, so nothing is written here. */
    fun newConversation() = startConversation(null)

    fun newProjectConversation(projectId: String) = startConversation(projectId)

    private fun startConversation(projectId: String?) {
        approvalFocus.value = null
        conversation.value = ChatSelection(draftProjectId = projectId)
        screen.value = Screen.CHAT
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { graph.data.conversations.rename(id, title) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationLock.withLock {
                try {
                    if (!graph.data.conversations.delete(id)) {
                        Toast.makeText(getApplication(), R.string.conversation_delete_busy, Toast.LENGTH_SHORT).show()
                    } else if (conversation.value.id == id) {
                        conversation.value = ChatSelection()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(getApplication(), R.string.conversation_delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Stops the answer of the open conversation; does nothing when it already finished. */
    fun stop() {
        val taskId = state.value.runningTaskId ?: return
        agent.runner.value?.cancel(taskId)
    }

    fun send(text: String, onComplete: (Boolean) -> Unit = {}) {
        val input = text.trim()
        if (input.isEmpty() || !providerConfigured.value) {
            onComplete(false)
            return
        }
        val selection = conversation.value
        viewModelScope.launch {
            conversationLock.withLock {
                val accepted = try {
                    val id = selection.savedId ?: graph.data.conversations.create(input, selection.draftProjectId).id.also {
                        selection.savedId = it
                    }
                    val runner = agent.runner.filterNotNull().first()
                    if (conversation.value.id == id) approvalFocus.value = null
                    runner.submit(TaskRequest(Origin.User, id, input))
                    if (conversation.value === selection && selection.id == null) conversation.value = ChatSelection(id)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(getApplication(), R.string.chat_send_failed, Toast.LENGTH_SHORT).show()
                    false
                }
                onComplete(accepted)
            }
        }
    }

    override fun onCleared() {
        agent.disconnect()
        super.onCleared()
    }
}
