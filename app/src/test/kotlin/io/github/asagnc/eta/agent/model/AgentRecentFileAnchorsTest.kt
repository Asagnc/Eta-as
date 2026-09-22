package io.github.asagnc.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRecentFileAnchorsTest {
    private fun assistantWithCalls(vararg calls: Pair<String, Any?>): JSONObject {
        val toolCalls = JSONArray()
        calls.forEachIndexed { index, (name, arguments) ->
            toolCalls.put(
                JSONObject()
                    .put("id", "call-$index")
                    .put(
                        "function",
                        JSONObject().put("name", name).put("arguments", arguments),
                    ),
            )
        }
        return JSONObject().put("role", "assistant").put("tool_calls", toolCalls)
    }

    @Test
    fun `collects the most recent paths first and de-duplicates`() {
        val messages = JSONArray()
            .put(assistantWithCalls("read_file" to """{"path":"/a/one.kt"}"""))
            .put(assistantWithCalls("edit_file" to """{"path":"/a/two.kt"}"""))
            .put(assistantWithCalls("read_file" to """{"path":"/a/one.kt"}"""))

        assertEquals(listOf("/a/one.kt", "/a/two.kt"), AgentRecentFileAnchors.collect(messages))
    }

    @Test
    fun `honours the anchor limit`() {
        val messages = JSONArray()
        for (index in 0 until 8) {
            messages.put(assistantWithCalls("read_file" to """{"path":"/f/$index.kt"}"""))
        }

        assertEquals(5, AgentRecentFileAnchors.collect(messages).size)
    }

    @Test
    fun `accepts object-shaped arguments and ignores other tools`() {
        val messages = JSONArray()
            .put(assistantWithCalls("device_status" to """{"x":1}"""))
            .put(assistantWithCalls("search_code" to JSONObject().put("pattern", "x").put("path", "/src")))

        assertEquals(listOf("/src"), AgentRecentFileAnchors.collect(messages))
    }

    @Test
    fun `skips malformed arguments and blank paths`() {
        val messages = JSONArray()
            .put(assistantWithCalls("read_file" to "not json"))
            .put(assistantWithCalls("read_file" to """{"path":"  "}"""))

        assertTrue(AgentRecentFileAnchors.collect(messages).isEmpty())
    }

    @Test
    fun `renders an anchor block only when there is something to anchor`() {
        assertEquals("", AgentRecentFileAnchors.render(emptyList()))

        val rendered = AgentRecentFileAnchors.render(listOf("/a/one.kt", "/a/two.kt"))
        assertTrue(rendered.contains("/a/one.kt、/a/two.kt"))
        assertTrue(rendered.startsWith("\n\n["))
    }
}
