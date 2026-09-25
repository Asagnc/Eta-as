package io.github.asagnc.sta.agent.terminal

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

    /**
     * 按字符预算裁剪文本，返回「裁后文本 + 是否还有剩余」。
     *
     * 不切在代理对中间：切一半会生成孤立代理，编码回字节时变成替换字符，续读偏移也就跟着偏了。
     * 多字节字符在字节层被切开时由解码方补替换字符，这里只保证字符层不产生新的坏边界。
     */
    fun clipChars(text: String, maxChars: Int): Pair<String, Boolean> {
        if (maxChars <= 0) return "" to text.isNotEmpty()
        if (text.length <= maxChars) return text to false
        var end = maxChars
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end) to true
    }

    /** 目录列表的解析结果：展示文本、目录内条目总数、是否因条目上限被截断。 */
    data class DirectoryListing(val text: String, val entryCount: Int, val truncated: Boolean)

    /**
     * 解析目录列表。
     *
     * `ls -l` 的首行是 `total N`（磁盘块统计，不是条目数），命令侧已用 `tail -n +2` 去掉，
     * 这里再兜一层：只丢**首行**的 `total`，免得某个文件名恰好以 "total " 开头时被误删。
     * [entryCount] 由命令侧单独统计（同一次 `ls` 的条目数），[maxEntries] 之外的部分不展示。
     */
    fun directoryListing(entriesText: String, entryCount: Int, maxEntries: Int): DirectoryListing {
        val lines = entriesText.lines().filter { it.isNotBlank() }
        val withoutTotal = if (lines.firstOrNull()?.startsWith("total ") == true) lines.drop(1) else lines
        val shown = withoutTotal.take(maxEntries.coerceAtLeast(0))
        return DirectoryListing(
            text = shown.joinToString("\n"),
            entryCount = entryCount,
            truncated = entryCount > shown.size,
        )
    }

    /** 一页命中：本页行、是否还有下一页。 */
    data class SearchPage(val lines: List<String>, val hasMore: Boolean)

    /**
     * 命中分页。命令侧按 `offset + limit + 1` 条取回，多取的那一条只用来判断"还有下一页"。
     * 截断判定必须把 offset 算进去：否则翻到最后一页时会因为总量大于 limit 而谎报还有下一页。
     */
    fun searchPage(lines: List<String>, offset: Int, limit: Int): SearchPage {
        val start = offset.coerceAtLeast(0)
        val page = lines.drop(start).take(limit.coerceAtLeast(1))
        return SearchPage(page, lines.size > start + page.size)
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

    /** 一次批量替换里针对单个文件的编辑请求。 */
    data class EditRequest(val path: String, val oldText: String, val newText: String, val replaceAll: Boolean)

    /** 单文件校验失败：带文件路径、错误码与可直接回给调用方的说明。 */
    data class EditFailure(val path: String, val code: String, val message: String)

    /** 批量替换的校验结果：全部通过时 [plan] 才非空。 */
    data class EditPlan(val plan: List<PlannedEdit>, val failures: List<EditFailure>) {
        val isComplete: Boolean get() = failures.isEmpty()
    }

    /** 校验通过的单文件改动，[content] 是替换后的全文。 */
    data class PlannedEdit(val request: EditRequest, val content: String, val occurrences: Int, val firstLine: Int)

    /**
     * 批量替换的校验阶段：只读全文、只判定，不写盘。
     *
     * 返回的 [EditPlan.isComplete] 为 false 时调用方必须整体放弃，一个文件都不写——
     * 批量改动里写一半留下的中间状态，比整批失败更难排查：调用方看到的是"部分成功"，
     * 却拿不到"哪些已经生效"的可靠依据。同一路径在一次请求里出现多次时按顺序叠加，
     * 让后一条基于前一条的结果继续匹配。
     *
     * @param contents 按 [EditRequest.path] 取到的原文；取不到（不存在、不可读、超限）由调用方
     *   预先记成 [EditFailure]，这里只处理已成功取到内容的路径。
     */
    fun planEdits(
        requests: List<EditRequest>,
        contents: Map<String, String>,
        loadFailure: (EditRequest) -> EditFailure?,
    ): EditPlan {
        val failures = mutableListOf<EditFailure>()
        val planned = linkedMapOf<String, PlannedEdit>()
        for (request in requests) {
            loadFailure(request)?.let {
                failures += it
                continue
            }
            val original = planned[request.path]?.content ?: contents[request.path]
            if (original == null) {
                failures += EditFailure(request.path, "PATH_NOT_FOUND", "读取不到文件内容：${request.path}")
                continue
            }
            if (request.oldText.isEmpty()) {
                failures += EditFailure(request.path, "INVALID_ARGUMENT", "old_text 不能为空")
                continue
            }
            when (val outcome = replace(original, request.oldText, request.newText, request.replaceAll)) {
                is ReplaceOutcome.NotFound -> failures += EditFailure(
                    request.path,
                    "EDIT_NOT_FOUND",
                    notFoundMessage(original, request.oldText, outcome.totalLines),
                )
                is ReplaceOutcome.Ambiguous -> failures += EditFailure(
                    request.path,
                    "EDIT_NOT_UNIQUE",
                    ambiguousMessage(original, outcome.lines),
                )
                is ReplaceOutcome.Applied -> planned[request.path] = PlannedEdit(
                    request = request,
                    content = outcome.content,
                    occurrences = outcome.occurrences,
                    firstLine = outcome.firstLine,
                )
            }
        }
        // 有一条失败就整批作废：调用方只看 plan 也不该看到"可以写"的条目。
        if (failures.isNotEmpty()) return EditPlan(plan = emptyList(), failures = failures)
        return EditPlan(plan = planned.values.toList(), failures = failures)
    }

    /**
     * old_text 未命中时的说明：给出最接近的原文与首处差异，调用方据此修正后重试。
     *
     * 单文件的两条通道与批量规划此前各写了一份，措辞已经开始漂移；同一类失败在模型看来
     * 必须是同一句话，所以文案收在这里，调用方只负责把它放进结果。
     */
    fun notFoundMessage(original: String, oldText: String, totalLines: Int): String = buildString {
        append("没有匹配 old_text 的文本（文件共 $totalLines 行）")
        val snippet = nearestSnippet(original, oldText)
        if (snippet.isBlank()) {
            append("；文件为空，或 old_text 与任何一行都没有公共前缀，请用 read_file 核对")
        } else {
            append("。最接近的原文（L 开头是行号）：\n")
            append(snippet)
            val difference = describeFirstDifference(original, oldText)
            if (difference.isNotBlank()) append("\n").append(difference)
            append("\n请按上面的原文修正 old_text 后重试")
        }
    }

    /**
     * diff 预览进模型前的裁剪。
     *
     * diff 是模型可见载荷，长 diff 会把真正的上下文挤掉；裁剪时必须带上原文长度——只写
     * `...[truncated]` 的话，调用方既不知道被砍了多少，也没法判断还要不要继续读。
     * 两条通道共用这里，避免一个裁一个不裁。
     */
    fun diffPreviewForPayload(original: String, updated: String, maxChars: Int = 16_000): String {
        val preview = diffPreview(original, updated)
        return if (preview.length <= maxChars) {
            preview
        } else {
            preview.take(maxChars) + "\n...[truncated: 已保留前 $maxChars 字符，原文共 ${preview.length} 字符]"
        }
    }

    /**
     * old_text 多处命中时的说明：给出命中行号与两种收窄方式（补上下文 / replace_all）。
     */
    fun ambiguousMessage(original: String, lines: List<Int>): String = buildString {
        append("old_text 命中 ${lines.size} 处（行 ${lines.joinToString("、")}）。")
        append("二选一：① 在 old_text 里带上相邻行，让它在文件中只出现一次；")
        append("② 若这 ${lines.size} 处都该改，就显式传 replace_all=true（会把 ${lines.size} 处全部替换）。")
        append("各命中处上下文（> 为命中行）：\n")
        append(ambiguitySnippet(original, lines))
    }

    /**
     * 批量读取的分节拼接：每个文件占一段，段头带路径与总行数，段内行号沿用真实行号。
     *
     * 总字符预算在文件之间共享（不像单文件读取那样每个文件各自 16k），单文件超出剩余预算时
     * 只给头部若干行并标记 [FileSection.truncated]，由调用方按 next_start_line 续读——
     * 一次读五个文件本该是一轮，但把五个文件的全文都塞进一轮就等于用上下文换往返，
     * 这里的取舍是宁可少给几行。
     */
    data class FileSection(
        val path: String,
        val text: String,
        val totalLines: Int,
        val firstLine: Int,
        val lastLine: Int,
        val truncated: Boolean,
        val nextStartLine: Int?,
    )

    fun joinSections(
        sections: List<Pair<String, LineSlice>>,
        budget: Int,
    ): Pair<String, List<FileSection>> {
        val builder = StringBuilder()
        val emitted = mutableListOf<FileSection>()
        for ((path, slice) in sections) {
            val lines = if (slice.text.isEmpty()) emptyList() else slice.text.split('\n')
            val header = "=== $path（共 ${slice.totalLines} 行）===\n"
            if (builder.length + header.length > budget) {
                emitted += FileSection(path, "", slice.totalLines, 0, 0, true, 1)
                continue
            }
            builder.append(header)
            var written = 0
            for (line in lines) {
                val rendered = "$line\n"
                if (builder.length + rendered.length > budget) break
                builder.append(rendered)
                written++
            }
            val sectionTruncated = slice.truncated || written < lines.size
            emitted += FileSection(
                path = path,
                text = lines.take(written).joinToString("\n"),
                totalLines = slice.totalLines,
                firstLine = if (written == 0) 0 else slice.firstLine,
                lastLine = if (written == 0) slice.firstLine - 1 else slice.firstLine + written - 1,
                truncated = sectionTruncated,
                nextStartLine = if (!sectionTruncated) {
                    null
                } else if (written == 0) {
                    slice.firstLine
                } else {
                    slice.firstLine + written
                },
            )
        }
        return builder.toString().trimEnd('\n') to emitted
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
