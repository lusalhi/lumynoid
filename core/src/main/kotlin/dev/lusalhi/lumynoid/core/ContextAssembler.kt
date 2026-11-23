package dev.lusalhi.lumynoid.core

/**
 * Naive context (AD-13): earlier answered turns, then the current turn. Tool rounds contribute
 * only as complete assistant/result groups. No trimming or budget; nonblank project instructions
 * lead as one system message, sent verbatim.
 */
object ContextAssembler {
    fun assemble(history: List<TaskEvent>, taskId: String, instructions: String? = null): LlmRequest {
        val turns = linkedMapOf<String, Turn>()
        var reachedCurrent = false
        for (event in history) {
            if (event is TaskEvent.UserMessage) {
                if (reachedCurrent) continue
                turns.putIfAbsent(event.taskId, Turn(event))
                reachedCurrent = event.taskId == taskId
            } else {
                turns[event.taskId]?.record(event)
            }
        }
        val messages = buildList {
            instructions?.takeIf { it.isNotBlank() }?.let { add(LlmMessage(Role.SYSTEM, it)) }
            for ((id, turn) in turns) {
                val current = id == taskId
                val answer = turn.answer
                if (!current && (answer == null || turn.unsuccessful)) continue
                add(LlmMessage(Role.USER, turn.user.text))
                turn.tools?.let { tools ->
                    for (request in tools.requests) appendToolRound(this, request, tools.results)
                }
                if (!current && answer != null) add(LlmMessage(Role.ASSISTANT, answer.text))
            }
        }
        return LlmRequest(messages)
    }

    private fun appendToolRound(
        messages: MutableList<LlmMessage>,
        request: TaskEvent.ToolCallRequested,
        results: Map<String, TaskEvent.ToolResult>,
    ) {
        if (request.calls.isEmpty() || request.calls.any { results[it.call.id]?.taskId != request.taskId }) return
        messages += request.toLlmMessage()
        for ((call) in request.calls) {
            messages += results.getValue(call.id).toLlmMessage()
        }
    }

    private class ToolRounds {
        val requests = mutableListOf<TaskEvent.ToolCallRequested>()
        val results = mutableMapOf<String, TaskEvent.ToolResult>()
    }

    private class Turn(val user: TaskEvent.UserMessage) {
        var answer: TaskEvent.AssistantMessage? = null
        var unsuccessful = false
        var tools: ToolRounds? = null

        fun record(event: TaskEvent) {
            when (event) {
                is TaskEvent.AssistantMessage -> answer = event
                is TaskEvent.TaskFailed, is TaskEvent.TaskCancelled -> unsuccessful = true
                is TaskEvent.ToolCallRequested -> {
                    val rounds = tools ?: ToolRounds().also { tools = it }
                    rounds.requests += event
                }
                is TaskEvent.ToolResult -> tools?.results?.putIfAbsent(event.callId, event)
                else -> Unit
            }
        }
    }
}

internal fun TaskEvent.ToolCallRequested.toLlmMessage(): LlmMessage =
    LlmMessage(Role.ASSISTANT, assistantText, toolCalls = calls.map { it.call })

internal fun TaskEvent.ToolResult.toLlmMessage(): LlmMessage =
    LlmMessage(Role.TOOL, outcome.output, toolCallId = callId)

/** A conversation is titled by its first user message; no LLM call is involved. */
object ConversationTitle {
    const val MAX_LENGTH = 60

    /** First non-blank line, whitespace collapsed, cut to [MAX_LENGTH] characters plus an ellipsis. Empty when blank. */
    fun from(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val collapsed = line.replace(Regex("\\s+"), " ")
        return if (collapsed.length > MAX_LENGTH) collapsed.take(MAX_LENGTH).trimEnd() + "…" else collapsed
    }
}
