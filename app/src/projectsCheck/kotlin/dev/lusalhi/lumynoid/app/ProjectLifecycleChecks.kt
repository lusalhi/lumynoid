package dev.lusalhi.lumynoid.app

import android.content.Context
import dev.lusalhi.lumynoid.core.ActiveModel
import dev.lusalhi.lumynoid.core.ApprovalDecision
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.ProjectPort
import dev.lusalhi.lumynoid.core.ProviderConfig
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.Role
import dev.lusalhi.lumynoid.core.SecretRef
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.ToolArtifact
import dev.lusalhi.lumynoid.core.parseToolArguments
import dev.lusalhi.lumynoid.userland.UserlandRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * Projects lifecycle diagnostics (stories 8.1, 8.4, 8.5, 8.6). Each entrypoint is independent:
 * it builds its own production graph over the launcher's isolated fixture context, exercises real
 * Room storage, the real TaskRunner and the real coding tools with deterministic fixture responses,
 * and returns on success or throws an AssertionError naming the first failed observation. No check
 * opens a network connection; the packaged rootfs is served by the fixture HTTP engine.
 */

/** 8.1 — two chats share one project's file and bundled package, with separate logs and history. */
internal suspend fun checkSharedProject(context: Context) {
    val nonce = UUID.randomUUID().toString().take(8)
    val source = """{"n":9007199254740993,"s":"halo, 世界 $nonce","x":[true,null]}"""
    val updated = " $source\n"
    val answerA = "A complete $nonce"
    val answerB = "B complete $nonce"
    val answerPlain = "plain response $nonce"
    val inputSetup = "setup runtime $nonce"
    val inputA = "share write install $nonce"
    val inputB = "share read edit format $nonce"
    val inputOther = "other project read $nonce"
    val inputPlainDenied = "plain tool denied $nonce"
    val inputPlainReply = "plain reply $nonce"
    val fixture = ProjectFixture(
        context,
        mapOf(
            inputSetup to listOf(
                fixtureCall("shell_runtime_setup"),
                fixtureCall("shell_runtime_probe", "operation" to "versions"),
                FixtureStep.Reply("runtime ready $nonce"),
            ),
            inputA to listOf(
                fixtureCall("shell_create_file", "path" to "data.json", "content" to source),
                fixtureCall("shell_npm_install", "package" to "@lumynoid/json-tools"),
                FixtureStep.Reply(answerA),
            ),
            inputB to listOf(
                fixtureCall("shell_read_file", "path" to "data.json"),
                fixtureCall(
                    "shell_replace_file", "path" to "data.json", "content" to updated,
                    "expectedSha256" to sha256Hex(source.encodeToByteArray()),
                ),
                fixtureCall("shell_run_script", "script" to "format_json", "input" to "data.json", "output" to "formatted.json"),
                FixtureStep.Reply(answerB),
            ),
            inputOther to listOf(
                fixtureCall("shell_read_file", "path" to "data.json"),
                FixtureStep.Reply("other cannot read $nonce"),
            ),
            inputPlainDenied to listOf(fixtureCall("shell_read_file", "path" to "data.json")),
            inputPlainReply to listOf(FixtureStep.Reply(answerPlain)),
        ),
        deleteWorkspace = { id -> UserlandRuntime.deleteWorkspace(context, id) },
    )
    try {
        val data = fixture.data
        val runner = fixture.runner
        fixture.awaitIdle("runner recovery before shared-project checks")
        val project = expectNotNull(data.projects.create("Shared $nonce"), "create shared project")
        val instructions = "Use the project fixture $nonce."
        expect(data.projects.saveInstructions(project.id, instructions), "save shared project instructions")
        val other = expectNotNull(data.projects.create("Other $nonce"), "create other project")
        val chatSetup = data.conversations.create("setup chat", project.id)
        val chatA = data.conversations.create("writer chat", project.id)
        val chatB = data.conversations.create("editor chat", project.id)
        val chatOther = data.conversations.create("other chat", other.id)
        val plain = data.conversations.create("plain chat")

        // The runtime installs from the packaged fixture through the real approval + download path.
        val setupTask = runner.submit(TaskRequest(Origin.User, chatSetup.id, inputSetup))
        val setupApproval = fixture.awaitApproval(chatSetup.id, setupTask, "runtime setup approval")
        expectEquals(RiskAssessment.EGRESS, setupApproval.approval.risk, "runtime setup risk assessment")
        expect(runner.resolve(setupApproval.approval.id, ApprovalDecision.APPROVE), "approve runtime setup")
        fixture.expectFinished(chatSetup.id, setupTask, "runtime setup task")
        val setupResults = fixture.results(chatSetup.id, setupTask)
        expectEquals(2, setupResults.size, "runtime setup tool results")
        expect(setupResults[0].succeeded == true, "rootfs installed from packaged fixture")
        expectEquals(0, setupResults[1].exitCode, "node/npm probe exit code")
        expect(setupResults[1].output.lineSequence().any { it.startsWith("v") }, "node/npm probe reports versions")

        // A writes the file and installs the bundled package.
        val taskA = runner.submit(TaskRequest(Origin.User, chatA.id, inputA))
        fixture.expectFinished(chatA.id, taskA, "writer task")
        val resultsA = fixture.results(chatA.id, taskA)
        expectEquals(2, resultsA.size, "writer tool results")
        expect(
            resultsA[0].succeeded == true && resultsA[0].artifacts == listOf(ToolArtifact("data.json", "text/plain")),
            "A creates data.json",
        )
        expectEquals(0, resultsA[1].exitCode, "A npm install exit code")
        expect(resultsA[1].succeeded == true, "A installs the bundled package")
        val workspace = fixture.workspaceDir(project.workspaceId)
        expectEquals(source, File(workspace, "data.json").readText(), "shared file on disk after A")
        expect(fixture.installsDir(project.workspaceId).isDirectory, "shared package install directory exists")

        // B reads, edits (with approval) and formats the same file and package.
        val taskB = runner.submit(TaskRequest(Origin.User, chatB.id, inputB))
        val approvalB = fixture.awaitApproval(chatB.id, taskB, "overwrite approval")
        expectEquals(RiskAssessment.DESTRUCTIVE, approvalB.approval.risk, "overwrite risk assessment")
        expectEquals(source, File(workspace, "data.json").readText(), "file unchanged while overwrite awaits approval")
        expect(runner.resolve(approvalB.approval.id, ApprovalDecision.APPROVE), "approve overwrite")
        fixture.expectFinished(chatB.id, taskB, "editor task")
        val resultsB = fixture.results(chatB.id, taskB)
        expectEquals(3, resultsB.size, "editor tool results")
        val read = expectNotNull(parseToolArguments(resultsB[0].output), "B read output parses as JSON")
        expectEquals(source, read["content"]?.jsonPrimitive?.content, "B reads A's bytes")
        expectEquals(sha256Hex(source.encodeToByteArray()), read["sha256"]?.jsonPrimitive?.content, "B read SHA-256")
        expect(resultsB[1].succeeded == true, "B replace succeeded")
        expectEquals(0, resultsB[2].exitCode, "formatter exit code")
        expect(
            resultsB[2].succeeded == true && resultsB[2].artifacts == listOf(ToolArtifact("formatted.json", "application/json")),
            "B formats through the shared package",
        )
        expectEquals(updated, File(workspace, "data.json").readText(), "B edit on disk")
        val formatted = File(workspace, "formatted.json").readText()
        expect(formatted != updated, "formatter changed whitespace")
        expectEquals(Json.parseToJsonElement(updated), Json.parseToJsonElement(formatted), "formatter preserved JSON tokens")
        expectEquals(1, fixture.installsDir(project.workspaceId).listFiles()?.size, "one shared package install per project")

        // Separate logs and request history for the two chats.
        val historyA = data.events.history(chatA.id)
        val historyB = data.events.history(chatB.id)
        expect(historyA.isNotEmpty() && historyB.isNotEmpty(), "both chats keep an event log")
        expect(historyA.all { it.taskId == taskA }, "A log holds only A's task")
        expect(historyB.all { it.taskId == taskB }, "B log holds only B's task")
        expect(fixture.llm.requests(inputB).isNotEmpty(), "B requests recorded")
        for (input in listOf(inputA, inputB)) {
            expect(
                fixture.llm.requests(input).all { request ->
                    request.messages.any { it.role == Role.SYSTEM && it.content == instructions }
                },
                "both sibling chats use the same project instructions",
            )
        }
        expect(
            fixture.llm.requests(inputB).none { request -> request.messages.any { inputA in it.content || answerA in it.content } },
            "B requests carry no A history",
        )
        expect(
            fixture.llm.requests(inputA).none { request -> request.messages.any { inputB in it.content || answerB in it.content } },
            "A requests carry no B history",
        )

        // Another project cannot read A's file.
        val taskOther = runner.submit(TaskRequest(Origin.User, chatOther.id, inputOther))
        fixture.expectFinished(chatOther.id, taskOther, "other-project read task")
        val otherResult = fixture.results(chatOther.id, taskOther).single()
        expect(otherResult.succeeded == false, "cross-project read refused")
        expect(otherResult.artifacts.isEmpty(), "cross-project read publishes nothing")
        expect(source !in otherResult.output && nonce !in otherResult.output, "cross-project read reveals no shared bytes")
        expect(!File(fixture.workspaceDir(other.workspaceId), "data.json").exists(), "other workspace untouched")

        // A plain chat has no workspace tools, but still answers.
        val taskDenied = runner.submit(TaskRequest(Origin.User, plain.id, inputPlainDenied))
        fixture.expectFailed(plain.id, taskDenied, FailureReason.MALFORMED_TOOL_CALL, "plain chat cannot call workspace tools")
        val plainHistory = data.events.history(plain.id)
        expect(plainHistory.none { it is TaskEvent.ToolResult }, "plain chat executed no tool")
        expect(plainHistory.none { source in TaskEventJson.encode(it) }, "plain chat log reveals no shared bytes")
        val taskPlain = runner.submit(TaskRequest(Origin.User, plain.id, inputPlainReply))
        fixture.expectFinished(plain.id, taskPlain, "plain chat still responds")
        expectEquals(
            answerPlain,
            data.events.history(plain.id).filterIsInstance<TaskEvent.AssistantMessage>().single { it.taskId == taskPlain }.text,
            "plain chat answer",
        )

        fixture.close()
        val reopened = ProjectFixture(context, emptyMap()) { id -> UserlandRuntime.deleteWorkspace(context, id) }
        try {
            expectEquals(historyA, reopened.data.events.history(chatA.id), "writer log survives reopening")
            expectEquals(historyB, reopened.data.events.history(chatB.id), "editor log survives reopening")
            expectEquals(updated, File(reopened.workspaceDir(project.workspaceId), "data.json").readText(), "shared edit survives reopening")
            expectEquals(1, reopened.installsDir(project.workspaceId).listFiles()?.size, "shared package install survives reopening")
        } finally {
            reopened.close()
        }
    } finally {
        fixture.close()
    }
}

/** 8.4 — a pending overwrite keeps its captured instructions; the next task reads the new ones. */
internal suspend fun checkProjectInstructions(context: Context) {
    val nonce = UUID.randomUUID().toString().take(8)
    val old = "OLD rules $nonce: keep the captured request."
    val new = "NEW rules $nonce: govern the next task."
    val before = "before $nonce"
    val after = "after $nonce"
    val inputOverwrite = "overwrite notes $nonce"
    val inputSibling = "sibling task $nonce"
    val inputNext = "next task $nonce"
    val fixture = ProjectFixture(
        context,
        mapOf(
            inputOverwrite to listOf(
                fixtureCall("shell_create_file", "path" to "notes.txt", "content" to before),
                fixtureCall(
                    "shell_replace_file", "path" to "notes.txt", "content" to after,
                    "expectedSha256" to sha256Hex(before.encodeToByteArray()),
                ),
                FixtureStep.Reply("overwrite done $nonce"),
            ),
            inputSibling to listOf(FixtureStep.Reply("sibling done $nonce")),
            inputNext to listOf(FixtureStep.Reply("next done $nonce")),
        ),
        deleteWorkspace = { id -> UserlandRuntime.deleteWorkspace(context, id) },
    )
    try {
        val data = fixture.data
        val runner = fixture.runner
        fixture.awaitIdle("runner recovery before instruction checks")
        val project = expectNotNull(data.projects.create("Instructions $nonce"), "create instructions project")
        val chat = data.conversations.create("owner chat", project.id)
        val sibling = data.conversations.create("sibling chat", project.id)
        expect(data.projects.saveInstructions(project.id, old), "save old instructions")

        // The overwrite pauses awaiting approval under the old instructions.
        val task = runner.submit(TaskRequest(Origin.User, chat.id, inputOverwrite))
        val approval = fixture.awaitApproval(chat.id, task, "overwrite approval")
        expectEquals(RiskAssessment.DESTRUCTIVE, approval.approval.risk, "overwrite risk assessment")
        expectEquals(before, File(fixture.workspaceDir(project.workspaceId), "notes.txt").readText(), "overwrite waits for approval")

        // Instructions change while the overwrite is pending.
        expect(data.projects.saveInstructions(project.id, new), "save new instructions while pending")

        // A sibling task runs on the new instructions without the owner's history.
        val siblingTask = runner.submit(TaskRequest(Origin.User, sibling.id, inputSibling))
        fixture.expectFinished(sibling.id, siblingTask, "sibling task while approval pending")
        val siblingRequests = fixture.llm.requests(inputSibling)
        expectEquals(1, siblingRequests.size, "sibling LLM invocations")
        expectEquals(Role.SYSTEM, siblingRequests[0].messages.first().role, "sibling request leads with instructions")
        expectEquals(new, siblingRequests[0].messages.first().content, "sibling reads new instructions")
        expect(
            siblingRequests[0].messages.none { inputOverwrite in it.content || before in it.content || after in it.content },
            "sibling request carries no owner context",
        )
        expect(data.events.history(sibling.id).all { it.taskId == siblingTask }, "sibling log separate from owner")

        // Approving resumes the captured request: still the old instructions, no sibling context.
        expect(runner.resolve(approval.approval.id, ApprovalDecision.APPROVE), "approve overwrite")
        fixture.expectFinished(chat.id, task, "resumed overwrite")
        expectEquals(after, File(fixture.workspaceDir(project.workspaceId), "notes.txt").readText(), "approved overwrite applied")
        val requests = fixture.llm.requests(inputOverwrite)
        expectEquals(3, requests.size, "overwrite LLM invocations")
        expectEquals(old, requests[1].messages.first().content, "captured request uses old instructions")
        expectEquals(old, requests[2].messages.first().content, "resume continues under old instructions")
        expect(requests[2].messages.none { new in it.content }, "resume never picks up new instructions")
        expect(requests[2].messages.none { inputSibling in it.content }, "resume carries no sibling history")

        // The next fresh task reads the new instructions.
        val nextTask = runner.submit(TaskRequest(Origin.User, chat.id, inputNext))
        fixture.expectFinished(chat.id, nextTask, "next task")
        val nextRequests = fixture.llm.requests(inputNext)
        expectEquals(1, nextRequests.size, "next task LLM invocations")
        expectEquals(new, nextRequests[0].messages.first().content, "next task reads new instructions")
        expect(nextRequests[0].messages.none { inputSibling in it.content }, "next task has no sibling history")

        // The shared limit counts Unicode code points, and rejection keeps the saved value.
        val atLimit = "𠮷".repeat(ProjectPort.MAX_INSTRUCTION_CODE_POINTS)
        expect(data.projects.saveInstructions(project.id, atLimit), "2000 code points accepted")
        expectEquals(atLimit, data.projects.instructionsForWorkspace(project.workspaceId), "saved at-limit instructions")
        expect(!data.projects.saveInstructions(project.id, atLimit + "𠮷"), "2001 code points rejected")
        expectEquals(atLimit, data.projects.instructionsForWorkspace(project.workspaceId), "rejection keeps saved instructions")
    } finally {
        fixture.close()
    }
}

/** 8.5 — pending work denies deletion; idle deletion removes one chat while the project survives. */
internal suspend fun checkConversationDeletion(context: Context) {
    val nonce = UUID.randomUUID().toString().take(8)
    val shared = "shared project data $nonce"
    val inputSeed = "seed shared file $nonce"
    val inputBusy = "busy task $nonce"
    val inputRead = "read shared file $nonce"
    val gate = CompletableDeferred<Unit>()
    val fixture = ProjectFixture(
        context,
        mapOf(
            inputSeed to listOf(
                fixtureCall("shell_create_file", "path" to "shared.txt", "content" to shared),
                FixtureStep.Reply("seeded $nonce"),
            ),
            inputBusy to listOf(FixtureStep.BlockedReply("working $nonce", gate)),
            inputRead to listOf(
                fixtureCall("shell_read_file", "path" to "shared.txt"),
                FixtureStep.Reply("read done $nonce"),
            ),
        ),
        deleteWorkspace = { id -> UserlandRuntime.deleteWorkspace(context, id) },
    )
    try {
        val data = fixture.data
        val runner = fixture.runner
        fixture.awaitIdle("runner recovery before chat-deletion checks")
        val project = expectNotNull(data.projects.create("Chat deletion $nonce"), "create deletion project")
        val busy = data.conversations.create("busy member", project.id)
        val idle = data.conversations.create("idle member", project.id)

        // The idle chat owns a finished task whose large result lives in the blob store.
        seedFinishedTask(fixture, idle.id, project.workspaceId, bigFixtureOutput("idle $nonce"))
        expect(fixture.blobDir(idle.id).isDirectory, "idle chat blob exists before deletion")

        // The shared workspace file is created through a real coding task.
        val seedTask = runner.submit(TaskRequest(Origin.User, busy.id, inputSeed))
        fixture.expectFinished(busy.id, seedTask, "seed shared file task")
        expectEquals(shared, File(fixture.workspaceDir(project.workspaceId), "shared.txt").readText(), "shared file on disk")
        val installMarker = File(fixture.installsDir(project.workspaceId), "fixture-package/marker")
        checkNotNull(installMarker.parentFile).mkdirs()
        installMarker.writeText(nonce)

        // Unfinished work denies both chat and project deletion without marking anything.
        val busyTask = runner.submit(TaskRequest(Origin.User, busy.id, inputBusy))
        fixture.awaitActive(busy.id, "busy task running")
        expect(!data.conversations.delete(busy.id), "pending chat deletion denied")
        expect(!data.projects.delete(project.id), "pending project deletion denied")
        expect(data.conversations.observeAll().first().any { it.id == busy.id }, "busy chat kept while pending")
        expect(data.events.history(busy.id).isNotEmpty(), "busy chat log kept while pending")
        expect(!data.projects.observeAll().first().single { it.id == project.id }.deleting, "denied deletion leaves no marker")
        expect(fixture.blobDir(idle.id).isDirectory, "idle chat untouched by denied deletion")

        // Once idle, deletion removes only the idle chat, its log and its blobs.
        gate.complete(Unit)
        fixture.expectFinished(busy.id, busyTask, "busy task completes")
        fixture.awaitIdle("runner idle after busy task")
        expect(data.conversations.delete(idle.id), "idle chat deletion accepted")
        expect(data.events.history(idle.id).isEmpty(), "idle chat log removed")
        expect(!fixture.blobDir(idle.id).exists(), "idle chat blobs removed")
        expect(data.conversations.observeAll().first().none { it.id == idle.id }, "idle chat row removed")
        expect(data.conversations.observeAll().first().any { it.id == busy.id }, "surviving member kept")
        expect(data.projects.observeAll().first().any { it.id == project.id }, "project kept after chat deletion")
        expectEquals(shared, File(fixture.workspaceDir(project.workspaceId), "shared.txt").readText(), "project data survives chat deletion")
        expectEquals(nonce, installMarker.readText(), "project package storage survives chat deletion")

        // The surviving member can still use the project's data through the real tools.
        val readTask = runner.submit(TaskRequest(Origin.User, busy.id, inputRead))
        fixture.expectFinished(busy.id, readTask, "survivor read task")
        val readOutcome = fixture.results(busy.id, readTask).single()
        expect(readOutcome.succeeded == true, "survivor reads project data")
        val readJson = expectNotNull(parseToolArguments(readOutcome.output), "survivor read output parses")
        expectEquals(shared, readJson["content"]?.jsonPrimitive?.content, "survivor reads the shared bytes")
    } finally {
        fixture.close()
    }
}

/** 8.6 — a deletion interrupted after its durable marker resumes on reopen and removes only the target. */
internal suspend fun checkProjectDeletion(context: Context) {
    val nonce = UUID.randomUUID().toString().take(8)
    val survivorContent = "survivor page $nonce"
    val inputSurvive = "survivor works $nonce"
    val inputRead = "survivor reads $nonce"
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val boundaryCalls = Collections.synchronizedList(mutableListOf<String>())
    val provider = ProviderConfig("provider-$nonce", "Fixture", null, "https://fixture.invalid/v1", SecretRef("key-$nonce"))

    // Phase 1: cleanup stops at a deterministic fixture I/O block after the durable marker commits.
    val first = ProjectFixture(
        context,
        mapOf(inputSurvive to listOf(FixtureStep.Reply("survivor ok $nonce"))),
        deleteWorkspace = { id ->
            boundaryCalls += id
            entered.complete(Unit)
            release.await()
        },
    )
    try {
        val data = first.data
        first.awaitIdle("runner recovery before project-deletion checks")
        val doomed = expectNotNull(data.projects.create("Doomed $nonce"), "create doomed project")
        val survivor = expectNotNull(data.projects.create("Survivor $nonce"), "create survivor project")
        expect(data.projects.saveInstructions(doomed.id, "Doomed rules $nonce"), "save doomed instructions")
        expect(data.projects.saveInstructions(survivor.id, "Survivor rules $nonce"), "save survivor instructions")
        val a1 = data.conversations.create("doomed member one", doomed.id)
        val a2 = data.conversations.create("doomed member two", doomed.id)
        val b1 = data.conversations.create("survivor member", survivor.id)
        val plain = data.conversations.create("plain chat")
        seedWorkspaceTree(first, doomed.workspaceId, "doomed page $nonce")
        seedWorkspaceTree(first, survivor.workspaceId, survivorContent)
        val sharedRuntime = File(context.filesDir, "userland/shared-$nonce.marker").apply {
            parentFile!!.mkdirs()
            writeText("shared runtime $nonce")
        }
        val legacy = File(context.filesDir, "workspaces/legacy-$nonce/notes.txt").apply {
            parentFile!!.mkdirs()
            writeText("legacy $nonce")
        }
        val a1Result = seedFinishedTask(
            first, a1.id, doomed.workspaceId, bigFixtureOutput("a1 $nonce"),
            artifacts = listOf(ToolArtifact("index.html", "text/html")),
        )
        seedFinishedTask(first, a2.id, doomed.workspaceId, bigFixtureOutput("a2 $nonce"))
        val b1Result = seedFinishedTask(
            first, b1.id, survivor.workspaceId, bigFixtureOutput("b1 $nonce"),
            artifacts = listOf(ToolArtifact("index.html", "text/html")),
        )
        data.providerConfigs.save(provider)
        data.providerConfigs.setActive(ActiveModel(provider.id, "fixture-model"))

        // Deletion commits the marker, cleans chat-owned data, then blocks in the workspace boundary.
        val deletion = first.scope.launch { data.projects.delete(doomed.id) }
        try {
            withTimeout(15_000) { entered.await() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Failed observation: deletion never reached the workspace boundary", e)
        }
        expect(data.projects.observeAll().first().single { it.id == doomed.id }.deleting, "durable deleting marker")
        expectEquals(listOf(doomed.workspaceId), boundaryCalls.toList(), "boundary entered once for the target")
        expect(data.events.history(a1.id).isEmpty(), "doomed chat log cleaned before the block")
        expect(!first.blobDir(a1.id).exists(), "doomed chat blobs cleaned before the block")
        expect(first.workspaceDir(doomed.workspaceId).exists(), "workspace kept while the boundary is blocked")

        // New work and preview are denied for the deleting project.
        expect(data.conversations.workspace(a1.id) == null, "deleting project supplies no workspace")
        expectThrows<IllegalStateException>("submit to a deleting project") {
            first.runner.submit(TaskRequest(Origin.User, a1.id, "blocked $nonce"))
        }
        expectThrows<IllegalStateException>("late member of a deleting project") {
            data.conversations.create("late member $nonce", doomed.id)
        }
        expect(!data.projects.saveInstructions(doomed.id, "late rules"), "instructions rejected while deleting")
        expect(data.projects.instructionsForWorkspace(doomed.workspaceId) == null, "no instructions while deleting")
        expect(!data.projects.delete(doomed.id), "concurrent cleanup rejected")
        expect(first.previewBytes(a1.id, a1Result, 0) == null, "preview denied while deleting")

        // The survivor keeps working and previewing while the boundary is blocked.
        val surviveTask = first.runner.submit(TaskRequest(Origin.User, b1.id, inputSurvive))
        first.expectFinished(b1.id, surviveTask, "survivor task during deletion")
        expectEquals(survivorContent, first.previewBytes(b1.id, b1Result, 0)?.decodeToString(), "survivor preview during deletion")

        // Simulate process death: interrupt the blocked cleanup; the persisted state stays.
        deletion.cancelAndJoin()
        expect(data.projects.observeAll().first().single { it.id == doomed.id }.deleting, "marker survives interruption")
        expect(first.workspaceDir(doomed.workspaceId).exists(), "workspace survives interruption")
        expectEquals("shared runtime $nonce", sharedRuntime.readText(), "shared runtime untouched by interruption")
        expectEquals("legacy $nonce", legacy.readText(), "legacy workspace untouched by interruption")
        first.close()

        // Phase 2: reopen the same persisted state and resume the deletion.
        val second = ProjectFixture(
            context,
            mapOf(
                inputSurvive to listOf(FixtureStep.Reply("survivor ok $nonce")),
                inputRead to listOf(
                    fixtureCall("shell_read_file", "path" to "index.html"),
                    FixtureStep.Reply("survivor read $nonce"),
                ),
            ),
            deleteWorkspace = { id ->
                boundaryCalls += id
                UserlandRuntime.deleteWorkspace(context, id)
            },
        )
        try {
            val reopened = second.data
            second.awaitIdle("reopened runner recovery")
            expect(reopened.projects.observeAll().first().single { it.id == doomed.id }.deleting, "marker durable across reopen")
            expect(reopened.conversations.workspace(a1.id) == null, "reopened deleting project supplies no workspace")
            expectThrows<IllegalStateException>("submit after reopen while deleting") {
                second.runner.submit(TaskRequest(Origin.User, a1.id, "blocked $nonce"))
            }
            expectThrows<IllegalStateException>("late member after reopen") {
                reopened.conversations.create("late member $nonce", doomed.id)
            }
            expect(!reopened.projects.saveInstructions(doomed.id, "late rules"), "instructions rejected after reopen")
            expect(second.previewBytes(a1.id, a1Result, 0) == null, "preview denied after reopen")

            // The survivor still works after the restart.
            val surviveAgain = second.runner.submit(TaskRequest(Origin.User, b1.id, inputSurvive))
            second.expectFinished(b1.id, surviveAgain, "survivor task after reopen")

            // Resume removes only the target project's records and files.
            expect(reopened.projects.delete(doomed.id), "resume deletion")
            expectEquals(listOf(doomed.workspaceId, doomed.workspaceId), boundaryCalls.toList(), "boundary retried once on resume")
            expect(reopened.events.history(a1.id).isEmpty() && reopened.events.history(a2.id).isEmpty(), "doomed logs removed")
            expect(!second.blobDir(a1.id).exists() && !second.blobDir(a2.id).exists(), "doomed blobs removed")
            expect(!second.workspaceDir(doomed.workspaceId).exists(), "doomed workspace removed")
            expect(!second.installsDir(doomed.workspaceId).exists(), "doomed installs removed")
            expectEquals(survivorContent, File(second.workspaceDir(survivor.workspaceId), "index.html").readText(), "survivor workspace kept")
            expect(second.installsDir(survivor.workspaceId).isDirectory, "survivor installs kept")
            expectEquals("shared runtime $nonce", sharedRuntime.readText(), "shared runtime kept")
            expectEquals("legacy $nonce", legacy.readText(), "legacy workspace kept")
            expectEquals(listOf(survivor.id), reopened.projects.observeAll().first().map { it.id }, "only the survivor project remains")
            expectEquals(setOf(b1.id, plain.id), reopened.conversations.observeAll().first().map { it.id }.toSet(), "survivor and plain chats remain")
            expectEquals(listOf(provider), reopened.providerConfigs.providers(), "providers survive deletion")
            expectEquals(ActiveModel(provider.id, "fixture-model"), reopened.providerConfigs.active(), "active model survives deletion")
            expect(second.previewBytes(a1.id, a1Result, 0) == null, "preview stays denied after removal")
            expectEquals(survivorContent, second.previewBytes(b1.id, b1Result, 0)?.decodeToString(), "survivor preview after resume")

            // A surviving member can still use the real tools on its own workspace.
            val readTask = second.runner.submit(TaskRequest(Origin.User, b1.id, inputRead))
            second.expectFinished(b1.id, readTask, "survivor read after resume")
            val outcome = second.results(b1.id, readTask).single()
            expect(outcome.succeeded == true, "survivor read succeeded after resume")
            expectEquals(survivorContent, parseToolArguments(outcome.output)?.get("content")?.jsonPrimitive?.content, "survivor reads its own bytes")

            // Already gone: a second resume is a no-op without another boundary call.
            expect(reopened.projects.delete(doomed.id), "idempotent resume")
            expectEquals(2, boundaryCalls.size, "no extra boundary call for an already-removed project")
        } finally {
            second.close()
        }
    } finally {
        first.close()
    }
}