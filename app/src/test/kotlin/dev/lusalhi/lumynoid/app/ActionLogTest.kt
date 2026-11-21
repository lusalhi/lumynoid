package dev.lusalhi.lumynoid.app

import dev.lusalhi.lumynoid.core.ApprovalResolution
import dev.lusalhi.lumynoid.core.FailureReason
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.ToolApproval
import dev.lusalhi.lumynoid.core.ToolArtifact
import dev.lusalhi.lumynoid.core.ToolCall
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.UiText
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLogTest {
    private fun call(id: String, name: String = "shell_read_file", args: Map<String, String> = emptyMap()) =
        TaskEvent.RequestedToolCall(
            ToolCall(id, name, "{}"),
            UiText(name, args.mapValues { (_, value) -> JsonPrimitive(value) }),
        )

    private fun requested(id: String, taskId: String, vararg calls: TaskEvent.RequestedToolCall, denied: List<String> = emptyList()) =
        TaskEvent.ToolCallRequested(id, taskId, 1L, "", calls.toList(), denied)

    private fun result(id: String, taskId: String, callId: String, outcome: ToolOutcome) =
        TaskEvent.ToolResult(id, taskId, 2L, callId, outcome)

    private fun actions(events: List<TaskEvent>) = actionLogRows(events).filterIsInstance<ActionLogRow.Action>()

    @Test
    fun resultsJoinByTaskAndCallWithoutMovingRequests() {
        val a = call("a")
        val b = call("b")
        val outcomeA = ToolOutcome("output a", target = "a.txt", succeeded = true)
        val outcomeB = ToolOutcome("output b", succeeded = false)
        val events = listOf(
            requested("r1", "t1", a, b),
            requested("r2", "t2", call("a")),
            result("res-b", "t1", "b", outcomeB),
            result("res-a", "t1", "a", outcomeA),
            result("res-other", "t2", "a", ToolOutcome("other task")),
        )
        val rows = actions(events)

        assertEquals(listOf("r1:a", "r1:b", "r2:a"), rows.map { it.key })
        assertEquals(outcomeA, rows[0].outcome)
        assertEquals("res-a", rows[0].resultEventId)
        assertEquals(outcomeB, rows[1].outcome)
        assertEquals("res-b", rows[1].resultEventId)
        assertEquals("other task", rows[2].outcome?.output)
        assertEquals(ActionStatus.SUCCEEDED, rows[0].status())
        assertEquals(ActionStatus.FAILED, rows[1].status())
    }

    @Test
    fun explicitVerdictsWinAndLegacyNonzeroExitIsFailed() {
        val events = listOf(
            requested("r1", "t", call("a"), call("b"), call("c"), call("d")),
            result("x1", "t", "a", ToolOutcome("ran", exitCode = 0, succeeded = false)),
            result("x2", "t", "b", ToolOutcome("legacy zero", exitCode = 0)),
            result("x3", "t", "c", ToolOutcome("legacy failure", exitCode = 1)),
            result("x4", "t", "d", ToolOutcome("legacy unknown")),
        )
        assertEquals(
            listOf(ActionStatus.FAILED, ActionStatus.UNCONFIRMED, ActionStatus.FAILED, ActionStatus.UNCONFIRMED),
            actions(events).map { it.status() },
        )
    }

    @Test
    fun preflightDenialMarksTheAssessedCallAndBlocksTheWholeBatch() {
        val events = listOf(
            requested("r1", "t", call("first"), call("denied"), call("later"), denied = listOf("denied")),
            TaskEvent.TaskFailed("end", "t", 3L, FailureReason.TOOL_DENIED),
        )
        val rows = actions(events)
        assertEquals(
            listOf(ActionStatus.NOT_RUN_BATCH, ActionStatus.DENIED_PREFLIGHT, ActionStatus.NOT_RUN_BATCH),
            rows.map { it.status() },
        )
        assertTrue(rows.all { it.outcome == null })

        // Chat keeps its existing blocked error instead of result-less activity cards.
        val chat = chatRows(events)
        assertEquals(listOf("end"), chat.map { it.key })
        assertTrue(chat.single() is ChatRow.Event)
    }

    @Test
    fun executionDenialIsDistinctAndNeverOverwritesAStoredResult() {
        val events = listOf(
            requested("r1", "t", call("a"), call("b")),
            TaskEvent.ToolExecutionDenied("d1", "t", 2L, "a"),
            result("x1", "t", "b", ToolOutcome("kept", succeeded = true)),
            TaskEvent.ToolExecutionDenied("d2", "t", 3L, "b"),
        )
        val rows = actions(events)
        assertEquals(ActionStatus.DENIED_EXECUTION, rows[0].status())
        assertEquals(ActionStatus.SUCCEEDED, rows[1].status())
        assertEquals("kept", rows[1].outcome?.output)
    }

    @Test
    fun approvalDecisionsJoinOnlyTheExactApprovalAndCall() {
        val approval = ToolApproval("ap", call("a"), RiskAssessment.DESTRUCTIVE, 10L)
        val base = listOf(
            requested("r1", "t", call("a")),
            TaskEvent.ApprovalRequested("ar", "t", 2L, approval),
        )
        fun statusWith(vararg extra: TaskEvent) = actions(base + extra).single().status()

        assertEquals(ActionStatus.AWAITING_APPROVAL, statusWith())
        assertEquals(
            ActionStatus.AWAITING_APPROVAL,
            statusWith(TaskEvent.ApprovalResolved("wrong", "t", 3L, "other-approval", "a", ApprovalResolution.DENIED)),
        )
        assertEquals(
            ActionStatus.AWAITING_APPROVAL,
            statusWith(TaskEvent.ApprovalResolved("wrong", "t", 3L, "ap", "other-call", ApprovalResolution.DENIED)),
        )
        assertEquals(
            ActionStatus.APPROVAL_DENIED,
            statusWith(TaskEvent.ApprovalResolved("ok", "t", 3L, "ap", "a", ApprovalResolution.DENIED)),
        )
        assertEquals(
            ActionStatus.APPROVAL_EXPIRED,
            statusWith(TaskEvent.ApprovalResolved("ok", "t", 3L, "ap", "a", ApprovalResolution.EXPIRED)),
        )
        assertEquals(
            ActionStatus.APPROVAL_CANCELLED,
            statusWith(TaskEvent.ApprovalResolved("ok", "t", 3L, "ap", "a", ApprovalResolution.CANCELLED)),
        )
        assertEquals(
            ActionStatus.SUCCEEDED,
            statusWith(
                TaskEvent.ApprovalResolved("ok", "t", 3L, "ap", "a", ApprovalResolution.APPROVED),
                result("x", "t", "a", ToolOutcome("done", succeeded = true)),
            ),
        )
    }

    @Test
    fun terminalEventsNeverInferThatAnActionWithNoResultDidNotRun() {
        fun ended(vararg end: TaskEvent) = actions(listOf(requested("r1", "t", call("a"))) + end).single().status()

        assertEquals(ActionStatus.PENDING, ended())
        assertEquals(ActionStatus.INTERRUPTED, ended(TaskEvent.TaskFailed("e", "t", 2L, FailureReason.INTERRUPTED)))
        assertEquals(ActionStatus.NO_RESULT, ended(TaskEvent.TaskFailed("e", "t", 2L, FailureReason.NETWORK)))
        assertEquals(ActionStatus.CANCELLED, ended(TaskEvent.TaskCancelled("e", "t", 2L)))
        assertEquals(ActionStatus.NO_RESULT, ended(TaskEvent.TaskFinished("e", "t", 2L)))
        // A stored result still speaks after the task ended.
        assertEquals(
            ActionStatus.SUCCEEDED,
            actions(
                listOf(
                    requested("r1", "t", call("a")),
                    result("x", "t", "a", ToolOutcome("done", succeeded = true)),
                    TaskEvent.TaskCancelled("e", "t", 2L),
                ),
            ).single().status(),
        )
    }

    @Test
    fun anUnresolvedApprovalDoesNotStayPendingAfterTheTaskEnds() {
        val approval = ToolApproval("ap", call("a"), RiskAssessment.EGRESS, 10L)
        val rows = actions(
            listOf(
                requested("r1", "t", call("a")),
                TaskEvent.ApprovalRequested("ar", "t", 2L, approval),
                TaskEvent.TaskCancelled("e", "t", 3L),
            ),
        )
        assertEquals(ActionStatus.CANCELLED, rows.single().status())
    }

    @Test
    fun malformedRetriesAndUnknownEventsStayVisibleWithUniqueKeys() {
        val events = listOf(
            TaskEvent.ToolCallRejected("rej", "t", 1L, 2),
            TaskEvent.LlmRetry("retry", "t", 2L, 3),
            TaskEvent.Unknown(),
            TaskEvent.Unknown(),
            TaskEvent.TaskFinished("fin", "t", 3L),
        )
        val rows = actionLogRows(events)
        assertEquals(listOf("rej", "retry", "unknown-2", "unknown-3", "fin"), rows.map { it.key })
        assertEquals(events.size, rows.map { it.key }.toSet().size)
        val notices = rows.filterIsInstance<ActionLogRow.Notice>()
        assertEquals(2, notices.count { it.event is TaskEvent.ToolCallRejected || it.event is TaskEvent.LlmRetry })
    }

    @Test
    fun anApprovalWithoutItsRequestStillAppearsAtItsStoredPosition() {
        val approval = ToolApproval("ap", call("a"), RiskAssessment.DESTRUCTIVE, 10L)
        val rows = actions(
            listOf(
                TaskEvent.UserMessage("u", "t", 1L, Origin.User, "hi"),
                TaskEvent.ApprovalRequested("ar", "t", 2L, approval),
                TaskEvent.ApprovalResolved("ok", "t", 3L, "ap", "a", ApprovalResolution.DENIED),
            ),
        )
        val row = rows.single()
        assertEquals("ar:a", row.key)
        assertEquals(ActionStatus.APPROVAL_DENIED, row.status())
    }

    @Test
    fun artifactIdentitiesComeFromTheStoredResultAndRelativePath() {
        val outcome = ToolOutcome(
            "formatted",
            artifacts = listOf(ToolArtifact("out/formatted.json", "application/json")),
            succeeded = true,
        )
        val row = actions(listOf(requested("r1", "t", call("a")), result("res-1", "t", "a", outcome))).single()
        assertEquals(
            listOf(ArtifactIdentity("conv-a", "res-1", "out/formatted.json", "application/json")),
            row.artifactIdentities("conv-a"),
        )
        assertTrue(actions(listOf(requested("r1", "t", call("a")))).single().artifactIdentities("conv-a").isEmpty())
    }

    @Test
    fun entryTargetFallsBackAcrossToolSubjects() {
        assertEquals("recorded.txt", actionTarget(call("a"), ToolOutcome("o", target = "recorded.txt")))
        assertEquals("src.json", actionTarget(call("a", args = mapOf("path" to "src.json")), null))
        assertEquals("in.json", actionTarget(call("a", "shell_run_script", mapOf("input" to "in.json", "output" to "out.json")), null))
        assertEquals("out.json", actionTarget(call("a", "shell_run_script", mapOf("output" to "out.json")), null))
        assertEquals("left-pad", actionTarget(call("a", "shell_npm_install", mapOf("package" to "left-pad")), null))
        assertEquals("version", actionTarget(call("a", "runtime_probe", mapOf("operation" to "version")), null))
        assertEquals("24.1", actionTarget(call("a", "runtime_setup", mapOf("version" to "24.1")), null))
        assertNull(actionTarget(call("a"), null))
    }

    @Test
    fun everyStatusHasItsOwnLabel() {
        val labels = ActionStatus.entries.map { it.labelRes() }
        assertEquals(labels.size, labels.toSet().size)
        assertNotEquals(0, labels.first())
    }

    @Test
    fun chunksReproduceTheExactOutputIncludingTheFinalCharacter() {
        val output = buildString {
            repeat(45_000) { append("line ").append(it).append('\n') }
            append("final byte \uD83D\uDE00")
        }
        val chunks = outputChunks(output)

        assertTrue(chunks.size > 1)
        assertEquals(output, chunks.joinToString(""))
        assertEquals(output.last(), chunks.last().last())
        assertTrue(chunks.all { it.length <= OUTPUT_CHUNK_CHARS })
        assertTrue(chunks.all { it.isNotEmpty() })
        // No chunk may end mid surrogate pair.
        assertTrue(chunks.dropLast(1).none { it.last().isHighSurrogate() })
    }

    @Test
    fun chunksNeverSplitSurrogatePairsEvenOnOddBoundaries() {
        val emoji = "\uD83D\uDE00"
        val output = emoji.repeat(10)
        val chunks = outputChunks(output, maxChars = 3)

        assertEquals(output, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 3 })
        assertTrue(chunks.none { it.last().isHighSurrogate() })
        assertTrue(chunks.none { it.first().isLowSurrogate() })
        assertEquals(emoji, chunks.last())
    }

    @Test
    fun chunkEdgeCases() {
        assertTrue(outputChunks("").isEmpty())
        assertEquals(listOf("abc"), outputChunks("abc", maxChars = 10))
        assertEquals(listOf("ab", "cd"), outputChunks("abcd", maxChars = 2))
        // A trailing lone high surrogate is preserved, not dropped.
        assertEquals(listOf("a\uD83D"), outputChunks("a\uD83D", maxChars = 2))
        var failed = false
        try {
            outputChunks("abc", maxChars = 1)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun anOrphanResultKeepsItsEvidenceAsAnUnknownCall() {
        val outcome = ToolOutcome("stored output", target = "data.json", succeeded = true)
        val rows = actions(
            listOf(
                result("orphan", "t", "lost-call", outcome),
                TaskEvent.TaskFinished("fin", "t", 3L),
            ),
        )
        val row = rows.single()
        assertEquals("orphan:lost-call", row.key)
        assertEquals(outcome, row.outcome)
        assertEquals("orphan", row.resultEventId)
        assertEquals(ActionStatus.SUCCEEDED, row.status())
        assertEquals("data.json", actionTarget(row.call, row.outcome))
    }

    @Test
    fun chunksSupportRandomAccessWithoutLosingContent() {
        val output = buildString { repeat(20_000) { append(it).append(' ') } }
        val chunks = outputChunks(output, maxChars = 100)

        assertTrue(chunks.size > 1)
        assertEquals(output.substring(0, chunks[0].length), chunks[0])
        val middle = chunks.size / 2
        val middleStart = chunks.take(middle).sumOf { it.length }
        assertEquals(output.substring(middleStart, middleStart + chunks[middle].length), chunks[middle])
        val lastStart = chunks.dropLast(1).sumOf { it.length }
        assertEquals(output.substring(lastStart), chunks.last())
        assertEquals(output.length, chunks.sumOf { it.length })
    }
}
