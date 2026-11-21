package dev.lusalhi.lumynoid.userland

import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.Tool
import dev.lusalhi.lumynoid.core.ToolArtifact
import dev.lusalhi.lumynoid.core.ToolContext
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.ToolProvider
import dev.lusalhi.lumynoid.core.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import java.security.MessageDigest

internal class CodingTools(
    private val workspace: CodingWorkspace,
    private val runtime: CodingRuntime,
) : ToolProvider {
    private val lock = Mutex()
    private val registered = Operation.entries.map(::CodingTool)

    override fun tools(): List<Tool> = registered

    private enum class Operation(
        val toolName: String,
        val description: String,
        val risk: RiskAssessment,
        vararg val keys: String,
    ) {
        READ("shell_read_file", "Read a workspace UTF-8 file; returns content and SHA-256.", RiskAssessment.READ_ONLY, "path"),
        CREATE("shell_create_file", "Create a new workspace UTF-8 file without replacing an existing path.",
            RiskAssessment.LOCAL_WRITE, "path", "content"),
        REPLACE("shell_replace_file", "Replace a workspace file with approval and its current SHA-256.",
            RiskAssessment.DESTRUCTIVE, "path", "content", "expectedSha256"),
        INSTALL("shell_npm_install", "Install the bundled npm package offline.", RiskAssessment.LOCAL_WRITE, "package"),
        FORMAT("shell_run_script", "Format workspace JSON into a new file using the built-in script.",
            RiskAssessment.LOCAL_WRITE, "script", "input", "output"),
    }

    private inner class CodingTool(private val operation: Operation) : Tool {
        override val name = operation.toolName
        override val description = operation.description
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                for (key in operation.keys) put(key, buildJsonObject {
                    put("type", "string")
                    fixedValue(key)?.let { put("enum", JsonArray(listOf(JsonPrimitive(it)))) }
                    if (key == "content") put("maxLength", CODING_FILE_LIMIT)
                })
            })
            put("additionalProperties", false)
            put("required", JsonArray(operation.keys.map(::JsonPrimitive)))
        }

        override fun assess(args: JsonObject): RiskAssessment {
            if (args.size != operation.keys.size) return RiskAssessment.DENIED
            for (key in operation.keys) {
                val value = args[key] as? JsonPrimitive ?: return RiskAssessment.DENIED
                if (!value.isString || !valid(key, value.content)) return RiskAssessment.DENIED
            }
            return operation.risk
        }

        override fun describe(args: JsonObject) = UiText(name, buildMap {
            for (key in operation.keys) {
                if (key != "content") (args[key] as? JsonPrimitive)?.let { put(key, it) }
            }
        })

        override suspend fun execute(args: JsonObject, context: ToolContext): ToolOutcome {
            if (assess(args) == RiskAssessment.DENIED) return ToolOutcome("Invalid coding arguments.")
            return withContext(Dispatchers.IO) {
                try {
                    when (operation) {
                        Operation.READ -> read(context, args.text("path"))
                        Operation.CREATE -> write(context, args.text("path"), args.text("content"), null)
                        Operation.REPLACE -> write(context, args.text("path"), args.text("content"), args.text("expectedSha256"))
                        Operation.INSTALL -> runtime.install(context)
                        Operation.FORMAT -> format(context, args.text("input"), args.text("output"))
                    }
                } catch (e: CodingReject) {
                    ToolOutcome(e.message.orEmpty())
                } catch (_: IOException) {
                    ToolOutcome("Coding operation failed.")
                }
            }
        }
    }

    private suspend fun read(context: ToolContext, path: String): ToolOutcome = inWorkspace(context) { directory, _ ->
        val bytes = workspace.readBounded(workspace.regularFile(directory, components(path)))
        val output = buildJsonObject {
            put("content", decodeUtf8(bytes))
            put("sha256", sha256(bytes))
        }
        ToolOutcome(output.toString(), target = path)
    }

    private suspend fun write(context: ToolContext, path: String, content: String, expected: String?): ToolOutcome {
        val bytes = try {
            content.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw CodingReject("Content is not valid UTF-8.")
        }
        if (bytes.size > CODING_FILE_LIMIT) throw CodingReject("Content exceeds the size limit.")
        val parts = components(path)
        val status = inWorkspace(context) { directory, job ->
            if (expected == null) {
                val parent = workspace.parentDirectory(directory, parts, create = true)
                workspace.publishNew(parent, parts.last(), bytes, job::ensureActive)
                "Created"
            } else {
                val file = workspace.regularFile(directory, parts)
                val current = workspace.readBounded(file)
                decodeUtf8(current)
                if (!sha256(current).equals(expected, ignoreCase = true)) {
                    throw CodingReject("File content changed. Read it again for the current SHA-256.")
                }
                workspace.replaceExisting(file, bytes, job::ensureActive)
                "Replaced"
            }
        }
        return ToolOutcome("$status $path.", target = path, artifacts = listOf(ToolArtifact(path, "text/plain")))
    }

    private suspend fun format(context: ToolContext, input: String, output: String): ToolOutcome {
        val source = inWorkspace(context) { directory, _ ->
            workspace.readBounded(workspace.regularFile(directory, components(input))).also(::decodeUtf8)
        }
        val result = runtime.format(context, source)
        val bytes = result.bytes ?: return result.outcome.copy(target = output)
        val failure = try {
            if (bytes.size > CODING_FILE_LIMIT) throw CodingReject("Formatted output exceeds the size limit.")
            decodeUtf8(bytes)
            val parts = components(output)
            inWorkspace(context) { directory, job ->
                val parent = workspace.parentDirectory(directory, parts, create = true)
                workspace.publishNew(parent, parts.last(), bytes, job::ensureActive)
            }
            null
        } catch (e: CodingReject) {
            e.message
        } catch (_: IOException) {
            "Could not publish the formatted file."
        }
        val status = failure ?: "Formatted $output."
        val report = result.outcome.output
        return result.outcome.copy(
            output = if (report.isEmpty()) status else "$report\n$status",
            target = output,
            artifacts = if (failure == null) listOf(ToolArtifact(output, "application/json")) else emptyList(),
        )
    }

    private suspend inline fun <T> inWorkspace(context: ToolContext, action: (Path, Job) -> T): T = lock.withLock {
        val job = currentCoroutineContext().job
        job.ensureActive()
        action(workspace.directory(context.workspaceId), job)
    }

    private companion object {
        val SHA256 = Regex("[0-9a-fA-F]{64}")

        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        fun components(path: String) = checkNotNull(CodingWorkspace.pathComponents(path))

        fun fixedValue(key: String): String? = when (key) {
            "package" -> CodingRuntime.PACKAGE_ID
            "script" -> CodingRuntime.SCRIPT_ID
            else -> null
        }

        fun valid(key: String, value: String): Boolean = when (key) {
            "path", "input", "output" -> CodingWorkspace.pathComponents(value) != null
            "content" -> value.length <= CODING_FILE_LIMIT
            "expectedSha256" -> SHA256.matches(value)
            else -> value == fixedValue(key)
        }

        fun decodeUtf8(bytes: ByteArray): String = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw CodingReject("File is not valid UTF-8.")
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    }
}
