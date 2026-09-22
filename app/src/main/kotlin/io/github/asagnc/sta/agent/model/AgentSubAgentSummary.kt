package io.github.asagnc.sta.agent.model

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

    /**
     * 段落标签前缀：标签 + 可选括注 + 冒号。
     *
     * 括注必须允许：模型经常在标签后补一句限定（实测 `**证据**（均为 Sta 自身，非问题所指外部产品）：`）。
     * 只认 `证据：` 会让这一行识别不出来，后面的证据、不确定、乃至结尾备注就全部并进结论段——
     * 结论从一句话膨胀成整份报告，再被当作历史结论每轮注入。
     */
    private val LABEL_PREFIX =
        Regex("""^(${CONCLUSION_LABEL}|${EVIDENCE_LABEL}|${UNCERTAINTY_LABEL})(?:[（(][^）)]*[）)])?[：:]\s*""")

    /** 整行只有一个标签：后面既没冒号也没内容。 */
    private val BARE_LABEL =
        Regex("""^(${CONCLUSION_LABEL}|${EVIDENCE_LABEL}|${UNCERTAINTY_LABEL})$""")

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
        // `---` 是摘要与结尾备注（模型自己加的「给主智能体的提示」之类）的分界：它后面的内容
        // 不属于三段，直接截断，不然这些内部备注会混进「不确定」段一起入库。
        raw.lineSequence()
            .takeWhile { !it.trimStart().startsWith("---") }
            .forEach { line ->
                val label = labelOf(line)
                if (label != null) {
                    current = label
                    sections.getOrPut(label) { mutableListOf() }
                    // 标签后面同一行还可能直接跟内容（`结论：xxx`）。
                    val inline = inlineOf(line)
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
     * 去掉加粗星号与行首的标题/列表符号。
     *
     * 去掉的是**全部** `*`（不只是首尾）：模型常写成 `**证据**：路径`，加粗的收尾星号夹在
     * 标签与冒号之间，只 trim 首尾是除不掉的。
     */
    private fun normalize(line: String): String =
        line.trim()
            .replace("*", "")
            .trimStart('#', '-', ' ')
            .trim()

    /** 识别一行是不是段落标签；兼容 `结论：`、`结论:`、`**结论**：`、`证据（说明）：` 等写法。 */
    private fun labelOf(line: String): String? {
        val cleaned = normalize(line)
        BARE_LABEL.matchEntire(cleaned)?.let { return it.groupValues[1] }
        return LABEL_PREFIX.find(cleaned)?.groupValues?.get(1)
    }

    /**
     * 标签行里跟在冒号后的内容（`结论：xxx` 里的 `xxx`）。
     *
     * 取「第一个不在括号里的冒号」：冒号可能落在括注之后（`证据（说明）：`），直接
     * `substringAfter('：')` 会被括注里的冒号骗到。扫的是**原始行**，正文里的 `*` 要保留。
     */
    private fun inlineOf(line: String): String {
        var depth = 0
        for ((index, ch) in line.withIndex()) {
            when (ch) {
                '（', '(' -> depth++
                '）', ')' -> if (depth > 0) depth--
                '：', ':' -> if (depth == 0) return line.substring(index + 1).trim()
            }
        }
        return ""
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
