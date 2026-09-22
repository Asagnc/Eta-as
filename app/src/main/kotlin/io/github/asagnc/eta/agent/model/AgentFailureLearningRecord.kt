package io.github.asagnc.eta.agent.model

/**
 * 把一次工具失败整理成可复用的教训。
 *
 * 只做「从工具结果里抽出字段」这一件事——落盘与回读归
 * [io.github.asagnc.eta.data.world.WorldKnowledgeStore]。
 * 这里不再有任何序列化格式：教训的字段直接进观测库的列，不再靠解析文本来读回。
 *
 * 抽成纯逻辑是为了能在 JVM 单测里覆盖判据，而判据决定"同一个坑会不会被重复记录"。
 */
internal object AgentFailureLearningRecord {

    /** 同一个签名在这个时间窗内只写一次，避免同一轮并行调用把观测库刷爆。 */
    const val DEDUPE_WINDOW_MS: Long = 10 * 60 * 1000L

    const val MAX_DETAIL_CHARS = 240
    const val MAX_COMMAND_CHARS = 200

    /** 回注给模型的历史条目上限：够说明"上次是什么情况"，不至于把旧记录整篇搬进上下文。 */
    const val MAX_HISTORY_CHARS = 600

    data class Entry(
        val toolName: String,
        val code: String,
        val detail: String,
        val command: String,
        val round: Int,
        val timestampMs: Long,
    ) {
        val signature: String get() = "$toolName|$code"

        /** 结论正文：注入时给模型看的那一句话。 */
        val summary: String get() = buildString {
            append('`').append(toolName).append("` 失败：")
            append(code.ifBlank { "error" })
            if (command.isNotBlank()) append("，命令 `").append(oneLine(command, MAX_COMMAND_CHARS)).append('`')
            if (detail.isNotBlank()) append("，表现：").append(oneLine(detail, MAX_DETAIL_CHARS))
        }

        /** 证据引用：失败现场是"命令 → 输出片段"的形式。 */
        val evidence: String get() = buildString {
            if (command.isNotBlank()) append(oneLine(command, MAX_COMMAND_CHARS)).append(" → ")
            append(code.ifBlank { "error" })
            if (detail.isNotBlank()) append("（").append(oneLine(detail, MAX_DETAIL_CHARS)).append("）")
        }
    }

    /** 从工具结果里抽出可记录的字段；结果不是 JSON 或没有明确失败时返回 null。 */
    fun of(
        toolName: String,
        round: Int,
        resultContent: String,
        command: String,
        timestampMs: Long,
    ): Entry? {
        val failure = AgentFailureSignature.of(toolName, resultContent) ?: return null
        val payload = runCatching { org.json.JSONObject(resultContent) }.getOrNull()
        val code = payload?.optString("code").orEmpty().ifBlank {
            payload?.optInt("exit_code", Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.let { "EXIT_$it" }
                .orEmpty()
        }
        return Entry(
            toolName = toolName,
            code = code,
            detail = failure.detail,
            command = command,
            round = round,
            timestampMs = timestampMs,
        )
    }

    private fun oneLine(value: String, limit: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(limit)
}
