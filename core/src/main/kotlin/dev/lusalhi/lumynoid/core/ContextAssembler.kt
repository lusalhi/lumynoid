package dev.lusalhi.lumynoid.core

/**
 * Naive context (AD-13): every earlier answered turn of the conversation, in log order, then the
 * current message. No trimming, no budget, no system prompt; those belong to later epics.
 */
object ContextAssembler {
    fun assemble(history: List<TaskEvent>, taskId: String): LlmRequest {
        val answers = history.filterIsInstance<TaskEvent.AssistantMessage>().associateBy { it.taskId }
        val messages = mutableListOf<LlmMessage>()
        for (event in history) {
            if (event !is TaskEvent.UserMessage) continue
            if (event.taskId == taskId) {
                messages += LlmMessage(Role.USER, event.text)
                break // anything logged after this message was queued later and must not leak in
            }
            val answer = answers[event.taskId] ?: continue // failed, cancelled or unfinished turns are skipped
            messages += LlmMessage(Role.USER, event.text)
            messages += LlmMessage(Role.ASSISTANT, answer.text)
        }
        return LlmRequest(messages)
    }
}

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
