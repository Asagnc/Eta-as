package io.github.asagnc.sta.agent.memory

import io.github.asagnc.sta.agent.model.AgentModelClient
import io.github.asagnc.sta.data.repository.AgentMemorySnapshot
import io.github.asagnc.sta.data.repository.MemoryMarkdown

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    /** 自动注入的记忆内容：能装进注入预算时是全文，否则是预算内挑选出的章节。 */
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
        hint: String = "",
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val content = snapshot.content
        // 装得下就注入全文：一轮 system 消息的成本不变，却省掉「读不全 → 再 memory_get」的往返。
        val full = content.isNotBlank() && content.length <= coreBudget
        // 超预算时按章节挑选（见 extractForBudget）；full 分支直接用全文，不需要拼接结果。
        val core = if (full) "" else extractForBudget(content, coreBudget, hint)
        val injected = if (full) content else core.take(coreBudget)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            injectedContent = injected,
            injectedFull = full,
            // 注入的不是全文就算截断：超预算时要么跳过了章节，要么按行截了开头。
            injectedTruncated = !full && injected != content,
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

    /**
     * 超出注入预算时挑选章节：按文件顺序注入没写作用域声明的章节，直到预算用完；写了
     * `<!-- scope: ... -->` 的章节只在匹配当前任务时注入，不匹配时只留在标题索引里
     * （模型需要时用 memory_get 取）。
     *
     * 对齐 Claude Code 的 path-scoped rules。装得下时仍然全量注入——按需加载只在预算不够时
     * 才划算，否则多一次往返反而更贵。
     */
    private fun extractForBudget(content: String, budget: Int, hint: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val builder = StringBuilder()
        // 不匹配的章节连同它的子章节一起跳过：子章节脱离父章节单独注入会失去上下文。
        var skippedLevel = Int.MAX_VALUE
        var blockedByScope = false
        for ((section, scope) in MemoryMarkdown.sections(content)) {
            if (section.level > skippedLevel) continue
            skippedLevel = Int.MAX_VALUE
            if (scope.isNotEmpty() && scope.none { hint.contains(it, ignoreCase = true) }) {
                skippedLevel = section.level
                blockedByScope = true
                continue
            }
            val body = MemoryMarkdown.sectionOwnBody(lines, section)
            if (body.isBlank()) continue
            if (builder.length + body.length + 2 > budget) continue
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(body)
        }
        // 单节就超过预算（装不下又没别的可挑）时退回开头一段：宁可截断，也不要一点记忆都不给。
        // 但若什么都没注入是因为作用域不匹配，那就该什么都不注入——那正是按需加载的本意。
        if (builder.isEmpty() && !blockedByScope) return truncatedHead(content, budget)
        return builder.toString()
    }

    /** 按行边界截取开头一段，避免把内容切在行中间。 */
    private fun truncatedHead(content: String, budget: Int): String {
        if (content.length <= budget) return content
        val head = content.take(budget)
        val cut = head.lastIndexOf('\n')
        return if (cut >= budget / 2) head.take(cut) else head
    }

    /**
     * 作用域匹配用的任务文本：最近几条消息的正文与工具调用参数。
     *
     * 只做忽略大小写的 `contains` 粗匹配，所以不需要精确解析消息结构：工具参数里通常带着正在
     * 处理的路径，正是 scope 声明会写的东西。从最新往前累加，最多 [maxMessages] 条、
     * [maxChars] 字符——最新的消息最能代表当前任务。
     */
    fun taskHint(
        history: List<AgentModelClient.ConversationMessage>,
        maxMessages: Int = TASK_HINT_MESSAGES,
        maxChars: Int = TASK_HINT_CHARS,
    ): String {
        if (history.isEmpty()) return ""
        val parts = ArrayList<String>(maxMessages)
        var used = 0
        for (message in history.asReversed()) {
            if (parts.size >= maxMessages || used >= maxChars) break
            val text = hintTextOf(message)
            if (text.isBlank()) continue
            val kept = text.take(maxChars - used)
            parts += kept
            used += kept.length
        }
        return parts.asReversed().joinToString("\n")
    }

    private fun hintTextOf(message: AgentModelClient.ConversationMessage): String = buildString {
        val content = message.content.take(HINT_CONTENT_CHARS)
        if (content.isNotBlank()) append(content)
        val calls = message.toolCallsJson.take(HINT_CALL_CHARS)
        if (calls.isNotBlank()) {
            if (isNotEmpty()) append(' ')
            append(calls)
        }
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

    // 作用域匹配用的任务文本上限：这是工程量级的选择（合计约 1k token，相对上下文成本可忽略），
    // 不是从某个基准测出来的——目标是覆盖最近一轮工具调用序列，最新的消息最能代表当前任务。
    private const val TASK_HINT_MESSAGES = 6
    private const val TASK_HINT_CHARS = 4_000
    private const val HINT_CONTENT_CHARS = 800
    private const val HINT_CALL_CHARS = 400
    private const val COMPACT_HEADING = "压缩指令"
    private const val COMPACT_HEADING_EN = "Compact instructions"
    private const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
