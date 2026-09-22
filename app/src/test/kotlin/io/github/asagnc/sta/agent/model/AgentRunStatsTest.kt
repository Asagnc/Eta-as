package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.runtime.AgentTokenUsage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunStatsTest {
    @Test
    fun concurrentToolRecordsAreCountedPerToolAndSortedByCalls() {
        val stats = AgentRunStats()
        val threads = 4
        val perThread = 25
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map { thread ->
                pool.submit {
                    start.await()
                    repeat(perThread) { index ->
                        // 每 5 次里有 1 次失败，验证成功与失败计数在并发写入下都不丢。
                        stats.recordTool("search_code", durationMs = 10, success = index % 5 != 0)
                        if (thread == 0) stats.recordTool("read_file", durationMs = 100, success = true)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val snapshot = stats.snapshot()
        assertEquals(100 + perThread, snapshot.getInt("tool_calls"))
        assertEquals(20, snapshot.getInt("tool_failures"))
        assertEquals(2, snapshot.getInt("distinct_tools"))
        val tools = snapshot.getJSONArray("tools")
        assertEquals("search_code", tools.getJSONObject(0).getString("name"))
        assertEquals(100, tools.getJSONObject(0).getInt("calls"))
        assertEquals(20, tools.getJSONObject(0).getInt("failed"))
        assertEquals(1_000L, tools.getJSONObject(0).getLong("total_ms"))
        assertEquals(10L, tools.getJSONObject(0).getLong("max_ms"))
        assertEquals(25, tools.getJSONObject(1).getInt("calls"))
        assertEquals(2_500L, tools.getJSONObject(1).getLong("total_ms"))
    }

    @Test
    fun roundsAndSingleCallBatchesAreCountedSeparatelyFromParallelism() {
        val stats = AgentRunStats()
        stats.roundStarted()
        stats.roundStarted()
        stats.recordParallelBatch(1)
        val snapshot = stats.snapshot()
        assertEquals(2, snapshot.getInt("rounds"))
        assertEquals(0, snapshot.getInt("parallel_batches"))
        assertEquals(0, snapshot.getInt("parallel_calls"))

        stats.recordParallelBatch(3)
        stats.recordParallelBatch(2)
        val updated = stats.snapshot()
        assertEquals(2, updated.getInt("parallel_batches"))
        assertEquals(5, updated.getInt("parallel_calls"))
    }

    @Test
    fun usageAccumulatesTotalsAndKeepsLatestContextSize() {
        val stats = AgentRunStats()
        stats.recordUsage(AgentTokenUsage(contextTokens = 1_000, inputTokens = 800, outputTokens = 100))
        stats.recordUsage(AgentTokenUsage(contextTokens = 2_400, inputTokens = 1_600, outputTokens = 200, cachedTokens = 500))
        val tokens = stats.snapshot().getJSONObject("tokens")
        assertEquals(2_400, tokens.getInt("input"))
        assertEquals(300, tokens.getInt("output"))
        assertEquals(500, tokens.getInt("cached"))
        assertEquals(2_400, tokens.getInt("context"))
    }

    @Test
    fun pruningAndContextNoticeCountersAreReported() {
        val stats = AgentRunStats()
        stats.updatePrunedToolResults(0)
        stats.updatePrunedToolResults(3)
        // 覆盖而非累加：后一轮请求会重新统计同一批结果，累加会把数字放大成没有意义的值。
        stats.updatePrunedToolResults(5)
        stats.recordContextNotice()
        val snapshot = stats.snapshot()
        assertEquals(5, snapshot.getInt("pruned_tool_results"))
        assertEquals(1, snapshot.getInt("context_notices"))
        val summary = stats.summaryText()
        assertTrue(summary.contains("最近一次请求压成占位的工具结果 5 条"))
        assertTrue(summary.contains("上下文压力提示生效 1 轮"))
    }

    @Test
    fun snapshotListsEightToolsWhileSummaryKeepsTotals() {
        val stats = AgentRunStats()
        repeat(12) { index -> stats.recordTool("tool_$index", durationMs = index * 10L, success = true) }
        val snapshot = stats.snapshot()
        assertEquals(12, snapshot.getInt("tool_calls"))
        assertEquals(12, snapshot.getInt("distinct_tools"))
        assertEquals(8, snapshot.getInt("listed_tools"))
        assertEquals(8, snapshot.getJSONArray("tools").length())

        val summary = stats.summaryText()
        assertTrue(summary.contains("12 次工具调用"))
        assertTrue(summary.contains("tool_11"))
        assertFalse(summary.contains("tool_9"))
    }
}
