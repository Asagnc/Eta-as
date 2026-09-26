package io.github.asagnc.sta.agent.model

import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestContextTest {
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 9, 20, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val plan = """[{"id":"1","content":"改代码","status":"in_progress"}]"""

    private fun textMessages(content: String): JSONArray = JSONArray().put(
        JSONObject().put("role", "user").put("content", content),
    )

    private fun partMessages(content: String): JSONArray = JSONArray().put(
        JSONObject().put("role", "user").put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", content)),
        ),
    )

    @Test
    fun `plan block is prepended above the clock line`() {
        val messages = textMessages("原始内容")

        AgentRequestContext.attach(messages, taskPlanJson = plan, planJson = null, now = now)

        val content = messages.getJSONObject(0).getString("content")
        assertTrue(content, content.startsWith("当前任务清单（0/1 完成，1 项未完成）："))
        assertTrue(content, content.contains("[task_plan] [>] 1 改代码"))
        assertTrue(content, content.contains(AgentRequestClock.PREFIX))
        assertTrue(content, content.endsWith("原始内容"))
    }

    @Test
    fun `attaching twice does not duplicate either block`() {
        val messages = textMessages("原始内容")

        AgentRequestContext.attach(messages, taskPlanJson = plan, planJson = null, now = now)
        AgentRequestContext.attach(messages, taskPlanJson = plan, planJson = null, now = now)

        val lines = messages.getJSONObject(0).getString("content").lines()
        assertEquals(1, lines.count { it.startsWith(AgentTaskPlanFormat.HEADER_PREFIX) })
        assertEquals(1, lines.count { it.startsWith(AgentTaskPlanFormat.ITEM_PREFIX) && it.contains("1 改代码") })
        // 断点提示行也属于计划块，重复注入时同样只能留一份。
        assertEquals(1, lines.count { it.contains("提示：上次运行停在第 1 步") })
        assertEquals(1, lines.count { it.startsWith(AgentRequestClock.PREFIX) })
    }

    @Test
    fun `without a plan only the clock line is added`() {
        val messages = textMessages("原始内容")

        AgentRequestContext.attach(messages, taskPlanJson = null, planJson = null, now = now)

        val content = messages.getJSONObject(0).getString("content")
        assertFalse(content, content.contains(AgentTaskPlanFormat.ITEM_PREFIX))
        assertTrue(content, content.contains(AgentRequestClock.PREFIX))
    }

    @Test
    fun `part content keeps the plan after the clock part`() {
        val messages = partMessages("原始内容")

        AgentRequestContext.attach(messages, taskPlanJson = plan, planJson = null, now = now)
        AgentRequestContext.attach(messages, taskPlanJson = plan, planJson = null, now = now)

        val parts = messages.getJSONObject(0).getJSONArray("content")
        assertEquals(3, parts.length())
        assertTrue(parts.getJSONObject(0).getString("text").startsWith(AgentRequestClock.PREFIX))
        assertTrue(parts.getJSONObject(1).getString("text").startsWith(AgentTaskPlanFormat.HEADER_PREFIX))
        assertEquals("原始内容", parts.getJSONObject(2).getString("text"))
    }

    @Test
    fun `messages without content are left alone`() {
        val messages = JSONArray().put(JSONObject().put("role", "user"))

        AgentRequestContext.attach(messages, taskPlanJson = plan, planJson = null, now = now)

        assertFalse(messages.getJSONObject(0).has("content"))
    }

    @Test
    fun `workspace tree is injected above the clock and below the plan`() {
        val messages = textMessages("原始内容")

        AgentRequestContext.attach(
            messages,
            taskPlanJson = plan,
            planJson = null,
            workspaceTree = TREE,
            now = now,
        )

        val text = messages.getJSONObject(0).getString("content")
        val planAt = text.indexOf(AgentTaskPlanFormat.HEADER_PREFIX)
        val treeAt = text.indexOf(AgentWorkspaceManifest.HEADER_PREFIX)
        val clockAt = text.indexOf(AgentRequestClock.PREFIX)
        assertTrue("拓扑块应当注入：$text", treeAt >= 0)
        assertTrue("拓扑在计划之后：$text", planAt < treeAt)
        assertTrue("拓扑在时钟行之前：$text", treeAt < clockAt)
        assertTrue("原始内容保持最后：$text", text.endsWith("原始内容"))
        assertTrue(text, text.contains("$ITEM_PREFIX  app/ (3)"))
    }

    @Test
    fun `attaching twice does not duplicate the workspace tree`() {
        val messages = textMessages("原始内容")

        AgentRequestContext.attach(messages, taskPlanJson = null, planJson = null, workspaceTree = TREE, now = now)
        AgentRequestContext.attach(messages, taskPlanJson = null, planJson = null, workspaceTree = TREE, now = now)

        val lines = messages.getJSONObject(0).getString("content").lines()
        assertEquals(1, lines.count { it.startsWith(AgentWorkspaceManifest.HEADER_PREFIX) })
        assertEquals(1, lines.count { it.contains("app/ (3)") })
        assertEquals(1, lines.count { it.startsWith(AgentRequestClock.PREFIX) })
    }

    @Test
    fun `without a tree nothing is added for it`() {
        val messages = textMessages("原始内容")

        AgentRequestContext.attach(messages, taskPlanJson = null, planJson = null, now = now)

        val content = messages.getJSONObject(0).getString("content")
        assertFalse(content, content.contains(AgentWorkspaceManifest.HEADER_PREFIX))
        assertFalse(content, content.contains(AgentWorkspaceManifest.ITEM_PREFIX))
    }

    private companion object {
        const val ITEM_PREFIX = AgentWorkspaceManifest.ITEM_PREFIX

        /** 拓扑已由 [AgentWorkspaceManifest.render] 渲染成文本，注入侧只负责摆放，不再解析。 */
        val TREE = """
            工作区目录拓扑（根为 /workspace，共 3 个目录，括号内为该目录直接子文件数）：
            [tree] . (2)
            [tree]   app/ (3)
            [tree]     src/ (1)
        """.trimIndent()
    }
}
