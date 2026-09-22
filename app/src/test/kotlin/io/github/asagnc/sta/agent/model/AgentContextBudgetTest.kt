package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextBudgetTest {

    private val tools = JSONArray()

    private fun messagesOf(content: String) =
        JSONArray().put(JSONObject().put("role", "user").put("content", content))

    @Test
    fun `cjk heavy estimate is corrected downward by real usage`() {
        val budget = AgentContextBudget(window = 1_000_000)
        val messages = messagesOf("测试内容".repeat(500))
        val before = budget.estimate(messages, tools)
        assertTrue("raw estimate should count CJK chars", before >= 2_000)

        // 服务端只报 1000：字符估算高估一倍，校准必须能向下修正而不是被夹在 1.0。
        budget.observe(AgentTokenUsage(inputTokens = 1_000), before)

        val after = budget.estimate(messages, tools)
        assertTrue("calibration should shrink the inflated estimate", after < before)
        assertTrue(after <= 1_100)
    }

    @Test
    fun `estimate is never below the last real input`() {
        val budget = AgentContextBudget(1_000_000)
        budget.observe(AgentTokenUsage(inputTokens = 50_000), 10_000)
        assertTrue(budget.estimate(messagesOf("hi"), tools) >= 50_000)
    }

    @Test
    fun `compaction waits until seventy-five percent of the window`() {
        val budget = AgentContextBudget(100_000)
        assertFalse(budget.shouldCompact(74_999))
        assertTrue(budget.shouldCompact(75_000))
        assertTrue(budget.exceedsWindow(100_000))
    }

    @Test
    fun `unknown window neither notices nor compacts`() {
        val budget = AgentContextBudget(null)
        assertFalse(budget.shouldCompact(10_000_000))
        assertEquals(null, budget.windowTokens)
    }

    @Test
    fun `overflow ceiling shrinks the effective window`() {
        val budget = AgentContextBudget(1_000_000)
        assertTrue(budget.shouldCompact(750_000))

        // 服务端在 460K 就拒收：声明窗口不可信，之后按实测上限触发。
        budget.noteOverflow(460_000)
        assertEquals(460_000, budget.effectiveWindow)
        assertFalse("刚压缩完不该立刻再触发", budget.shouldCompact(344_000))
        assertTrue(budget.shouldCompact(345_000))

        // 再遇到更小的上限时取更小的那个。
        budget.noteOverflow(300_000)
        assertEquals(300_000, budget.effectiveWindow)
    }
}
