package io.github.mangi.eta.agent.terminal

/**
 * 文本文件的按行读取与定点替换。
 *
 * Root 与普通身份两条通道共用这里的结果：通道只负责取回全文、写回结果，
 * 行号计算、匹配判定与差异摘要不依赖具体通道，便于单独验证。
 */
internal object FileTextOperations {
    /** 定点替换与差异对照都要先拿到全文，超过该长度时要求改用 terminal 通道处理。 */
    const val MAX_EDIT_BYTES = 256 * 1024
    const val DEFAULT_SEARCH_RESULTS = 50
    const val MAX_SEARCH_RESULTS = 500

    data class LineSlice(
        val text: String,
        val totalLines: Int,
        val firstLine: Int,
        val lastLine: Int,
        val truncated: Boolean,
    )

    sealed interface ReplaceOutcome {
        /** [occurrences] 与 [firstLine] 描述被替换的位置，供调用方回报结果。 */
        data class Applied(val content: String, val occurrences: Int, val firstLine: Int) : ReplaceOutcome

        data class NotFound(val totalLines: Int) : ReplaceOutcome

        /** 多处命中且未要求全部替换时，列出命中行供调用方补足上下文后重试。 */
        data class Ambiguous(val lines: List<Int>) : ReplaceOutcome
    }

    /** 与 BufferedReader.readLine 一致的行划分：末尾换行不产生额外空行，空内容为 0 行。 */
    fun linesOf(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        return content.split("\n").let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    }

    /**
     * 按 1 起始的行号截取内容，输出行首带真实行号。
     * [endLine] 为 null 表示读到文件末尾；超出实际行数时向实际末尾收敛。
     */
    fun sliceLines(content: String, startLine: Int, endLine: Int?, maxChars: Int = 16_000): LineSlice {
        val lines = linesOf(content)
        val totalLines = lines.size
        val start = startLine.coerceAtLeast(1)
        if (totalLines == 0 || start > totalLines) {
            return LineSlice("", totalLines, start, start, truncated = false)
        }
        val end = (endLine ?: totalLines).coerceAtLeast(start).coerceAtMost(totalLines)
        val builder = StringBuilder()
        var truncated = false
        var emitted = 0
        for (lineNumber in start..end) {
            val rendered = "$lineNumber\t${lines[lineNumber - 1]}\n"
            if (builder.length + rendered.length > maxChars) {
                truncated = true
                break
            }
            builder.append(rendered)
            emitted++
        }
        return LineSlice(
            text = builder.toString().trimEnd('\n'),
            totalLines = totalLines,
            firstLine = start,
            lastLine = start + emitted - 1,
            truncated = truncated || end < totalLines,
        )
    }

    /** 统计 [oldText] 在 [content] 中的出现行号；未命中返回空列表。 */
    fun occurrenceLines(content: String, oldText: String): List<Int> {
        if (oldText.isEmpty()) return emptyList()
        val lines = mutableListOf<Int>()
        var index = content.indexOf(oldText)
        while (index >= 0) {
            lines += lineNumberAt(content, index)
            index = content.indexOf(oldText, index + oldText.length)
        }
        return lines
    }

    /**
     * oldText 未命中时，挑一段最接近的原文回给调用方：按行比对与 oldText 首行的公共前缀长度
     * 取最佳行，带上前后各 [radius] 行与真实行号。
     *
     * 这样一次失败的替换里就带着可直接改写的原文，不必再花一轮 read_file 去核对——
     * 之前只回报"没有匹配"，调用方往往要重读整个文件才能定位差在哪。
     */
    fun nearestSnippet(content: String, oldText: String, radius: Int = 2, maxChars: Int = 800): String {
        val lines = linesOf(content)
        if (lines.isEmpty()) return ""
        val needle = oldText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        var bestIndex = -1
        var bestScore = 0
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val score = commonPrefixLength(trimmed, needle)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        if (bestIndex < 0) return ""
        val start = (bestIndex - radius).coerceAtLeast(0)
        val end = (bestIndex + radius).coerceAtMost(lines.size - 1)
        val builder = StringBuilder()
        for (index in start..end) {
            val rendered = "L${index + 1}: ${lines[index]}\n"
            if (builder.length + rendered.length > maxChars) break
            builder.append(rendered)
        }
        return builder.toString().trimEnd('\n')
    }

    /**
     * 多处命中时，给出每处命中及其前后 [radius] 行，行号带真实编号。
     *
     * 只回行号时调用方还得再读一次文件才能区分；每处附上上下文，一次就能补足出唯一片段。
     * 预算按「命中行优先」分配：每处先保证命中行本身出现，剩余额度才用来补上下文——否则
     * 一处超长上下文就能把后面的命中处全挤掉，调用方反而拿不到区分依据。
     */
    fun ambiguitySnippet(
        content: String,
        lineNumbers: List<Int>,
        radius: Int = 1,
        maxChars: Int = 1_200,
    ): String {
        val lines = linesOf(content)
        if (lines.isEmpty() || lineNumbers.isEmpty()) return ""
        val targets = lineNumbers.distinct().sorted().filter { line -> line - 1 in lines.indices }
        if (targets.isEmpty()) return ""
        val builder = StringBuilder()
        var shown = 0
        for (line in targets) {
            val block = snippetBlock(lines, line - 1, radius, maxChars - builder.length)
            if (block.isEmpty()) break
            builder.append(block)
            shown++
        }
        val text = builder.toString().trimEnd('\n')
        if (shown >= targets.size) return text
        val note = "（另有 ${targets.size - shown} 处命中因字符预算 $maxChars 未展示）"
        return if (text.isEmpty()) note else "$text\n$note"
    }

    /** 一处命中的展示块：命中行一定在内，上下文按「越近越优先」塞进剩余预算。 */
    private fun snippetBlock(lines: List<String>, index: Int, radius: Int, budget: Int): String {
        val header = "命中 @L${index + 1}：\n"
        val marker = snippetLine(">", index + 1, lines[index])
        if (header.length + marker.length > budget) return ""
        val builder = StringBuilder(header).append(marker)
        for (offset in 1..radius) {
            val before = index - offset
            if (before >= 0) {
                val text = snippetLine(" ", before + 1, lines[before])
                if (builder.length + text.length <= budget) builder.insert(header.length, text)
            }
            val after = index + offset
            if (after < lines.size) {
                val text = snippetLine(" ", after + 1, lines[after])
                if (builder.length + text.length <= budget) builder.append(text)
            }
        }
        return builder.toString()
    }

    /** 单行渲染；超长行截断，免得一行就吃掉整段预算。 */
    private fun snippetLine(marker: String, lineNumber: Int, text: String): String {
        val clipped = if (text.length <= SNIPPET_LINE_CHARS) text else text.take(SNIPPET_LINE_CHARS) + "…"
        return "$marker L$lineNumber: $clipped\n"
    }

    private const val SNIPPET_LINE_CHARS = 200

    /**
     * 指出 old_text 与文件里最接近那段的**第一处差异**。
     *
     * 引号、全角半角、缩进、不可见字符这类差异肉眼几乎看不出来——只回"最接近的原文"时调用方
     * 仍要反复比对；这里先按首行定位最接近的位置，再逐行比对，点出第一处不同的行号与字符。
     */
    fun describeFirstDifference(content: String, oldText: String): String {
        val lines = linesOf(content)
        if (lines.isEmpty()) return ""
        val needles = oldText.lines()
        val anchor = needles.firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        var bestIndex = -1
        var bestScore = 0
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val score = commonPrefixLength(trimmed, anchor)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        if (bestIndex < 0) return ""
        for ((offset, needle) in needles.withIndex()) {
            val index = bestIndex + offset
            if (index >= lines.size) {
                return "old_text 比文件长：文件到第 ${lines.size} 行结束，" +
                    "old_text 第 ${offset + 1} 行起没有对应内容"
            }
            val actual = lines[index]
            if (actual == needle) continue
            return "差异位置：第 ${index + 1} 行第 ${firstDifferenceAt(actual, needle)}"
        }
        return "old_text 从文件第 ${bestIndex + 1} 行起逐行都能对上，但整段未命中——" +
            "说明这段还出现在别处，或前后存在未比对到的内容"
    }

    /** 行内第一处差异，形如 `第 N 个字符起——文件里是 X，old_text 里是 Y`。 */
    private fun firstDifferenceAt(actual: String, needle: String): String {
        val limit = minOf(actual.length, needle.length)
        var position = 0
        while (position < limit && actual[position] == needle[position]) position++
        val actualChar = if (position < actual.length) describeChar(actual[position]) else "（行尾）"
        val needleChar = if (position < needle.length) describeChar(needle[position]) else "（行尾）"
        return "${position + 1} 个字符起——文件里是 $actualChar，old_text 里是 $needleChar"
    }

    private fun describeChar(char: Char): String = when {
        char == '\t' -> "'\\t'（制表符）"
        char == ' ' -> "空格"
        char.isWhitespace() -> "空白字符 U+${char.code.toString(16).uppercase().padStart(4, '0')}"
        char.code < 0x20 -> "控制字符 U+${char.code.toString(16).uppercase().padStart(4, '0')}"
        char.code > 0x7F -> "'$char'（U+${char.code.toString(16).uppercase().padStart(4, '0')}）"
        else -> "'$char'"
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return index
    }

    fun replace(content: String, oldText: String, newText: String, replaceAll: Boolean): ReplaceOutcome {
        val totalLines = linesOf(content).size
        if (oldText.isEmpty()) return ReplaceOutcome.NotFound(totalLines)
        val lines = occurrenceLines(content, oldText)
        return when {
            lines.isEmpty() -> ReplaceOutcome.NotFound(totalLines)
            lines.size > 1 && !replaceAll -> ReplaceOutcome.Ambiguous(lines.take(10))
            else -> ReplaceOutcome.Applied(
                content = if (replaceAll) content.replace(oldText, newText) else content.replaceFirst(oldText, newText),
                occurrences = lines.size,
                firstLine = lines.first(),
            )
        }
    }

    /**
     * 差异摘要：保留改动区间两侧各 [contextLines] 行，其余部分折叠成省略提示。
     * 仅用于回显改动，不参与写盘判定。
     */
    fun diffPreview(before: String, after: String, contextLines: Int = 3): String {
        val beforeLines = linesOf(before)
        val afterLines = linesOf(after)
        var prefix = 0
        val maxPrefix = minOf(beforeLines.size, afterLines.size)
        while (prefix < maxPrefix && beforeLines[prefix] == afterLines[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(beforeLines.size, afterLines.size) - prefix
        while (suffix < maxSuffix &&
            beforeLines[beforeLines.size - 1 - suffix] == afterLines[afterLines.size - 1 - suffix]
        ) {
            suffix++
        }
        val head = (prefix - contextLines).coerceAtLeast(0)
        val tailStart = (beforeLines.size - suffix + contextLines).coerceAtMost(beforeLines.size)
        val removedLines = beforeLines.size - suffix - prefix
        val addedLines = afterLines.size - suffix - prefix
        val builder = StringBuilder()
        if (head > 0) builder.append("...（前 $head 行未改动）\n")
        for (index in head until prefix) {
            builder.append("  ${index + 1}\t${beforeLines[index]}\n")
        }
        for (index in prefix until beforeLines.size - suffix) {
            builder.append("- ${index + 1}\t${beforeLines[index]}\n")
        }
        for (index in 0 until addedLines) {
            builder.append("+ ${prefix + index + 1}\t${afterLines[prefix + index]}\n")
        }
        if (removedLines == 0 && addedLines == 0) builder.append("（无内容变化）\n")
        for (index in (beforeLines.size - suffix) until tailStart) {
            builder.append("  ${index + 1}\t${beforeLines[index]}\n")
        }
        if (tailStart < beforeLines.size) builder.append("...（后 ${beforeLines.size - tailStart} 行未改动）\n")
        return builder.toString()
    }

    /** 把字符下标换算成 1 起始的行号。 */
    private fun lineNumberAt(content: String, index: Int): Int {
        var line = 1
        val limit = index.coerceAtMost(content.length)
        for (position in 0 until limit) {
            if (content[position] == '\n') line++
        }
        return line
    }
}
