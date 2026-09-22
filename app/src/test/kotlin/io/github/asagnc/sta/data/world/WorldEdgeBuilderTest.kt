package io.github.asagnc.sta.data.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建边规则测试。
 *
 * 三层边的权重比例决定聚类边界落在哪里，这里把每种情形都钉住：什么情况该连边、
 * 连哪一层、权重多少、什么情况**不该**连边。
 */
class WorldEdgeBuilderTest {

    private fun node(
        id: String,
        files: Set<String> = emptySet(),
        symbols: Set<String> = emptySet(),
        keywords: Set<String> = emptySet(),
    ) = WorldEdgeBuilder.Node(id, files, symbols, keywords)

    @Test
    fun `共享文件产生一条 file 边`() {
        val a = node("a", files = setOf("/x/One.kt"))
        val b = node("b", files = setOf("/x/One.kt"))
        val planned = WorldEdgeBuilder.planFor(a, listOf(b))
        assertEquals(1, planned.size)
        assertEquals(WorldEdgeBuilder.Layer.FILE, planned[0].layer)
        assertTrue(planned[0].weight > 0)
    }

    @Test
    fun `文件边权重有上限`() {
        // 共享一个文件与共享三个文件都该有边，但不应因共享数量无限增长：
        // 否则反复观测同一件事的节点权重会吃掉整个图。
        val many = (1..10).map { "/x/F$it.kt" }.toSet()
        val a = node("a", files = many)
        val b = node("b", files = many)
        val planned = WorldEdgeBuilder.planFor(a, listOf(b))
        val fileEdge = planned.first { it.layer == WorldEdgeBuilder.Layer.FILE }
        assertTrue(fileEdge.weight <= WorldEdgeBuilder.WEIGHT_FILE)
    }

    @Test
    fun `单个共享符号不产生符号边`() {
        // 单个同名符号太容易是巧合（两个模块各有一个 init 之类），需要更多重叠才算数。
        val a = node("a", symbols = setOf("SharedSymbol"))
        val b = node("b", symbols = setOf("SharedSymbol"))
        val planned = WorldEdgeBuilder.planFor(a, listOf(b))
        assertFalse(planned.any { it.layer == WorldEdgeBuilder.Layer.SYMBOL })
    }

    @Test
    fun `两个以上共享符号产生符号边`() {
        val shared = setOf("SymbolAlpha", "SymbolBeta")
        val a = node("a", symbols = shared)
        val b = node("b", symbols = shared)
        val planned = WorldEdgeBuilder.planFor(a, listOf(b))
        assertTrue(planned.any { it.layer == WorldEdgeBuilder.Layer.SYMBOL })
    }

    @Test
    fun `文件层权重高于符号层`() {
        // 共享文件是同一性判断（几乎不可能出错），共享符号是强相关（偶有同名）。
        // 这个排序决定聚类边界，不能反。
        assertTrue(WorldEdgeBuilder.WEIGHT_FILE > WorldEdgeBuilder.WEIGHT_SYMBOL)
        assertTrue(WorldEdgeBuilder.WEIGHT_SYMBOL > WorldEdgeBuilder.WEIGHT_KEYWORD)
    }

    @Test
    fun `关键词重叠过低不产生边`() {
        val a = node("a", keywords = setOf("alpha", "beta", "gamma"))
        val b = node("b", keywords = setOf("delta", "epsilon", "zeta"))
        val planned = WorldEdgeBuilder.planFor(a, listOf(b))
        assertTrue(planned.isEmpty())
    }

    @Test
    fun `关键词高度重合产生关键词边`() {
        val shared = setOf("clustering", "observation", "workspace")
        val a = node("a", keywords = shared)
        val b = node("b", keywords = shared)
        val planned = WorldEdgeBuilder.planFor(a, listOf(b))
        assertTrue(planned.any { it.layer == WorldEdgeBuilder.Layer.KEYWORD })
    }

    @Test
    fun `与自己不连边`() {
        val a = node("a", files = setOf("/x/One.kt"))
        val planned = WorldEdgeBuilder.planFor(a, listOf(a))
        assertTrue("自己与自己不该产生边", planned.isEmpty())
    }

    @Test
    fun `边是无向的按字典序排两端`() {
        // 否则 (A,B) 与 (B,A) 会被当成两条不同的边，唯一索引也挡不住重复。
        val a = node("zzz", files = setOf("/x/One.kt"))
        val b = node("aaa", files = setOf("/x/One.kt"))
        val planned = WorldEdgeBuilder.planFor(a, listOf(b))
        assertEquals("aaa", planned[0].srcId)
        assertEquals("zzz", planned[0].dstId)
    }

    @Test
    fun `同一对节点的边 id 与顺序无关`() {
        assertEquals(
            WorldGraphStore.edgeId("a", "b", WorldEdgeBuilder.Layer.FILE),
            WorldGraphStore.edgeId("b", "a", WorldEdgeBuilder.Layer.FILE),
        )
        // 不同层必须是不同的边 id，否则多层边会互相覆盖。
        assertFalse(
            WorldGraphStore.edgeId("a", "b", WorldEdgeBuilder.Layer.FILE) ==
                WorldGraphStore.edgeId("a", "b", WorldEdgeBuilder.Layer.SYMBOL),
        )
    }

    @Test
    fun `一个节点可与多个既有节点各建边`() {
        val a = node("a", files = setOf("/x/One.kt"))
        val b = node("b", files = setOf("/x/One.kt"))
        val c = node("c", files = setOf("/x/One.kt"))
        val planned = WorldEdgeBuilder.planFor(a, listOf(b, c))
        assertEquals(2, planned.size)
    }

    @Test
    fun `关键词按频次取前若干`() {
        val keywords = WorldEdgeBuilder.keywordsOf(
            "observation observation observation workspace workspace other",
        )
        // 高频词更能代表这段文本在讲什么。
        assertTrue(keywords.contains("observation"))
        assertTrue(keywords.contains("workspace"))
        assertEquals(0, keywords.size.compareTo(24).coerceAtMost(0).let { if (keywords.size <= 24) 0 else 1 })
    }

    @Test
    fun `中文按二元切分`() {
        val keywords = WorldEdgeBuilder.keywordsOf("观测层的空间维度")
        // 不引入分词器，二元切分对「两段文本是否在讲相近的事」这个用途已经够用。
        assertTrue(keywords.contains("观测"))
        assertTrue(keywords.contains("空间"))
    }

    @Test
    fun `停用词不进关键词`() {
        val keywords = WorldEdgeBuilder.keywordsOf("this file that code with the")
        assertFalse(keywords.contains("this"))
        assertFalse(keywords.contains("file"))
        assertFalse(keywords.contains("code"))
    }

    @Test
    fun `空关键词不产生关键词边`() {
        val a = node("a")
        val b = node("b")
        assertTrue(WorldEdgeBuilder.planFor(a, listOf(b)).isEmpty())
    }

    @Test
    fun `层枚举可从字符串还原`() {
        assertEquals(WorldEdgeBuilder.Layer.FILE, WorldEdgeBuilder.Layer.from("file"))
        assertEquals(WorldEdgeBuilder.Layer.SEMANTIC, WorldEdgeBuilder.Layer.from("semantic"))
        assertNull(WorldEdgeBuilder.Layer.from("unknown"))
        assertNotNull(WorldEntityExtractor.EntityKind.from("symbol"))
    }
}