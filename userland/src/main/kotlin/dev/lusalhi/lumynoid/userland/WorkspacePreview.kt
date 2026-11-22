package dev.lusalhi.lumynoid.userland

import android.content.Context
import java.io.IOException

/**
 * Read-only access to one conversation's existing workspace for the offline artifact preview.
 * Nothing is created: a missing workspace or file is simply unavailable. Every read reuses the
 * coding tools' workspace validation — component-wise resolution without following links,
 * single-linked regular files only, and the same bounded read — so preview cannot widen the
 * coding file boundary. Rejections are data-only nulls; no host path or file content is disclosed.
 *
 * [read] is synchronous and must run on an I/O or WebView interception thread, never the main thread.
 */
class WorkspacePreview(context: Context, private val workspaceId: String) {
    private val workspace = CodingWorkspace(UserlandRuntime.workspaceRoot(context))

    init {
        require(CodingWorkspace.isValidId(workspaceId)) { "Invalid workspace ID" }
    }

    /** The current bytes of one workspace-relative file, or null when it is unavailable or rejected. */
    fun read(relativePath: String): ByteArray? {
        val components = CodingWorkspace.pathComponents(relativePath) ?: return null
        return try {
            val directory = workspace.existingDirectory(workspaceId) ?: return null
            workspace.readBounded(workspace.regularFile(directory, components))
        } catch (_: CodingReject) {
            null
        } catch (_: IOException) {
            null
        }
    }
}
