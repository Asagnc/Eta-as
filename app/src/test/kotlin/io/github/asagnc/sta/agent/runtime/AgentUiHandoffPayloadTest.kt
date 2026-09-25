package io.github.asagnc.sta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * handoff 载荷的解析契约：这些字符串落在 DB 列里，历史数据必须一直能读回来。
 * 这里锁的是「老形态仍然可读」，不包含跨版本字段保留——项目未发布、单用户、整库重建，
 * 不需要为「旧代码读新载荷」付出复杂度。
 */
class AgentUiHandoffPayloadTest {
    @Test
    fun readsCurrentShape() {
        val raw = JSONObject()
            .put("type", "agent_ui_handoff")
            .put("version", 2)
            .put("conversationId", "c1")
            .put(
                "supplements",
                JSONArray().put(
                    JSONObject().put("index", 1).put("text", "补充").put("createdAt", 5L)
                )
            )
            .toString()

        val parsed = AgentUiHandoffPayload.from(raw)

        assertEquals("c1", parsed.conversationId)
        assertEquals(1, parsed.supplements.size)
        assertEquals(1, JSONObject(parsed.toJson()).optJSONArray("supplements")?.length())
    }

    @Test
    fun payloadWithoutVersionIsStillReadable() {
        val raw = """{"type":"agent_ui_handoff","conversationId":"c3"}"""

        assertEquals("c3", AgentUiHandoffPayload.from(raw).conversationId)
    }

    @Test
    fun plainConversationIdIsAcceptedAsTheOldestShape() {
        assertEquals("c4", AgentUiHandoffPayload.from("c4").conversationId)
    }

    @Test
    fun foreignTypeFallsBackToTreatingRawAsConversationId() {
        val raw = """{"type":"something_else","conversationId":"c5"}"""

        assertEquals(raw, AgentUiHandoffPayload.from(raw).conversationId)
        assertTrue(AgentUiHandoffPayload.from(raw).supplements.isEmpty())
    }
}
