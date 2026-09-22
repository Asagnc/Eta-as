package io.github.asagnc.eta.agent.model

import io.github.asagnc.eta.agent.runtime.AgentRunController
import io.github.asagnc.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject

internal interface AgentProviderClient {
    val id: String
    val capabilities: ProviderCapabilities

    fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit = {}
    ): ProviderResponse
}

internal data class ProviderCapabilities(
    val endpoint: EndpointKind,
    val streamingText: Boolean,
    val streamingToolCalls: Boolean,
    val imageInput: Boolean,
    val toolResultImages: Boolean,
    val strictTools: Boolean,
    val parallelToolCalls: Boolean
)

internal enum class EndpointKind {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES
}

internal enum class ProviderRequestPurpose {
    CHAT, COMPACTION, REPLY_REWRITE;

    val allowsTools: Boolean get() = this == CHAT
}

/** 去掉上游点名拒收的字段；返回自身以便串在构建链上。 */
internal fun JSONObject.dropRejectedFields(fields: Set<String>): JSONObject {
    fields.forEach { name -> if (name.isNotBlank()) remove(name) }
    return this
}

internal data class ProviderRequest(
    val config: AgentModelClient.ModelConfig,
    val messages: JSONArray,
    val tools: JSONArray,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    /** 上游点名拒收、且省略后不改变模型行为的字段；重试时从请求体里去掉。 */
    val dropFields: Set<String> = emptySet(),
    /**
     * 上游拒收历史里回放的思考块（签名无效 / 块被改写）时置位：
     * 按官方修法剥掉全部 thinking 与 redacted_thinking 块后再试一次。
     */
    val dropThinkingBlocks: Boolean = false,
) {
    val effectiveConfig: AgentModelClient.ModelConfig get() = if (!purpose.allowsTools) {
        config.copy(hostedWebSearchEnabled = false, extraBodyJson = "", customBody = emptyList())
    } else config
    val effectiveTools: JSONArray get() = if (purpose.allowsTools) tools else JSONArray()
}

internal data class ProviderResponse(
    val assistantMessage: JSONObject
) {
    val stopReason: AssistantStopReason
        get() = AssistantStopReason.fromWireValue(assistantMessage.optString("finish_reason"))
}

internal enum class AssistantStopReason {
    END_TURN,
    TOOL_USE,
    OUTPUT_LIMIT,
    CONTENT_FILTER,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): AssistantStopReason =
            when (value?.trim()?.lowercase()) {
                "stop", "end_turn" -> END_TURN
                "tool_calls", "tool_use" -> TOOL_USE
                "length", "max_tokens" -> OUTPUT_LIMIT
                "content_filter", "refusal" -> CONTENT_FILTER
                else -> UNKNOWN
            }
    }
}

internal enum class AssistantBlockKind {
    TEXT,
    THINKING,
    TOOL_CALL,
}

internal sealed interface ProviderEvent {
    data object RequestStarted : ProviderEvent

    data class ResponseHeaders(
        val httpCode: Int
    ) : ProviderEvent

    data class BlockStart(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : ProviderEvent

    data class BlockDelta(
        val kind: AssistantBlockKind,
        val index: Int,
        val delta: String,
    ) : ProviderEvent

    data class BlockEnd(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val content: String = "",
        val replaceContent: Boolean = false,
    ) : ProviderEvent

    data class Usage(
        val usage: AgentTokenUsage,
        val contextInputTokens: Int? = usage.inputTokens ?: usage.contextTokens?.let {
            (it - (usage.outputTokens ?: 0)).coerceAtLeast(0)
        },
    ) : ProviderEvent

    data class HostedToolStarted(
        val id: String,
        val name: String,
    ) : ProviderEvent

    data class HostedToolFinished(
        val id: String,
        val name: String,
        val success: Boolean,
    ) : ProviderEvent

    data class Completed(
        val reason: String?
    ) : ProviderEvent
}
