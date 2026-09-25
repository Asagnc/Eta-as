package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextBudgetCompositionTest {

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private val tools: JSONArray = JSONArray().put(
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject().put("name", "terminal").put("description", "运行命令")),
    )

    @Test
    fun `分段之和与 rawEstimate 完全一致`() {
        val messages = JSONArray()
            .put(message("system", "你是 Sta，遵守规则。"))
            .put(message("user", "看一下设备状态"))
            .put(message("tool", "{\"ok\":true,\"battery\":80}"))
            .put(message("assistant", "电池 80%"))

        val composition = AgentContextBudget.compositionOf(messages, tools)

        assertEquals(AgentContextBudget.rawEstimate(messages, tools), composition.total)
    }

    @Test
    fun `system 与 developer 计入常驻段 tool 计入工具结果`() {
        val system = message("system", "常驻规则")
        val developer = message("developer", "补充规则")
        val toolResult = message("tool", "{\"ok\":true}")
        val user = message("user", "问题")
        val assistant = message("assistant", "回答")
        val messages = JSONArray()
            .put(system).put(developer).put(toolResult).put(user).put(assistant)

        val composition = AgentContextBudget.compositionOf(messages)

        assertEquals(
            AgentContextBudget.messageTokens(system) + AgentContextBudget.messageTokens(developer),
            composition.system,
        )
        assertEquals(AgentContextBudget.messageTokens(toolResult), composition.toolResults)
        assertEquals(
            AgentContextBudget.messageTokens(user) + AgentContextBudget.messageTokens(assistant),
            composition.history,
        )
        assertEquals(AgentContextBudget.textTokens(JSONArray().toString()) + 16, composition.tools)
    }

    @Test
    fun `工具定义只影响 tools 段`() {
        val messages = JSONArray().put(message("user", "hi"))
        val without = AgentContextBudget.compositionOf(messages)
        val with = AgentContextBudget.compositionOf(messages, tools)

        assertTrue(with.tools > without.tools)
        assertEquals(without.system, with.system)
        assertEquals(without.history, with.history)
        assertEquals(without.toolResults, with.toolResults)
    }
}
