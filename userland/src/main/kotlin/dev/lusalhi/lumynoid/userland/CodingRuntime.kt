package dev.lusalhi.lumynoid.userland

import android.content.res.AssetManager
import dev.lusalhi.lumynoid.core.ToolContext
import dev.lusalhi.lumynoid.core.ToolOutcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Base64

/** Formatter result: [bytes] is non-null only after exit and lossless output validation. */
internal data class CodingFormatResult(val outcome: ToolOutcome, val bytes: ByteArray? = null)

/**
 * App-owned npm operations over the bundled Linux runtime. Installs only the digest-pinned
 * first-party tarball from app assets, with app-owned manifest, config, HOME and fresh cache;
 * project files, npm config and other installs are never bound into coding guests. Verified
 * installs are promoted atomically per workspace and bundle digest, and the formatter loads
 * one committed entrypoint by fixed path. Guest writers are reaped before output validation
 * or staging cleanup; expected failures stay data-only and never disclose host paths.
 */
internal class CodingRuntime(
    private val userland: UserlandRuntime,
    private val workspace: CodingWorkspace,
    private val assets: AssetManager,
    private val installRoot: Path,
    private val stageRoot: Path,
) {
    private val mutex = Mutex()

    suspend fun install(context: ToolContext): ToolOutcome = mutex.withLock {
        val target = installedDirectory(context)
        if (verifiedInstall(target)) return ToolOutcome("$PACKAGE_ID is already installed.", target = PACKAGE_ID, succeeded = true)

        val parent = Files.createDirectories(target.parent)
        Files.newDirectoryStream(parent, "$INSTALL_STAGE_PREFIX*").use { abandoned -> abandoned.forEach(::deleteTree) }
        val stage = Files.createTempDirectory(parent, INSTALL_STAGE_PREFIX)
        try {
            val archive = stage.resolve(BUNDLE_NAME)
            val encoded = assets.open(BUNDLE_ASSET).bufferedReader().use { it.readText() }
            Files.write(archive, Base64.getMimeDecoder().decode(encoded))
            if (Files.size(archive) != BUNDLE_SIZE || !matchesDigest(archive, BUNDLE_SHA256)) {
                return ToolOutcome("Bundled package failed digest verification.", target = PACKAGE_ID, succeeded = false)
            }
            Files.write(stage.resolve("package.json"), APP_MANIFEST.encodeToByteArray())
            Files.write(stage.resolve("npmrc"), APP_NPMRC.encodeToByteArray())
            Files.createDirectory(stage.resolve("home"))
            Files.createDirectory(stage.resolve("cache"))
            val result = runGuest(
                context, NPM_ARGV, "/work", mapOf(stage.toString() to "/work"),
                mapOf(
                    "HOME" to "/work/home",
                    "NPM_CONFIG_USERCONFIG" to "/work/npmrc",
                    "NPM_CONFIG_GLOBALCONFIG" to "/dev/null",
                ),
                PACKAGE_ID, INSTALL_TIMEOUT_MS,
            )
            if (result.exitCode != 0) return result
            val installed = stage.resolve("node_modules/$PACKAGE_ID")
            if (!verifiedModule(installed)) return result.withCheckFailure("Installed package failed source verification.")

            // Keep only the checked module, not the temporary npm configuration, cache or logs.
            Files.write(installed.resolve(MARKER), BUNDLE_SHA256.encodeToByteArray())
            currentCoroutineContext().ensureActive()
            deleteTree(target) // If present, this digest's install failed verification above.
            currentCoroutineContext().ensureActive()
            Files.move(installed, target, ATOMIC_MOVE)
            result
        } finally {
            deleteTree(stage)
        }
    }

    suspend fun format(context: ToolContext, input: ByteArray): CodingFormatResult = mutex.withLock {
        val installed = installedDirectory(context)
        if (!verifiedInstall(installed)) return CodingFormatResult(
            ToolOutcome("$PACKAGE_ID is not installed in this workspace; run shell_npm_install first.", target = SCRIPT_ID, succeeded = false),
        )
        val stage = Files.createTempDirectory(Files.createDirectories(stageRoot), "coding-")
        try {
            Files.write(stage.resolve("input.json"), input)
            val result = runGuest(
                context, FORMAT_ARGV, "/io", mapOf(installed.toString() to "/coding", stage.toString() to "/io"),
                emptyMap(), SCRIPT_ID, FORMAT_TIMEOUT_MS,
            )
            if (result.exitCode != 0) return CodingFormatResult(result)

            // await() has reaped every guest writer. A zero exit alone does not publish a file.
            val file = stage.resolve("output.json")
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > CODING_FILE_LIMIT) {
                return CodingFormatResult(result.withCheckFailure("Formatter output is missing or exceeds the size limit."))
            }
            val output = Files.readAllBytes(file)
            if (!sameJsonTokens(input, output)) {
                return CodingFormatResult(result.withCheckFailure("Formatter changed more than JSON whitespace."))
            }
            CodingFormatResult(result, output)
        } finally {
            deleteTree(stage)
        }
    }

    private suspend fun runGuest(
        context: ToolContext,
        argv: List<String>,
        cwd: String,
        binds: Map<String, String>,
        environment: Map<String, String>,
        target: String,
        timeout: Long,
    ): ToolOutcome {
        currentCoroutineContext().ensureActive()
        val session = try {
            userland.startGuest(context, argv, cwd, binds, environment)
        } catch (e: UserlandSetupRequired) {
            return ToolOutcome(e.message.orEmpty(), target = target, succeeded = false)
        }
        return when (val result = session.await(timeout)) {
            ProcessResult.Deadline -> ToolOutcome("Coding runtime operation exceeded its deadline.", target = target, succeeded = false)
            is ProcessResult.Exited -> ToolOutcome(
                userland.redactHostPaths(result.output).replace(installRoot.toString(), "<host-path>"),
                target = target, exitCode = result.code, succeeded = result.code == 0,
            )
        }
    }

    private fun installedDirectory(context: ToolContext): Path {
        val id = workspace.directory(checkNotNull(context.workspaceId)).fileName
        return installRoot.resolve(id).resolve(BUNDLE_SHA256)
    }

    private fun verifiedInstall(directory: Path) =
        Files.isDirectory(directory, NOFOLLOW_LINKS) &&
            Files.isRegularFile(directory.resolve(MARKER), NOFOLLOW_LINKS) &&
            directory.resolve(MARKER).toFile().readText() == BUNDLE_SHA256 && verifiedModule(directory)

    private fun verifiedModule(directory: Path) =
        matchesDigest(directory.resolve(ENTRYPOINT), ENTRYPOINT_SHA256) &&
            matchesDigest(directory.resolve("package.json"), MANIFEST_SHA256)

    private fun matchesDigest(file: Path, expected: String) =
        Files.isRegularFile(file, NOFOLLOW_LINKS) &&
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).toHexString() == expected

    private fun ToolOutcome.withCheckFailure(message: String) =
        copy(output = if (output.isEmpty()) message else "$output\n$message", succeeded = false)

    companion object {
        const val PACKAGE_ID = "@lumynoid/json-tools"
        const val SCRIPT_ID = "format_json"
        const val INSTALL_STAGE_PREFIX = ".install-"

        // Regenerate with: npm pack in a copy of assets/coding/package, then base64 -w0 the
        // tarball into assets/coding/lumynoid-json-tools-1.0.0.tgz.b64 and update these pins.
        private const val BUNDLE_ASSET = "coding/lumynoid-json-tools-1.0.0.tgz.b64"
        private const val BUNDLE_NAME = "lumynoid-json-tools-1.0.0.tgz"
        private const val BUNDLE_SIZE = 2331L
        private const val BUNDLE_SHA256 = "f41a1abafd7139e78a62e2f43d83e0bfc7e43534a1044503ca0e801a9a67a293"
        private const val ENTRYPOINT_SHA256 = "0f3587bece5512df7f9dcf525d49fe2eceecdbedec16e22d4dfab74be0e9b153"
        private const val MANIFEST_SHA256 = "b7d35879f794790ced862e38bc785ba2c20bc7a95476055b06fd61c2dda67024"
        private const val ENTRYPOINT = "index.js"
        private const val MARKER = ".lumynoid-coding-sha256"
        private const val INSTALL_TIMEOUT_MS = 120_000L
        private const val FORMAT_TIMEOUT_MS = 60_000L

        private const val APP_MANIFEST = "{\"name\":\"lumynoid-coding\",\"version\":\"1.0.0\",\"private\":true}\n"
        private const val APP_NPMRC =
            "offline=true\nignore-scripts=true\naudit=false\nfund=false\npackage-lock=false\nupdate-notifier=false\n"

        private val NPM_ARGV = listOf(
            "/usr/bin/npm", "install",
            "--offline", "--ignore-scripts", "--no-audit", "--no-fund", "--no-package-lock", "--no-progress",
            "--cache", "/work/cache",
            "/work/$BUNDLE_NAME",
        )
        private val FORMAT_ARGV = listOf("/usr/bin/node", "/coding/$ENTRYPOINT", "/io/input.json", "/io/output.json")
    }
}

// The verified formatter validates the input grammar. Compare raw token spans so decoding,
// reserializing or even joining two adjacent number tokens cannot alter project data.
private fun sameJsonTokens(input: ByteArray, output: ByteArray): Boolean {
    var i = 0
    var j = 0
    while (true) {
        while (i < input.size && isJsonSpace(input[i])) i++
        while (j < output.size && isJsonSpace(output[j])) j++
        if (i == input.size || j == output.size) return i == input.size && j == output.size
        val end = tokenEnd(input, i)
        if (end - i != tokenEnd(output, j) - j) return false
        while (i < end) if (input[i++] != output[j++]) return false
    }
}

private fun tokenEnd(bytes: ByteArray, start: Int): Int {
    var i = start + 1
    val first = bytes[start].toInt().toChar()
    if (first in "{}[],:") return i
    if (first == '"') {
        while (i < bytes.size) {
            when (bytes[i++].toInt().toChar()) {
                '"' -> break
                '\\' -> if (i < bytes.size) i++
            }
        }
        return i
    }
    while (i < bytes.size && !isJsonSpace(bytes[i]) && bytes[i].toInt().toChar() !in "{}[],:\"") i++
    return i
}

private fun isJsonSpace(byte: Byte) = when (byte.toInt()) {
    9, 10, 13, 32 -> true
    else -> false
}
