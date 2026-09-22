package io.github.asagnc.sta.agent.model

import org.json.JSONObject

/** 只声明当前会话的历史检索与压缩请求，不允许模型指定其他会话身份。 */
internal object AgentConversationToolCatalog {
    const val READ_HISTORY = "conversation_history"
    const val COMPACT_CONTEXT = "compact_context"

    /** 压缩指令长度上限；与压缩器共用同一个常量，工具声明和实际截断不会脱节。 */
    const val MAX_INSTRUCTIONS_CHARS = AgentContextCompactor.MAX_COMPACT_INSTRUCTIONS_CHARS

    fun schema(): JSONObject = AgentToolSchema.function(
        name = READ_HISTORY,
        description = "读取当前会话的完整脱敏历史。上下文摘要有损，需要核对旧指令、操作细节或工具结果时使用。" +
            "query 可搜索文本；省略 query 则按 message_index、offset 分页读取。沿 next_message_index 和 next_offset 继续，" +
            "续读固定同一份历史，从零开始可刷新；不要把分页内容误认为完整历史。敏感工具原文和瞬时图片不在持久历史中。",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("maxLength", 500))
            .put("message_index", JSONObject().put("type", "integer").put("minimum", 0))
            .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
            .put("max_chars", JSONObject().put("type", "integer").put("minimum", 256).put("maximum", 8000))),
    )

    /**
     * 压缩请求工具：用户明确要求压缩时，模型用它把「这次要重点保留什么」转达给宿主。
     *
     * 压缩本身是宿主行为（要发一次摘要请求并重写历史），工具只负责提出请求——所以描述里强调
     * 只在用户明确要求时调用，避免模型自行决定丢掉历史。
     */
    fun compactSchema(): JSONObject = AgentToolSchema.function(
        name = COMPACT_CONTEXT,
        description = "把较早的会话历史压缩成摘要，腾出上下文空间。压缩后原始细节会丢失，" +
            "需要时只能用 conversation_history 查回或重新执行工具，所以只在用户明确要求压缩上下文时调用" +
            "（例如「压缩一下」「上下文太长了」），不要自己判断该不该压缩。" +
            "instructions 填用户希望压缩时重点保留的内容；用户没有特别要求就省略。",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("instructions", JSONObject().put("type", "string")
                .put("maxLength", MAX_INSTRUCTIONS_CHARS)
                .put("description", "压缩时特别要保留的内容；用户没有特别要求时省略"))),
    )

    /** 从工具参数里取压缩指令；参数不合法或没填时返回空串（照常压缩，只是没有额外关注点）。 */
    fun instructionsOf(argumentsJson: String): String =
        runCatching { JSONObject(argumentsJson).optString("instructions").trim() }
            .getOrDefault("")
            .take(MAX_INSTRUCTIONS_CHARS)
}
