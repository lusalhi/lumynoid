package dev.lusalhi.lumynoid.data

import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson
import dev.lusalhi.lumynoid.core.UuidV7
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal data class EventBlobRef(val id: String, val size: Int, val sha256: String)

internal class EventBlobStore(private val root: File) {
    fun write(conversationId: String, bytes: ByteArray): EventBlobRef {
        val ref = EventBlobRef(UuidV7.next(), bytes.size, sha256(bytes))
        val directory = Files.createDirectories(directory(conversationId).toPath())
        val temporary = Files.createTempFile(directory, ".event-", ".part")
        val published = directory.resolve(ref.id)
        var durable = false
        return try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temporary, published, ATOMIC_MOVE)
            // Publish directory entries before SQLite can commit a reference to the file.
            FileChannel.open(directory, READ).use { it.force(true) }
            FileChannel.open(directory.parent, READ).use { it.force(true) }
            FileChannel.open(directory.parent.parent, READ).use { it.force(true) }
            durable = true
            ref
        } finally {
            // The reference has not escaped yet, so no committed row can point at these files.
            runCatching { Files.deleteIfExists(temporary) }
            if (!durable) runCatching { Files.deleteIfExists(published) }
        }
    }

    fun read(conversationId: String, ref: EventBlobRef): String {
        require(UUID.fromString(ref.id).toString() == ref.id)
        val file = File(directory(conversationId), ref.id)
        check(file.length() == ref.size.toLong())
        val bytes = file.readBytes()
        check(bytes.size == ref.size && sha256(bytes) == ref.sha256)
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    /** Removes a blob whose event row never committed; other blobs stay untouched. */
    fun discard(conversationId: String, ref: EventBlobRef) {
        requireRootIsNotSymlink()
        require(UUID.fromString(ref.id).toString() == ref.id)
        val directory = directory(conversationId).toPath()
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Invalid conversation blob directory" }
        if (Files.deleteIfExists(directory.resolve(ref.id))) FileChannel.open(directory, READ).use { it.force(true) }
    }

    /** Removes only this conversation's blob tree, never following symbolic links; absent trees are fine. */
    fun deleteConversation(conversationId: String) {
        requireRootIsNotSymlink()
        val directory = directory(conversationId).toPath()
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return
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
        // Persist the removed directory entry in the blob root.
        FileChannel.open(directory.parent, READ).use { it.force(true) }
    }

    /** A symlinked root could point cleanup outside the app's storage; the encoded leaf below it is safe to unlink. */
    private fun requireRootIsNotSymlink() {
        check(!Files.isSymbolicLink(root.toPath())) { "Refusing to touch a symlinked blob root: $root" }
    }

    private fun directory(conversationId: String): File {
        require(conversationId.isNotEmpty()) { "Missing conversation ID" }
        return File(root, Base64.getUrlEncoder().withoutPadding().encodeToString(conversationId.encodeToByteArray()))
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt()
                append(HEX[value ushr 4 and 15])
                append(HEX[value and 15])
            }
        }
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}

/** A storage-only versioned envelope; inline TaskEvent JSON stays unchanged. */
internal data class StoredEventBlob(val ref: EventBlobRef, val eventType: String, val needsRecovery: Boolean) {
    companion object {
        const val MAX_INLINE_BYTES = 256 * 1024
        private const val PREVIEW_LENGTH = 512
        private const val KEY = "event_blob_v1"
        private const val PREFIX = "{\"$KEY\":"
        private const val TYPE_PREFIX = "{\"type\":\""

        fun isEnvelope(payload: String): Boolean = payload.startsWith(PREFIX)

        fun encode(ref: EventBlobRef, event: TaskEvent, payload: String): String = buildJsonObject {
            put(KEY, buildJsonObject {
                put("id", ref.id)
                put("size", ref.size)
                put("sha256", ref.sha256)
                put("type", eventType(payload))
                put("needs_recovery", event.needsRecovery)
                val end = if (payload[PREVIEW_LENGTH - 1].isHighSurrogate()) PREVIEW_LENGTH - 1 else PREVIEW_LENGTH
                put("preview", payload.substring(0, end))
            })
        }.toString()

        fun decode(payload: String): StoredEventBlob {
            val fields = TaskEventJson.json.parseToJsonElement(payload).jsonObject.getValue(KEY).jsonObject
            val read = { key: String -> fields.getValue(key).jsonPrimitive }
            val size = read("size").int
            require(size > MAX_INLINE_BYTES)
            return StoredEventBlob(
                EventBlobRef(read("id").content, size, read("sha256").content),
                read("type").content,
                read("needs_recovery").boolean,
            )
        }

        // TaskEventJson writes the class discriminator first for all events.
        fun eventType(payload: String): String {
            check(payload.startsWith(TYPE_PREFIX))
            return payload.substring(TYPE_PREFIX.length, payload.indexOf('"', TYPE_PREFIX.length))
        }
    }
}
