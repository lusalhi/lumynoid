package dev.lusalhi.lumynoid.userland

import android.content.Context
import android.os.Build
import dev.lusalhi.lumynoid.core.RiskAssessment
import dev.lusalhi.lumynoid.core.Tool
import dev.lusalhi.lumynoid.core.ToolContext
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.core.ToolProvider
import dev.lusalhi.lumynoid.core.ToolSessionRelease
import dev.lusalhi.lumynoid.core.UiText
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths

enum class RuntimeOperation(internal val wireName: String, internal val argv: List<String>, internal val cwd: String) {
    VERSIONS("versions", listOf("/bin/sh", "-c", "/usr/bin/node --version && /usr/bin/npm --version"), "/"),
    LIST_WORKSPACE("list_workspace", listOf("/bin/ls", "-1a", "/workspace"), "/workspace"),
}

/** App-owned operations for the bundled Linux runtime. Commands and mounts are selected in code. */
class UserlandRuntime internal constructor(
    private val nativeLibraries: Path,
    private val workspaceRoot: Path,
    private val scratchRoot: Path,
    private val rootfs: RootfsInstaller,
    private val processes: UserlandProcesses,
    private val releaseUrl: String,
) : ToolProvider, ToolSessionRelease {
    override fun tools(): List<Tool> = listOf(setupTool, probeTool)

    override suspend fun release(context: ToolContext) = processes.release(context.taskId)

    suspend fun operate(operation: RuntimeOperation, context: ToolContext): ToolOutcome = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        when (val result = startGuest(context, operation.argv, operation.cwd).await(30_000)) {
            ProcessResult.Deadline -> ToolOutcome("Runtime operation exceeded its deadline.", target = operation.wireName)
            is ProcessResult.Exited -> ToolOutcome(
                result.output, target = operation.wireName, exitCode = result.code,
            )
        }
    }

    internal fun startGuest(context: ToolContext, argv: List<String>, cwd: String = "/workspace"): UserlandProcesses.Session {
        val workspace = workspace(context.workspaceId)
        val installed = rootfs.locate() ?: throw IOException("Linux runtime setup is required")
        val scratch = Files.createTempDirectory(Files.createDirectories(scratchRoot), "proot-")
        try {
            return processes.start(
                context.taskId,
                listOf(
                    nativeLibraries.resolve("libproot.so").toString(),
                    "--kill-on-exit", "-r", installed.toString(),
                    "-b", "$workspace:/workspace", "-b", "/dev/null:/dev/null", "-w", cwd,
                ) + argv,
                scratch,
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "HOME" to "/tmp",
                    "LANG" to "C",
                    "NPM_CONFIG_USERCONFIG" to "/dev/null",
                    "NPM_CONFIG_GLOBALCONFIG" to "/etc/npmrc",
                    "PROOT_LOADER" to nativeLibraries.resolve("libproot_loader.so").toString(),
                    "PROOT_TMP_DIR" to scratch.toString(),
                ),
                afterExit = { deleteTree(scratch) },
            )
        } catch (e: Exception) {
            deleteTree(scratch)
            throw e
        }
    }

    private fun workspace(id: String): Path {
        require(WORKSPACE_ID.matches(id)) { "Invalid workspace ID" }
        Files.createDirectories(workspaceRoot)
        require(Files.isDirectory(workspaceRoot, NOFOLLOW_LINKS)) { "Invalid workspace root" }
        val workspace = workspaceRoot.toRealPath().resolve(id)
        try {
            Files.createDirectory(workspace)
        } catch (_: FileAlreadyExistsException) {
            require(Files.isDirectory(workspace, NOFOLLOW_LINKS)) { "Invalid workspace directory" }
        }
        require(':' !in workspace.toString()) { "Invalid workspace mount path" }
        return workspace
    }

    private val setupTool = object : Tool {
        override val name = "shell_runtime_setup"
        override val description = "Install the pinned Linux runtime. A network download requires user approval."
        override val inputSchema = schema(JsonObject(emptyMap()))

        override fun assess(args: JsonObject) = if (args.isEmpty()) RiskAssessment.EGRESS else RiskAssessment.DENIED
        override fun describe(args: JsonObject) = UiText(
            "runtime_setup", mapOf("version" to JsonPrimitive(rootfs.spec.version), "source" to JsonPrimitive(releaseUrl)),
        )

        override suspend fun execute(args: JsonObject, context: ToolContext): ToolOutcome {
            require(args.isEmpty())
            rootfs.install(context.taskId)
            return ToolOutcome("Installed ${rootfs.spec.version}.", target = rootfs.spec.version)
        }
    }

    private val probeTool = object : Tool {
        override val name = "shell_runtime_probe"
        override val description = "Report installed Node and npm versions or list files in the active workspace."
        override val inputSchema = schema(buildJsonObject {
            put("operation", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(RuntimeOperation.entries.map { JsonPrimitive(it.wireName) }))
            })
        })

        override fun assess(args: JsonObject) =
            if (operation(args) == null) RiskAssessment.DENIED else RiskAssessment.READ_ONLY

        override fun describe(args: JsonObject) = UiText(
            "runtime_probe", mapOf("operation" to JsonPrimitive(operation(args)?.wireName.orEmpty())),
        )

        override suspend fun execute(args: JsonObject, context: ToolContext): ToolOutcome =
            operate(requireNotNull(operation(args)) { "Unknown runtime operation" }, context)

        private fun operation(args: JsonObject): RuntimeOperation? {
            val value = args["operation"] as? JsonPrimitive ?: return null
            if (args.size != 1 || !value.isString) return null
            return RuntimeOperation.entries.firstOrNull { it.wireName == value.content }
        }
    }

    companion object {
        private val WORKSPACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        private val RELEASE_TAG = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9.-]+)?")

        fun create(context: Context, http: HttpClient): UserlandRuntime? {
            if ("arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS) return null
            val nativeLibraries = Paths.get(context.applicationInfo.nativeLibraryDir)
            val supervisor = nativeLibraries.resolve("libuserland_supervisor.so")
            if (!Files.isExecutable(supervisor)) return null

            val releaseTag = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            check(RELEASE_TAG.matches(releaseTag)) { "Invalid runtime release tag" }
            val spec = RootfsSpec.load(context.assets)
            val url = "https://github.com/lusalhi/lumynoid/releases/download/$releaseTag/${spec.asset}"
            val processes = UserlandProcesses(supervisor)
            val files = context.filesDir.toPath()
            return UserlandRuntime(
                nativeLibraries,
                files.resolve("workspaces"),
                context.cacheDir.toPath().resolve("userland"),
                RootfsInstaller(files.resolve("userland"), spec, ReleaseRootfsSource(http, url), processes),
                processes,
                url,
            )
        }

        private fun schema(properties: JsonObject): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", properties)
            put("additionalProperties", false)
            put("required", JsonArray(properties.keys.map { JsonPrimitive(it) }))
        }
    }
}
