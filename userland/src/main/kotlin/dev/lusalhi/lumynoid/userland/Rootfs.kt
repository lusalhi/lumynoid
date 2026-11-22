package dev.lusalhi.lumynoid.userland

import android.content.res.AssetManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Properties

internal data class RootfsSpec(val version: String, val asset: String, val size: Long, val sha256: String) {
    init {
        require(Regex("[a-z0-9.\\-]+").matches(version))
        require(asset == "lumynoid-rootfs-$version-arm64.tar.gz")
        require(size > 0 && Regex("[0-9a-f]{64}").matches(sha256))
    }

    companion object {
        fun load(assets: AssetManager): RootfsSpec {
            val values = Properties()
            assets.open("lumynoid-userland.properties").use(values::load)
            return RootfsSpec(
                values.getProperty("version"), values.getProperty("asset"),
                values.getProperty("size").toLong(), values.getProperty("sha256"),
            )
        }
    }
}

internal fun interface RootfsSource {
    suspend fun transfer(write: (ByteArray, Int) -> Unit)
}

internal class ReleaseRootfsSource(private val http: HttpClient, private val url: String) : RootfsSource {
    override suspend fun transfer(write: (ByteArray, Int) -> Unit) {
        http.prepareGet(url) { timeout { requestTimeoutMillis = 180_000 } }.execute { response ->
            if (response.status != HttpStatusCode.OK) throw IOException("Rootfs download returned HTTP ${response.status.value}")
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                write(buffer, count)
            }
        }
    }
}

internal class RootfsInstaller(
    private val directory: Path,
    val spec: RootfsSpec,
    private val source: RootfsSource,
    private val processes: UserlandProcesses,
) {
    private val mutex = Mutex()
    private val installed = directory.resolve(spec.version)
    private val marker = installed.resolve(".lumynoid-rootfs-sha256")

    fun locate(): Path? = installed.takeIf {
        Files.isDirectory(it, NOFOLLOW_LINKS) && Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
            marker.toFile().readText() == spec.sha256
    }

    suspend fun install(taskId: String): Path = mutex.withLock {
        var published = false
        try {
            withContext(Dispatchers.IO) {
                locate()?.let { return@withContext it }
                Files.createDirectories(directory)
                Files.newDirectoryStream(directory, ".install-*").use { abandoned -> abandoned.forEach(::deleteTree) }
                val stage = Files.createTempDirectory(directory, ".install-")
                try {
                    val archive = stage.resolve(spec.asset)
                    download(archive)
                    val candidate = Files.createDirectory(stage.resolve("rootfs"))
                    extract(taskId, archive, candidate, stage)
                    candidate.resolve(marker.fileName).toFile().writeText(spec.sha256)
                    currentCoroutineContext().ensureActive()
                    deleteTree(installed)
                    currentCoroutineContext().ensureActive()
                    Files.move(candidate, installed, ATOMIC_MOVE)
                    published = true
                    installed
                } finally {
                    deleteTree(stage)
                }
            }
        } catch (e: CancellationException) {
            // withContext can observe cancellation while dispatching a successful commit back to the caller.
            if (published) withContext(NonCancellable + Dispatchers.IO) { deleteTree(installed) }
            throw e
        }
    }

    private suspend fun extract(taskId: String, archive: Path, candidate: Path, stage: Path) {
        val result = processes.run(
            taskId,
            listOf("/system/bin/toybox", "tar", "-xzf", archive.toString(), "-C", candidate.toString()),
            stage,
            emptyMap(),
            timeoutMillis = 60_000,
        )
        if (result !is ProcessResult.Exited || result.code != 0) throw IOException("Rootfs extraction failed: $result")
    }

    private suspend fun download(archive: Path) {
        val job = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newOutputStream(archive).buffered().use { output ->
            source.transfer { bytes, count ->
                job.ensureActive()
                size += count
                if (size > spec.size) throw IOException("Rootfs archive exceeds its expected size")
                digest.update(bytes, 0, count)
                output.write(bytes, 0, count)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (size != spec.size || sha256 != spec.sha256) throw IOException("Rootfs archive failed SHA-256 or size validation")
    }
}

internal fun deleteTree(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    }
}
