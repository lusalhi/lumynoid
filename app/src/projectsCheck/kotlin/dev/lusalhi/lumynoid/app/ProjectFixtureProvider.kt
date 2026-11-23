package dev.lusalhi.lumynoid.app

import android.content.Context
import android.os.SystemClock
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmError
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.LlmRequest
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.ToolArtifact
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.ToolRegistry
import dev.lusalhi.lumynoid.data.LumynoidData
import dev.lusalhi.lumynoid.userland.UserlandRuntime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID

/**
 * Shared fixture support for the Projects lifecycle checks (stories 8.1, 8.4, 8.5, 8.6).
 *
 * Every check runs against the production graph — real Room storage through [LumynoidData], the
 * real [TaskRunner] admission gate and the real userland coding tools — with a deterministic
 * scripted [LlmPort] and a Ktor MockEngine that serves the packaged rootfs archive offline.
 * The launcher hands each case an isolated context (own databases/files/cache roots); fixtures
 * never touch the signed app's data and never open a network connection.
 */

/** Every assertion names the observation that failed so the one-tap report is actionable. */
internal fun expect(condition: Boolean, observation: String) {
    if (!condition) throw AssertionError("Failed observation: $observation")
}

internal fun <T> expectEquals(expected: T, actual: T, observation: String) {
    if (expected != actual) {
        throw AssertionError("Failed observation: $observation — expected [$expected] but was [$actual]")
    }
}

internal fun <T : Any> expectNotNull(value: T?, observation: String): T {
    if (value == null) throw AssertionError("Failed observation: $observation — value was null")
    return value
}

internal suspend inline fun <reified E : Throwable> expectThrows(observation: String, block: suspend () -> Unit) {
    val error = runCatching { block() }.exceptionOrNull()
    if (error !is E) {
        val actual = error?.let { "${it::class.java.simpleName}: ${it.message}" } ?: "success"
        throw AssertionError("Failed observation: $observation — expected ${E::class.java.simpleName} but was $actual")
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** One scripted LLM invocation. Steps are consumed in order of completed tool rounds. */
internal sealed interface FixtureStep {
    data class Reply(val text: String) : FixtureStep
    data class Invoke(val name: String, val argsJson: String) : FixtureStep

    /** Streams a partial answer, then suspends until the check releases the gate. */
    data class BlockedReply(val text: String, val gate: CompletableDeferred<Unit>) : FixtureStep
}

internal fun fixtureCall(name: String, vararg args: Pair<String, String>): FixtureStep.Invoke =
    FixtureStep.Invoke(name, buildJsonObject { for ((key, value) in args) put(key, value) }.toString())

/**
 * Deterministic [LlmPort]: the current user input selects a script, and the number of TOOL
 * messages already in the request selects the next step. Approval continuations replay the
 * captured request, so the step index stays stable across a pause. Every request is recorded
 * for the history/instruction assertions; a script mismatch fails the task and is rethrown by
 * the fixture's terminal wait with the original observation.
 */
internal class ScriptedLlm(private val script: Map<String, List<FixtureStep>>) : LlmPort {
    private val lock = Any()
    private val recorded = mutableListOf<Pair<String, LlmRequest>>()

    @Volatile
    var scriptFailure: Throwable? = null
        private set

    override fun stream(request: LlmRequest): Flow<LlmChunk> {
        val user = request.messages.indexOfLast { it.role == Role.USER }
        val input = request.messages.getOrNull(user)?.content
        val steps = input?.let { script[it] }
        var round = 0
        for (index in user + 1 until request.messages.size) {
            if (request.messages[index].role == Role.TOOL) round++
        }
        val step = steps?.getOrNull(round)
        if (input == null || step == null) {
            val failure = AssertionError(
                "Failed observation: fixture LLM script mismatch — input [$input] invocation ${round + 1} " +
                    "has no scripted step (${steps?.size ?: 0} steps scripted)",
            )
            scriptFailure = failure
            return flow { emit(LlmChunk.Failed(LlmError.Server)) }
        }
        synchronized(lock) { recorded += input to request }
        return flow {
            when (step) {
                is FixtureStep.Reply -> emit(LlmChunk.TextDelta(step.text))
                is FixtureStep.Invoke -> emit(LlmChunk.ToolCallDelta(0, step.name, step.argsJson))
                is FixtureStep.BlockedReply -> {
                    emit(LlmChunk.TextDelta(step.text))
                    step.gate.await()
                }
            }
            emit(LlmChunk.Done)
        }
    }

    fun requests(input: String): List<LlmRequest> =
        synchronized(lock) { recorded.filter { it.first == input }.map { it.second } }
}

/**
 * Offline stand-in for the release download: serves exactly the URL [UserlandRuntime.create]
 * derives from the installed version tag, from the packaged `${asset}.fixture` bytes. Any other
 * request is refused, so no check can reach the network.
 */
internal fun fixtureHttpClient(context: Context): HttpClient {
    val properties = Properties()
    context.assets.open("lumynoid-userland.properties").use(properties::load)
    val asset = expectNotNull(
        properties.getProperty("asset"),
        "rootfs spec asset name in lumynoid-userland.properties",
    )
    val size = expectNotNull(properties.getProperty("size"), "rootfs spec size")
    val tag = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    val url = "https://github.com/lusalhi/lumynoid/releases/download/$tag/$asset"
    return HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                if (request.url.toString() == url) {
                    val stream = context.assets.open("$asset.fixture").toByteReadChannel(Dispatchers.IO)
                    respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, size))
                } else {
                    respond("Fixture engine refuses unexpected request: ${request.url}", HttpStatusCode.NotFound)
                }
            }
        }
    }
}

/**
 * One isolated production graph: Room + runner + userland over the launcher-supplied fixture
 * context. [deleteWorkspace] is the project-deletion boundary; checks pass the production
 * [UserlandRuntime.deleteWorkspace] unless they need a deterministic I/O block.
 */
internal class ProjectFixture(
    val context: Context,
    script: Map<String, List<FixtureStep>>,
    deleteWorkspace: suspend (String) -> Unit,
) {
    val llm = ScriptedLlm(script)
    val http: HttpClient = fixtureHttpClient(context)
    val userland: UserlandRuntime = try {
        expectNotNull(
            runCatching { UserlandRuntime.create(context, http) }.getOrElse {
                throw AssertionError("Failed observation: UserlandRuntime.create — ${it.message}", it)
            },
            "UserlandRuntime.create (requires arm64-v8a and an executable libuserland_supervisor.so)",
        )
    } catch (e: Throwable) {
        http.close()
        throw e
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val data: LumynoidData = LumynoidData(
        context,
        deleteWhenIdle = { id, delete -> runner.deleteWhenIdle(id, delete) },
        withLifecycle = { action -> runner.withConversationLifecycle(action) },
        deleteWorkspace = deleteWorkspace,
    )
    val runner: TaskRunner by lazy {
        TaskRunner(
            llm, data.events, scope,
            tools = ToolRegistry(listOf(userland)),
            toolSessions = userland,
            elapsedMillis = SystemClock::elapsedRealtime,
            maxLlmCalls = data.taskSettings::maxLlmCalls,
            resolveWorkspace = data.conversations::workspace,
            instructionsForWorkspace = data.projects::instructionsForWorkspace,
        )
    }

    fun workspaceDir(workspaceId: String): File = File(context.filesDir, "workspaces/$workspaceId")
    fun installsDir(workspaceId: String): File = File(context.filesDir, "coding-installs/$workspaceId")
    fun blobDir(conversationId: String): File = File(
        context.filesDir,
        "event-blobs/" + Base64.getUrlEncoder().withoutPadding().encodeToString(conversationId.encodeToByteArray()),
    )

    suspend fun previewBytes(conversationId: String, resultEventId: String, index: Int): ByteArray? {
        val published = resolvePublishedArtifact(data, context, conversationId, resultEventId, index) ?: return null
        return published.workspace.read(published.artifact.path)
    }

    suspend fun results(conversationId: String, taskId: String): List<ToolOutcome> =
        data.events.history(conversationId).filterIsInstance<TaskEvent.ToolResult>()
            .filter { it.taskId == taskId }.map { it.outcome }

    private suspend fun <T> bounded(observation: String, millis: Long = 60_000, block: suspend () -> T): T =
        try {
            withTimeout(millis) { block() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Failed observation: $observation — timed out after ${millis}ms", e)
        }

    suspend fun awaitEvent(
        conversationId: String,
        taskId: String,
        observation: String,
        matches: (TaskEvent) -> Boolean,
    ): TaskEvent = bounded(observation) {
        data.events.observe(conversationId)
            .map { events -> events.firstOrNull { it.taskId == taskId && matches(it) } }
            .first { it != null }!!
    }

    suspend fun awaitApproval(conversationId: String, taskId: String, observation: String): TaskEvent.ApprovalRequested =
        bounded(observation) {
            runner.pendingApprovals.map { approvals ->
                approvals.values.firstOrNull { it.conversationId == conversationId && it.event.taskId == taskId }
            }.first { it != null }!!.event
        }

    suspend fun awaitTerminal(conversationId: String, taskId: String, observation: String): TaskEvent {
        val event = awaitEvent(conversationId, taskId, observation) { !it.needsRecovery }
        llm.scriptFailure?.let { throw it }
        return event
    }

    suspend fun expectFinished(conversationId: String, taskId: String, observation: String) {
        val terminal = awaitTerminal(conversationId, taskId, observation)
        expect(
            terminal is TaskEvent.TaskFinished,
            "$observation — terminal was ${terminal::class.simpleName}" +
                (terminal as? TaskEvent.TaskFailed)?.let { " (${it.reason})" }.orEmpty(),
        )
    }

    suspend fun expectFailed(conversationId: String, taskId: String, reason: FailureReason, observation: String) {
        val terminal = awaitTerminal(conversationId, taskId, observation)
        expect(
            terminal is TaskEvent.TaskFailed && terminal.reason == reason,
            "$observation — terminal was $terminal",
        )
    }

    suspend fun awaitIdle(observation: String) {
        bounded(observation, 15_000) { runner.pendingConversations.first { it != null && it.isEmpty() } }
    }

    suspend fun awaitActive(conversationId: String, observation: String) {
        bounded(observation, 15_000) { runner.active.first { it?.conversationId == conversationId } }
    }

    private var closed = false

    /** Stops the runner scope first, then closes Room and the fixture engine. Idempotent. */
    suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            try {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                data.close()
            } finally {
                http.close()
            }
        }
    }
}

/** Seeds one workspace and its installed-package tree, as the instrumented deletion test does. */
internal fun seedWorkspaceTree(fixture: ProjectFixture, workspaceId: String, pageContent: String) {
    fixture.workspaceDir(workspaceId).apply {
        mkdirs()
        resolve("index.html").writeText(pageContent)
    }
    fixture.installsDir(workspaceId).apply {
        mkdirs()
        resolve("fixture").writeText("installed fixture")
    }
}

/**
 * Seeds a finished task whose large result is stored as an event blob, through the real Room
 * repository (the proven androidTest seeding pattern). Returns the result event ID.
 */
internal suspend fun seedFinishedTask(
    fixture: ProjectFixture,
    conversationId: String,
    workspaceId: String?,
    output: String,
    artifacts: List<ToolArtifact> = emptyList(),
): String {
    val taskId = UUID.randomUUID().toString()
    val resultId = UUID.randomUUID().toString()
    val at = System.currentTimeMillis()
    fixture.data.events.createTask(taskId, TaskRequest(Origin.User, conversationId, "seed fixture"), at, workspaceId)
    fixture.data.events.append(
        conversationId,
        TaskEvent.ToolResult(resultId, taskId, at, "seed-call", ToolOutcome(output, artifacts = artifacts, succeeded = true)),
    )
    fixture.data.events.append(conversationId, TaskEvent.TaskFinished(UUID.randomUUID().toString(), taskId, at + 1))
    return resultId
}

/** Large enough to leave the inline event row and live in the blob store. */
internal fun bigFixtureOutput(marker: String): String = "界".repeat(90_000) + marker