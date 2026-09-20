package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderReasoningTest {
    @Test
    fun mandatoryModelRejectsOffInsteadOfSilentlyDisablingReasoning() {
        val config = config(
            source = ProviderSourceTypes.MOONSHOT,
            model = "kimi-k3",
            effort = ReasoningEffort.OFF,
        ).copy(
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.HIGH),
                mandatory = true,
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyOpenAiCompatibleRequest(JSONObject(), config)
        }
    }

    @Test
    fun defaultDoesNotOverrideAdvancedRequestBody() {
        val request = JSONObject()
            .put("reasoning_effort", "medium")
            .put("thinking_budget", 1234)

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.DEFAULT),
        )

        assertEquals("medium", request.getString("reasoning_effort"))
        assertEquals(1234, request.getInt("thinking_budget"))
    }

    @Test
    fun openAiNamedEffortOverridesAdvancedValue() {
        val request = JSONObject().put("reasoning_effort", "low")

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.XHIGH),
        )

        assertEquals("xhigh", request.getString("reasoning_effort"))
    }

    @Test
    fun openAiMinimalUsesDocumentedNamedValue() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.MINIMAL),
        )

        assertEquals("minimal", request.getString("reasoning_effort"))
    }

    @Test
    fun openAiRejectsUnsupportedMaxEffort() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyOpenAiCompatibleRequest(
                JSONObject(),
                config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.MAX),
            )
        }
    }

    @Test
    fun openAiOffUsesDocumentedNoneValue() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENAI, effort = ReasoningEffort.OFF),
        )

        assertEquals("none", request.getString("reasoning_effort"))
    }

    @Test
    fun anthropicMaxUsesAdaptiveThinkingAndLargeOutputLimit() {
        val request = JSONObject()
            .put("max_tokens", 4096)
            .put("output_config", JSONObject().put("other", true))

        ProviderReasoning.applyAnthropicRequest(
            request,
            config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.MAX),
        )

        assertEquals("adaptive", request.getJSONObject("thinking").getString("type"))
        assertEquals("max", request.getJSONObject("output_config").getString("effort"))
        assertTrue(request.getJSONObject("output_config").getBoolean("other"))
        assertEquals(65_536, request.getInt("max_tokens"))
    }

    @Test
    fun anthropicOffUsesDisabledThinkingAndRemovesOnlyEffortOverride() {
        val request = JSONObject()
            .put("thinking", JSONObject().put("type", "adaptive"))
            .put("output_config", JSONObject().put("effort", "high").put("other", true))

        ProviderReasoning.applyAnthropicRequest(
            request,
            config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.OFF),
        )

        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.getJSONObject("output_config").has("effort"))
        assertTrue(request.getJSONObject("output_config").getBoolean("other"))
    }

    @Test
    fun anthropicRejectsUnsupportedMinimalEffort() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderReasoning.applyAnthropicRequest(
                JSONObject(),
                config(source = ProviderSourceTypes.ANTHROPIC, effort = ReasoningEffort.MINIMAL),
            )
        }
    }

    @Test
    fun deepSeekWritesThinkingTypeAndExactEffort() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.DEEPSEEK, effort = ReasoningEffort.MAX),
        )

        assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("max", request.getString("reasoning_effort"))
    }

    @Test
    fun kimiK3UsesTopLevelEffortWithoutThinkingObject() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.MOONSHOT,
                model = "kimi-k3",
                effort = ReasoningEffort.HIGH,
            ),
        )

        assertEquals("high", request.getString("reasoning_effort"))
        assertFalse(request.has("thinking"))
    }

    @Test
    fun qwenBudgetReservesAnswerTokensBelowCompletionLimit() {
        val request = JSONObject().put("max_completion_tokens", 20_000)

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.BAILIAN,
                model = "qwen3.7-plus",
                effort = ReasoningEffort.HIGH,
            ),
        )

        assertTrue(request.getBoolean("enable_thinking"))
        assertEquals(17_500, request.getInt("thinking_budget"))
    }

    @Test
    fun siliconFlowMapsEveryNamedLevelToBudget() {
        val budgets = mapOf(
            ReasoningEffort.MINIMAL to 128,
            ReasoningEffort.LOW to 1_024,
            ReasoningEffort.MEDIUM to 4_096,
            ReasoningEffort.HIGH to 8_192,
            ReasoningEffort.XHIGH to 16_384,
            ReasoningEffort.MAX to 32_768,
        )

        budgets.forEach { (effort, budget) ->
            val request = JSONObject()
            ProviderReasoning.applyOpenAiCompatibleRequest(
                request,
                config(source = ProviderSourceTypes.SILICONFLOW, effort = effort),
            )
            assertEquals(budget, request.getInt("thinking_budget"))
        }
    }

    @Test
    fun mimoOffUsesDocumentedDisabledThinkingType() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.MIMO, effort = ReasoningEffort.OFF),
        )

        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun openRouterUsesNestedReasoningEffort() {
        val request = JSONObject().put("reasoning", JSONObject().put("exclude", true))

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.OPENROUTER, effort = ReasoningEffort.LOW),
        )

        assertEquals("low", request.getJSONObject("reasoning").getString("effort"))
        assertTrue(request.getJSONObject("reasoning").getBoolean("exclude"))
    }

    @Test
    fun stepFunUsesOnlyVerifiedNamedLevels() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(
                source = ProviderSourceTypes.STEPFUN,
                model = "step-3.5-flash-2603",
                effort = ReasoningEffort.LOW,
            ),
        )

        assertEquals("low", request.getString("reasoning_effort"))
    }

    @Test
    fun defaultOnlyProvidersDoNotInventRequestFields() {
        listOf(ProviderSourceTypes.MINIMAX, ProviderSourceTypes.CUSTOM).forEach { source ->
            val request = JSONObject().put("metadata", "kept")

            ProviderReasoning.applyOpenAiCompatibleRequest(
                request,
                config(source = source, effort = ReasoningEffort.DEFAULT),
            )

            assertEquals(setOf("metadata"), request.keys().asSequence().toSet())
        }
    }

    @Test
    fun autoEffortFollowsPurposeAndTurn() {
        val capabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
            ),
            canDisable = true,
        )
        val firstTurn = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "约束"))
            .put(JSONObject().put("role", "user").put("content", "帮我查日志"))
        val laterTurn = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "帮我查日志"))
            .put(JSONObject().put("role", "assistant").put("content", "好的"))
            .put(JSONObject().put("role", "tool").put("content", "结果"))

        val firstTurnRequest = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(
            firstTurnRequest,
            autoConfig(capabilities),
            ProviderRequestPurpose.CHAT,
            firstTurn,
        )
        assertEquals("high", firstTurnRequest.getString("reasoning_effort"))

        val laterTurnRequest = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(
            laterTurnRequest,
            autoConfig(capabilities),
            ProviderRequestPurpose.CHAT,
            laterTurn,
        )
        // 工具回填轮不再思考：这一档在 OpenAI 源上就是 reasoning_effort=none
        assertEquals("none", laterTurnRequest.getString("reasoning_effort"))

        val compactionRequest = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(
            compactionRequest,
            autoConfig(capabilities),
            ProviderRequestPurpose.COMPACTION,
            laterTurn,
        )
        assertEquals("low", compactionRequest.getString("reasoning_effort"))
    }

    @Test
    fun autoEffortIsNormalizedToModelCapabilitiesInsteadOfThrowing() {
        val request = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            autoConfig(ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW))),
            ProviderRequestPurpose.CHAT,
            JSONArray().put(JSONObject().put("role", "user").put("content", "你好")),
        )

        assertEquals("low", request.getString("reasoning_effort"))
    }

    @Test
    fun autoEffortStaysInsidePortableTiersWhenCapabilitiesAreUnknown() {
        val request = JSONObject()
        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.DEEPSEEK, effort = ReasoningEffort.AUTO)
                .copy(model = "deepseek-reasoner", reasoningCapabilities = null),
            ProviderRequestPurpose.CHAT,
            JSONArray()
                .put(JSONObject().put("role", "user").put("content", "继续"))
                .put(JSONObject().put("role", "assistant").put("content", "好的")),
        )

        // 模型元数据缺失（多数中转站如此）时只产出 Low / High：low/high 是公共子集，
        // 不会给 K3、StepFun 这类只认 low/high 的供应商发 Medium
        assertEquals("high", request.getString("reasoning_effort"))
    }

    @Test
    fun customOffSendsToggleFieldsBecauseNamedEffortIsIgnored() {
        val request = JSONObject().put("reasoning_effort", "high").put("thinking_budget", 4096)

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.CUSTOM, effort = ReasoningEffort.OFF),
        )

        // reasoning_effort=none 会被中转站忽略（实测 tokenrhythm 的 deepseek-flash 照样思考），
        // 所以关思考必须发开关型字段，并清掉与开关冲突的旧字段。
        assertFalse(request.getBoolean("enable_thinking"))
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.has("reasoning_effort"))
        assertFalse(request.has("thinking_budget"))
    }

    @Test
    fun customNamedEffortKeepsUsingReasoningEffort() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.CUSTOM, effort = ReasoningEffort.HIGH),
        )

        assertEquals("high", request.getString("reasoning_effort"))
        assertFalse(request.has("enable_thinking"))
        assertFalse(request.has("thinking"))
    }

    @Test
    fun autoEffortTurnsThinkingOffOnToolRoundsForCustomRelays() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.CUSTOM, effort = ReasoningEffort.AUTO),
            ProviderRequestPurpose.CHAT,
            JSONArray()
                .put(JSONObject().put("role", "user").put("content", "继续"))
                .put(JSONObject().put("role", "assistant").put("content", "好的"))
                .put(JSONObject().put("role", "tool").put("content", "{\"ok\":true}")),
        )

        // 中转站认不了 reasoning_effort=none，自动档的工具轮必须落到开关字段上才算真的关掉思考
        assertFalse(request.getBoolean("enable_thinking"))
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.has("reasoning_effort"))
    }

    @Test
    fun autoEffortEscalatesBackToHighAfterToolFailure() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            config(source = ProviderSourceTypes.CUSTOM, effort = ReasoningEffort.AUTO),
            ProviderRequestPurpose.CHAT,
            JSONArray()
                .put(JSONObject().put("role", "user").put("content", "继续"))
                .put(JSONObject().put("role", "assistant").put("content", "我查一下"))
                .put(
                    JSONObject().put("role", "tool")
                        .put("content", "{\"ok\":false,\"code\":\"PATH_NOT_FOUND\"}"),
                ),
        )

        // 工具失败是"这题难"的唯一硬证据：这一轮升回高档
        assertEquals("high", request.getString("reasoning_effort"))
        assertFalse(request.has("enable_thinking"))
    }

    @Test
    fun autoEffortStaysAtProviderDefaultWhenModelCannotDisableThinking() {
        val request = JSONObject()

        ProviderReasoning.applyOpenAiCompatibleRequest(
            request,
            autoConfig(
                ModelReasoningCapabilities(
                    supportedEfforts = listOf(
                        ReasoningEffort.LOW,
                        ReasoningEffort.MEDIUM,
                        ReasoningEffort.HIGH,
                    ),
                    mandatory = true,
                ),
            ),
            ProviderRequestPurpose.CHAT,
            JSONArray()
                .put(JSONObject().put("role", "user").put("content", "继续"))
                .put(JSONObject().put("role", "assistant").put("content", "好的"))
                .put(JSONObject().put("role", "tool").put("content", "{\"ok\":true}")),
        )

        // 能力里没有 OFF（mandatory）时归一到 DEFAULT：一个字段都不发，绝不硬塞关思考的开关
        assertFalse(request.has("reasoning_effort"))
        assertFalse(request.has("enable_thinking"))
        assertFalse(request.has("thinking"))
    }

    private fun autoConfig(capabilities: ModelReasoningCapabilities) = config(
        source = ProviderSourceTypes.OPENAI,
        effort = ReasoningEffort.AUTO,
    ).copy(reasoningCapabilities = capabilities)

    private fun config(
        source: String,
        effort: ReasoningEffort,
        model: String = "test-model",
    ) = AgentModelClient.ModelConfig(
        providerSourceType = source,
        baseUrl = "https://example.com/v1",
        apiKey = "key",
        model = model,
        systemPrompt = "system",
        thinkingEnabled = effort.enablesReasoning,
        reasoningEffort = effort,
        reasoningCapabilities = ModelReasoningCapabilities(
            supportedEfforts = ReasoningEffort.entries.filter {
                it != ReasoningEffort.OFF && it != ReasoningEffort.DEFAULT
            },
            canDisable = true,
        ),
    )
}
