package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.runtime.AgentEvent
import io.github.asagnc.sta.agent.runtime.AgentRunController

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    /**
     * 输出上限提升：默认不写进请求体（沿用上游默认值），某一轮被截断后，后续轮次带上更大的
     * 上限（档位见 OUTPUT_LIMIT_BOOSTS）。AgentLoop 一次 run 只建一个实例，所以这些状态跨轮
     * 有效；上游点名拒收这个字段时 boostDisabled 会永久关掉它，避免每轮白试一次。
     */
    private var outputBoost: Int? = null
    private var boostDisabled = false
    private var observedOutputTokens: Int? = null

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        var activeRequest = request
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            var attemptOutputTokens: Int? = null
            try {
                val attemptRequest = outputBoost?.let { boost ->
                    activeRequest.copy(config = activeRequest.config.copy(maxOutputTokens = boost))
                } ?: activeRequest
                val response = provider.complete(attemptRequest, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    if (event is ProviderEvent.Usage) attemptOutputTokens = event.usage.outputTokens
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                if (response.isUnexplainedEmptyCompletion()) {
                    // 上游声称正常结束却什么都没给：这多是上游或网关抖动，按瞬时失败重试，
                    // 而不是让整次运行直接失败。长度截断、内容过滤这类有明确原因的空态不在此列。
                    throw AgentModelFailure(
                        code = "EMPTY_COMPLETION",
                        retryable = true,
                        message = "模型接口本轮返回空响应（finish_reason=" +
                            response.assistantMessage.optString("finish_reason") +
                            "），既没有正文也没有工具调用。",
                    )
                }
                if (response.stopReason == AssistantStopReason.OUTPUT_LIMIT) {
                    // 本轮被输出上限截断（工具参数被截掉是典型表现）。这里不静默重放本轮：
                    // AgentLoop 仍按既有语义把 TRUNCATED_TOOL_CALL 提示给模型，只是把下一次
                    // 请求的预算提高一档，让模型重提时不会再被同一个默认上限卡住。
                    observedOutputTokens = attemptOutputTokens ?: observedOutputTokens
                    if (!boostDisabled) {
                        nextOutputBoost(observedOutputTokens, outputBoost)?.let { outputBoost = it }
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified, recoveryAllowed = false,
                )
                if (outputBoost != null && !boostDisabled &&
                    classified.message.orEmpty().mentionsOutputLimitField()
                ) {
                    // 上游不认或不允许这个字段：撤回提升，回到默认预算再试一次，不占用瞬时错误预算。
                    boostDisabled = true
                    outputBoost = null
                    onEvent(AgentEvent.ModelRetryScheduled(round, retries + 1, MAX_RETRIES, 0, classified.code))
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                val droppable = classified.droppableField()
                if (droppable != null && droppable !in activeRequest.dropFields) {
                    // 上游点名拒收某个语义冗余的字段：去掉它再试一次，不占用瞬时错误的重试预算。
                    onEvent(AgentEvent.ModelRetryScheduled(round, retries + 1, MAX_RETRIES, 0, classified.code))
                    activeRequest = activeRequest.copy(dropFields = activeRequest.dropFields + droppable)
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                if (classified.rejectsThinkingReplay() && !activeRequest.dropThinkingBlocks) {
                    // 上游拒收历史里回放的思考块（签名无效或被改写）：按官方修法剥掉全部
                    // thinking / redacted_thinking 块再试一次，不占用瞬时错误的重试预算。
                    onEvent(AgentEvent.ModelRetryScheduled(round, retries + 1, MAX_RETRIES, 0, classified.code))
                    activeRequest = activeRequest.copy(dropThinkingBlocks = true)
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    /**
     * 只有"上游申报正常结束（END_TURN）、却既无正文也无工具调用"才算是不可解释的空响应。
     * 其余空态（长度截断、内容过滤、未完成）都带有明确原因，重试不会改变结果。
     */
    private fun ProviderResponse.isUnexplainedEmptyCompletion(): Boolean {
        if (stopReason != AssistantStopReason.END_TURN) return false
        val calls = assistantMessage.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) return false
        val content = assistantMessage.optString("content").trim()
        return content.isEmpty() || content == "null"
    }

    /**
     * 输出被上限截断时挑下一个预算档：只挑比「已观察到的输出量」和「当前档」都更大的档位。
     * 上游没回 usage 时观察值缺失，就从最小档起步；没有更大的档位可用时返回 null（不再提升）。
     */
    private fun nextOutputBoost(observedOutputTokens: Int?, current: Int?): Int? =
        OUTPUT_LIMIT_BOOSTS.firstOrNull { boost ->
            boost > (current ?: 0) && (observedOutputTokens == null || boost > observedOutputTokens)
        }

    private fun String.mentionsOutputLimitField(): Boolean =
        contains("max_output_tokens", ignoreCase = true) ||
            contains("max_tokens", ignoreCase = true) ||
            contains("max_completion_tokens", ignoreCase = true)

    companion object {
        private const val MAX_RETRIES = 2
        private const val BASE_DELAY_MS = 2_000L

        /** 输出上限的提升档位：覆盖常见的 4K/8K 网关默认值，又不超过主流模型的上限。 */
        private val OUTPUT_LIMIT_BOOSTS = intArrayOf(32_768, 131_072)
    }
}
