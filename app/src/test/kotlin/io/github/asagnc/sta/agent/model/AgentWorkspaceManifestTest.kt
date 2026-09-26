package io.github.asagnc.sta.agent.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentWorkspaceManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rendersWholePathDirectoryLinesWithFilesIndentedBelow() {
        val text = AgentWorkspaceManifest.render(
            listOf(
                AgentWorkspaceManifest.Dir("", listOf("README.md")),
                AgentWorkspaceManifest.Dir("app", listOf("A.kt", "B.kt")),
                AgentWorkspaceManifest.Dir("app/src", listOf("C.kt")),
            ),
        )!!

        val lines = text.lines()
        assertTrue(lines[0], lines[0].startsWith(AgentWorkspaceManifest.HEADER_PREFIX))
        assertTrue(lines[0], lines[0].contains("根为 ${AgentWorkspaceManifest.SANDBOX_ROOT}"))
        assertTrue(lines[0], lines[0].contains("3 个目录"))
        assertTrue(lines[0], lines[0].contains("4 个文件"))
        // 目录行给完整相对路径，文件名只给基名——模型拼接即得，不需要按缩进重建路径。
        assertEquals("$ITEM_PREFIX./", lines[1])
        assertEquals("$ITEM_PREFIX  README.md", lines[2])
        assertEquals("${ITEM_PREFIX}app/", lines[3])
        assertEquals("$ITEM_PREFIX  A.kt", lines[4])
        assertEquals("$ITEM_PREFIX  B.kt", lines[5])
        assertEquals("${ITEM_PREFIX}app/src/", lines[6])
        assertEquals("$ITEM_PREFIX  C.kt", lines[7])
    }

    @Test
    fun rendersDeterministicallyRegardlessOfInputOrder() {
        val a = listOf(
            AgentWorkspaceManifest.Dir("docs", listOf("guide.md")),
            AgentWorkspaceManifest.Dir("", listOf("README.md")),
            AgentWorkspaceManifest.Dir("app", listOf("A.kt")),
        )

        assertEquals(
            AgentWorkspaceManifest.render(a),
            AgentWorkspaceManifest.render(a.reversed()),
        )
    }

    @Test
    fun emptyManifestRendersNothing() {
        assertNull(AgentWorkspaceManifest.render(emptyList()))
    }

    @Test
    fun reportsOmittedFileCountInsteadOfSilentlyTruncating() {
        val many = listOf(
            AgentWorkspaceManifest.Dir("pkg", (1..AgentWorkspaceManifest.MAX_FILES + 5).map { "F$it.kt" }),
        )

        val lines = AgentWorkspaceManifest.render(many)!!.lines()

        assertTrue(lines.last(), lines.last().contains("其余 5 个文件略"))
    }

    @Test
    fun scanSkipsBuildNoiseAndListsDirectFilesSorted() {
        val root = temporaryFolder.newFolder("workspace")
        File(root, "app").mkdirs()
        File(root, "app/B.kt").writeText("b")
        File(root, "app/A.kt").writeText("a")
        File(root, "app/src").mkdirs()
        File(root, "app/src/C.kt").writeText("c")
        File(root, "build").mkdirs()
        File(root, "build/junk.bin").writeText("junk")
        File(root, ".git").mkdirs()
        File(root, "README.md").writeText("readme")

        val dirs = AgentWorkspaceManifest.scan(root).associateBy { it.path }

        assertEquals(setOf("", "app", "app/src"), dirs.keys)
        assertEquals(listOf("README.md"), dirs[""]?.files)
        assertEquals("文件名排序，且只列直接子文件", listOf("A.kt", "B.kt"), dirs["app"]?.files)
        assertEquals(listOf("C.kt"), dirs["app/src"]?.files)
    }

    @Test
    fun scanOfMissingDirectoryIsEmpty() {
        assertEquals(emptyList<AgentWorkspaceManifest.Dir>(), AgentWorkspaceManifest.scan(File("/definitely/missing")))
    }

    private companion object {
        val ITEM_PREFIX = AgentWorkspaceManifest.ITEM_PREFIX
    }
}
