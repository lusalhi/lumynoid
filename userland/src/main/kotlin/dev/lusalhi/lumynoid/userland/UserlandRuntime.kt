package dev.lusalhi.lumynoid.userland

import android.content.Context
import android.content.res.AssetManager
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

enum class RuntimeOperation(internal val wireName: String, internal val argv: List<String>, internal val cwd: String) {
    VERSIONS("versions", listOf("/bin/sh", "-c", "/usr/bin/node --version && /usr/bin/npm --version"), "/"),
    LIST_WORKSPACE("list_workspace", listOf("/bin/ls", "-1a", "/workspace"), "/workspace"),
}

internal class UserlandSetupRequired : IOException("Linux runtime setup is required. Call shell_runtime_setup, then retry.")

/** App-owned operations for the bundled Linux runtime. Commands and mounts are selected in code. */
class UserlandRuntime internal constructor(
    private val nativeLibraries: Path,
    private val workspaceRoot: Path,
    private val scratchRoot: Path,
    private val rootfs: RootfsInstaller,
    private val processes: UserlandProcesses,
    private val releaseUrl: String,
    private val assets: AssetManager,
) : ToolProvider, ToolSessionRelease {
    private val codingWorkspace = CodingWorkspace(workspaceRoot)
    private val codingRuntime = CodingRuntime(
        this, codingWorkspace, assets, workspaceRoot.resolveSibling("coding-installs"), scratchRoot,
    )
    private val codingTools = CodingTools(codingWorkspace, codingRuntime)

    override fun tools(): List<Tool> = listOf(setupTool, probeTool) + codingTools.tools()

    override suspend fun release(context: ToolContext) = processes.release(context.taskId)

    suspend fun operate(operation: RuntimeOperation, context: ToolContext): ToolOutcome = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val session = try {
            startGuest(context, operation.argv, operation.cwd)
        } catch (e: UserlandSetupRequired) {
            return@withContext ToolOutcome(e.message.orEmpty(), target = operation.wireName)
        }
        when (val result = session.await(30_000)) {
            ProcessResult.Deadline -> ToolOutcome("Runtime operation exceeded its deadline.", target = operation.wireName)
            is ProcessResult.Exited -> ToolOutcome(
                redactHostPaths(result.output), target = operation.wireName, exitCode = result.code,
            )
        }
    }

    internal fun startGuest(context: ToolContext, argv: List<String>, cwd: String = "/workspace"): UserlandProcesses.Session =
        launchGuest(
            context, argv, cwd,
            mapOf(codingWorkspace.directory(context.workspaceId).toString() to "/workspace"),
            emptyMap(),
        )

    /** Coding guest: explicit app-owned binds only; the project workspace is not mounted unless listed. */
    internal fun startGuest(
        context: ToolContext,
        argv: List<String>,
        cwd: String,
        binds: Map<String, String>,
        environment: Map<String, String>,
    ): UserlandProcesses.Session = launchGuest(context, argv, cwd, binds, environment)

    private fun launchGuest(
        context: ToolContext,
        argv: List<String>,
        cwd: String,
        binds: Map<String, String>,
        environment: Map<String, String>,
    ): UserlandProcesses.Session {
        require(binds.keys.none { ':' in it }) { "Invalid guest bind path" }
        val installed = rootfs.locate() ?: throw UserlandSetupRequired()
        val scratch = Files.createTempDirectory(Files.createDirectories(scratchRoot), "proot-")
        try {
            return processes.start(
                context.taskId,
                listOf(
                    nativeLibraries.resolve("libproot.so").toString(),
                    "--kill-on-exit", "-r", installed.toString(),
                ) + binds.flatMap { (host, guest) -> listOf("-b", "$host:$guest") } +
                    listOf("-b", "/dev/null:/dev/null", "-w", cwd) + argv,
                scratch,
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "HOME" to "/tmp",
                    "LANG" to "C",
                    "NPM_CONFIG_USERCONFIG" to "/dev/null",
                    "NPM_CONFIG_GLOBALCONFIG" to "/etc/npmrc",
                    "PROOT_LOADER" to nativeLibraries.resolve("libproot_loader.so").toString(),
                    "PROOT_TMP_DIR" to scratch.toString(),
                ) + environment,
                afterExit = { deleteTree(scratch) },
            )
        } catch (e: Exception) {
            deleteTree(scratch)
            throw e
        }
    }

    /** Keep recorded guest output data-only: proot diagnostics may echo host mount paths. */
    internal fun redactHostPaths(text: String): String {
        var result = text
        for (root in listOfNotNull(nativeLibraries, workspaceRoot, scratchRoot, rootfs.locate())) {
            result = result.replace(root.toString(), "<host-path>")
        }
        return result
    }

    private val setupTool = object : Tool {
        override val name = "shell_runtime_setup"
        override val description = "Install the pinned Linux runtime with user approval. Use only when setup is required."
        override val inputSchema = schema(JsonObject(emptyMap()))

        override fun assess(args: JsonObject) = if (args.isEmpty()) RiskAssessment.EGRESS else RiskAssessment.DENIED
        override fun describe(args: JsonObject) = UiText(
            "runtime_setup", mapOf(
                "version" to JsonPrimitive(rootfs.spec.version),
                "source" to JsonPrimitive(releaseUrl),
                "sizeBytes" to JsonPrimitive(rootfs.spec.size),
            ),
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
                context.assets,
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
