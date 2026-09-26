package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.data.world.WorldKnowledgeLogic
import io.github.asagnc.sta.data.world.WorldKnowledgeStore
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

        assertEquals("${AgentRecallFormat.HEADER_PREFIX}（观测库里可复用的结论，此处只列标题，正文按需检索）：", lines.first())
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
        // 上限条标题 + 1 行提示
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

    @Test
    fun injectsTitlesInsteadOfFullConclusions() {
        // 注入带正文就等于把存量数据无条件摆到模型面前：一条 4000 字的报告即使被截断，
        // 也仍然占着上千字的注意力预算。标题是单行的，总量有界，正文交给 world_recall。
        // 用「可复用但长于标题上限」的样本：超过入库上限的条目根本进不了库，那是写入侧的事。
        val long = "结".repeat(100) + "。后续细节不应出现在注入里"
        val lines = AgentRecallFormat.injectedLines(listOf(entry(summary = long)), now)!!

        val item = lines.first { it.startsWith(AgentRecallFormat.ITEM_PREFIX) }
        // 标题收在长度上限附近，且完整正文没有跟进来。
        assertTrue(item.endsWith("…"))
        assertTrue(item.length <= WorldKnowledgeLogic.MAX_TITLE_CHARS + 20)
        assertTrue(!item.contains("后续细节不应出现在注入里"))
        assertTrue(lines.last().contains("world_recall"))
    }

    @Test
    fun skipsUnusableStoredEntries() {
        // 形态不可复用的存量条目（工具输出流水账、整份报告）既不该注入正文，也不该占标题位。
        val raw = "命令1: ok=true exit_code=0 stdout前400字符=" + "x".repeat(400)
        val lines = AgentRecallFormat.injectedLines(
            listOf(entry(summary = raw), entry(summary = "可用结论")),
            now,
        )!!

        assertTrue(lines.none { it.contains("命令1:") })
        assertTrue(lines.any { it.contains("可用结论") })
    }

    @Test
    fun skipsEntriesAlreadyInContext() {
        // 会话里已经出现过的结论不必再注入：模型已经从工具结果里读到过它。
        val lines = AgentRecallFormat.injectedLines(
            listOf(entry(summary = "已经在会话里的结论"), entry(summary = "新结论")),
            now,
            alreadyInContext = { probe -> probe.startsWith("已经在会话里") },
        )!!

        assertTrue(lines.none { it.contains("已经在会话里的结论") })
        assertTrue(lines.any { it.contains("新结论") })
    }

    @Test
    fun probeIsNormalizedPrefix() {
        assertEquals("一句话结论", AgentRecallFormat.probeOf("  一句话结论  \n"))
    }

    @Test
    fun skipsInconclusiveConclusions() {
        // 「查不到」型的结论没有可复用的知识；写库侧已拦新的，这里兜住修复前的历史脏数据。
        val lines = AgentRecallFormat.injectedLines(
            listOf(
                entry(summary = "无法给出结论。本环境没有联网工具。"),
                entry(summary = "可用结论"),
            ),
            now,
        )!!

        assertTrue(lines.none { it.contains("无法给出结论") })
        assertTrue(lines.any { it.contains("可用结论") })
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
