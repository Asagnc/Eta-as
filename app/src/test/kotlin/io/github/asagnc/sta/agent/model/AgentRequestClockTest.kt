package io.github.asagnc.sta.agent.model

import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestClockTest {

    private val now: ZonedDateTime =
        ZonedDateTime.of(2026, 9, 18, 2, 45, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun `clock line carries time, zone and weekday`() {
        val line = AgentRequestClock.line(now)
        assertTrue(line.startsWith(AgentRequestClock.PREFIX))
        assertTrue(line.contains("2026-09-18 02:45"))
        assertTrue(line.contains("Asia/Shanghai"))
        assertTrue(line.contains("星期五"))
    }

    @Test
    fun `attach writes to the last message and leaves history untouched`() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "sys"))
            .put(JSONObject().put("role", "user").put("content", "第一问"))
            .put(JSONObject().put("role", "tool").put("content", "工具结果"))

        AgentRequestClock.attach(messages, now)
        AgentRequestClock.attach(messages, now.plusMinutes(1))

        assertEquals("第一问", messages.getJSONObject(1).getString("content"))
        val last = messages.getJSONObject(2).getString("content")
        assertTrue(last.contains("2026-09-18 02:46"))
        assertTrue(last.endsWith("工具结果"))
        assertEquals(1, last.lines().count { it.startsWith(AgentRequestClock.PREFIX) })
    }

    @Test
    fun `attach keeps multimodal content order`() {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", "看图"))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", parts))

        AgentRequestClock.attach(messages, now)

        val content = messages.getJSONObject(0).getJSONArray("content")
        assertTrue(content.getJSONObject(0).getString("text").startsWith(AgentRequestClock.PREFIX))
        assertEquals("看图", content.getJSONObject(1).getString("text"))
    }

    @Test
    fun `attach is a no-op for an empty request`() {
        val messages = JSONArray()
        AgentRequestClock.attach(messages, now)
        assertEquals(0, messages.length())
    }
}
