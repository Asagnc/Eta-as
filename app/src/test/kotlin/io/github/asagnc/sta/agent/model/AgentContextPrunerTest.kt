package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextPrunerTest {

    @Test
    fun onlyTheMostRecentToolResultsKeepTheirFullContent() {
        val messages = conversation(toolResults = 3)
        val originalLastTool = messages.getJSONObject(7).getString("content")

        val result = AgentContextPruner.prune(messages, keepRecentToolResults = 1)

        assertEquals(2, result.prunedCount)
        assertEquals(
            AgentContextPruner.placeholder(),
            result.messages.getJSONObject(3).getString("content"),
        )
        assertEquals(
            AgentContextPruner.placeholder(),
            result.messages.getJSONObject(5).getString("content"),
        )
        assertEquals(originalLastTool, result.messages.getJSONObject(7).getString("content"))
        assertEquals("system", result.messages.getJSONObject(0).getString("role"))
        assertEquals("user 输入", result.messages.getJSONObject(1).getString("content"))
        assertTrue(result.messages.getJSONObject(2).has("tool_calls"))
    }

    @Test
    fun pruningNeverMutatesTheStoredHistory() {
        val messages = conversation(toolResults = 3)
        val before = messages.toString()

        AgentContextPruner.prune(messages, keepRecentToolResults = 0)

        // 请求视图的构造不能改写历史：会话记录与归档仍然要保留完整工具结果。
        assertEquals(before, messages.toString())
        assertFalse(messages.toString().contains("pruned"))
    }

    @Test
    fun untouchedMessagesAreSharedAndRewrittenOnesAreCloned() {
        val messages = conversation(toolResults = 3)

        val result = AgentContextPruner.prune(messages, keepRecentToolResults = 1)

        // 只有被改写的消息需要克隆，其余共享原对象——这正是省掉整段深拷贝的地方。
        assertSame(messages.getJSONObject(0), result.messages.getJSONObject(0))
        assertSame(messages.getJSONObject(7), result.messages.getJSONObject(7))
        assertNotSame(messages.getJSONObject(3), result.messages.getJSONObject(3))
    }

    @Test
    fun shortResultsAndDisabledPruningAreLeftAlone() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "tool").put("content", """{"ok":true}"""))
            .put(JSONObject().put("role", "tool").put("content", "x".repeat(500)))
        val kept = AgentContextPruner.prune(messages, keepRecentToolResults = 1)

        assertEquals(0, kept.prunedCount)
        assertEquals("""{"ok":true}""", kept.messages.getJSONObject(0).getString("content"))

        val second = JSONArray().put(JSONObject().put("role", "tool").put("content", "x".repeat(500)))
        val disabled = AgentContextPruner.prune(second, keepRecentToolResults = -1)

        assertEquals(0, disabled.prunedCount)
        // 关闭清理时视图仍是独立的顶层数组（元素共享），注入逻辑才动不到历史。
        assertNotSame(second, disabled.messages)
        assertSame(second.getJSONObject(0), disabled.messages.getJSONObject(0))
        assertEquals("x".repeat(500), second.getJSONObject(0).getString("content"))
    }

    @Test
    fun viewIsAlwaysAFreshTopLevelArray() {
        val messages = conversation(toolResults = 2)

        // 顶层数组必须独立：注入逻辑会在视图上替换元素，共享顶层数组就会改到历史，
        // 而历史对象一旦被替换，按引用相等定位瞬时观察消息的删除就会失效。
        val result = AgentContextPruner.prune(messages, keepRecentToolResults = 99)

        assertEquals(0, result.prunedCount)
        assertNotSame(messages, result.messages)
        assertSame(messages.getJSONObject(0), result.messages.getJSONObject(0))
    }

    @Test
    fun transientObservationIsClonedIntoTheView() {
        // 瞬时观察（工具截图）在下一轮会被从历史里按引用摘掉，视图若共享同一个对象，
        // 截图就会在请求里多留一轮。
        val transient = JSONObject()
            .put("role", "user")
            .put("content", "观察图")
            .put("_sta_observation", true)
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "提示"))
            .put(transient)

        val result = AgentContextPruner.prune(messages, keepRecentToolResults = 99)

        assertNotSame(transient, result.messages.getJSONObject(1))
        assertSame(messages.getJSONObject(0), result.messages.getJSONObject(0))
    }

    @Test
    fun resultThatAloneExceedsTheProtectionBudgetIsNotProtected() {
        // 保护区从尾部往前累加，越线即停：一条 150k 字符（约 50k token）的结果自己就跨过了
        // 40k 预算，越线时它已经不在保护区内，因此仍会被裁。
        // 这是原有语义（旧实现同样是先累加、越线 break、再入集合），不是本次改动引入的；
        // 实践中最近的若干条结果由 keepRecentToolResults 兜住，受影响的只是更早的大结果。
        val messages = JSONArray()
            .put(JSONObject().put("role", "tool").put("content", "y".repeat(150_000)))

        val result = AgentContextPruner.prune(messages, keepRecentToolResults = 0)

        assertEquals(1, result.prunedCount)
        assertEquals(
            AgentContextPruner.placeholder(),
            result.messages.getJSONObject(0).getString("content"),
        )
    }

    @Test
    fun resultsInsideProtectedTokensAreLeftAlone() {
        // 少量、较新的结果全部落在保护区内，一条都不裁。
        val messages = JSONArray()
            .put(JSONObject().put("role", "tool").put("content", "y".repeat(300)))
            .put(JSONObject().put("role", "tool").put("content", "z".repeat(300)))

        val result = AgentContextPruner.prune(messages, keepRecentToolResults = 0)

        assertEquals(0, result.prunedCount)
        assertEquals("y".repeat(300), result.messages.getJSONObject(0).getString("content"))
    }

    private fun conversation(toolResults: Int): JSONArray = JSONArray().also { messages ->
        messages
            .put(JSONObject().put("role", "system").put("content", "系统提示"))
            .put(JSONObject().put("role", "user").put("content", "user 输入"))
        repeat(toolResults) { index ->
            messages.put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-$index"))),
            )
            messages.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call-$index")
                    // 保护区按 token 划分（最近 40k token 内的工具结果不裁剪），
                    // 内容要足够大才能落到保护区之外，否则一条也裁不掉。
                    .put("content", "工具结果 ${index + 1}：" + "y".repeat(150_000)),
            )
        }
    }
}
