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

    @Test
    fun rendersTypeNamesAfterFileNameOnlyWhenPresent() {
        val text = AgentWorkspaceManifest.render(
            listOf(
                AgentWorkspaceManifest.Dir(
                    path = "app",
                    files = listOf("A.kt", "notes.md", "B.kt"),
                    symbols = mapOf("A.kt" to listOf("Foo", "Bar")),
                ),
            ),
        )!!

        val lines = text.lines()
        // lines[0] 是表头，lines[1] 是目录行，文件行从 lines[2] 开始。
        assertEquals("$ITEM_PREFIX  A.kt — Foo, Bar", lines[2])
        // 没有类型名（非 Kotlin 文件或读取失败）的文件保持原样，不留空的后缀。
        assertEquals("$ITEM_PREFIX  notes.md", lines[3])
        assertEquals("$ITEM_PREFIX  B.kt", lines[4])
    }

    @Test
    fun scanExtractsTopLevelTypesFromKotlinFilesOnly() {
        val root = temporaryFolder.newFolder("symbols")
        File(root, "A.kt").writeText(
            "package x\n" +
                "\n" +
                "internal object Alpha\n" +
                "\n" +
                "class Beta\n" +
                "\n" +
                "sealed interface Contract\n" +
                "\n" +
                "data class Gamma(val a: Int)\n" +
                "\n" +
                "fun helper() = 1\n" +
                "\n" +
                "class Outer {\n" +
                "    class Nested\n" +
                "}\n",
        )
        File(root, "note.md").writeText("class NotAKotlin\n")

        val symbols = AgentWorkspaceManifest.scan(root).single().symbols

        // 只取类型声明：顶层 fun 不算，缩进的嵌套类不算。
        assertEquals(listOf("Alpha", "Beta", "Contract", "Gamma", "Outer"), symbols["A.kt"])
        // 非 Kotlin 文件不进映射，渲染时退化为只有文件名。
        assertNull(symbols["note.md"])
    }

    @Test
    fun rendersDeterministicallyWithSymbols() {
        val a = listOf(
            AgentWorkspaceManifest.Dir("z", listOf("Z.kt"), mapOf("Z.kt" to listOf("Zed"))),
            AgentWorkspaceManifest.Dir("", listOf("R.kt"), mapOf("R.kt" to listOf("Rex"))),
        )

        assertEquals(AgentWorkspaceManifest.render(a), AgentWorkspaceManifest.render(a.reversed()))
    }

    private companion object {
        val ITEM_PREFIX = AgentWorkspaceManifest.ITEM_PREFIX
    }
}
