package io.github.mangi.eta.agent.model

/**
 * 子智能体摘要的结构化解析。
 *
 * 摘要被要求写成「结论 / 证据 / 不确定」三段，证据统一成 `路径:行号` 或 `命令 → 输出片段`。
 * 这里把它解析成结构化引用，而不是继续当自由文本用，原因有两个：
 *
 * 1. 证据里的文件路径可以变成知识条目的依赖指纹（见 `WorldKnowledgeLogic.Dependency`），
 *    回读时能校验「这条结论依赖的文件是否已改」，避免过期结论被当作事实注入。
 * 2. 主智能体按证据回读原文核对时，需要的是路径与行号，而不是一段需要再解析的自然语言。
 *
 * 解析器对格式保持宽容：模型不一定每次都写得工整，解析不出结构的段落会落进 [Summary.raw]，
 * 不会丢内容。
 */
internal object AgentSubAgentSummary {

    /** 一条证据引用。 */
    data class Evidence(
        /** 证据种类：`file`（路径:行号）或 `command`（命令 → 输出）。 */
        val kind: Kind,
        /** 文件路径或命令本身。 */
        val target: String,
        /** 行号；非文件证据或没写行号时为 0。 */
        val line: Int,
        /** 原始那一行，保留给需要展示原文的场景。 */
        val raw: String,
    ) {
        enum class Kind { FILE, COMMAND }
    }

    data class Summary(
        val conclusion: String,
        val evidence: List<Evidence>,
        val uncertainty: List<String>,
        /** 原始摘要全文，解析失败时的兜底。 */
        val raw: String,
    ) {
        /** 证据里出现过的文件路径，按出现顺序去重。 */
        val filePaths: List<String>
            get() = evidence.asSequence()
                .filter { it.kind == Evidence.Kind.FILE }
                .map { it.target }
                .distinct()
                .toList()
    }

    private const val CONCLUSION_LABEL = "结论"
    private const val EVIDENCE_LABEL = "证据"
    private const val UNCERTAINTY_LABEL = "不确定"

    /** `路径:行号`，路径里允许出现盘符与空格之外的大多数字符。 */
    private val FILE_PATTERN = Regex("""^(.+?):(\d+)$""")

    /**
     * 解析摘要。任何输入都返回结果：解析不出三段时，全文落进 [Summary.raw]，
     * [Summary.conclusion] 退化为全文，调用方不必先判断格式是否合规。
     */
    fun parse(text: String): Summary {
        val raw = text.trim()
        if (raw.isBlank()) return Summary("", emptyList(), emptyList(), "")

        val sections = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        raw.lines().forEach { line ->
            val label = labelOf(line)
            if (label != null) {
                current = label
                sections.getOrPut(label) { mutableListOf() }
                // 标签后面同一行还可能直接跟内容（`结论：xxx`）。
                // 不能写成 substringAfter('：').substringAfter(':')：后者在没有半角冒号时
                // 会返回默认值，把前一步取到的内容吞掉。按实际出现的分隔符取一次即可。
                val separator = if (line.contains('：')) '：' else ':'
                val inline = line.substringAfter(separator, "").trim()
                if (inline.isNotEmpty()) sections.getValue(label).add(inline)
            } else if (current != null) {
                sections.getValue(current!!).add(line)
            }
        }

        val conclusion = sections[CONCLUSION_LABEL]?.joinToString(" ")?.trim().orEmpty()
        val evidence = sections[EVIDENCE_LABEL].orEmpty()
            .mapNotNull { parseEvidence(it) }
        val uncertainty = sections[UNCERTAINTY_LABEL].orEmpty()
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }

        // 没解析出结论时退回全文：宁可把原样内容交出去，也不要因为格式不合规就丢掉产出。
        return Summary(
            conclusion = conclusion.ifBlank { raw },
            evidence = evidence,
            uncertainty = uncertainty,
            raw = raw,
        )
    }

    /**
     * 识别一行是不是段落标签；兼容 `结论：`、`结论:`、`**结论**：` 等写法。
     *
     * 先去掉全部 `*`（不只是首尾）：模型常写成 `**证据**：路径`，加粗的收尾星号夹在
     * 标签与冒号之间，只 trim 首尾是除不掉的。
     */
    private fun labelOf(line: String): String? {
        val cleaned = line.trim()
            .replace("*", "")
            .trimStart('#', '-', ' ')
            .trim()
        return when {
            cleaned.startsWith("${CONCLUSION_LABEL}：") ||
                cleaned.startsWith("${CONCLUSION_LABEL}:") -> CONCLUSION_LABEL

            cleaned.startsWith("${EVIDENCE_LABEL}：") ||
                cleaned.startsWith("${EVIDENCE_LABEL}:") -> EVIDENCE_LABEL

            cleaned.startsWith("${UNCERTAINTY_LABEL}：") ||
                cleaned.startsWith("${UNCERTAINTY_LABEL}:") -> UNCERTAINTY_LABEL

            cleaned == CONCLUSION_LABEL -> CONCLUSION_LABEL
            cleaned == EVIDENCE_LABEL -> EVIDENCE_LABEL
            cleaned == UNCERTAINTY_LABEL -> UNCERTAINTY_LABEL
            else -> null
        }
    }

    /**
     * 解析一条证据。
     *
     * 命令证据优先：`命令 → 输出` 里的箭头是明确分隔符，而路径里的冒号（如 `C:\x`）会让
     * 「按冒号判断是不是文件」产生歧义。
     */
    private fun parseEvidence(line: String): Evidence? {
        val text = line.trim().removePrefix("-").trim().removePrefix("*").trim()
        if (text.isEmpty()) return null
        val cleaned = text.trim('`').trim()
        if (cleaned.contains(" → ")) {
            val target = cleaned.substringBefore(" → ").trim().trim('`')
            if (target.isEmpty()) return null
            return Evidence(Evidence.Kind.COMMAND, target, 0, text)
        }
        val match = FILE_PATTERN.matchEntire(cleaned)
        if (match != null) {
            val path = match.groupValues[1].trim().trim('`')
            val lineNumber = match.groupValues[2].toIntOrNull() ?: 0
            // 纯数字「路径」是时间或编号，不是文件引用。
            if (path.isNotEmpty() && !path.all { it.isDigit() }) {
                return Evidence(Evidence.Kind.FILE, path, lineNumber, text)
            }
        }
        return null
    }
}
