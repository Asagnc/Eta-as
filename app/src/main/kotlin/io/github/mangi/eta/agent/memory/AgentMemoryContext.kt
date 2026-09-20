package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.MemoryMarkdown

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    /** 自动注入的记忆内容：能装进注入预算时是全文，否则是 # 核心记忆 章节。 */
    val injectedContent: String,
    /** injectedContent 是否为记忆全文（为真时不需要再为了解记忆调用 memory_get）。 */
    val injectedFull: Boolean,
    val injectedTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
    /** 用户写在记忆里的压缩偏好；对齐 Claude Code 在 CLAUDE.md 写 `# Compact instructions` 的做法。 */
    val compactInstructions: String = "",
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            injectedContent = "",
            injectedFull = false,
            injectedTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val content = snapshot.content
        // 装得下就注入全文：一轮 system 消息的成本不变，却省掉「读不全 → 再 memory_get」的往返。
        val full = content.isNotBlank() && content.length <= coreBudget
        val core = extractCore(content)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            injectedContent = if (full) content else core.take(coreBudget),
            injectedFull = full,
            injectedTruncated = !full && core.length > coreBudget,
            headingIndex = MemoryMarkdown.sectionIndex(content),
            coreBudgetChars = coreBudget,
            compactInstructions = extractCompactInstructions(content),
        )
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    private fun extractCore(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING }
        if (start < 0) return ""
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    /**
     * 取出记忆里的「压缩指令」章节。
     *
     * 对齐 Claude Code：压缩偏好写在记忆文件里（CLAUDE.md 的 `# Compact instructions`），
     * 而不是另开一个设置项——用户已经能用记忆编辑器和 memory_write 维护它。
     */
    private fun extractCompactInstructions(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { line ->
            MemoryMarkdown.heading(line)?.let { heading ->
                heading.title.equals(COMPACT_HEADING, ignoreCase = true) ||
                    heading.title.equals(COMPACT_HEADING_EN, ignoreCase = true)
            } == true
        }
        if (start < 0) return ""
        val level = MemoryMarkdown.heading(lines[start])?.level ?: return ""
        val end = ((start + 1) until lines.size).firstOrNull { index ->
            val heading = MemoryMarkdown.heading(lines[index])
            heading != null && heading.level <= level
        } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }

    private const val CORE_HEADING = "# 核心记忆"
    private const val COMPACT_HEADING = "压缩指令"
    private const val COMPACT_HEADING_EN = "Compact instructions"
    private const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
