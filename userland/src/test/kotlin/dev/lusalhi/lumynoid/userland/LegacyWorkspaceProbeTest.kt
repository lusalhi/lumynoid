package dev.lusalhi.lumynoid.userland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LegacyWorkspaceProbeTest {
    @get:Rule val files = TemporaryFolder()

    private val workspaceRoot get() = files.root.toPath().resolve("workspaces")
    private val installRoot get() = files.root.toPath().resolve("coding-installs")

    @Test
    fun missingAndOnlyEmptyDirectoriesAreNotDataAndNeverCreateTheOtherRoot() {
        assertEquals(false, UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "plain"))
        assertFalse(Files.exists(workspaceRoot))
        Files.createDirectories(workspaceRoot.resolve("plain/nested/empty"))
        assertEquals(false, UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "plain"))
        assertFalse(Files.exists(installRoot))
    }

    @Test
    fun bothFileOnlyAndPackageOnlyScopesAreFoundWithoutChangingStorage() {
        val file = workspaceRoot.resolve("file-chat/notes.txt")
        val module = installRoot.resolve("package-chat").resolve(BUNDLE_DIGEST)
        Files.createDirectories(file.parent)
        Files.createDirectories(workspaceRoot.resolve("package-chat"))
        Files.createDirectories(module)
        Files.writeString(file, "legacy notes")
        Files.writeString(module.resolve("index.js"), "legacy package")
        Files.writeString(module.resolve(".lumynoid-coding-sha256"), BUNDLE_DIGEST)
        Files.writeString(module.resolve("package.json"), "{}")

        assertEquals(true, UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "file-chat"))
        assertEquals(true, UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "package-chat"))
        assertEquals(false, UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "other-chat"))
        assertEquals(0L, Files.list(workspaceRoot.resolve("package-chat")).use { it.count() })
        assertFalse(Files.exists(installRoot.resolve("file-chat")))
        assertEquals("legacy notes", Files.readString(file))
        assertEquals("legacy package", Files.readString(module.resolve("index.js")))
    }

    @Test
    fun abandonedInstallStagingIsNotAnInstalledPackageAndIsLeftUntouched() {
        val staging = Files.createDirectories(installRoot.resolve("chat/.install-abandoned"))
        val partial = Files.writeString(staging.resolve("package.json"), "unpublished")

        assertEquals(false, UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "chat"))
        assertEquals("unpublished", Files.readString(partial))

        val workspace = Files.createDirectories(workspaceRoot.resolve("chat/.install-user-dir"))
        Files.writeString(workspace.resolve("notes.txt"), "user file")
        assertEquals(true, UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "chat"))
    }

    @Test
    fun invalidSecondRootCannotBeTreatedAsAbsentEvenWithFilesInTheFirst() {
        val directory = Files.createDirectories(workspaceRoot.resolve("chat"))
        Files.writeString(directory.resolve("notes.txt"), "kept")
        Files.createSymbolicLink(installRoot, workspaceRoot)

        assertNull(UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "chat"))
        assertNull(UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "../workspaces/chat"))
        assertTrue(Files.isSymbolicLink(installRoot))
        Files.delete(installRoot)
        assertEquals(true, UserlandRuntime.legacyWorkspaceHasData(workspaceRoot, "chat"))
    }

    private companion object {
        const val BUNDLE_DIGEST = "f41a1abafd7139e78a62e2f43d83e0bfc7e43534a1044503ca0e801a9a67a293"
    }
}
