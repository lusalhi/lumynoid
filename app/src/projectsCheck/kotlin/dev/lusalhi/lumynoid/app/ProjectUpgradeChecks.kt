package dev.lusalhi.lumynoid.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.system.Os
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import dev.lusalhi.lumynoid.core.ConversationWorkspace
import dev.lusalhi.lumynoid.core.LlmPort
import dev.lusalhi.lumynoid.core.Origin
import dev.lusalhi.lumynoid.core.TaskEvent
import dev.lusalhi.lumynoid.core.TaskEventJson
import dev.lusalhi.lumynoid.core.TaskRequest
import dev.lusalhi.lumynoid.core.TaskRunner
import dev.lusalhi.lumynoid.core.ToolArtifact
import dev.lusalhi.lumynoid.core.ToolOutcome
import dev.lusalhi.lumynoid.data.LumynoidData
import dev.lusalhi.lumynoid.userland.UserlandRuntime
import dev.lusalhi.lumynoid.userland.WorkspacePreview
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Story 8.2 — upgrade an actual Epic 2 (schema v4) database through the real Room migrations and the
 * read-only userland adoption, then prove pending/unreadable chats stay unavailable, adoption resumes
 * after a reopen, and original events and files survive without copies.
 *
 * Story 8.3 — open persisted text and HTML artifacts through the production resolver and offline
 * WebView, prove script behavior is opt-in, and prove a wrong conversation, an out-of-scope path and a
 * deleting project reveal nothing.
 *
 * Both run fully offline on disposable, per-case storage and need no instrumentation permission.
 */

private const val UNTITLED = "Untitled Project"
private const val DB_NAME = "lumynoid.db"

// ----------------------------------------------------------------------------------------------------
// Shared fixture composition
// ----------------------------------------------------------------------------------------------------

/**
 * The production composition (AD-1) on disposable storage: a real [LumynoidData] whose lifecycle and
 * idle gates are the real [TaskRunner] admission gate, exactly as [AppGraph] wires them. The scripted
 * provider throws if ever reached, so no check can touch the network. The runner is lazy so its crash
 * recovery runs only after the fixture log already ends in a terminal event (recovery is then a no-op).
 */
private class FixtureGraph(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val data: LumynoidData = LumynoidData(
        context,
        deleteWhenIdle = { id, delete -> runner.deleteWhenIdle(id, delete) },
        withLifecycle = { action -> runner.withConversationLifecycle(action) },
        deleteWorkspace = { id -> UserlandRuntime.deleteWorkspace(context, id) },
    )
    val runner: TaskRunner by lazy {
        TaskRunner(
            LlmPort { error("A fixture reached the provider; these checks must stay offline") },
            data.events, scope,
            elapsedMillis = SystemClock::elapsedRealtime,
            resolveWorkspace = data.conversations::workspace,
            instructionsForWorkspace = data.projects::instructionsForWorkspace,
        )
    }

    override fun close() {
        scope.cancel()
        data.close()
    }
}

private fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError(message())
}

private fun <T> expectEqual(expected: T, actual: T, label: String) {
    if (expected != actual) throw AssertionError("$label: expected <$expected> but was <$actual>")
}

/** Turns a bounded wait's own timeout into an actionable failure; outer cancellation still propagates. */
private suspend fun <T> bounded(timeoutMillis: Long, label: String, block: suspend () -> T): T = try {
    withTimeout(timeoutMillis) { block() }
} catch (e: TimeoutCancellationException) {
    throw AssertionError("Timed out after ${timeoutMillis}ms waiting for $label")
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Relative path -> content digest for every regular file under [root]; an absent root is empty. */
private fun treeSnapshot(root: File): Map<String, String> {
    if (!root.exists()) return emptyMap()
    val result = sortedMapOf<String, String>()
    root.walkTopDown().filter { it.isFile }.forEach { file ->
        result[file.relativeTo(root).path] = sha256(file.readBytes())
    }
    return result
}

private fun openReadOnly(context: Context): SQLiteDatabase =
    SQLiteDatabase.openDatabase(context.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READONLY)

private fun eventPayload(context: Context, eventId: String): String? = openReadOnly(context).use { db ->
    db.rawQuery("SELECT payload FROM task_event WHERE id = ?", arrayOf(eventId)).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

// ----------------------------------------------------------------------------------------------------
// Story 8.2 — legacy upgrade and adoption
// ----------------------------------------------------------------------------------------------------

internal suspend fun checkLegacyUpgrade(context: Context) {
    val files = context.filesDir
    val workspaces = files.resolve("workspaces")
    val installs = files.resolve("coding-installs")
    val notes = workspaces.resolve("a-file/notes.txt")
    val packageModule = installs.resolve("b-package/digest/index.js")
    val draft = workspaces.resolve("d-untitled/draft.txt")

    // Original on-disk state left by Epic 2: a file chat, a package-only chat, an empty chat, an
    // untitled file chat. Adoption must classify these without copying or moving anything.
    notes.parentFile!!.mkdirs(); notes.writeText("saved notes")
    packageModule.parentFile!!.mkdirs(); packageModule.writeText("saved package")
    workspaces.resolve("c-empty").mkdirs()
    draft.parentFile!!.mkdirs(); draft.writeText("saved draft")

    // Bootstrap an actual SQLite v4 database from the packaged exported schema, then seed the v4 log.
    bootstrapSchemaV4(context)
    val originalMessage = TaskEvent.UserMessage("e0", "t1", 1L, Origin.User, "Original user message")
    val originalPreview = TaskEvent.ToolResult(
        "v4-preview", "t1", 2L, "legacy-file",
        ToolOutcome("Published notes", artifacts = listOf(ToolArtifact("notes.txt", "text/plain")), succeeded = true),
    )
    val expectedOrder = listOf("e0", originalPreview.id, "e1", "e2")
    openReadWrite(context).use { db ->
        db.execSQL(
            "INSERT INTO conversation (id, created_at, title) VALUES " +
                "('a-file', 1, 'Old title'), ('b-package', 1, 'Old title'), ('c-empty', 1, 'Plain'), ('d-untitled', 1, '')",
        )
        db.execSQL("INSERT INTO task (id, conversation_id, workspace_id, origin, created_at) VALUES ('t1', 'a-file', 'a-file', 'user', 2)")
        db.compileStatement("INSERT INTO task_event (id, task_id, conversation_id, at, payload) VALUES (?, ?, ?, ?, ?)").use { st ->
            for (event in listOf(originalMessage, originalPreview)) {
                st.bindString(1, event.id)
                st.bindString(2, event.taskId)
                st.bindString(3, "a-file")
                st.bindLong(4, event.at)
                st.bindString(5, TaskEventJson.encode(event))
                st.executeInsert()
            }
        }
    }

    val caseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
        // First open runs the real migrations 4 -> 7. The large result is stored through the production
        // blob store; the terminal event keeps the task finished so runner recovery never rewrites the log.
        val first = FixtureGraph(context)
        val largeResult: TaskEvent.ToolResult
        val terminal: TaskEvent.TaskFinished
        try {
            largeResult = TaskEvent.ToolResult("e1", "t1", 3L, "call", ToolOutcome("x".repeat(270_000)))
            terminal = TaskEvent.TaskFinished("e2", "t1", 4L)
            first.data.events.append("a-file", largeResult)
            first.data.events.append("a-file", terminal)
            expectEqual(7, pragmaUserVersion(context), "migrated schema version")

            expectEqual(TaskEventJson.encode(originalPreview), eventPayload(context, originalPreview.id), "v4 preview payload survived migration")
            val originalPayloads = expectedOrder.associateWith { eventPayload(context, it) }
            val originalBlobs = treeSnapshot(files.resolve("event-blobs"))
            expect(originalBlobs.isNotEmpty()) { "The large result should have been stored as a blob" }

            // Interrupt adoption: a-file is unreadable (null), b-package and c-empty settle, d-untitled
            // pauses mid-probe so the pass is cut off before it finishes.
            val paused = CompletableDeferred<Unit>()
            val adoption = caseScope.launch {
                first.data.adoptLegacyWorkspaces(UNTITLED) { id ->
                    when (id) {
                        "a-file" -> null
                        "b-package" -> UserlandRuntime.legacyWorkspaceHasData(context, id)
                        "c-empty" -> UserlandRuntime.legacyWorkspaceHasData(context, id)
                        "d-untitled" -> { paused.complete(Unit); awaitCancellation() }
                        else -> error("Unexpected adoption candidate: $id")
                    }
                }
            }
            bounded(20_000, "adoption to reach the interrupted chat") { paused.await() }

            // An independent reader (the preview path) sees pending chats as unavailable while the
            // settled chats already have scope, and the original log is intact.
            val reader = FixtureGraph(context)
            try {
                expectEqual(null, reader.data.conversations.workspace("a-file"), "pending file chat scope")
                expectEqual(null, resolvePublishedArtifact(reader.data, context, "a-file", originalPreview.id, 0), "pending preview scope")
                expectEqual(null, reader.data.conversations.workspace("d-untitled"), "interrupted chat scope")
                expectEqual(ConversationWorkspace.Project("b-package"), reader.data.conversations.workspace("b-package"), "package chat scope")
                expectEqual(ConversationWorkspace.Plain, reader.data.conversations.workspace("c-empty"), "empty chat scope")
                expect(!reader.data.conversations.delete("a-file")) { "A pending chat must not be deletable" }
                val blocked = runCatching {
                    reader.runner.submit(TaskRequest(Origin.User, "a-file", "not admitted"))
                }.exceptionOrNull()
                expect(blocked is IllegalStateException) { "Pending chat reached admission: $blocked" }
                expectEqual(1, taskCount(context), "task count during interruption")
                expectEqual(expectedOrder, reader.data.events.history("a-file").map { it.id }, "log order during interruption")
                expectEqual(largeResult, reader.data.event("a-file", "e1"), "blob-backed result during interruption")
            } finally {
                reader.close()
                adoption.cancelAndJoin()
            }
            first.close()

            // Reopen once and resume: only the still-pending chats are probed, now with the real
            // read-only userland probe. Completed and fresh chats are never re-probed.
            val reopened = FixtureGraph(context)
            try {
                expectEqual(null, reopened.data.conversations.workspace("a-file"), "file chat still pending after reopen")
                expectEqual(null, reopened.data.conversations.workspace("d-untitled"), "interrupted chat still pending after reopen")
                val fresh = reopened.data.conversations.create("fresh chat")
                expectEqual(ConversationWorkspace.Plain, reopened.data.conversations.workspace(fresh.id), "fresh chat scope")

                reopened.data.adoptLegacyWorkspaces(UNTITLED) { id ->
                    expect(id == "a-file" || id == "d-untitled") { "Resume probed a settled or fresh chat: $id" }
                    UserlandRuntime.legacyWorkspaceHasData(context, id)
                }

                val adopted = reopened.data.projects.observeAll().first()
                expectEqual(
                    mapOf("a-file" to "Old title", "b-package" to "Old title", "d-untitled" to UNTITLED),
                    adopted.associate { it.workspaceId to it.name },
                    "adopted project names",
                )
                val membership = reopened.data.conversations.observeAll().first().associate { it.id to it.projectId }
                for (project in adopted) {
                    expectEqual(project.id, membership[project.workspaceId], "membership of ${project.workspaceId}")
                    expectEqual(ConversationWorkspace.Project(project.workspaceId), reopened.data.conversations.workspace(project.workspaceId), "adopted scope")
                }
                expectEqual(null, membership.getValue("c-empty"), "empty chat stays plain")
                expectEqual(ConversationWorkspace.Plain, reopened.data.conversations.workspace("c-empty"), "empty chat scope after resume")
                expect(membership.containsKey(fresh.id)) { "The fresh chat must survive adoption" }
                expectEqual(ConversationWorkspace.Plain, reopened.data.conversations.workspace(fresh.id), "fresh chat scope after resume")

                // A second resume has nothing left to do and must not probe anything.
                reopened.data.adoptLegacyWorkspaces("Other title") { error("Adoption ran after completion: $it") }

                // Original events survive byte-for-byte (inline and blob envelope) and decode identically.
                for ((id, payload) in originalPayloads) {
                    expectEqual(payload, eventPayload(context, id), "preserved payload of $id")
                }
                expectEqual(originalMessage, reopened.data.event("a-file", "e0"), "preserved inline message")
                expectEqual(largeResult, reopened.data.event("a-file", "e1"), "preserved blob result")
                expectEqual(terminal, reopened.data.event("a-file", "e2"), "preserved terminal event")
                expectEqual(expectedOrder, reopened.data.events.history("a-file").map { it.id }, "preserved log order")
                val preview = resolvePublishedArtifact(reopened.data, context, "a-file", originalPreview.id, 0)
                expectEqual("saved notes", preview?.let { it.workspace.read(it.artifact.path)?.decodeToString() }, "original v4 preview after adoption")
                expectEqual(originalBlobs, treeSnapshot(files.resolve("event-blobs")), "blob tree unchanged (no copies)")

                // Original files survive in place; the package-only chat gained no workspace copy.
                expectEqual("saved notes", notes.readText(), "file chat notes")
                expectEqual("saved package", packageModule.readText(), "package module")
                expectEqual("saved draft", draft.readText(), "untitled draft")
                expect(!workspaces.resolve("b-package").exists()) { "Package-only adoption must not copy a workspace" }
                expectEqual(listOf(listOf("t1", "a-file", "a-file")), taskRows(context), "historical task row preserved")
                expectEqual(7, pragmaUserVersion(context), "final schema version")
            } finally {
                reopened.close()
            }
        } catch (e: Throwable) {
            first.close()
            throw e
        }
    } finally {
        caseScope.cancel()
    }
}

/** Creates the v4 tables, indices and Room master row from the packaged exported schema. */
private fun bootstrapSchemaV4(context: Context) {
    val text = context.assets.open("legacy-schema-v4.json").bufferedReader().use { it.readText() }
    val database = Json.parseToJsonElement(text).jsonObject.getValue("database").jsonObject
    expectEqual(4, database.getValue("version").jsonPrimitive.int, "packaged schema version")
    openReadWrite(context).use { db ->
        for (entity in database.getValue("entities").jsonArray) {
            val obj = entity.jsonObject
            val table = obj.getValue("tableName").jsonPrimitive.content
            db.execSQL(obj.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            obj["indices"]?.jsonArray?.forEach { index ->
                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        database["setupQueries"]?.jsonArray?.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.execSQL("PRAGMA user_version = 4")
    }
}

private fun openReadWrite(context: Context): SQLiteDatabase =
    SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(DB_NAME), null)

private fun pragmaUserVersion(context: Context): Int = openReadOnly(context).use { db ->
    db.rawQuery("PRAGMA user_version", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
}

private fun taskCount(context: Context): Int = openReadOnly(context).use { db ->
    db.rawQuery("SELECT COUNT(*) FROM task", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
}

private fun taskRows(context: Context): List<List<String>> = openReadOnly(context).use { db ->
    db.rawQuery("SELECT id, conversation_id, workspace_id FROM task ORDER BY id", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
        }
    }
}

// ----------------------------------------------------------------------------------------------------
// Story 8.3 — persisted artifact preview
// ----------------------------------------------------------------------------------------------------

internal suspend fun checkProjectPreview(context: Context) {
    expect(WebView.getCurrentWebViewPackage() != null) { "No WebView package is available to render the preview" }
    val graph = FixtureGraph(context)
    val deletingBlobs = mutableListOf<File>()
    try {
        val data = graph.data
        val workspaces = context.filesDir.resolve("workspaces")

        // The owning project and chat, with persisted text and HTML artifacts plus an out-of-scope path.
        val project = checkNotNull(data.projects.create("Preview project"))
        val chat = data.conversations.create("Offline preview fixture", project.id)
        val other = checkNotNull(data.projects.create("Unrelated preview project"))
        val otherChat = data.conversations.create("Unrelated preview chat", other.id)
        workspaces.resolve(other.workspaceId).apply { mkdirs(); resolve("index.html").writeText("<h1>Unrelated workspace marker</h1>") }

        val notes = "Native preview text marker\n" + "αbeta".repeat(500) + "\nEnd marker"
        val directory = workspaces.resolve(project.workspaceId).apply { mkdirs() }
        directory.resolve("notes.txt").writeText(notes)
        directory.resolve("index.html").writeText(
            """
            <!doctype html><meta charset="utf-8"><h1 id="status">Workspace page marker</h1>
            <a id="outside" href="https://outside.invalid/page">External preview link</a>
            <script>document.getElementById('status').textContent = 'Preview script marker';</script>
            """.trimIndent(),
        )
        val outsidePath = "../${project.workspaceId}-outside.html"
        workspaces.resolve("${project.workspaceId}-outside.html").writeText("<h1>Outside workspace marker</h1>")

        val resultId = "preview-result"
        val taskId = "preview-task"
        val at = 1_000L
        val artifacts = listOf(
            ToolArtifact("notes.txt", "text/plain"),
            ToolArtifact("index.html", "text/html"),
            ToolArtifact(outsidePath, "text/html"),
        )
        data.events.createTask(taskId, TaskRequest(Origin.User, chat.id, "Published preview files."), at, project.workspaceId)
        data.events.append(chat.id, TaskEvent.ToolResult(resultId, taskId, at + 1, "publish", ToolOutcome("Published", artifacts = artifacts, succeeded = true)))
        data.events.append(chat.id, TaskEvent.TaskFinished("preview-finished", taskId, at + 2))

        // Valid text is readable offline through the production resolver and the same bounded reader.
        val text = resolvePublishedArtifact(data, context, chat.id, resultId, 0)
        expect(text != null) { "The persisted text artifact did not resolve" }
        expect(!text!!.artifact.isHtml()) { "notes.txt should be a text artifact" }
        expectEqual(notes, text.workspace.read(text.artifact.path)?.decodeToString(), "offline text bytes")

        // Valid HTML resolves and renders offline; scripts stay off until opted in.
        val html = resolvePublishedArtifact(data, context, chat.id, resultId, 1)
        expect(html != null) { "The persisted HTML artifact did not resolve" }
        expect(html!!.artifact.isHtml()) { "index.html should be an HTML artifact" }
        expect(html.artifact.path == "index.html") { "Unexpected HTML path" }

        withOfflinePreview(context, html.artifact.path, javascript = false, read = html.workspace::read) { view ->
            expect(!withContext(Dispatchers.Main) { view.settings.javaScriptEnabled }) { "JavaScript must default to off" }
            expect(withContext(Dispatchers.Main) { view.settings.blockNetworkLoads }) { "The offline preview must block network loads" }
            expect(findText(view, "Workspace page marker") > 0) { "The static HTML marker did not render with scripts off" }
            expectEqual(0, findText(view, "Preview script marker"), "A script ran without opt-in")
        }
        withOfflinePreview(context, html.artifact.path, javascript = true, read = html.workspace::read) { view ->
            expect(withContext(Dispatchers.Main) { view.settings.javaScriptEnabled }) { "Opt-in did not enable JavaScript" }
            bounded(10_000, "the opted-in script to run") {
                while (evaluate(view, "document.getElementById('status')?.textContent") != "\"Preview script marker\"") delay(50)
            }
            expect(findText(view, "Preview script marker") > 0) { "The opted-in script marker did not render" }
            // An external link cannot navigate the sandboxed preview (no egress, no location change).
            val before = evaluate(view, "location.href")
            evaluate(view, "document.getElementById('outside').click()")
            expectEqual(before, evaluate(view, "location.href"), "external navigation changed the preview location")
        }

        // A wrong conversation reveals nothing: the result event is scoped to its own conversation.
        expectEqual(null, resolvePublishedArtifact(data, context, otherChat.id, resultId, 1), "wrong conversation target")
        expectEqual(null, resolvePublishedArtifact(data, context, chat.id, resultId, 99), "out-of-range artifact index")
        expectEqual(null, resolvePublishedArtifact(data, context, chat.id, "missing-result", 1), "missing result event")

        // An out-of-scope path resolves to a record but yields no bytes and no rendered document.
        val outside = resolvePublishedArtifact(data, context, chat.id, resultId, 2)
        expect(outside != null) { "The out-of-scope artifact record should still resolve" }
        expectEqual(null, outside!!.workspace.read(outside.artifact.path), "out-of-scope path leaked bytes")
        expectPreviewUnavailable(context, outside.artifact.path, outside.workspace::read)
        expectEqual(null, WorkspacePreview(context, project.workspaceId).read("missing.html"), "missing file leaked bytes")

        // A deleting project no longer supplies scope: its artifacts reveal nothing and work is denied.
        val deleting = checkNotNull(data.projects.create("Deleting preview project"))
        val deletingChat = data.conversations.create("Deleting preview chat", deleting.id)
        workspaces.resolve(deleting.workspaceId).apply { mkdirs(); resolve("index.html").writeText("<h1>Deleting marker</h1>") }
        val deletingResult = "deleting-result"
        data.events.createTask("deleting-task", TaskRequest(Origin.User, deletingChat.id, "Create fixture"), at, deleting.workspaceId)
        data.events.append(deletingChat.id, TaskEvent.ToolResult(deletingResult, "deleting-task", at + 1, "publish", ToolOutcome("Published", artifacts = listOf(ToolArtifact("index.html", "text/html")), succeeded = true)))
        data.events.append(deletingChat.id, TaskEvent.TaskFinished("deleting-finished", "deleting-task", at + 2))
        val blobDir = context.filesDir.resolve("event-blobs/" + Base64.getUrlEncoder().withoutPadding().encodeToString(deletingChat.id.encodeToByteArray()))
        blobDir.mkdirs(); blobDir.resolve("fixture").writeText("stored output fixture")
        deletingBlobs += blobDir

        // Deny this one directory so scoped deletion stops after the durable deleting marker.
        Os.chmod(blobDir.path, 0)
        val deletionFailed = runCatching { data.projects.delete(deleting.id) }.exceptionOrNull()
        expect(deletionFailed != null) { "Scoped deletion should have stopped on the unreadable blob directory" }
        bounded(12_000, "the deleting marker to persist") {
            data.projects.observeAll().first { rows -> rows.any { it.id == deleting.id && it.deleting } }
        }
        expectEqual(null, data.conversations.workspace(deletingChat.id), "deleting project supplied scope")
        expectEqual(null, resolvePublishedArtifact(data, context, deletingChat.id, deletingResult, 0), "deleting project artifact revealed")
        val blockedSubmit = runCatching { graph.runner.submit(TaskRequest(Origin.User, deletingChat.id, "Blocked task")) }.exceptionOrNull()
        expect(blockedSubmit is IllegalStateException) { "A deleting project admitted work: $blockedSubmit" }
        val blockedChat = runCatching { data.conversations.create("Blocked chat", deleting.id) }.exceptionOrNull()
        expect(blockedChat is IllegalStateException) { "A deleting project admitted a new chat: $blockedChat" }

        // The healthy project's artifact still resolves while the deleting one is unavailable.
        expect(resolvePublishedArtifact(data, context, chat.id, resultId, 1) != null) { "The healthy project artifact stopped resolving" }
    } finally {
        for (dir in deletingBlobs) if (dir.exists()) Os.chmod(dir.path, 0x1c0) // 0700, so fixture cleanup can remove it
        graph.close()
    }
}

// ----------------------------------------------------------------------------------------------------
// Offline WebView probes (production factory, real render conditions, bounded waits)
// ----------------------------------------------------------------------------------------------------

/**
 * Renders one document with the production offline WebView and runs [block] once it has actually
 * loaded and laid out. The WebView is created and destroyed on the main thread; the read callback runs
 * on the interception thread. An unavailable main document is a failure here.
 */
private suspend fun withOfflinePreview(
    context: Context,
    path: String,
    javascript: Boolean,
    read: (String) -> ByteArray?,
    block: suspend (WebView) -> Unit,
) {
    val unavailable = CompletableDeferred<Unit>()
    val loaded = CompletableDeferred<Unit>()
    val view = withContext(Dispatchers.Main) {
        createOfflinePreviewWebView(context, path, javascript, read) { unavailable.complete(Unit) }.also { v ->
            v.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (newProgress == 100) loaded.complete(Unit)
                }
            }
        }
    }
    try {
        bounded(20_000, "preview load of <$path>") {
            select<Unit> {
                loaded.onAwait { }
                unavailable.onAwait { }
            }
        }
        expect(!unavailable.isCompleted) { "Offline preview became unavailable for <$path>" }
        expect(loaded.isCompleted) { "Offline preview did not finish loading <$path>" }
        withContext(Dispatchers.Main) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        bounded(10_000, "preview layout of <$path>") {
            while (withContext(Dispatchers.Main) { view.contentHeight } <= 0) delay(50)
        }
        block(view)
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { view.destroy() }
    }
}

/** A rejected or missing main document must signal the native unavailable state, not a blank success. */
private suspend fun expectPreviewUnavailable(context: Context, path: String, read: (String) -> ByteArray?) {
    val unavailable = CompletableDeferred<Unit>()
    val view = withContext(Dispatchers.Main) {
        createOfflinePreviewWebView(context, path, false, read) { unavailable.complete(Unit) }
    }
    try {
        bounded(20_000, "unavailable signal for <$path>") { unavailable.await() }
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { view.destroy() }
    }
}

/** The renderer's find-in-page count for [text]; a real rendered-text condition that works with scripts off. */
private suspend fun findText(view: WebView, text: String): Int {
    val result = CompletableDeferred<Int>()
    withContext(Dispatchers.Main) {
        view.setFindListener { _, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) result.complete(numberOfMatches)
        }
        view.findAllAsync(text)
    }
    return bounded(10_000, "find-in-page for <$text>") { result.await() }
}

private suspend fun evaluate(view: WebView, script: String): String? {
    val result = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) { view.evaluateJavascript(script) { result.complete(it) } }
    return bounded(10_000, "script evaluation") { result.await() }
}
