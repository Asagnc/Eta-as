package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.data.model.ModelReasoningCapabilities
import io.github.asagnc.sta.data.model.ProviderSourceTypes
import io.github.asagnc.sta.data.model.ReasoningEffort
import io.github.asagnc.sta.data.provider.ProviderSourceRegistry
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal object ProviderReasoning {
    private const val ANTHROPIC_LARGE_MAX_TOKENS = 65_536

    /** 自动档判断"上一轮工具失败"时，只看尾部这么多条工具结果。 */
    private const val AUTO_FAILURE_SCAN_MESSAGES = 6

    fun applyOpenAiCompatibleRequest(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
        messages: JSONArray = JSONArray(),
    ) {
        val effort = validatedEffort(config, purpose, messages)
        val sourceType = sourceType(config)
        if (
            config.reasoningCapabilities == null &&
            !isLegacyReasoningModel(sourceType, config.model)
        ) {
            return
        }
        if (effort == ReasoningEffort.DEFAULT) {
            applyProviderDefault(request, config, sourceType)
            return
        }
        when (sourceType) {
            ProviderSourceTypes.BAILIAN -> applyBailian(request, config, effort)
            ProviderSourceTypes.SILICONFLOW -> applySiliconFlow(request, config, effort)
            ProviderSourceTypes.DEEPSEEK -> applyDeepSeek(request, effort)
            ProviderSourceTypes.MOONSHOT -> applyMoonshot(request, config, effort)
            ProviderSourceTypes.MIMO -> applyToggleOnlyProvider(request, "MiMo", effort)
            ProviderSourceTypes.MINIMAX -> unsupportedEffort("MiniMax", effort)
            ProviderSourceTypes.OPENROUTER -> applyOpenRouter(request, effort)
            ProviderSourceTypes.STEPFUN -> applyStepFun(request, effort)
            ProviderSourceTypes.OPENAI -> applyOpenAi(request, effort)
            ProviderSourceTypes.CUSTOM -> applyCustomReasoning(request, effort)
        }
    }

    fun applyResponsesRequest(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
        messages: JSONArray = JSONArray(),
    ) {
        val auto = config.effectiveReasoningEffort == ReasoningEffort.AUTO
        if (config.reasoningCapabilities == null && !auto) return
        val effort = validatedEffort(config, purpose, messages)
        if (sourceType(config) == ProviderSourceTypes.OPENAI && effort == ReasoningEffort.MAX) {
            unsupportedEffort("OpenAI", effort)
        }
        request.put(
            "reasoning",
            JSONObject().apply {
                if (effort == ReasoningEffort.OFF) {
                    put("effort", "none")
                } else {
                    put("summary", "auto")
                    if (effort != ReasoningEffort.DEFAULT) put("effort", effort.wireValue)
                }
            },
        )
    }

    private fun applyProviderDefault(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        sourceType: String,
    ) {
        if (request.has("thinking")) return
        val model = config.model.trim().lowercase()
        if (
            (sourceType == ProviderSourceTypes.MOONSHOT || sourceType == ProviderSourceTypes.BAILIAN) &&
            model.startsWith("kimi-k2.6")
        ) {
            request.put(
                "thinking",
                JSONObject()
                    .put("type", "enabled")
                    .put("keep", "all")
            )
        }
    }

    fun applyAnthropicRequest(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
        messages: JSONArray = JSONArray(),
    ) {
        val effort = validatedEffort(config, purpose, messages)
        if (effort == ReasoningEffort.DEFAULT) return
        if (effort == ReasoningEffort.OFF) {
            request.put("thinking", JSONObject().put("type", "disabled"))
            request.optJSONObject("output_config")?.let { outputConfig ->
                outputConfig.remove("effort")
                if (outputConfig.length() == 0) request.remove("output_config")
            }
            return
        }
        require(effort != ReasoningEffort.MINIMAL) {
            "Anthropic 不支持 Minimal thinking effort"
        }
        request.put(
            "thinking",
            JSONObject()
                .put("type", "adaptive")
                .put("display", "summarized")
        )
        val outputConfig = request.optJSONObject("output_config") ?: JSONObject()
        outputConfig.put("effort", effort.wireValue)
        request.put("output_config", outputConfig)
        if (effort == ReasoningEffort.XHIGH || effort == ReasoningEffort.MAX) {
            request.put("max_tokens", max(request.optInt("max_tokens", 0), ANTHROPIC_LARGE_MAX_TOKENS))
        }
    }

    private fun applyBailian(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort,
    ) {
        val model = config.model.trim().lowercase()
        when {
            model.startsWith("qwen3.7-") -> applyQwenBudget(request, config, effort)
            model.startsWith("qwen3.8-") -> applyNamedReasoningEffort(request, effort)
            "deepseek" in model || model.startsWith("kimi-k3") ->
                applyNamedReasoningEffort(request, effort)
            model.startsWith("kimi-k2.6") || model.startsWith("kimi-k2.5") ->
                applyToggleOnlyProvider(
                    request = request,
                    providerName = "百炼 Kimi",
                    effort = effort,
                    keepAll = model.startsWith("kimi-k2.6"),
                )
            else -> applyNamedReasoningEffort(request, effort)
        }
    }

    private fun applyQwenBudget(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort,
    ) {
        if (effort == ReasoningEffort.OFF) {
            request.put("enable_thinking", false)
            request.remove("thinking_budget")
            return
        }
        val requestedBudget = when (effort) {
            ReasoningEffort.MINIMAL -> unsupportedEffort("百炼 Qwen", effort)
            ReasoningEffort.LOW -> 4_096
            ReasoningEffort.MEDIUM -> 16_384
            ReasoningEffort.HIGH -> 32_768
            ReasoningEffort.XHIGH -> 65_536
            ReasoningEffort.MAX -> config.reasoningCapabilities?.maxBudgetTokens ?: 65_536
            ReasoningEffort.OFF,
            ReasoningEffort.DEFAULT,
            ReasoningEffort.AUTO -> return
        }
        request.put("enable_thinking", true)
        request.put("thinking_budget", clampBudgetToCompletionLimit(request, requestedBudget))
    }

    private fun clampBudgetToCompletionLimit(request: JSONObject, requestedBudget: Int): Int {
        if (!request.has("max_completion_tokens") || request.isNull("max_completion_tokens")) {
            return requestedBudget
        }
        val completionLimit = request.optInt("max_completion_tokens", requestedBudget)
        if (completionLimit <= 0) return requestedBudget
        val answerReserve = max(2_048, completionLimit / 8)
        return requestedBudget.coerceAtMost((completionLimit - answerReserve).coerceAtLeast(1))
    }

    private fun applySiliconFlow(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort,
    ) {
        if (effort == ReasoningEffort.OFF) {
            request.put("enable_thinking", false)
            request.remove("thinking_budget")
            return
        }
        val budget = when (effort) {
            ReasoningEffort.MINIMAL -> 128
            ReasoningEffort.LOW -> 1_024
            ReasoningEffort.MEDIUM -> 4_096
            ReasoningEffort.HIGH -> 8_192
            ReasoningEffort.XHIGH -> 16_384
            ReasoningEffort.MAX -> 32_768
            ReasoningEffort.OFF,
            ReasoningEffort.DEFAULT,
            ReasoningEffort.AUTO -> return
        }.coerceAtMost(config.reasoningCapabilities?.maxBudgetTokens ?: 32_768)
        if (config.reasoningCapabilities?.canDisable == true) {
            request.put("enable_thinking", true)
        }
        request.put("thinking_budget", budget)
    }

    private fun applyDeepSeek(request: JSONObject, effort: ReasoningEffort) {
        applyThinkingToggle(request, effort)
        if (effort != ReasoningEffort.OFF) {
            val providerEffort = when (effort) {
                ReasoningEffort.MINIMAL -> unsupportedEffort("DeepSeek", effort)
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH -> ReasoningEffort.HIGH
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX -> ReasoningEffort.MAX
                ReasoningEffort.DEFAULT,
                ReasoningEffort.OFF,
                ReasoningEffort.AUTO -> return
            }
            request.put("reasoning_effort", providerEffort.wireValue)
        } else {
            request.remove("reasoning_effort")
        }
    }

    private fun applyMoonshot(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort,
    ) {
        val model = config.model.trim().lowercase()
        when {
            model.startsWith("kimi-k3") -> {
                require(effort in setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX)) {
                    "Kimi K3 不支持 ${effort.displayName} thinking effort"
                }
                applyNamedReasoningEffort(request, effort)
            }
            model.startsWith("kimi-k2.7-code") -> unsupportedEffort("Kimi K2.7 Code", effort)
            model.startsWith("kimi-k2.6") || model.startsWith("kimi-k2.5") ->
                applyToggleOnlyProvider(
                    request = request,
                    providerName = "Kimi",
                    effort = effort,
                    keepAll = model.startsWith("kimi-k2.6"),
                )
            else -> applyNamedReasoningEffort(request, effort)
        }
    }

    private fun applyToggleOnlyProvider(
        request: JSONObject,
        providerName: String,
        effort: ReasoningEffort,
        keepAll: Boolean = false,
    ) {
        if (effort != ReasoningEffort.OFF) unsupportedEffort(providerName, effort)
        applyThinkingToggle(request, effort, keepAll)
    }

    private fun applyStepFun(request: JSONObject, effort: ReasoningEffort) {
        require(effort == ReasoningEffort.LOW || effort == ReasoningEffort.HIGH) {
            "StepFun 不支持 ${effort.displayName} thinking effort"
        }
        applyNamedReasoningEffort(request, effort)
    }

    private fun applyThinkingToggle(
        request: JSONObject,
        effort: ReasoningEffort,
        keepAll: Boolean = false,
    ) {
        val enabled = effort != ReasoningEffort.OFF
        val thinking = JSONObject().put("type", if (enabled) "enabled" else "disabled")
        if (enabled && keepAll) thinking.put("keep", "all")
        request.put("thinking", thinking)
    }

    private fun applyOpenRouter(request: JSONObject, effort: ReasoningEffort) {
        val reasoning = request.optJSONObject("reasoning") ?: JSONObject()
        reasoning.put("effort", if (effort == ReasoningEffort.OFF) "none" else effort.wireValue)
        request.put("reasoning", reasoning)
    }

    private fun applyOpenAi(request: JSONObject, effort: ReasoningEffort) {
        if (effort == ReasoningEffort.MAX) unsupportedEffort("OpenAI", effort)
        applyNamedReasoningEffort(request, effort)
    }

    private fun applyNamedReasoningEffort(request: JSONObject, effort: ReasoningEffort) {
        request.put("reasoning_effort", if (effort == ReasoningEffort.OFF) "none" else effort.wireValue)
    }

    /**
     * 自定义中转站没有统一的思考开关：`reasoning_effort: none` 被相当一部分中转站直接忽略
     * （实测 tokenrhythm 的 deepseek-flash 照样输出思考），所以关闭思考必须同时给出开关型字段——
     * vLLM/SGLang 系认 enable_thinking，Anthropic 风格的中转认 thinking.type。
     * 上游若点名拒收这两个字段，AgentModelRetry 会去掉它们重试，退回模型默认行为。
     */
    private fun applyCustomReasoning(request: JSONObject, effort: ReasoningEffort) {
        if (effort != ReasoningEffort.OFF) {
            applyNamedReasoningEffort(request, effort)
            return
        }
        request.put("enable_thinking", false)
        request.put("thinking", JSONObject().put("type", "disabled"))
        request.remove("reasoning_effort")
        request.remove("thinking_budget")
    }

    private fun unsupportedEffort(providerName: String, effort: ReasoningEffort): Nothing =
        throw IllegalArgumentException("$providerName 不支持 ${effort.displayName} thinking effort")

    private fun validatedEffort(
        config: AgentModelClient.ModelConfig,
        purpose: ProviderRequestPurpose,
        messages: JSONArray,
    ): ReasoningEffort {
        val requested = config.effectiveReasoningEffort
        val capabilities = config.reasoningCapabilities
        if (requested == ReasoningEffort.AUTO) {
            // 自动档解析出的档位是"这个任务该多深"，未必是当前模型支持的档，
            // 所以走归一化；手选档位仍然严格校验并保持原有的报错提示。
            val resolved = resolveAutoEffort(purpose, messages, capabilities)
            return capabilities?.normalize(resolved) ?: resolved
        }
        if (capabilities == null) return requested
        require(requested in capabilities.selectableEfforts) {
            "当前模型不支持 ${requested.displayName} thinking effort"
        }
        require(!capabilities.mandatory || requested != ReasoningEffort.OFF) {
            "当前模型强制启用思考，不能选择 Off"
        }
        return requested
    }

    /**
     * 自动档的档位规则，只依赖可观察的事实，不猜任务难度：
     * 压缩、回复重写这类辅助请求用低档；主循环首轮用高档（这一轮定方向，最值得想）；
     * 之后的工具回填轮不再思考——工具轮的输出是机械的，思考只是白烧 1~3 秒；
     * 上一轮的工具结果里出现失败时升回高档，因为那是"这题难"的唯一硬证据。
     *
     * 拿不到模型能力（多数中转站没有模型元数据）时只产出 Low / High：这是各家 effort 字段
     * 的公共子集，Kimi K3、StepFun 这类只认 low/high 的供应商不会因此报错。
     * 关思考同样只在模型能力支持时才会真的发出去：能力里没有 OFF 时
     * [ModelReasoningCapabilities.normalize] 会把 OFF 归一到 DEFAULT。
     */
    private fun resolveAutoEffort(
        purpose: ProviderRequestPurpose,
        messages: JSONArray,
        capabilities: ModelReasoningCapabilities?,
    ): ReasoningEffort = when {
        purpose != ProviderRequestPurpose.CHAT -> ReasoningEffort.LOW
        !hasAssistantTurn(messages) -> ReasoningEffort.HIGH
        hasRecentToolFailure(messages) -> ReasoningEffort.HIGH
        capabilities == null -> ReasoningEffort.HIGH
        else -> ReasoningEffort.OFF
    }

    private fun hasAssistantTurn(messages: JSONArray): Boolean {
        for (index in 0 until messages.length()) {
            val role = messages.optJSONObject(index)?.optString("role").orEmpty()
            if (role == "assistant" || role == "tool") return true
        }
        return false
    }

    /**
     * 尾部若干条工具结果里有没有失败：工具结果统一带 `ok` 字段，失败时是 false。
     * 只看尾部是为了不让很早以前的失败一直把后续轮次钉在高档上。
     */
    private fun hasRecentToolFailure(messages: JSONArray): Boolean {
        var inspected = 0
        var index = messages.length() - 1
        while (index >= 0 && inspected < AUTO_FAILURE_SCAN_MESSAGES) {
            val message = messages.optJSONObject(index) ?: break
            if (message.optString("role") == "tool") {
                inspected++
                val text = message.opt("content")?.toString().orEmpty()
                if (text.replace(" ", "").contains("\"ok\":false")) return true
            }
            index--
        }
        return false
    }

    private fun sourceType(config: AgentModelClient.ModelConfig): String =
        ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )

    private fun isLegacyReasoningModel(sourceType: String, modelId: String): Boolean {
        val model = modelId.trim().lowercase()
        return when (sourceType) {
            ProviderSourceTypes.OPENAI -> model.startsWith("gpt-5") || model.startsWith("o")
            ProviderSourceTypes.ANTHROPIC -> model.startsWith("claude-")
            ProviderSourceTypes.BAILIAN ->
                model.startsWith("qwen3.7-") ||
                    model.startsWith("qwen3.8-") ||
                    model.startsWith("kimi-") ||
                    "deepseek" in model
            ProviderSourceTypes.DEEPSEEK -> true
            ProviderSourceTypes.MOONSHOT -> model.startsWith("kimi-")
            ProviderSourceTypes.MIMO -> model.startsWith("mimo-v2.5")
            ProviderSourceTypes.STEPFUN -> model.startsWith("step-3.5-flash-2603")
            ProviderSourceTypes.OPENROUTER -> true
            ProviderSourceTypes.MINIMAX,
            ProviderSourceTypes.SILICONFLOW,
            ProviderSourceTypes.CUSTOM -> false
            else -> false
        }
    }
}
