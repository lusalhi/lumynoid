package dev.lusalhi.lumynoid.userland

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Maximum size of a workspace file the coding tools read or write. */
internal const val CODING_FILE_LIMIT = 256 * 1024

/** An expected, data-only rejection. The message is safe to show: it never carries host paths or file contents. */
internal class CodingReject(message: String) : Exception(message)

/**
 * The app-private workspace root and the only host-side file operations on it. Every path is resolved
 * component by component without following links, and every check and write runs under the caller's
 * serialized lock so the validated path is still the path that is written.
 */
internal class CodingWorkspace(private val root: Path) {

    /** The private directory for one workspace ID, created on first use. Same identity rules as the runtime mount. */
    fun directory(id: String): Path {
        require(isValidId(id)) { "Invalid workspace ID" }
        Files.createDirectories(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Invalid workspace root" }
        val workspace = root.toRealPath().resolve(id)
        try {
            Files.createDirectory(workspace)
        } catch (_: FileAlreadyExistsException) {
            require(Files.isDirectory(workspace, NOFOLLOW_LINKS)) { "Invalid workspace directory" }
        }
        require(':' !in workspace.toString()) { "Invalid workspace mount path" }
        return workspace
    }

    /** Resolves the parent directories of a file path; existing ones must be real directories, missing ones are created only when [create]. */
    fun parentDirectory(workspace: Path, components: List<String>, create: Boolean): Path {
        var current = workspace
        // Resolve each parent only after the preceding component has been checked.
        for (index in 0 until components.lastIndex) {
            val next = current.resolve(components[index])
            if (Files.exists(next, NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(next, NOFOLLOW_LINKS)) throw CodingReject("Path component is not a directory.")
            } else if (create) {
                Files.createDirectory(next)
            } else {
                throw CodingReject("Directory not found.")
            }
            current = next
        }
        return current
    }

    /** An existing single-linked regular file; symlinks, directories, special files and multiply linked files are rejected. */
    fun regularFile(workspace: Path, components: List<String>): Path {
        val path = parentDirectory(workspace, components, create = false).resolve(components.last())
        if (!Files.exists(path, NOFOLLOW_LINKS)) throw CodingReject("File not found.")
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw CodingReject("Not a regular file.")
        val links = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number
        if (links.toInt() != 1) throw CodingReject("File has multiple hard links.")
        return path
    }

    fun readBounded(path: Path): ByteArray = Files.newByteChannel(path, READ, NOFOLLOW_LINKS).use { channel ->
        val size = channel.size()
        if (size > CODING_FILE_LIMIT) throw CodingReject("File exceeds the size limit.")
        val bytes = ByteArray(size.toInt())
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw CodingReject("File changed during read.")
        }
        if (channel.size() != size) throw CodingReject("File changed during read.")
        bytes
    }

    fun publishNew(parent: Path, name: String, bytes: ByteArray, beforeCommit: () -> Unit) =
        publish(parent.resolve(name), bytes, newOnly = true, beforeCommit)

    fun replaceExisting(path: Path, bytes: ByteArray, beforeCommit: () -> Unit) =
        publish(path, bytes, newOnly = false, beforeCommit)

    private fun publish(path: Path, bytes: ByteArray, newOnly: Boolean, beforeCommit: () -> Unit) {
        val stage = Files.createTempFile(path.parent, ".coding-", ".tmp")
        try {
            Files.write(stage, bytes)
            // The caller holds the only workspace write lock through the check and rename.
            // Android may deny hard links even within an app's own workspace.
            if (newOnly && !Files.notExists(path, NOFOLLOW_LINKS)) {
                throw CodingReject("Destination already exists or is inaccessible.")
            }
            beforeCommit()
            Files.move(stage, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(stage)
        }
    }

    companion object {
        private val WORKSPACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

        /** The same identity rules as [directory], checked without creating anything. */
        fun isValidId(id: String): Boolean = WORKSPACE_ID.matches(id)

        /** Splits a safe workspace-relative path into components, or null for absolute, traversal, empty or NUL-bearing paths. */
        fun pathComponents(path: String): List<String>? {
            if (path.isEmpty() || path.startsWith("/") || '\u0000' in path) return null
            val components = path.split('/')
            if (components.any { it.isEmpty() || it == "." || it == ".." }) return null
            return components
        }
    }
}

/** Deletes one idle workspace-owned tree. A missing tree needs no cleanup. */
internal fun deleteWorkspaceTree(root: Path, id: String) {
    require(CodingWorkspace.isValidId(id)) { "Invalid workspace ID" }
    if (Files.notExists(root, NOFOLLOW_LINKS)) return
    if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw IOException("Invalid workspace storage root")
    val directory = root.resolve(id)
    if (Files.notExists(directory, NOFOLLOW_LINKS)) return
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw IOException("Workspace entry is not a directory")
    Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
