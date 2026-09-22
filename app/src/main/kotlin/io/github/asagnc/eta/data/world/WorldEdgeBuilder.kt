package io.github.asagnc.eta.data.world

/**
 * 从实体集合构建边的**纯逻辑**部分。
 *
 * 三层边按可靠性降序：
 *
 * 1. **file**：共享文件路径——最硬的证据，同一个文件就是同一个文件。
 * 2. **symbol**：共享代码符号——较强，但要防「同名不同物」（两个模块各有一个 `init`）。
 * 3. **keyword**：结论文本的关键词重叠——最弱，只用来连接那些既无文件也无符号的观测
 *    （例如「这个 provider 不支持某字段」这类纯经验结论）。
 *
 * 第四层 `semantic` 由 LLM 补断点，不在本类：那是有成本且可能出错的判断，必须与
 * 确定性的三层分开，否则一旦聚类结果不对，无法判断是哪一层的责任。
 *
 * 本类只做「哪些节点之间该有边、权重多少」的判断，不碰数据库，因此可以在纯 JVM 单测
 * 里把权重规则钉死。
 */
internal object WorldEdgeBuilder {

    /** 边的层次。 */
    enum class Layer(val value: String) {
        FILE("file"),
        SYMBOL("symbol"),
        KEYWORD("keyword"),

        /** 由 LLM 判断的语义边；本类不产出，仅作为取值定义。 */
        SEMANTIC("semantic"),

        ;

        companion object {
            fun from(value: String): Layer? = entries.firstOrNull { it.value == value }
        }
    }

    /**
     * 各层的基础权重。
     *
     * 数值取自「证据强度」的排序而不是随便定的：共享文件是同一性判断（几乎不可能出错），
     * 共享符号是强相关（偶有同名），关键词重叠是弱相关（只是用词相近）。三者的比例
     * 决定聚类边界落在哪里——文件权的权重若不够高，一个文件里的一堆符号会把不同模块
     * 的节点也拉进来。
     */
    const val WEIGHT_FILE = 3.0
    const val WEIGHT_SYMBOL = 1.5
    const val WEIGHT_KEYWORD = 0.5

    /**
     * 一条待落库的边。
     *
     * [srcId] 与 [dstId] 是**观测条目的 id**（不是实体 id）：聚类聚的是「哪些观测在讲
     * 同一件事」，实体只是用来判断这件事的媒介。这个区分很关键——如果边连的是实体，
     * 聚出来的就是「哪些概念相关」，而不是「哪些工作记录属于同一个领域」。
     */
    data class Planned(
        val srcId: String,
        val dstId: String,
        val layer: Layer,
        val weight: Double,
    )

    /**
     * 一条观测在图里的坐标。
     *
     * [files] 与 [symbols] 来自 [WorldEntityExtractor]，[keywords] 由 [keywordsOf] 从
     * 结论文本得出。
     */
    data class Node(
        val id: String,
        val files: Set<String>,
        val symbols: Set<String>,
        val keywords: Set<String>,
    )

    /**
     * 为一个节点的各层关系建边。
     *
     * 只与**已有节点**建边（[existing]），而不是两两全量重算：这是一次插入式的增量更新，
     * 新观测进来时看它和谁相连，而不是把整张图重建一遍。Zep 选 label propagation 而非
     * Leiden 也是同一个理由——增量扩展才是这个规模下可行的做法。
     *
     * 返回的边**不含重复**：同一对节点之间同一层只留一条，权重取最大值而不是相加
     * （相加会让反复观测同一件事的节点权重无限增长，最终吃掉整个图）。
     */
    fun planFor(
        node: Node,
        existing: List<Node>,
    ): List<Planned> {
        val planned = mutableListOf<Planned>()
        existing.forEach { other ->
            if (other.id == node.id) return@forEach
            // 每层各自只出一条边，取该层的最大重叠强度。
            fileWeight(node, other)?.let { planned.add(edge(node.id, other.id, Layer.FILE, it)) }
            symbolWeight(node, other)?.let { planned.add(edge(node.id, other.id, Layer.SYMBOL, it)) }
            keywordWeight(node, other)?.let { planned.add(edge(node.id, other.id, Layer.KEYWORD, it)) }
        }
        return planned
    }

    /** 共享文件：按共享数量缩放，但**封顶**——一个文件几十条观测共享时不该压倒一切。 */
    private fun fileWeight(a: Node, b: Node): Double? {
        val shared = a.files.intersect(b.files).size
        if (shared == 0) return null
        return WEIGHT_FILE * minOf(shared, MAX_SHARED_FILE_BONUS) / MAX_SHARED_FILE_BONUS
    }

    /** 共享符号：同样封顶，且门槛比文件高（符号是弱证据，需要更多重叠才算数）。 */
    private fun symbolWeight(a: Node, b: Node): Double? {
        val shared = a.symbols.intersect(b.symbols).size
        if (shared < MIN_SHARED_SYMBOLS) return null
        return WEIGHT_SYMBOL * minOf(shared, MAX_SHARED_SYMBOL_BONUS) / MAX_SHARED_SYMBOL_BONUS
    }

    /**
     * 关键词重叠：用 Jaccard 相似度，需超过阈值才算有边。
     *
     * 用 Jaccard 而不是「共享个数」：后者会让长结论天然与所有东西相连（它的词多），
     * 而 Jaccard 对两边长度都做了归一化，短结论之间高度重合才判定相关。
     */
    private fun keywordWeight(a: Node, b: Node): Double? {
        if (a.keywords.isEmpty() || b.keywords.isEmpty()) return null
        val intersection = a.keywords.intersect(b.keywords).size
        val union = a.keywords.union(b.keywords).size
        if (union == 0) return null
        val jaccard = intersection.toDouble() / union
        if (jaccard < MIN_KEYWORD_JACCARD) return null
        return WEIGHT_KEYWORD * jaccard
    }

    private fun edge(srcId: String, dstId: String, layer: Layer, weight: Double) = Planned(
        // 边是无向的，统一按字典序排两端：否则 (A,B) 与 (B,A) 会被当成两条不同的边，
        // 唯一索引也就挡不住重复了。
        srcId = minOf(srcId, dstId),
        dstId = maxOf(srcId, dstId),
        layer = layer,
        weight = weight,
    )

    /**
     * 从结论文本提取关键词。
     *
     * 只收长度足够的拉丁词与中文片段，并剔除停用词。刻意保持简单：这层是最弱的边，
     * 不值得为它引入分词器或词干化——那些会带来依赖，而收益只是让最弱的一层稍准一点。
     */
    fun keywordsOf(text: String, limit: Int = MAX_KEYWORDS): Set<String> {
        if (text.isBlank()) return emptySet()
        val tokens = mutableListOf<String>()
        // 英文/数字词：长度 4 以上才有区分度（`the`、`and` 这类全部落入长度过滤）。
        WORD_PATTERN.findAll(text).forEach { match ->
            val word = match.value.lowercase()
            if (word.length >= MIN_KEYWORD_CHARS && word !in STOP_WORDS) tokens.add(word)
        }
        // 中文：按二元切分（「文件工具」→ 文件/件工/工具）。不引入分词器，二元切分对
        // 「判断两段文本是否在讲相近的事」这个用途已经够用。
        CJK_PATTERN.findAll(text).forEach { match ->
            val run = match.value
            if (run.length < MIN_CJK_CHARS) return@forEach
            for (index in 0..run.length - 2) {
                tokens.add(run.substring(index, index + 2))
            }
        }
        // 按出现频次排序后截断：高频词更能代表这段文本在讲什么。
        return tokens.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
            .toSet()
    }

    /** 共享文件数量的计分上限：再多的重叠也不额外加分。 */
    private const val MAX_SHARED_FILE_BONUS = 3

    /** 共享符号数量的计分上限。 */
    private const val MAX_SHARED_SYMBOL_BONUS = 4

    /** 至少要共享几个符号才算有符号边：单个同名符号太容易是巧合。 */
    private const val MIN_SHARED_SYMBOLS = 2

    /** 关键词边的 Jaccard 门槛。 */
    private const val MIN_KEYWORD_JACCARD = 0.25

    /** 每个节点最多保留多少关键词。 */
    private const val MAX_KEYWORDS = 24

    private const val MIN_KEYWORD_CHARS = 4

    /** 中文片段至少这么长才切二元组（两字词只产出一个二元组，就是它自己）。 */
    private const val MIN_CJK_CHARS = 2

    private val WORD_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*")

    private val CJK_PATTERN = Regex("[\\u4e00-\\u9fa5]+")

    /** 英文停用词与通用技术词：它们在任何技术文本里都出现，不携带区分度。 */
    private val STOP_WORDS = setOf(
        "this", "that", "with", "from", "have", "has", "been", "were", "will",
        "would", "could", "should", "there", "their", "them", "they", "what",
        "when", "where", "which", "while", "about", "into", "than", "then",
        "some", "such", "only", "also", "more", "most", "much", "many", "very",
        "does", "doing", "done", "make", "made", "take", "taken", "give",
        "file", "files", "test", "tests", "code", "line", "lines", "note",
        "true", "false", "null", "void", "return", "value", "values", "type",
        "name", "names", "case", "cases", "used", "using", "use", "used",
        "result", "results", "error", "errors", "problem", "issue", "issues",
        "exist", "exists", "found", "need", "needs", "want", "wants",
        "https", "http", "www", "com", "org", "github",
    )
}