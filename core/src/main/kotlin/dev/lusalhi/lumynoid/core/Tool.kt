package dev.lusalhi.lumynoid.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.regex.Pattern

data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

@Serializable
data class ToolCall(val id: String, val name: String, val argsJson: String)

// The tree reader accepts arbitrary bare literals. Check lexical tokens before it checks structure.
private val jsonToken = Pattern.compile(
    """[ \t\r\n]++|[{}\[\],:]|"(?:[^"\\\x00-\x1f]++|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*+"|""" +
        """(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)(?=[ \t\r\n,}\]]|\z)""",
)

/** Parses a complete tool argument document with strict JSON syntax and an object root. */
fun parseToolArguments(text: String): JsonObject? {
    val matcher = jsonToken.matcher(text)
    var offset = 0
    while (offset < text.length) {
        if (!matcher.region(offset, text.length).lookingAt()) return null
        offset = matcher.end()
    }
    return try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

@Serializable
data class UiText(val key: String, val args: Map<String, JsonPrimitive> = emptyMap())

@Serializable
enum class RiskAssessment {
    @SerialName("read_only") READ_ONLY,
    @SerialName("local_write") LOCAL_WRITE,
    @SerialName("destructive") DESTRUCTIVE,
    @SerialName("financial") FINANCIAL,
    @SerialName("egress") EGRESS,
    @SerialName("persistent") PERSISTENT,
    @SerialName("denied") DENIED,
}

data class ToolContext(val taskId: String, val conversationId: String, val workspaceId: String?)

/** Releases task-owned tool resources before the runner hands the execution slot to another turn. */
fun interface ToolSessionRelease {
    suspend fun release(context: ToolContext)
}

/** A file published in the tool call's workspace, never a host path. */
@Serializable
data class ToolArtifact(val path: String, val mediaType: String)

@Serializable
data class ToolOutcome(
    val output: String,
    val target: String? = null,
    val exitCode: Int? = null,
    val artifacts: List<ToolArtifact> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val truncationHint: String? = null,
    /** Null when the tool did not record whether its operation succeeded (including legacy results). */
    val succeeded: Boolean? = null,
)

interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    val requiresWorkspace: Boolean get() = false

    /** Assess current risk without performing the action. Return [RiskAssessment.DENIED] for invalid arguments. */
    fun assess(args: JsonObject): RiskAssessment
    /** Describe the action and its target for approval using the same state as [assess]. */
    fun describe(args: JsonObject): UiText
    suspend fun execute(args: JsonObject, context: ToolContext): ToolOutcome
}

fun interface ToolProvider {
    fun tools(): List<Tool>
}

class ToolRegistry(private val providers: List<ToolProvider> = emptyList()) {
    internal data class Entry(val definition: ToolDefinition, val tool: Tool)

    internal fun snapshot(hasWorkspace: Boolean): Map<String, Entry> {
        if (providers.isEmpty()) return emptyMap()
        return buildMap {
            for (provider in providers) {
                for (tool in provider.tools()) {
                    if (!hasWorkspace && tool.requiresWorkspace) continue
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
