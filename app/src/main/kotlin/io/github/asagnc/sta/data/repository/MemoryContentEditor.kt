package io.github.asagnc.sta.data.repository

/** 把整份记忆切成行；空文件是空列表，其余按 `\n` 切分（保留行尾空行）。 */
internal fun String.memoryLines(): List<String> = if (isEmpty()) emptyList() else split('\n')

/**
 * MEMORY.md 的纯内容变换：不碰文件、不碰锁，因此可以在本地单测里覆盖章节边界这类易错规则。
 * 需要落盘的调用方（[AgentMemoryStore]）负责原子写与 revision 校验。
 */
internal object MemoryContentEditor {
    data class Edit(
        val content: String,
        val section: AgentMemorySectionOutcome? = null,
    )

    fun replaceRange(content: String, startLine: Int, endLine: Int, replacement: String): Edit {
        val lines = content.memoryLines().toMutableList()
        if (startLine < 1 || endLine < startLine || endLine > lines.size) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效；请重新读取记忆后再试",
            )
        }
        val inserted = replacement.memoryLines()
        lines.subList(startLine - 1, endLine).clear()
        if (inserted.isNotEmpty()) {
            lines.addAll(startLine - 1, inserted)
        }
        return Edit(lines.joinToString("\n"))
    }

    fun append(content: String, addition: String): Edit {
        if (addition.isEmpty()) return Edit(content)
        if (content.isEmpty()) return Edit(addition)
        return Edit(content.trimEnd('\n') + "\n" + addition)
    }

    /** 命中同名章节就整章替换（标题行到下一个同级或更高级标题之前），未命中则在文末新增。 */
    fun upsertSection(content: String, heading: String, body: String): Edit {
        val title = MemoryMarkdown.title(heading)
        if (title.isEmpty()) {
            throw AgentMemoryException(
                code = "MEMORY_HEADING_INVALID",
                message = "upsert_section 需要非空的 heading",
            )
        }
        val lines = content.memoryLines().toMutableList()
        val bodyLines = body.memoryLines()
        val existing = MemoryMarkdown.findSection(lines, title)
        if (existing == null) {
            val headingText = MemoryMarkdown.headingText(heading)
            val kept = lines.dropLastWhile(String::isBlank).toMutableList()
            if (kept.isNotEmpty()) kept.add("")
            val startLine = kept.size + 1
            kept.add(headingText)
            kept.addAll(bodyLines)
            return Edit(
                content = kept.joinToString("\n"),
                section = AgentMemorySectionOutcome(
                    heading = headingText,
                    replaced = false,
                    startLine = startLine,
                    endLine = kept.size,
                ),
            )
        }
        // 命中已有章节时保留它原来的标题行（层级不变），只有新增章节才用调用方给的层级。
        val nested = lines.subList(existing.startIndex + 1, existing.endIndex)
            .count { line -> (MemoryMarkdown.heading(line)?.level ?: 0) > existing.level }
        val keepsNested = bodyLines.any { line -> (MemoryMarkdown.heading(line)?.level ?: 0) > existing.level }
        if (nested > 0 && !keepsNested) {
            throw AgentMemoryException(
                code = "MEMORY_SECTION_NESTED",
                message = "「${existing.title}」下面还有 $nested 个子章节，整体替换会一并删掉它们；" +
                    "请改为 upsert 要改的子章节，或把子章节内容一并写进 content。",
            )
        }
        val block = buildList {
            add(existing.headingText)
            addAll(bodyLines)
        }
        lines.subList(existing.startIndex, existing.endIndex).clear()
        lines.addAll(existing.startIndex, block)
        return Edit(
            content = lines.joinToString("\n"),
            section = AgentMemorySectionOutcome(
                heading = existing.headingText,
                replaced = true,
                startLine = existing.startIndex + 1,
                endLine = existing.startIndex + block.size,
            ),
        )
    }
}
