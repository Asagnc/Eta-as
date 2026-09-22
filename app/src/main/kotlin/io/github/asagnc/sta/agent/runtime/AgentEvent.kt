package io.github.asagnc.sta.agent.runtime

import io.github.asagnc.sta.core.toSafeLogToken

internal sealed interface AgentEvent {
    fun toLogLine(): String

    enum class AssistantBlockKind {
        TEXT,
        THINKING,
        TOOL_CALL,
    }

    data class ContextCompaction(
        val operationId: String,
        val phase: String,
        val tokensBefore: Int,
        val tokensAfter: Int? = null,
        val reasonCode: String = "",
    ) : AgentEvent {
        /** 面向用户的原因说明；未知原因码返回 null，此时退回通用文案。 */
        val reasonText: String?
            get() = when (reasonCode) {
                REASON_TRIGGER_RATIO -> "上下文已接近窗口上限"
                REASON_OVERFLOW -> "模型报告上下文超出容量"
                REASON_MANUAL -> "手动压缩"
                REASON_REQUESTED -> "按你的要求压缩"
                REASON_FINAL -> "运行结束整理"
                else -> null
            }

        val displayMessage: String get() = when (phase) {
            PHASE_STARTED -> when (reasonCode) {
                // 声明窗口比服务端实际接受的大时，进度条永远到不了触发线；
                // 这里顺带给出可操作的建议，而不只是解释现象。
                REASON_OVERFLOW -> "$RUNNING_DETAIL（模型报告上下文超出容量，声明窗口可能偏大；" +
                    "可在模型设置里把上下文窗口改成约 ${compactionTokenCount(tokensBefore)} 量级）"
                else -> reasonText?.let { "$RUNNING_DETAIL（$it）" } ?: RUNNING_DETAIL
            }
            PHASE_COMPLETED -> "上下文已压缩：约 ${compactionTokenCount(tokensBefore)} → " +
                "${compactionTokenCount(tokensAfter ?: 0)} tokens" +
                (reasonText?.let { "（$it）" } ?: "")
            else -> "上下文压缩失败，原始上下文已保留。"
        }
        override fun toLogLine(): String =
            "context_compaction phase=${phase.toSafeLogToken()}, before=$tokensBefore, after=$tokensAfter, code=${reasonCode.toSafeLogToken()}"

        companion object {
            const val PHASE_STARTED = "started"
            const val PHASE_COMPLETED = "completed"
            /** 触发压缩的原因码：与失败码共用同一字段。 */
            const val REASON_TRIGGER_RATIO = "CONTEXT_TRIGGER_RATIO"
            const val REASON_OVERFLOW = "CONTEXT_OVERFLOW"
            const val REASON_MANUAL = "CONTEXT_MANUAL"
            /** 模型按用户要求转达的压缩请求（compact_context）。 */
            const val REASON_REQUESTED = "CONTEXT_REQUESTED"
            const val REASON_FINAL = "CONTEXT_FINAL"
            /** 进行中的展示文案前缀；UI 的运行态判定用 phase，不依赖这里的完整文案。 */
            const val RUNNING_DETAIL = "正在压缩上下文…"
        }
    }

    data class RunStarted(
        val initialImages: Int,
        val initialImageBytes: Int,
        val toolCount: Int,
        val terminalTools: Boolean
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_started images=$initialImages, image_bytes=$initialImageBytes, tools=$toolCount, terminal=$terminalTools"
    }

    data class RoundStarted(
        val round: Int,
        val messageCount: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "round_started round=$round, messages=$messageCount"
    }

    data class ModelRetryScheduled(
        val round: Int,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Int,
        val reasonCode: String,
    ) : AgentEvent {
        val displayMessage: String
            get() = "模型请求暂时中断，${delayMs / 1000} 秒后重试（$attempt/$maxAttempts）；此前工具结果已保留。"

        override fun toLogLine(): String =
            "model_retry_scheduled round=$round, attempt=$attempt, delay_ms=$delayMs, code=${reasonCode.toSafeLogToken()}"
    }

    data class ProviderRequestStarted(
        val round: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_request_started round=$round"
    }

    data class ProviderResponseStarted(
        val round: Int,
        val httpCode: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_response_started round=$round, http_code=$httpCode"
    }

    data class AssistantBlockStart(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_start round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}"
    }

    data class AssistantBlockDelta(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val deltaChars: Int,
        val delta: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_delta round=$round, kind=$kind, index=$index, chars=$deltaChars"
    }

    data class AssistantBlockEnd(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val contentChars: Int,
        val replacementContent: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_end round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}, chars=$contentChars"
    }

    data class AssistantReceived(
        val round: Int,
        val contentChars: Int,
        val reasoningContent: String,
        val toolNames: List<String>
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_received round=$round, content_chars=$contentChars, " +
                "reasoning_chars=${reasoningContent.length}, tool_count=${toolNames.size}, " +
                "tools=${toolNames.take(MAX_LOGGED_TOOL_NAMES).map { it.toSafeLogToken() }}"
    }

    data class UsageReceived(
        val round: Int,
        val usage: AgentTokenUsage,
        /**
         * 本轮请求的本地上下文估算。服务端回报的 total_tokens 有时明显小于实际发出的规模
         * （提示缓存、网关改写），只按它显示会让进度条长期偏低；展示与统计取两者较大值。
         */
        val estimatedContextTokens: Int? = null,
        /** 触发压缩实际使用的窗口：声明窗口与服务端实测上限的较小值。 */
        val windowTokens: Int? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "usage_received round=$round, ctx=${usage.contextTokens}, est=$estimatedContextTokens, " +
                "window=$windowTokens, in=${usage.inputTokens}, out=${usage.outputTokens}, " +
                "reasoning=${usage.reasoningTokens}, cache=${usage.cachedTokens}"
    }

    data class UserSupplementReceived(
        val index: Int,
        val text: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "user_supplement_received index=$index, chars=${text.length}"
    }

    data class ToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val argsPreview: String,
        val command: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_started round=$round, name=${name.toSafeLogToken()}, " +
                "args_chars=${argsPreview.length}, command_chars=${command?.length ?: 0}"
    }

    data class ToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val resultSummary: String,
        val imageCount: Int,
        val imageBytes: Int,
        /** 可选：旧版本 Runtime 不发送，消费端缺省时回退到摘要文本判断。 */
        val success: Boolean? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_finished round=$round, name=${name.toSafeLogToken()}, " +
                "${resultSummary.toSafeResultLogFields()}, images=$imageCount, image_bytes=$imageBytes"
    }

    data class HostedToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_started round=$round, name=${name.toSafeLogToken()}"
    }

    data class HostedToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val success: Boolean,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_finished round=$round, name=${name.toSafeLogToken()}, success=$success"
    }

    data class ToolImagesAttached(
        val round: Int,
        val toolName: String,
        val imageCount: Int,
        val imageBytes: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_images_attached round=$round, name=${toolName.toSafeLogToken()}, " +
                "images=$imageCount, image_bytes=$imageBytes"
    }

    /** 方案文档快照（submit_plan 的产出）。 */
    data class PlanUpdated(
        /** 方案快照 JSON：title、digest、content、steps、alternatives、status。 */
        val planJson: String,
    ) : AgentEvent {
        override fun toLogLine(): String = "plan_updated title=${planJson.toSafeLogToken()}"
    }

    data class TaskPlanUpdated(
        /** 完整清单快照的 JSON 数组，每项含 id、content、status。 */
        val planJson: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "task_plan_updated chars=${planJson.length}"
    }

    data class RunStatsReported(
        /** 本次 run 的度量快照 JSON，字段见 AgentRunStats.snapshot()。 */
        val statsJson: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_stats_reported chars=${statsJson.length}"
    }

    /** 运行结束时 agent 自己的复盘：发现的问题交给界面提示用户。 */
    data class SelfReview(
        val text: String,
    ) : AgentEvent {
        override fun toLogLine(): String = "self_review chars=${text.length}"
    }

    data class SubAgentUpdated(
        val id: String,
        val role: String,
        /** started / finished / failed。 */
        val phase: String,
        val summaryChars: Int,
        val errorCode: String = "",
        /**
         * 触发这次委派的 delegate 工具调用 id。
         * 界面据此把进度挂到对话流里那一步上，而不是另开一张全局面板。
         */
        val toolCallId: String = "",
    ) : AgentEvent {
        val displayMessage: String
            get() = when (phase) {
                PHASE_STARTED -> "子智能体「$role」已启动"
                PHASE_FINISHED -> "子智能体「$role」已返回摘要"
                else -> "子智能体「$role」未完成${errorCode.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
            }

        override fun toLogLine(): String =
            "sub_agent_updated id=${id.toSafeLogToken()}, role=${role.toSafeLogToken()}, " +
                "phase=${phase.toSafeLogToken()}, chars=$summaryChars, code=${errorCode.toSafeLogToken()}"

        companion object {
            const val PHASE_STARTED = "started"
            const val PHASE_FINISHED = "finished"
        }
    }

    data class RunFinished(
        val round: Int,
        val contentChars: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_finished round=$round, content_chars=$contentChars"
    }

    data class RunFailed(
        val reason: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_failed reason_chars=${reason.length}"
    }
}

private const val MAX_LOGGED_TOOL_NAMES = 8
private const val RESULT_CODE_MARKER = "code="

private fun compactionTokenCount(value: Int): String =
    java.text.NumberFormat.getIntegerInstance().format(value)

/** 摘要字段分隔符：旧格式用逗号，人文化摘要用间隔号。 */
private val RESULT_FIELD_SEPARATORS = listOf(", ", " · ")

private fun String.toSafeResultLogFields(): String = buildString {
    append("summary_chars=").append(this@toSafeResultLogFields.length)
    extractResultCode()?.let { resultCode ->
        append(", code=").append(resultCode.toSafeLogToken())
    }
}

private fun String.extractResultCode(): String? {
    val fieldStart = when {
        startsWith(RESULT_CODE_MARKER) -> 0
        else -> RESULT_FIELD_SEPARATORS
            .mapNotNull { separator ->
                indexOf(separator + RESULT_CODE_MARKER)
                    .takeIf { it >= 0 }
                    ?.plus(separator.length)
            }
            .minOrNull() ?: return null
    }
    val valueStart = fieldStart + RESULT_CODE_MARKER.length
    val valueEnd = RESULT_FIELD_SEPARATORS
        .mapNotNull { separator ->
            indexOf(separator, startIndex = valueStart).takeIf { it >= 0 }
        }
        .minOrNull() ?: length
    return substring(valueStart, valueEnd)
}
