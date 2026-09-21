package io.github.mangi.eta.data.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldTraceRenderTest {

    private val now = 1_700_000_000_000L

    @Test
    fun emptyTreeRendersNothing() {
        assertEquals("", WorldTraceStore.renderTree(emptyList(), now))
    }

    @Test
    fun childIsIndentedUnderItsParent() {
        val nodes = listOf(
            node(id = "root", parentId = "", role = "检索"),
            node(id = "child", parentId = "root", role = "审查"),
        )

        val lines = WorldTraceStore.renderTree(nodes, now).lines()

        // 父在子之前、子多一层缩进：层级靠缩进表达，模型据此看出谁派的谁。
        assertTrue(lines[0].startsWith("- [检索]"))
        assertTrue(lines[1].startsWith("  - [审查]"))
    }

    @Test
    fun grandChildGetsTwoLevelsOfIndent() {
        val nodes = listOf(
            node(id = "a", parentId = "", role = "检索"),
            node(id = "b", parentId = "a", role = "审查"),
            node(id = "c", parentId = "b", role = "验证"),
        )

        val lines = WorldTraceStore.renderTree(nodes, now).lines()

        assertTrue(lines[2].startsWith("    - [验证]"))
    }

    @Test
    fun showsAgeAndNodeId() {
        val nodes = listOf(node(id = "n1", parentId = "", startedAt = now - 2 * 60 * 60_000L))

        val line = WorldTraceStore.renderTree(nodes, now)

        assertTrue(line.contains("2 小时前"))
        assertTrue(line.contains("n1"))
    }

    @Test
    fun marksFailedNodes() {
        val nodes = listOf(node(id = "n1", parentId = "", status = "TIMEOUT"))

        // 状态不是 ok 时要写出来，否则失败节点看起来和正常节点一样。
        assertTrue(WorldTraceStore.renderTree(nodes, now).contains("TIMEOUT"))
    }

    @Test
    fun reportsWhereTheBodyLives() {
        val inline = node(id = "a", parentId = "", content = "x".repeat(100), contentBytes = 100)
        val external = node(
            id = "b",
            parentId = "",
            content = "",
            contentPath = "/tmp/b.txt",
            contentBytes = 500_000,
        )

        val rendered = WorldTraceStore.renderTree(listOf(inline, external), now)

        assertTrue(rendered.contains("正文 100 字节"))
        assertTrue(rendered.contains("正文较大，已存文件"))
    }

    @Test
    fun collapsesMultilineSummaryToOneLine() {
        val nodes = listOf(node(id = "n1", parentId = "", summary = "第一行\n第二行"))

        val lines = WorldTraceStore.renderTree(nodes, now).lines()

        // 一个节点一行，摘要里的换行不能把树撑散。
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("第一行 第二行"))
    }

    private fun node(
        id: String,
        parentId: String,
        role: String = "检索",
        status: String = WorldTraceStore.TRACE_STATUS_OK,
        summary: String = "结论",
        content: String = "",
        contentPath: String = "",
        contentBytes: Long = 0,
        startedAt: Long = now,
    ) = WorldTraceEntity(
        id = id,
        parentId = parentId,
        traceId = "trace-1",
        runId = "run-1",
        sessionId = "session-1",
        agent = "subagent",
        role = role,
        startedAt = startedAt,
        endedAt = startedAt + 1000,
        status = status,
        summary = summary,
        conclusion = summary,
        evidence = "",
        uncertainty = "",
        content = content,
        contentPath = contentPath,
        contentBytes = contentBytes,
    )
}
