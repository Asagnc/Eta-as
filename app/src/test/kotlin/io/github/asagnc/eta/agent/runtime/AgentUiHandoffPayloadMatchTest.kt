package io.github.asagnc.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话归属判断只依赖 handoff payload 的解析结果，这里不经过 Robolectric 与数据库，
 * 便于在任意本地环境直接执行。
 */
class AgentUiHandoffPayloadMatchTest {
    @Test
    fun jsonPayloadYieldsItsConversationId() {
        val payload = AgentUiHandoffPayload(conversationId = "conv-a").toJson()
        assertEquals("conv-a", AgentUiHandoffPayload.from(payload).conversationId)
    }

    @Test
    fun legacyPlainTextPayloadIsItsOwnConversationId() {
        assertEquals("conv-a", AgentUiHandoffPayload.from("conv-a").conversationId)
    }

    @Test
    fun unparsablePayloadNeverEqualsARealConversationId() {
        assertEquals("", AgentUiHandoffPayload.from("").conversationId)
        assertEquals("{\"broken", AgentUiHandoffPayload.from("{\"broken").conversationId)
    }
}
