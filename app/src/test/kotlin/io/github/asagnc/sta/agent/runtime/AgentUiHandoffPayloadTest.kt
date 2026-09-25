package io.github.asagnc.sta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 载荷的跨版本契约：这些字符串落在 DB 列与外部归档里，会被更老的代码读回来，也会被
 * 读出来改完再写回去（见 AgentContinuationBuilder）。契约一旦破掉，表现是「用户升级跑过一次、
 * 降级回去会话内容就少了」，而且不会有任何报错。
 */
class AgentUiHandoffPayloadTest {
    @Test
    fun unknownFieldsFromNewerVersionSurviveARewrite() {
        val raw = JSONObject()
            .put("type", "agent_ui_handoff")
            .put("version", 99)
            .put("conversationId", "c1")
            .put(
                "supplements",
                JSONArray().put(
                    JSONObject().put("index", 1).put("text", "补充").put("createdAt", 5L)
                )
            )
            .put("futureField", "别弄丢我")
            .toString()

        val parsed = AgentUiHandoffPayload.from(raw)

        assertEquals("c1", parsed.conversationId)
        assertEquals(1, parsed.supplements.size)
        // 改一下再写回去，是新版本才认识的字段最容易丢的时刻。
        val rewritten = JSONObject(parsed.copy(conversationId = "c2").toJson())
        assertEquals("c2", rewritten.optString("conversationId"))
        assertEquals("别弄丢我", rewritten.optString("futureField"))
        assertEquals(1, rewritten.optJSONArray("supplements")?.length())
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
