package io.github.asagnc.eta.agent.terminal

import io.github.asagnc.eta.core.AgentLogger
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessTerminalAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ordinaryRunCommandUsesAppIdentityWithoutGrant() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val result = JSONObject(controller.runCommand("printf hello", temporary.root.path, 5))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("user", result.getString("identity"))
            assertEquals("hello", result.getString("stdout"))
        } finally { controller.closeAll() }
    }

    @Test fun explicitRootCommandIsRejectedWithoutGrant() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val result = JSONObject(controller.terminalOpenAndExec("id", "/", 5000, "root", false))
            assertFalse(result.getBoolean("ok"))
            assertEquals("ROOT_REQUIRED", result.getString("code"))
        } finally { controller.closeAll() }
    }

    @Test fun ordinaryFileReadCannotFollowWorkspaceLinkOutsideAllowedRoots() {
        val workspace = File(TerminalRuntime.userWorkspacePath).apply { mkdirs() }
        val link = File(workspace, "test-link-${System.nanoTime()}")
        val outside = temporary.newFile("outside").apply { writeText("private") }
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            val result = JSONObject(UserFileAccess.read(link.path, 0, 100))
            assertFalse(result.getBoolean("ok"))
            assertEquals("INVALID_PATH", result.getString("code"))
        } finally { Files.deleteIfExists(link.toPath()) }
    }

    @Test fun ordinaryReadsReportOffsetsForTheReturnedChunk() {
        val file = File(TerminalRuntime.userWorkspacePath, "test-read-${System.nanoTime()}")
        try {
            file.parentFile!!.mkdirs()
            file.writeText("a".repeat(20_000))
            val first = JSONObject(UserFileAccess.read(file.path, 0, 200_000))
            assertTrue(first.getBoolean("truncated"))
            assertEquals(first.getString("content").length, first.getInt("bytes_read"))
            val second = JSONObject(UserFileAccess.read(file.path, first.getInt("bytes_read"), 200_000))
            assertFalse(second.getBoolean("truncated"))
            assertEquals(20_000, first.getString("content").length + second.getString("content").length)
        } finally { file.delete() }
    }

    @Test fun ordinaryFindFilesMatchesGlobAndHintsMissingPath() {
        val dir = File(TerminalRuntime.userWorkspacePath, "test-find-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "alpha.kt").writeText("")
            File(dir, "beta.txt").writeText("")

            val found = JSONObject(UserFileAccess.findFiles(dir.path, "*.kt", 10, noIgnore = false, hidden = false))
            assertTrue(found.toString(), found.getBoolean("ok"))
            assertEquals(1, found.getInt("count"))
            assertEquals("alpha.kt", File(found.getJSONArray("files").getString(0)).name)

            val missing = JSONObject(
                UserFileAccess.findFiles(File(dir, "nope").path, "*.kt", 10, noIgnore = false, hidden = false),
            )
            assertFalse(missing.getBoolean("ok"))
            assertEquals("PATH_NOT_FOUND", missing.getString("code"))
            assertTrue(missing.getString("message"), missing.getString("message").contains("路径不存在"))
            assertTrue(missing.getString("message"), missing.getString("message").contains("alpha.kt"))

            val search = JSONObject(
                UserFileAccess.searchCode(
                    File(dir, "nope").path, "alpha", null, 10, 0, 1000, false,
                    ignoreCase = false, offset = 0, noIgnore = false, hidden = false,
                ),
            )
            assertEquals("PATH_NOT_FOUND", search.getString("code"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
