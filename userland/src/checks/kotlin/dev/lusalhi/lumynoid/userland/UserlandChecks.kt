package dev.lusalhi.lumynoid.userland

import android.content.Context
import android.content.res.AssetManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.lusalhi.lumynoid.core.ApprovalDecision
import dev.lusalhi.lumynoid.core.EventRepository
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.LlmChunk
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.Tool
import dev.lusalhi.lumynoid.core.ToolContext
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.ToolProvider
import dev.lusalhi.lumynoid.core.ToolRegistry
import dev.lusalhi.lumynoid.core.UiText
import dev.lusalhi.lumynoid.core.UnfinishedTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

internal class UserlandChecks(private val target: Context, private val fixtureAssets: AssetManager) {
    suspend fun approvalDenialThenInstallAndBuiltInOperations() = withFixture { fixture ->
        val runner = fixture.runner(
            mapOf(
                "deny" to listOf(setup),
                "install" to listOf(setup, versions, listing),
                "unknown" to listOf(Call("shell_runtime_probe", """{"operation":"shell"}""")),
                "invalid-workspace" to listOf(listing),
            ),
        )
        val denied = fixture.submit(runner, "deny")
        val approval = fixture.approval(denied)
        assertEquals(RiskAssessment.EGRESS, approval.approval.risk)
        assertEquals(0, fixture.opens.get())
        assertTrue(runner.resolve(approval.approval.id, ApprovalDecision.DENY))
        assertEquals(FailureReason.APPROVAL_DENIED, (fixture.terminal(denied) as TaskEvent.TaskFailed).reason)
        assertEquals(0, fixture.opens.get())
        fixture.assertNoInstall()

        Files.write(fixture.workspace.resolve("workspace-marker.txt"), byteArrayOf(42))
        val filenames = (1..220).map { "file-$it-${"x".repeat(80)}.txt" }
        filenames.forEach { Files.createFile(fixture.workspace.resolve(it)) }
        val task = fixture.submit(runner, "install")
        fixture.approve(runner, task)
        assertTrue(fixture.terminal(task) is TaskEvent.TaskFinished)
        assertEquals(1, fixture.opens.get())
        assertTrue(fixture.installer.locate() != null)
        val outcomes = fixture.results(task)
        assertEquals(3, outcomes.size)
        assertEquals(outcomes[1].output, 0, outcomes[1].exitCode)
        assertTrue(outcomes[1].output, outcomes[1].output.lineSequence().toList().containsAll(listOf("v22.23.5", "11.6.4")))
        assertEquals(outcomes[2].output, 0, outcomes[2].exitCode)
        assertEquals(
            (filenames + listOf(".", "..", "workspace-marker.txt")).toSet(),
            outcomes[2].output.lineSequence().filter { it.isNotEmpty() }.toSet(),
        )

        val unknown = fixture.submit(runner, "unknown")
        assertEquals(FailureReason.TOOL_DENIED, (fixture.terminal(unknown) as TaskEvent.TaskFailed).reason)
        Files.createSymbolicLink(fixture.workspace.parent.resolve("wrong"), fixture.workspace)
        for (workspaceId in listOf("../workspace", "wrong")) {
            val bad = fixture.submit(runner, "invalid-workspace", workspaceId)
            assertTrue(fixture.terminal(bad) is TaskEvent.TaskFailed)
            assertTrue(fixture.results(bad).isEmpty())
        }
        fixture.assertScratchEmpty()
    }

    suspend fun alteredArchiveAndCancelledTransferLeaveNoInstallAndCanRetry() = withFixture { fixture ->
        val runner = fixture.runner(mapOf("altered" to listOf(setup), "cancelled" to listOf(setup), "retry" to listOf(setup)))
        fixture.sourceMode = SourceMode.ALTERED
        val altered = fixture.submit(runner, "altered")
        fixture.approve(runner, altered)
        assertTrue(fixture.terminal(altered) is TaskEvent.TaskFailed)
        assertEquals(1, fixture.opens.get())
        fixture.assertNoInstall()

        fixture.sourceMode = SourceMode.PAUSE
        val cancelled = fixture.submit(runner, "cancelled")
        fixture.approve(runner, cancelled)
        withTimeout(10_000) { fixture.transferStarted.await() }
        runner.cancel(cancelled)
        assertTrue(fixture.terminal(cancelled) is TaskEvent.TaskCancelled)
        assertEquals(2, fixture.opens.get())
        fixture.assertNoInstall()

        fixture.sourceMode = SourceMode.VALID
        val retry = fixture.submit(runner, "retry")
        fixture.approve(runner, retry)
        assertTrue(fixture.terminal(retry) is TaskEvent.TaskFinished)
        assertTrue(fixture.installer.locate() != null)
        assertEquals(3, fixture.opens.get())
    }

    suspend fun normalExitFailureAndDeadlineReapDescendantsBeforeNextTask() = withFixture { fixture ->
        val calls = mapOf(
            "install" to listOf(setup),
            "exit" to listOf(Call("shell_fixture_exit")),
            "fail" to listOf(Call("shell_fixture_fail")),
            "deadline" to listOf(Call("shell_fixture_deadline")),
        )
        val runner = fixture.runner(
            calls,
            fixture.forkingTool("exit", "exit 0"),
            fixture.forkingTool("fail", "exit 9"),
            fixture.forkingTool("deadline", "wait", timeout = 1_000),
        )
        fixture.install(runner)
        for ((scenario, code) in listOf("exit" to 0, "fail" to 9, "deadline" to null)) {
            fixture.clearPids()
            val task = fixture.submit(runner, scenario)
            val peer = fixture.submit(runner, "peer")
            assertTrue(fixture.terminal(task) is TaskEvent.TaskFinished)
            val result = fixture.results(task).single()
            assertEquals(code, result.exitCode)
            if (scenario == "deadline") assertEquals("deadline", result.output)
            assertTrue("Peer ran before cleanup in $scenario", fixture.terminal(peer) is TaskEvent.TaskFinished)
            fixture.assertScratchEmpty()
        }
    }

    suspend fun cancellationAndApprovalReleaseAnActiveTreeBeforeNextTask() = withFixture { fixture ->
        val runner = fixture.runner(
            mapOf(
                "install" to listOf(setup),
                "cancel" to listOf(Call("shell_fixture_cancel")),
                "error" to listOf(Call("shell_fixture_error")),
                "approval" to listOf(Call("shell_fixture_approval"), setup),
            ),
            fixture.forkingTool("cancel", "wait"),
            fixture.forkingTool("error", "wait", whileActive = { error("Fixture tool failed") }),
            fixture.forkingTool("approval", "wait", whileActive = { ToolOutcome("waiting for approval") }),
        )
        fixture.install(runner)
        val cancelled = fixture.submit(runner, "cancel")
        fixture.awaitLivePids()
        val peerAfterCancel = fixture.submit(runner, "peer")
        runner.cancel(cancelled)
        assertTrue(fixture.terminal(cancelled) is TaskEvent.TaskCancelled)
        assertTrue(fixture.terminal(peerAfterCancel) is TaskEvent.TaskFinished)
        fixture.assertScratchEmpty()

        fixture.clearPids()
        val failed = fixture.submit(runner, "error")
        val peerAfterFailure = fixture.submit(runner, "peer")
        assertTrue(fixture.terminal(failed) is TaskEvent.TaskFailed)
        assertTrue(fixture.terminal(peerAfterFailure) is TaskEvent.TaskFinished)
        fixture.assertScratchEmpty()

        fixture.clearPids()
        val approvalTask = fixture.submit(runner, "approval")
        val peerAfterApproval = fixture.submit(runner, "peer")
        fixture.approval(approvalTask)
        assertTrue(fixture.terminal(peerAfterApproval) is TaskEvent.TaskFinished)
        fixture.assertScratchEmpty()
        fixture.approve(runner, approvalTask)
        assertTrue(fixture.terminal(approvalTask) is TaskEvent.TaskFinished)
        assertEquals(1, fixture.opens.get())
    }

    suspend fun releaseStopsOtherProcessesWhenOneCleanupFails() = withFixture { fixture ->
        val taskId = "cleanup-failure"
        val command = listOf("/system/bin/sleep", "60")
        val failure = java.io.IOException("Failed to delete scratch directory")
        val processes = fixture.processes
        processes.start(taskId, command, fixture.workspace, emptyMap(), afterExit = { throw failure })
        val second = processes.start(taskId, command, fixture.workspace, emptyMap())
        try {
            assertEquals(failure, runCatching { processes.release(taskId) }.exceptionOrNull())
            assertTrue("Second process was not stopped", second.await(1_000) is ProcessResult.Exited)
        } finally {
            processes.release(taskId)
        }
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            test(fixture)
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture {
        private val spec = RootfsSpec.load(target.assets)
        private val native = Paths.get(target.applicationInfo.nativeLibraryDir)
        private val files = Files.createTempDirectory(target.filesDir.toPath(), "userland-test-")
        private val rootfsDirectory = files.resolve("rootfs")
        private val scratch = files.resolve("scratch")
        val workspace: Path = Files.createDirectories(files.resolve("workspaces/workspace"))
        private val pidFiles = listOf(workspace.resolve("direct.pid"), workspace.resolve("detached.pid"))
        val opens = AtomicInteger()
        val transferStarted = CompletableDeferred<Unit>()
        @Volatile var sourceMode = SourceMode.VALID
        val processes = UserlandProcesses(native.resolve("libuserland_supervisor.so"))
        val installer = RootfsInstaller(rootfsDirectory, spec, RootfsSource(::transfer), processes)
        private val runtime = UserlandRuntime(
            native, workspace.parent, scratch, installer, processes, "https://example.invalid/${spec.asset}",
        )
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val events = FixtureEvents()

        private suspend fun transfer(write: (ByteArray, Int) -> Unit) {
            opens.incrementAndGet()
            fixtureAssets.open("${spec.asset}.fixture").use { input ->
                val buffer = ByteArray(64 * 1024)
                var first = true
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (first && sourceMode == SourceMode.ALTERED) buffer[0] = buffer[0].inc()
                    write(buffer, count)
                    if (first && sourceMode == SourceMode.PAUSE) {
                        transferStarted.complete(Unit)
                        awaitCancellation()
                    }
                    first = false
                }
            }
        }

        fun runner(calls: Map<String, List<Call>>, vararg fixtureTools: Tool): TaskRunner {
            val llm = LlmPort { request ->
                flow {
                    val message = request.messages.last()
                    if (message.role == Role.TOOL) {
                        emit(LlmChunk.TextDelta("complete"))
                    } else if (message.content == "peer") {
                        check(pids().none(::alive)) { "Previous runtime process is still alive" }
                        emit(LlmChunk.TextDelta("clean"))
                    } else {
                        calls.getValue(message.content).forEachIndexed { index, call ->
                            emit(LlmChunk.ToolCallDelta(index, call.name, call.args))
                        }
                    }
                    emit(LlmChunk.Done)
                }
            }
            return TaskRunner(
                llm, events, scope, tools = ToolRegistry(listOf(runtime, ToolProvider { fixtureTools.toList() })),
                toolSessions = runtime,
            )
        }

        suspend fun submit(runner: TaskRunner, message: String, workspaceId: String = "workspace") =
            runner.submit(TaskRequest(Origin.User, "$message-${events.nextConversation.incrementAndGet()}", workspaceId, message))

        suspend fun approval(task: String) =
            events.await(task) { it is TaskEvent.ApprovalRequested } as TaskEvent.ApprovalRequested

        suspend fun approve(runner: TaskRunner, task: String) {
            assertTrue(runner.resolve(approval(task).approval.id, ApprovalDecision.APPROVE))
        }

        suspend fun terminal(task: String) = events.await(task) { !it.needsRecovery }

        fun results(task: String) = events.all.value.map { it.second }
            .filterIsInstance<TaskEvent.ToolResult>().filter { it.taskId == task }.map { it.outcome }

        suspend fun install(runner: TaskRunner) {
            val task = submit(runner, "install")
            approve(runner, task)
            assertTrue(terminal(task) is TaskEvent.TaskFinished)
        }

        fun assertNoInstall() {
            assertNull(installer.locate())
            assertEquals(emptyList<String>(), rootfsDirectory.toFile().list()?.toList().orEmpty())
        }

        fun assertScratchEmpty() {
            assertEquals(emptyList<String>(), scratch.toFile().list()?.toList().orEmpty())
        }

        private fun pids() = pidFiles.map { it.toFile().readText().trim().toInt() }

        fun clearPids() = pidFiles.forEach { Files.deleteIfExists(it) }

        private fun alive(pid: Int): Boolean = try {
            Os.kill(pid, 0)
            true
        } catch (e: ErrnoException) {
            e.errno != OsConstants.ESRCH
        }

        suspend fun awaitLivePids() = withTimeout(10_000) {
            while (!pidFiles.all { Files.exists(it) && Files.size(it) > 0L }) delay(10)
            check(pids().all(::alive)) { "Fixture descendants did not start" }
        }

        fun forkingTool(
            scenario: String,
            finish: String,
            timeout: Long = 30_000,
            whileActive: (() -> ToolOutcome)? = null,
        ) = object : Tool {
            override val name = "shell_fixture_$scenario"
            override val description = "Run the process lifetime fixture."
            override val inputSchema = buildJsonObject { put("type", "object") }
            override fun assess(args: JsonObject) = RiskAssessment.READ_ONLY
            override fun describe(args: JsonObject) = UiText("process_fixture")

            override suspend fun execute(args: JsonObject, context: ToolContext): ToolOutcome {
                val script = """
                    /bin/busybox setsid /bin/sh -c 'echo "$$" > /workspace/detached.pid; exec /bin/sleep 60' &
                    /bin/sleep 60 &
                    echo "$!" > /workspace/direct.pid
                    while [ ! -s /workspace/detached.pid ]; do /bin/sleep 0.02; done
                    $finish
                """.trimIndent()
                val session = runtime.startGuest(context, listOf("/bin/sh", "-c", script))
                if (whileActive != null) {
                    awaitLivePids()
                    return whileActive()
                }
                return when (val result = session.await(timeout)) {
                    ProcessResult.Deadline -> ToolOutcome("deadline")
                    is ProcessResult.Exited -> ToolOutcome(result.output, exitCode = result.code)
                }
            }
        }

        suspend fun close() {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            deleteTree(files)
        }
    }

    private class FixtureEvents : EventRepository {
        val nextConversation = AtomicInteger()
        val all = MutableStateFlow<List<Pair<String, TaskEvent>>>(emptyList())
        override suspend fun createTask(taskId: String, request: TaskRequest, at: Long) = Unit
        override suspend fun append(conversationId: String, event: TaskEvent) {
            all.update { it + (conversationId to event) }
        }
        override fun observe(conversationId: String) = all.map { it.filter { (id, _) -> id == conversationId }.map { it.second } }
        override suspend fun history(conversationId: String) = all.value.filter { (id, _) -> id == conversationId }.map { it.second }
        override suspend fun unfinishedTasks() = emptyList<UnfinishedTask>()

        suspend fun await(task: String, matches: (TaskEvent) -> Boolean): TaskEvent = withTimeout(30_000) {
            all.first { log -> log.any { (_, event) -> event.taskId == task && matches(event) } }
                .first { (_, event) -> event.taskId == task && matches(event) }.second
        }
    }

    private enum class SourceMode { VALID, ALTERED, PAUSE }
    private data class Call(val name: String, val args: String = "{}")

    private companion object {
        val setup = Call("shell_runtime_setup")
        val versions = Call("shell_runtime_probe", """{"operation":"versions"}""")
        val listing = Call("shell_runtime_probe", """{"operation":"list_workspace"}""")
    }
}
