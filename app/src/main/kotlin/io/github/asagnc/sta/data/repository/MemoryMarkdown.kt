package io.github.asagnc.sta.data.repository

/**
 * MEMORY.md 的 Markdown 标题解析：章节定位、标题规范化，以及带行号范围的标题索引。
 *
 * 章节语义：一个章节从它的标题行开始，到下一个同级或更高级标题之前结束
 * （`# 核心记忆` 会覆盖它下面所有 `##` 小节）；没有下一个边界标题时到文件末尾。
 */
internal object MemoryMarkdown {
    private val HEADING_LINE = Regex("^(#{1,6})[ \\t]+(\\S.*?)[ \\t]*$")

    /** 章节作用域声明：标题行正下方的 `<!-- scope: a, b -->`。 */
    private val SCOPE_LINE = Regex("^<!--\\s*scope\\s*:\\s*(.+?)\\s*-->$")
    private const val DEFAULT_LEVEL = 2
    private const val INDEX_LIMIT_CHARS = 4_000

    data class Heading(val level: Int, val title: String, val text: String)

    data class Section(
        val headingText: String,
        val title: String,
        val level: Int,
        val startIndex: Int,
        val endIndex: Int,
    )

    /** 解析标题行；不是标题、或 `#` 后没有标题文本时返回 null。 */
    fun heading(line: String): Heading? {
        val match = HEADING_LINE.find(line) ?: return null
        val level = match.groupValues[1].length
        val title = match.groupValues[2].trim()
        if (title.isEmpty()) return null
        return Heading(level, title, "#".repeat(level) + " " + title)
    }

    /** 去掉 `#` 前缀与空白后的标题名，用于跨层级比较（`## 设备` 与 `设备` 等价）。 */
    fun title(raw: String): String = raw.trim().dropWhile { character -> character == '#' }.trim()

    /** 规范化标题行；调用方没写 `#` 时按二级标题处理。 */
    fun headingText(raw: String): String {
        val trimmed = raw.trim()
        val match = HEADING_LINE.find(trimmed)
        if (match != null) return "#".repeat(match.groupValues[1].length) + " " + match.groupValues[2].trim()
        return "#".repeat(DEFAULT_LEVEL) + " " + title(trimmed)
    }

    /** 按标题名找章节（忽略 `#` 层级，取第一个命中）。 */
    fun findSection(lines: List<String>, title: String): Section? {
        val headings = headingsOf(lines)
        val position = headings.indexOfFirst { candidate -> candidate.heading.title.equals(title, ignoreCase = true) }
        if (position < 0) return null
        val target = headings[position]
        return Section(
            headingText = target.heading.text,
            title = target.heading.title,
            level = target.heading.level,
            startIndex = target.index,
            endIndex = sectionEnd(headings, position, lines.size),
        )
    }

    /**
     * 解析章节标题行正下方的 `<!-- scope: 匹配项, ... -->` 作用域声明；没有声明返回空列表。
     *
     * 对齐 Claude Code 的 path-scoped rules：章节可以声明「只对某类工作生效」，
     * 不相关时不占注入预算，只出现在标题索引里（模型需要时用 memory_get 取）。
     */
    fun scopeOf(lines: List<String>, section: Section): List<String> {
        val first = section.startIndex + 1
        if (first >= lines.size || first > section.endIndex) return emptyList()
        val match = SCOPE_LINE.find(lines[first].trim()) ?: return emptyList()
        return match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 按文件顺序列出所有章节（章节 → 作用域匹配项；没写声明时是空列表）。 */
    fun sections(content: String): List<Pair<Section, List<String>>> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        val headings = headingsOf(lines)
        return headings.mapIndexed { position, located ->
            val section = Section(
                headingText = located.heading.text,
                title = located.heading.title,
                level = located.heading.level,
                startIndex = located.index,
                endIndex = sectionEnd(headings, position, lines.size),
            )
            section to scopeOf(lines, section)
        }
    }

    /** 只列出带作用域声明的章节，用于预算不够时挑选注入。 */
    fun scopedSections(content: String): List<Pair<Section, List<String>>> =
        sections(content).filter { (_, scope) -> scope.isNotEmpty() }

    /** 章节正文（含标题行、含子章节），用于整节注入。 */
    fun sectionBody(lines: List<String>, section: Section): String =
        lines.subList(section.startIndex, section.endIndex.coerceAtMost(lines.size)).joinToString("\n")

    /**
     * 章节自有正文（含标题行，**不含**子章节）：标题行到第一个子标题之前。
     *
     * 逐节注入时用它拼接：[sectionBody] 会把子章节一起带上，逐节取整段就会重复注入同一批内容。
     */
    fun sectionOwnBody(lines: List<String>, section: Section): String {
        val end = section.endIndex.coerceAtMost(lines.size)
        val ownEnd = ((section.startIndex + 1) until end)
            .firstOrNull { index -> heading(lines[index]) != null }
            ?: end
        return lines.subList(section.startIndex, ownEnd).joinToString("\n")
    }

    /** 带行号范围的标题索引，例如 `## 设备  [L12-45]`；超出上限时按行截断。 */
    fun sectionIndex(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val headings = headingsOf(lines)
        if (headings.isEmpty()) return ""
        val entries = headings.mapIndexed { position, target ->
            // sectionEnd 返回下一个边界标题的 0 起下标（没有则为行数），正好是本节最后一行的 1 起行号。
            val endLine = sectionEnd(headings, position, lines.size)
            "${target.heading.text}  [L${target.index + 1}-$endLine]"
        }
        val joined = entries.joinToString("\n")
        if (joined.length <= INDEX_LIMIT_CHARS) return joined
        val cut = joined.lastIndexOf('\n', INDEX_LIMIT_CHARS - 2).coerceAtLeast(1)
        return joined.take(cut) + "\n…"
    }

    private data class LocatedHeading(val index: Int, val heading: Heading)

    private fun headingsOf(lines: List<String>): List<LocatedHeading> = lines.mapIndexedNotNull { index, line ->
        heading(line)?.let { parsed -> LocatedHeading(index, parsed) }
    }

    /**
     * 章节结束位置：下一个同级或更高级标题的 0 起下标；没有则返回行数。
     * 作为 [Section.endIndex] 时是开区间边界，作为行号时等于本节最后一行的 1 起行号。
     */
    private fun sectionEnd(headings: List<LocatedHeading>, position: Int, lineCount: Int): Int {
        val level = headings[position].heading.level
        return headings.drop(position + 1).firstOrNull { candidate -> candidate.heading.level <= level }?.index
            ?: lineCount
    }
}
