package io.github.mangi.eta.data.world

/**
 * 从观测文本里抽取实体（图节点）。
 *
 * 这是整套聚类的**确定性骨架**：文件路径与代码符号都是文本里客观存在的事实，
 * 抽取它们不需要模型、不会幻觉、零成本。Zep 之所以必须用 LLM 抽取实体，是因为它的
 * episode 是无结构的自然语言对话；而 Eta 的观测是代码工作记录，**结构本来就写在
 * 文本里**——用 LLM 去抽这些等于用大炮打蚊子，还引入了本来不存在的错误来源。
 *
 * 抽出来的实体分三种 kind（见 [EntityKind]），其中 `concept` 不在这里产生：它需要
 * LLM，属于补断点的那一层（`semantic` 边），本类只负责确定性的两种。
 *
 * 单独成文件而非塞进 store，是为了能在纯 JVM 单测里覆盖：抽取规则错了会让整张图
 * 建立在意料之外的结构上，而图错了聚类必然错，这类错误在真机上极难察觉。
 */
internal object WorldEntityExtractor {

    /** 实体的三种种类。 */
    enum class EntityKind(val value: String) {
        /** 代码文件路径。 */
        FILE("file"),

        /** 代码标识符（类名 / 函数名 / 常量名）。 */
        SYMBOL("symbol"),

        /** 语义概念，由 LLM 抽取。 */
        CONCEPT("concept"),

        ;

        companion object {
            fun from(value: String): EntityKind? = entries.firstOrNull { it.value == value }
        }
    }

    /** 抽出的一个实体引用。 */
    data class Extracted(
        val kind: EntityKind,
        /** 归一后的名字：文件为绝对路径，符号为原样标识符。 */
        val name: String,
    )

    /**
     * 一次抽取的完整结果。
     *
     * 文件与符号分开给出而不是混成一个列表：建边时两者的语义不同——「共享文件」比
     * 「共享符号」强得多（同名函数在不同模块里很常见，而同一个文件就是同一个文件）。
     * 分成两组让权重可以分别设定，而不是靠事后猜。
     */
    data class Bundle(
        val files: List<String>,
        val symbols: List<String>,
    ) {
        val isEmpty: Boolean get() = files.isEmpty() && symbols.isEmpty()

        fun all(): List<Extracted> =
            files.map { Extracted(EntityKind.FILE, it) } +
                symbols.map { Extracted(EntityKind.SYMBOL, it) }
    }

    /**
     * 从结论、证据与不确定项里抽取实体。
     *
     * [workspaceRoot] 用于把证据里的相对路径补成绝对路径，与
     * [WorldKnowledgeLogic.normalizePath] 同一口径——两处归一规则若不一致，同一条观测
     * 会因为来源不同而被当成两个不同的实体，图的连通性会莫名其妙地断掉。
     */
    fun extract(
        conclusion: String,
        evidence: String,
        uncertainty: String = "",
        workspaceRoot: String = "",
    ): Bundle {
        val text = listOf(conclusion, evidence, uncertainty)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (text.isBlank()) return Bundle(emptyList(), emptyList())

        val files = extractFiles(text, workspaceRoot)
        // 符号从「非路径部分」里抽：路径本身含有大量看起来像标识符的片段
        // （如 `AgentLoop.kt` 里的 AgentLoop），把它们当符号会人为制造出大量虚假的
        // 「共享符号」边——凡是提到同一个文件的观测都会共享这批符号，等于把文件边
        // 重复计一遍，权重失真。
        val symbols = extractSymbols(text, files)
        return Bundle(files = files, symbols = symbols)
    }

    /**
     * 抽取文件路径。
     *
     * 两个来源：① Java/Kotlin 常见源文件扩展名；② 路径分隔符明显的字符串。
     * 都要求以扩展名结尾，避免把 `Foo.bar` 这类点号表达式误判成文件。
     */
    fun extractFiles(text: String, workspaceRoot: String): List<String> {
        val found = linkedSetOf<String>()
        FILE_EXTENSION_PATTERN.findAll(text).forEach { match ->
            val raw = match.value.trim().trim('`', '"', '\'', ',', ';', '(', ')')
            // 去掉常见的前缀噪声：反引号、markdown 链接残留、行号后缀。
            val cleaned = raw.substringBeforeLast(':').ifBlank { raw }
            if (cleaned.length < MIN_FILE_NAME_CHARS) return@forEach
            if (!cleaned.contains('.') && !cleaned.contains('/')) return@forEach
            val normalized = WorldKnowledgeLogic.normalizePath(cleaned, workspaceRoot) ?: return@forEach
            found.add(normalized)
        }
        return found.toList()
    }

    /**
     * 抽取代码符号。
     *
     * 只认「形状明确」的标识符，不放宽到任意单词：宁可漏掉一些真符号，也不要让普通
     * 英文单词混进来——后者会让几乎所有观测都通过关键词产生弱边，图会退化成一团，
     * 聚类也就失去意义。
     *
     * 判据（满足任一）：
     * - 反引号包裹的标识符（模型引用符号时的标准写法）
     * - 驼峰命名且长度足够（`WorldKnowledgeStore`、`AgentLoop`）
     * - 全大写常量（`KEEP_ENTRIES`、`MAX_RECALL_CHARS`）
     *
     * [filePaths] 是已经从文本里抽出的文件路径，用于排除路径内部的片段。
     */
    fun extractSymbols(text: String, filePaths: List<String> = emptyList()): List<String> {
        // 先把文件路径整体挖掉，剩下的才用来找符号。
        var scrubbed = text
        filePaths.forEach { path ->
            scrubbed = scrubbed.replace(path, " ")
            // 相对路径的写法（如 `WorldDatabase.kt`）也要挖掉。
            path.substringAfterLast('/').let { name -> scrubbed = scrubbed.replace(name, " ") }
        }

        val found = linkedSetOf<String>()

        // 反引号是有意标记：模型把它包起来就是在说「这是符号」。所以这里**不做形状
        // 过滤**——`reasoning_effort` 是全小写下划线命名，形状判据会把它当普通词挡掉，
        // 但它确实是符号，而且是那种只看形状永远认不出来的符号。
        BACKTICK_PATTERN.findAll(scrubbed).forEach { match ->
            val candidate = match.groupValues[1].trim()
            if (candidate.length in MIN_SYMBOL_CHARS..MAX_SYMBOL_CHARS) found.add(candidate)
        }
        CAMEL_PATTERN.findAll(scrubbed).forEach { match ->
            val candidate = match.value
            if (isPlausibleSymbol(candidate)) found.add(candidate)
        }
        CONSTANT_PATTERN.findAll(scrubbed).forEach { match ->
            val candidate = match.value
            if (isPlausibleSymbol(candidate)) found.add(candidate)
        }
        return found.toList()
    }

    /**
     * 判断一个候选是不是像样的符号。
     *
     * 排除两类噪声：常见的英文技术词汇（`Android`、`SQLite` 这类词单看形状和类名一样），
     * 以及过短的标识符（`Id`、`Ok` 几乎不携带区分度，只会制造虚假连接）。
     */
    private fun isPlausibleSymbol(candidate: String): Boolean {
        if (candidate.length < MIN_SYMBOL_CHARS) return false
        if (candidate.length > MAX_SYMBOL_CHARS) return false
        if (candidate in STOP_WORDS) return false
        // 纯小写单词不是符号（符号要么有驼峰、要么全大写、要么带下划线）。
        if (candidate.all { it.isLowerCase() || it.isDigit() || it == '_' }) return false
        return true
    }

    /** 单字符符号没有区分度。 */
    private const val MIN_SYMBOL_CHARS = 6

    /** 超长「标识符」多半是把一整行连起来了（Markdown 表格、无空格长串）。 */
    private const val MAX_SYMBOL_CHARS = 60

    /** 文件名至少要有 `a.kt` 这么长，更短的通常是句末标点造成的误配。 */
    private const val MIN_FILE_NAME_CHARS = 4

    /**
     * 文件路径：以常见源文件扩展名结尾，允许路径分隔符与常见文件名符号。
     *
     * 这个正则刻意宽松（冒号、减号、点号都允许）——漏掉一个文件路径会缺一条强边，
     * 而多匹配一段文本只会产生一个没用的实体，前者代价更高。
     */
    private val FILE_EXTENSION_PATTERN = Regex(
        """[A-Za-z0-9_./\\-]+\.(kt|kts|java|xml|json|gradle|pro|properties|md|txt|sh|py|c|cpp|h|hpp|rs|go|js|ts|tsx|jsx|yml|yaml|toml|sql|smali|dex|apk|so|elf|bin|log|csv|html|css)""",
        RegexOption.IGNORE_CASE,
    )

    /** 反引号包裹的标识符，模型引用代码符号时的标准写法。 */
    private val BACKTICK_PATTERN = Regex("""`([A-Za-z_][A-Za-z0-9_]{2,})`""")

    /**
     * 驼峰命名：不论大小写开头，但**必须含词段边界**。
     *
     * 支持小写开头是必需的：Kotlin 里函数名、变量名几乎全是 `lowerCamelCase`
     * （`registerParallelSafe`、`recordDegradation`），只认大写开头会漏掉一大半符号。
     *
     * 但也不能放宽到"任意小写词"——那样 `document`、`because` 这类普通词全会进来。
     * 判据是**至少两个词段**：`registerParallelSafe` 有三个段，`document` 只有一个。
     * 单个普通英文词永远只有一段，因此天然被排除，不需要额外的词典。
     */
    private val CAMEL_PATTERN = Regex("""\b[a-zA-Z][a-z0-9]*(?:[A-Z][a-z0-9]+)+\b""")

    /** 全大写常量，至少两个单词用下划线连接。 */
    private val CONSTANT_PATTERN = Regex("""\b[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+\b""")

    /**
     * 常见技术词汇，形状像符号但没有区分度。
     *
     * 只收「几乎每篇技术文本都会出现」的词：这类词在任何两条观测里都共享，加进来
     * 等于给所有节点之间连上一条弱边，把图变成完全图。
     */
    private val STOP_WORDS = setOf(
        "Android", "Java", "Kotlin", "Python", "Linux", "Debian", "Ubuntu", "Google",
        "GitHub", "SQLite", "Room", "HTTP", "HTTPS", "JSON", "XML", "HTML", "CSS",
        "API", "SDK", "CLI", "UI", "IO", "URL", "URI", "CPU", "RAM", "Gradle",
        "Maven", "Compose", "Jetpack", "System", "Network", "Database", "Message",
        "Service", "Manager", "Handler", "Listener", "Adapter", "Controller",
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
        "January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December",
    )
}