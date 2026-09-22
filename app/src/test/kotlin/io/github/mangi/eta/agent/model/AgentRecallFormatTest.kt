package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.world.WorldKnowledgeLogic
import io.github.mangi.eta.data.world.WorldKnowledgeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRecallFormatTest {

    private val now = 1_700_000_000_000L

    @Test
    fun noEntriesProducesNothing() {
        // 没有可注入的结论时不该留下一个空表头：那只是白白占上下文。
        assertNull(AgentRecallFormat.injectedLines(emptyList(), now))
    }

    @Test
    fun injectsFreshEntriesWithAge() {
        val lines = AgentRecallFormat.injectedLines(
            listOf(entry(summary = "观测库已独立成库", ageMs = 30 * 60_000L)),
            now,
        )!!

        assertEquals("${AgentRecallFormat.HEADER_PREFIX}（观测库里已有的结论，需要细节时用 world_recall 检索）：", lines.first())
        assertTrue(lines[1].startsWith(AgentRecallFormat.ITEM_PREFIX))
        assertTrue(lines[1].contains("30 分钟前"))
        assertTrue(lines[1].contains("观测库已独立成库"))
    }

    @Test
    fun skipsStaleEntries() {
        // 依赖已变更的结论不在启动注入里出现：启动时没有追问的语境，
        // 把一条「可能已失效」的结论摆在最前面，比不提更危险。
        val lines = AgentRecallFormat.injectedLines(
            listOf(
                entry(summary = "已失效的结论", freshness = WorldKnowledgeLogic.Freshness.STALE),
                entry(summary = "仍然成立的结论"),
            ),
            now,
        )!!

        assertTrue(lines.none { it.contains("已失效的结论") })
        assertTrue(lines.any { it.contains("仍然成立的结论") })
    }

    @Test
    fun skipsMissingAndSensitiveEntries() {
        val lines = AgentRecallFormat.injectedLines(
            listOf(
                entry(summary = "依赖文件没了", freshness = WorldKnowledgeLogic.Freshness.MISSING),
                entry(summary = "含敏感内容", sensitive = true),
                entry(summary = "可用结论"),
            ),
            now,
        )!!

        assertTrue(lines.none { it.contains("依赖文件没了") })
        assertTrue(lines.none { it.contains("含敏感内容") })
        assertTrue(lines.any { it.contains("可用结论") })
    }

    @Test
    fun capsInjectedEntries() {
        // 注入量必须小：预先全量加载会挤占注意力预算，细节交给 world_recall。
        val entries = (1..10).map { index -> entry(summary = "结论 $index") }

        val lines = AgentRecallFormat.injectedLines(entries, now)!!

        val injected = lines.count { it.startsWith(AgentRecallFormat.ITEM_PREFIX) }
        // 3 条结论 + 1 行提示
        assertEquals(AgentRecallFormat.MAX_ENTRIES + 1, injected)
    }

    @Test
    fun endsWithConflictHint() {
        // 历史结论是背景，不是本次任务的要求——必须写明冲突时以本次为准。
        val lines = AgentRecallFormat.injectedLines(listOf(entry(summary = "旧结论")), now)!!

        assertTrue(lines.last().contains("以本次为准"))
    }

    @Test
    fun collapsesMultilineSummaries() {
        // 注入的行必须一条一行，不能因为原文带换行撑开成多条消息。
        val lines = AgentRecallFormat.injectedLines(
            listOf(entry(summary = "第一行\n第二行\n第三行")),
            now,
        )!!

        assertTrue(lines[1].contains("第一行 第二行 第三行"))
    }

    private fun entry(
        summary: String,
        ageMs: Long = 60_000L,
        freshness: WorldKnowledgeLogic.Freshness = WorldKnowledgeLogic.Freshness.FRESH,
        sensitive: Boolean = false,
    ) = WorldKnowledgeStore.Recalled(
        id = "id-$summary",
        kind = WorldKnowledgeStore.KIND_FINDING,
        scope = "",
        summary = summary,
        evidence = "",
        uncertainty = "",
        createdAt = now - ageMs,
        freshness = freshness,
        sensitive = sensitive,
    )
}
