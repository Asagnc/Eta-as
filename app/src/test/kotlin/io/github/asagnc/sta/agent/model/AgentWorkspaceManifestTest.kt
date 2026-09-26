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
    fun rendersHeaderThenIndentedDirectoriesWithDirectFileCounts() {
        val text = AgentWorkspaceManifest.render(
            listOf(
                AgentWorkspaceManifest.Dir("", 2),
                AgentWorkspaceManifest.Dir("app", 3),
                AgentWorkspaceManifest.Dir("app/src", 0),
                AgentWorkspaceManifest.Dir("docs", 1),
            ),
        )!!

        val lines = text.lines()
        assertTrue(lines[0], lines[0].startsWith(AgentWorkspaceManifest.HEADER_PREFIX))
        assertTrue(lines[0], lines[0].contains("根为 ${AgentWorkspaceManifest.SANDBOX_ROOT}"))
        assertEquals("$ITEM_PREFIX. (2)", lines[1])
        assertEquals("$ITEM_PREFIX  app/ (3)", lines[2])
        assertEquals("$ITEM_PREFIX    src/ (0)", lines[3])
        assertEquals("$ITEM_PREFIX  docs/ (1)", lines[4])
    }

    @Test
    fun rendersDeterministicallyRegardlessOfInputOrder() {
        val a = listOf(
            AgentWorkspaceManifest.Dir("docs", 1),
            AgentWorkspaceManifest.Dir("", 2),
            AgentWorkspaceManifest.Dir("app/src", 0),
            AgentWorkspaceManifest.Dir("app", 3),
        )
        val b = a.reversed()

        assertEquals(
            AgentWorkspaceManifest.render(a),
            AgentWorkspaceManifest.render(b),
        )
    }

    @Test
    fun emptyTopologyRendersNothing() {
        assertNull(AgentWorkspaceManifest.render(emptyList()))
    }

    @Test
    fun reportsOmittedCountInsteadOfSilentlyTruncating() {
        val many = (1..AgentWorkspaceManifest.MAX_DIRS + 7).map {
            AgentWorkspaceManifest.Dir("pkg$it", it)
        }

        val lines = AgentWorkspaceManifest.render(many)!!.lines()

        assertEquals(AgentWorkspaceManifest.MAX_DIRS + 2, lines.size)
        assertTrue(lines.last(), lines.last().contains("其余 7 个目录略"))
    }

    @Test
    fun scanSkipsBuildNoiseAndCountsOnlyDirectFiles() {
        val root = temporaryFolder.newFolder("workspace")
        File(root, "app").mkdirs()
        File(root, "app/A.kt").writeText("a")
        File(root, "app/B.kt").writeText("b")
        File(root, "app/src").mkdirs()
        File(root, "app/src/C.kt").writeText("c")
        File(root, "build").mkdirs()
        File(root, "build/junk.bin").writeText("junk")
        File(root, ".git").mkdirs()
        File(root, "README.md").writeText("readme")

        val dirs = AgentWorkspaceManifest.scan(root).associateBy { it.path }

        assertEquals(setOf("", "app", "app/src"), dirs.keys)
        assertEquals("根目录只数直接子文件", 1, dirs[""]?.fileCount)
        assertEquals("不递归累计子目录里的文件", 2, dirs["app"]?.fileCount)
        assertEquals(1, dirs["app/src"]?.fileCount)
    }

    @Test
    fun scanOfMissingDirectoryIsEmpty() {
        assertEquals(emptyList<AgentWorkspaceManifest.Dir>(), AgentWorkspaceManifest.scan(File("/definitely/missing")))
    }

    private companion object {
        /** 与条目前缀相同，单独取个短名让上面的行断言读起来不至于被长常量名淹没。 */
        val ITEM_PREFIX = AgentWorkspaceManifest.ITEM_PREFIX
    }
}
