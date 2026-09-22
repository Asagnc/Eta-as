package io.github.asagnc.eta.agent.model

import io.github.asagnc.eta.agent.runtime.AgentEvent
import io.github.asagnc.eta.agent.runtime.AgentRunCancelledException
import io.github.asagnc.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class AgentModelRetryTest {
    @Test
    fun retriesAreBoundedAndBackoffIsPerModelRound() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw SocketTimeoutException("timeout") })
        }
        assertEquals(3, calls)
        assertEquals(listOf(2_000L, 4_000L), delays)
        assertTrue(failure.message.orEmpty().contains("已重试 2 次"))
        delays.clear()
        calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw IOException("connection reset")
            response()
        })
        assertEquals(2, result.round)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherRequest() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            complete(
                AgentModelRetry { control, _ -> control.cancel() },
                provider { _, _ -> calls++; throw SocketTimeoutException() },
                controller,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailuresAndHostedToolFailuresDoNotReplayProvider() {
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val callbackFailure = IOException("checkpoint write failed")
        val thrown = assertThrows(IOException::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.RequestStarted)
                response()
            }, onProviderEvent = { _, _ -> throw callbackFailure })
        }
        assertSame(callbackFailure, thrown)
        assertThrows(AgentModelFailure::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.HostedToolStarted("search-1", "web_search"))
                throw SocketTimeoutException()
            })
        }
    }

    @Test
    fun retriesUnexplainedEmptyCompletionAndStopsOnExplainedEmptyStates() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) emptyResponse("stop") else response()
        })
        assertEquals(2, result.round)
        assertEquals(listOf(2_000L), delays)

        var alwaysEmptyCalls = 0
        val exhausted = assertThrows(AgentModelFailure::class.java) {
            complete(AgentModelRetry { _, _ -> }, provider { _, _ ->
                alwaysEmptyCalls++
                emptyResponse("stop")
            })
        }
        assertEquals(3, alwaysEmptyCalls)
        assertTrue(exhausted.message.orEmpty().contains("已重试 2 次"))
        assertTrue(exhausted.message.orEmpty().contains("finish_reason=stop"))

        // 有明确原因的空态不走空响应重试：内容过滤交给上层的提示，长度截断走输出上限提升。
        var filteredCalls = 0
        complete(AgentModelRetry { _, _ -> fail("内容过滤的空响应不该重试") }, provider { _, _ ->
            filteredCalls++
            emptyResponse("content_filter")
        })
        assertEquals(1, filteredCalls)
    }

    @Test
    fun raisesOutputBudgetForTheRoundAfterATruncatedOne() {
        val requestedLimits = mutableListOf<Int?>()
        val retry = AgentModelRetry { _, _ -> fail("输出上限提升不该走退避等待") }
        val provider = provider { request, _ ->
            requestedLimits += request.config.maxOutputTokens
            if (requestedLimits.size == 1) emptyResponse("length") else response()
        }
        complete(retry, provider)
        val second = complete(retry, provider)
        assertEquals(listOf(null, 32_768), requestedLimits)
        assertEquals("完成", second.response.assistantMessage.getString("content"))
    }

    @Test
    fun raisesOutputBudgetOncePerTruncatedRoundUntilHigherTiersRunOut() {
        val requestedLimits = mutableListOf<Int?>()
        val retry = AgentModelRetry { _, _ -> fail("输出上限提升不该走退避等待") }
        val provider = provider { request, _ ->
            requestedLimits += request.config.maxOutputTokens
            emptyResponse("length")
        }
        repeat(4) { complete(retry, provider) }
        assertEquals(listOf(null, 32_768, 131_072, 131_072), requestedLimits)
    }

    @Test
    fun retriesOnceWithoutReplayedThinkingBlocksWhenUpstreamRejectsSignatures() {
        val events = mutableListOf<AgentEvent>()
        val observed = mutableListOf<Boolean>()
        var calls = 0
        val result = complete(
            retry = AgentModelRetry { _, _ -> fail("剥掉思考块的重试不该退避等待") },
            provider = provider { request, _ ->
                observed += request.dropThinkingBlocks
                if (calls++ == 0) throw signatureRejection()
                response()
            },
            onEvent = events::add,
        )

        assertEquals(listOf(false, true), observed)
        assertEquals(2, result.round)
        assertEquals("完成", result.response.assistantMessage.getString("content"))
        assertEquals(1, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().size)

        // 剥掉思考块后仍被拒收：不再循环重试，直接把上游错误交给上层。
        var repeated = 0
        assertThrows(AgentModelFailure::class.java) {
            complete(
                retry = AgentModelRetry { _, _ -> fail("剥掉思考块的重试不该退避等待") },
                provider = provider { _, _ ->
                    repeated++
                    throw signatureRejection()
                },
            )
        }
        assertEquals(2, repeated)
    }

    @Test
    fun classifiesTransientFailuresWithoutRetryingPermanentFailures() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 529)) {
            assertTrue(AgentModelFailure.http(status, "").retryable)
        }
        for (status in listOf(400, 401, 403, 404)) {
            assertFalse(AgentModelFailure.http(status, "").retryable)
        }
        assertFalse(AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").retryable)
        assertTrue(AgentModelFailure.http(400, """{"code":"model_not_available"}""").retryable)
        assertTrue(AgentModelFailure.http(503, """{"error":{"code":"server_error"}}""").retryable)
        assertFalse(AgentModelFailure.http(400, """{"code":"unknown_field"}""").retryable)
        assertNull(AgentModelFailure.transport(SSLHandshakeException("certificate")))
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "过载").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "authentication_error"), "认证失败").retryable)
        assertFalse(AgentModelFailure.http(503, "secret request text").message.orEmpty().contains("secret"))
    }

    @Test
    fun readsFailureDetailAndCodeFromTopLevelErrorBody() {
        val failure = AgentModelFailure.http(
            400,
            """{"code":"MODEL_NOT_AVAILABLE","message":"模型不可用：deepseek-flash"}""",
        )
        assertEquals("HTTP_400", failure.code)
        assertTrue(failure.message.orEmpty().contains("模型不可用：deepseek-flash"))
        assertEquals(
            "CONTEXT_OVERFLOW",
            AgentModelFailure.http(400, """{"code":"input_too_long"}""").code,
        )
        assertTrue(
            AgentModelFailure.http(503, """{"code":"provider_unavailable"}""")
                .message.orEmpty().contains("provider_unavailable")
        )
    }

    private fun complete(
        retry: AgentModelRetry,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        onProviderEvent: (Int, ProviderEvent) -> Unit = { _, _ -> },
        onEvent: (AgentEvent) -> Unit = {},
    ) = retry.complete(
        initialRound = 1,
        request = ProviderRequest(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            JSONArray(), JSONArray(),
        ),
        provider = provider,
        controller = controller,
        onEvent = onEvent,
        onProviderEvent = onProviderEvent,
        discardAttemptReasoning = {},
    )

    private fun signatureRejection() = AgentModelFailure.http(
        400,
        """{"message":"***.***.content.0: Invalid `signature` in `thinking` block","reason":"THINKING_SIGNATURE_INVALID"}""",
    )

    private fun emptyResponse(reason: String) = ProviderResponse(
        JSONObject().put("content", "").put("finish_reason", reason),
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ) = action(request, onEvent)
        }
}
