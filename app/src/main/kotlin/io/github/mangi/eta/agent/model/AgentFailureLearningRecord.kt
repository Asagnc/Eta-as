package io.github.mangi.eta.agent.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把一次工具失败整理成一行可复用的教训记录。
 *
 * 目的不是"记日志"（日志已经有了），而是让同一种失败在下一次能更快被认出来：
 * 记录里带上工具名、失败签名、当时的上下文与修复入口，写进 Eta 自己的 files 目录，
 * 之后可以被检索、也可以在排查同类问题时直接引用。
 *
 * 只保留外部反馈型失败（工具结果里 ok=false）；工具参数被拒（INVALID_ARGUMENTS 之类）同样算，
 * 因为它们指向的是模型自己的调用习惯。抽成纯逻辑是为了能在 JVM 单测里覆盖格式与去重规则。
 */
internal object AgentFailureLearningRecord {

    /** 同一个签名在这个时间窗内只写一次，避免同一轮并行调用把文件刷爆。 */
    const val DEDUPE_WINDOW_MS: Long = 10 * 60 * 1000L

    const val MAX_DETAIL_CHARS = 240
    const val MAX_COMMAND_CHARS = 200

    data class Entry(
        val toolName: String,
        val code: String,
        val detail: String,
        val command: String,
        val round: Int,
        val timestampMs: Long,
    ) {
        val signature: String get() = "$toolName|$code"

        fun toMarkdown(): String = buildString {
            append("- ")
            append(formatTime(timestampMs))
            append(" · `")
            append(toolName)
            append("` · ")
            append(code.ifBlank { "error" })
            append(" · round ")
            append(round)
            append('\n')
            if (command.isNotBlank()) {
                append("  - 命令：`")
                append(oneLine(command, MAX_COMMAND_CHARS))
                append("`\n")
            }
            if (detail.isNotBlank()) {
                append("  - 表现：")
                append(oneLine(detail, MAX_DETAIL_CHARS))
                append('\n')
            }
        }
    }

    /**
     * 判断这次失败是否值得记。签名与最近 [recent] 条记录里的任一条相同就跳过——
     * 同一坑反复踩时，记录里已经有它了，再写一遍只会淹掉真正的新信息。
     */
    fun shouldRecord(entry: Entry, recent: List<Entry>): Boolean =
        recent.none { existing ->
            existing.signature == entry.signature &&
                entry.timestampMs - existing.timestampMs < DEDUPE_WINDOW_MS
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

    /** 读取既有文件里的签名，用于跨轮去重（文件很小，整体扫一遍即可）。 */
    fun parseRecentSignatures(content: String, nowMs: Long): List<Entry> {
        val entries = mutableListOf<Entry>()
        content.lineSequence()
            .filter { it.startsWith("- ") }
            .forEach { line ->
                val parts = line.removePrefix("- ").split(" · ")
                if (parts.size < 3) return@forEach
                val timestamp = parseTime(parts[0]) ?: return@forEach
                if (nowMs - timestamp > DEDUPE_WINDOW_MS) return@forEach
                val toolName = parts[1].trim().trim('`')
                val code = parts[2].trim()
                entries += Entry(toolName, code, "", "", 0, timestamp)
            }
        return entries
    }

    private fun oneLine(value: String, limit: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(limit)

    private fun formatTime(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestampMs))

    private fun parseTime(text: String): Long? =
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(text.trim())?.time
        }.getOrNull()
}
