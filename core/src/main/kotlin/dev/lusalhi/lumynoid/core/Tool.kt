package dev.lusalhi.lumynoid.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

@Serializable
data class ToolCall(val id: String, val name: String, val argsJson: String)

@Serializable
data class UiText(val key: String, val args: Map<String, JsonPrimitive> = emptyMap())

enum class RiskAssessment { READ_ONLY, LOCAL_WRITE, DESTRUCTIVE, FINANCIAL, EGRESS, PERSISTENT, DENIED }

data class ToolContext(val taskId: String, val conversationId: String, val workspaceId: String)

@Serializable
data class ToolOutcome(
    val output: String,
    val target: String? = null,
    val exitCode: Int? = null,
    val artifactPaths: List<String> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val truncationHint: String? = null,
)

interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonObject

    /** Validate against the tool's input contract and return DENIED when the arguments are invalid. */
    fun assess(args: JsonObject): RiskAssessment
    fun describe(args: JsonObject): UiText
    suspend fun execute(args: JsonObject, context: ToolContext): ToolOutcome
}

fun interface ToolProvider {
    fun tools(): List<Tool>
}

class ToolRegistry(private val providers: List<ToolProvider> = emptyList()) {
    internal data class Entry(val definition: ToolDefinition, val tool: Tool)

    internal fun snapshot(): Map<String, Entry> {
        if (providers.isEmpty()) return emptyMap()
        return buildMap {
            for (provider in providers) {
                for (tool in provider.tools()) {
                    val name = tool.name
                    require(namePattern.matches(name)) { "Invalid tool name: $name" }
                    require(ownerPrefixes.any { name.startsWith(it) }) { "Missing tool owner prefix: $name" }
                    check(name !in this) { "Duplicate tool name: $name" }
                    val schema = JsonObject(tool.inputSchema.mapValues { it.value.detached() })
                    put(name, Entry(ToolDefinition(name, tool.description, schema), tool))
                }
            }
        }
    }

    private companion object {
        val namePattern = Regex("[a-z][a-z0-9_]{0,63}")
        val ownerPrefixes = listOf("shell_", "web_", "skill_", "schedule_")
    }
}

private fun JsonElement.detached(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { it.value.detached() })
    is JsonArray -> JsonArray(map { it.detached() })
    is JsonPrimitive -> this
}
