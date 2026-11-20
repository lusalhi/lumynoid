package dev.lusalhi.lumynoid.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextAssemblerTest {
    private var n = 0
    private fun user(task: String, text: String) = TaskEvent.UserMessage("e${n++}", task, n.toLong(), Origin.User, text)
    private fun answer(task: String, text: String) = TaskEvent.AssistantMessage("e${n++}", task, n.toLong(), text)
    private fun failed(task: String) = TaskEvent.TaskFailed("e${n++}", task, n.toLong(), FailureReason.NETWORK)
    private fun cancelled(task: String) = TaskEvent.TaskCancelled("e${n++}", task, n.toLong())

    private fun u(t: String) = LlmMessage(Role.USER, t)
    private fun a(t: String) = LlmMessage(Role.ASSISTANT, t)

    @Test
    fun earlierAnsweredTurnsComeInOrderThenTheNewMessage() {
        val history = listOf(user("t1", "one"), answer("t1", "A1"), user("t2", "two"), answer("t2", "A2"), user("t3", "three"))
        assertEquals(listOf(u("one"), a("A1"), u("two"), a("A2"), u("three")), ContextAssembler.assemble(history, "t3").messages)
    }

    @Test
    fun failedAndCancelledTurnsAreLeftOut() {
        val history = listOf(
            user("t1", "one"), failed("t1"),
            user("t2", "two"), cancelled("t2"),
            user("t3", "three"), answer("t3", "A3"),
            user("t4", "four"),
        )
        assertEquals(listOf(u("three"), a("A3"), u("four")), ContextAssembler.assemble(history, "t4").messages)
    }

    @Test
    fun messagesQueuedAfterTheCurrentOneNeverLeakIn() {
        val history = listOf(user("A", "a"), answer("A", "RA"), user("B", "b"), user("C", "c"))
        assertEquals(listOf(u("a"), a("RA"), u("b")), ContextAssembler.assemble(history, "B").messages)
    }

    @Test
    fun unknownEventsAreIgnored() {
        val history = listOf(user("t1", "one"), TaskEvent.Unknown("x", "t1", 1L), answer("t1", "A1"), user("t2", "two"))
        assertEquals(listOf(u("one"), a("A1"), u("two")), ContextAssembler.assemble(history, "t2").messages)
    }

    @Test
    fun onlyCompleteSameTaskToolRoundsEnterTheContext() {
        val old = ToolCall("old-call", "web_fixture_lookup", "{}")
        val failedCall = ToolCall("failed-call", old.name, "{}")
        val current = ToolCall("current-call", old.name, "{}")
        fun request(task: String, call: ToolCall) = TaskEvent.ToolCallRequested(
            "request-$task", task, 0L, "", listOf(TaskEvent.RequestedToolCall(call, UiText("card.fixture.lookup"))),
        )
        fun result(task: String, call: ToolCall, output: String) =
            TaskEvent.ToolResult("result-$task", task, 0L, call.id, ToolOutcome(output))
        val history = listOf(
            user("old", "previous"), request("old", old),
            user("failed", "failed"), request("failed", failedCall), result("failed", failedCall, "failed output"),
            answer("failed", "interrupted answer"), failed("failed"),
            user("current", "now"), user("later", "later"),
            result("later", old, "wrong task"), answer("old", "previous answer"),
            request("current", current), result("current", current, "right task"),
        )

        assertEquals(
            listOf(
                u("previous"), a("previous answer"), u("now"),
                LlmMessage(Role.ASSISTANT, "", toolCalls = listOf(current)),
                LlmMessage(Role.TOOL, "right task", toolCallId = current.id),
            ),
            ContextAssembler.assemble(history, "current").messages,
        )
    }

    @Test
    fun titleIsTheFirstNonBlankLineCollapsedAndCut() {
        assertEquals("hello big world", ConversationTitle.from("\n  \n  hello   big\tworld \nsecond line"))
        assertEquals("", ConversationTitle.from("  \n\t "))
        assertEquals("x".repeat(60) + "…", ConversationTitle.from("x".repeat(61)))
        assertEquals("x".repeat(60), ConversationTitle.from("x".repeat(60)))
    }
}
