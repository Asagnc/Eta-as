package io.github.asagnc.eta.agent.model

import java.net.ProtocolException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelFailureTest {
    @Test
    fun `fields that only affect optimisation can be dropped automatically`() {
        assertEquals(
            "prompt_cache_key",
            failure("UNKNOWN_FIELD", "未知请求字段：prompt_cache_key").droppableField(),
        )
        assertEquals(
            "cache_control",
            failure("UNKNOWN_FIELD", "未知请求字段：cache_control").droppableField(),
        )
        assertEquals(
            "stream_options",
            failure("INVALID_REQUEST", "Unrecognized request argument supplied: stream_options").droppableField(),
        )
        assertEquals(
            "tool_choice",
            failure("MODEL_TOOL_CHOICE_NOT_SUPPORTED", "模型不支持 tool_choice").droppableField(),
        )
        assertEquals(
            "tool_choice",
            failure("UNSUPPORTED_PARAMETER", "Unsupported parameter: tool_choice").droppableField(),
        )
    }

    @Test
    fun `fields that change model behaviour are never dropped automatically`() {
        assertNull(failure("UNKNOWN_FIELD", "未知请求字段：thinking").droppableField())
        assertNull(failure("UNKNOWN_FIELD", "未知请求字段：messages").droppableField())
        assertNull(failure("UNKNOWN_FIELD", "未知请求字段：tools").droppableField())
        assertNull(failure("MODEL_NOT_AVAILABLE", "模型不可用：deepseek-flash").droppableField())
        assertNull(failure("INVALID_REQUEST", "参数无效").droppableField())
        assertNull(failure("RATE_LIMITED", "请求过于频繁").droppableField())
    }

    @Test
    fun `rejected thinking replay is recoverable by dropping thinking blocks`() {
        assertTrue(
            failure(
                "THINKING_SIGNATURE_INVALID",
                "***.***.content.0: Invalid `signature` in `thinking` block",
            ).rejectsThinkingReplay()
        )
        assertTrue(
            failure(
                "INVALID_REQUEST",
                "messages.2.content.0: `thinking` or `redacted_thinking` blocks in the latest " +
                    "assistant message cannot be modified. Looks like you removed 1 redacted_thinking block.",
            ).rejectsThinkingReplay()
        )
        // 缺思考块不是签名/改写问题：剥掉思考块只会让情况更糟，不能自动恢复。
        assertFalse(
            failure(
                "INVALID_REQUEST",
                "messages.1.content.0.type: Expected `thinking`, but found `text`. " +
                    "When `thinking` is enabled, a final `assistant` message must start with a thinking block",
            ).rejectsThinkingReplay()
        )
        assertFalse(failure("UNKNOWN_FIELD", "未知请求字段：thinking").rejectsThinkingReplay())
        assertFalse(failure("RATE_LIMITED", "请求过于频繁").rejectsThinkingReplay())
    }

    @Test
    fun `closed tls connection is treated as a retryable network interruption`() {
        val failure = AgentModelFailure.transport(SSLException("connection closed"))
        assertEquals("MODEL_CONNECTION_FAILED", failure?.code)
        assertEquals(true, failure?.retryable)
    }

    @Test
    fun `handshake and protocol failures stay non retryable`() {
        assertNull(AgentModelFailure.transport(SSLHandshakeException("PKIX path building failed")))
        assertNull(AgentModelFailure.transport(ProtocolException("unexpected end of stream")))
    }

    private fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
}
