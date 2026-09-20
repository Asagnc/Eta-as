package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import org.json.JSONArray
import org.json.JSONObject

/** 普通身份只访问终端工作区、免 Root 环境和 Android 已授权的共享存储。 */
internal object UserFileAccess {
    private const val MAX_SCAN_BYTES = 512 * 1024L
    private const val MAX_SCAN_DEPTH = 8

    /** 写入通道的临时文件后缀，与 root 通道保持同一个命名约定。 */
    private const val TEMP_SUFFIX = FileToolLimits.WRITE_TEMP_SUFFIX

    /**
     * 检索时跳过的目录：免 Root 通道是纯 Kotlin 遍历，不像 root 通道那样能用 ripgrep
     * 自动尊重 .gitignore，不剪枝就会把构建产物和依赖目录整棵读一遍。
     * 传 no_ignore=true 时不再剪枝（与 root 通道的 --no-ignore 对齐）。
     */
    private val IGNORED_DIRECTORIES = setOf(
        ".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", "dist",
    )

    /** Linux 工具环境工作目录的别名；普通身份下它与 userWorkspacePath 指向同一份目录。 */
    private const val WORKSPACE_ALIAS = "/workspace"

    /** 带错误码的失败：让免 Root 通道也能回和 root 通道同一套 code。 */
    private class FileToolFailure(val code: String, message: String) : IllegalStateException(message)

    private fun fail(code: String, message: String): Nothing = throw FileToolFailure(code, message)

    fun resolve(path: String): File {
        val workspace = File(TerminalRuntime.userWorkspacePath)
        val raw = path.trim().ifBlank { workspace.absolutePath }
        val file = when {
            raw == "~" -> workspace
            raw.startsWith("~/") -> File(workspace, raw.removePrefix("~/"))
            // 免 Root 身份下文件工具也在 Android 命名空间里执行，而 /workspace 是 Linux 环境
            // 工作目录的写法：直接当绝对路径会落到允许范围之外被拒。
            raw == WORKSPACE_ALIAS -> workspace
            raw.startsWith("$WORKSPACE_ALIAS/") -> File(workspace, raw.removePrefix("$WORKSPACE_ALIAS/"))
            raw.startsWith('/') -> File(raw)
            else -> File(workspace, raw)
        }.canonicalFile
        val roots = listOf(workspace, File(workspace.parentFile, "proot"), File("/storage/emulated/0"))
        require(roots.any { root -> file.toPath().startsWith(root.canonicalFile.toPath()) }) { "路径不在普通终端可访问范围内" }
        return file
    }

    fun read(path: String, offsetBytes: Int, maxBytes: Int): String = operation {
        val file = resolve(path)
        if (!file.exists()) {
            return@operation JSONObject().put("ok", false).put("tool", "read_file")
                .put("code", "PATH_NOT_FOUND").put("message", PathHints.missingPathMessage(file))
        }
        if (file.isDirectory) {
            return@operation JSONObject().put("ok", false).put("tool", "read_file")
                .put("code", "NOT_A_FILE")
                .put("message", "路径是目录：${file.absolutePath}。列目录用 list_directory；要读文件请带上目录里的文件名")
        }
        require(file.canRead()) { "文件不可读：${file.absolutePath}" }
        val offset = offsetBytes.coerceAtLeast(0)
        val limit = maxBytes.coerceIn(1, FileToolLimits.MAX_READ_BYTES)
        // 多读一个字节：正好读满 limit 时无法区分「刚好读完」与「还有后续」。
        val probe = RandomAccessFile(file, "r").use { input ->
            input.seek(offset.toLong())
            val available = (input.length() - offset).coerceAtLeast(0)
            ByteArray(minOf(limit + 1L, available).toInt()).also { input.readFully(it) }
        }
        val byteTruncated = probe.size > limit
        val raw = if (byteTruncated) probe.copyOf(limit) else probe
        // 字节上限之内还可能撞上字符预算，两者都要如实回报，续读偏移才靠得住。
        val (text, charTruncated) = FileTextOperations.clipChars(
            raw.decodeToString(),
            FileToolLimits.MAX_OUTPUT_CHARS,
        )
        val bytesRead = text.toByteArray().size
        val hasMore = byteTruncated || charTruncated
        JSONObject().put("ok", true).put("tool", "read_file").put("path", file.absolutePath)
            .put("offset_bytes", offset).put("bytes_read", bytesRead).put("truncated", hasMore)
            .put("next_offset_bytes", if (hasMore) offset + bytesRead else JSONObject.NULL)
            .put("total_bytes", if (hasMore) file.length() else JSONObject.NULL)
            .put("content", text)
    }

    fun write(path: String, content: String, append: Boolean): String = operation {
        val file = resolve(path)
        val bytes = content.toByteArray()
        require(bytes.size <= FileToolLimits.MAX_WRITE_BYTES) { "写入内容过大" }
        val parent = requireNotNull(file.parentFile) { "目标没有父目录" }
        require(parent.mkdirs() || parent.isDirectory) { "目录不可创建" }
        require(!file.exists() || file.isFile) { "目标不是普通文件" }
        if (append) appendVerified(file, bytes) else replaceAtomically(file, bytes)
        JSONObject().put("ok", true).put("tool", "write_file").put("path", file.absolutePath)
            .put("mode", if (append) "append" else "overwrite").put("bytes_written", bytes.size)
            .put("verified", true)
    }

    fun readLines(path: String, startLine: Int, endLine: Int?, maxChars: Int): String = operation {
        val file = resolve(path)
        if (!file.exists()) {
            return@operation JSONObject().put("ok", false).put("tool", "read_file")
                .put("code", "PATH_NOT_FOUND").put("message", PathHints.missingPathMessage(file))
        }
        if (file.isDirectory) {
            return@operation JSONObject().put("ok", false).put("tool", "read_file")
                .put("code", "NOT_A_FILE")
                .put("message", "路径是目录：${file.absolutePath}。列目录用 list_directory；要读文件请带上目录里的文件名")
        }
        require(file.canRead()) { "文件不可读：${file.absolutePath}" }
        val start = startLine.coerceAtLeast(1)
        val limit = maxChars.coerceIn(1, FileToolLimits.MAX_OUTPUT_CHARS)
        val builder = StringBuilder()
        var totalLines = 0
        var emitted = 0
        var truncated = false
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                totalLines++
                val lineNumber = totalLines
                if (lineNumber < start || (endLine != null && lineNumber > endLine)) return@forEach
                val rendered = "$lineNumber\t$line\n"
                if (builder.length + rendered.length <= limit) {
                    builder.append(rendered)
                    emitted++
                } else {
                    truncated = true
                }
            }
        }
        if (start > totalLines) {
            return@operation JSONObject().put("ok", false).put("code", "LINE_OUT_OF_RANGE")
                .put("message", "起始行 $start 超出文件总行数 $totalLines")
        }
        val hasMore = truncated || (endLine != null && endLine < totalLines)
        val json = JSONObject().put("ok", true).put("tool", "read_file").put("path", file.absolutePath)
            .put("start_line", start)
            .put("end_line", start + emitted - 1)
            .put("total_lines", totalLines)
            .put("content", builder.toString().trimEnd('\n'))
            .put("truncated", hasMore)
            .put("next_start_line", if (hasMore) start + emitted else JSONObject.NULL)
        if (emitted == 0 && totalLines > 0) {
            json.put(
                "warning",
                "单行长度超过 max_chars=$limit，本轮没有输出任何行；请调大 max_chars，或改用字节模式 offset_bytes 续读",
            )
        }
        json
    }

    fun editFile(path: String, oldText: String, newText: String, replaceAll: Boolean): String = operation {
        val file = resolve(path)
        require(file.isFile && file.canRead()) { "文件不可读取" }
        require(file.length() <= FileTextOperations.MAX_EDIT_BYTES) { "文件超过定点替换上限" }
        val original = file.readText()
        when (val outcome = FileTextOperations.replace(original, oldText, newText, replaceAll)) {
            is FileTextOperations.ReplaceOutcome.NotFound -> JSONObject().put("ok", false)
                .put("code", "EDIT_NOT_FOUND")
                .put(
                    "message",
                    buildString {
                        append("没有匹配 old_text 的文本（文件共 ${outcome.totalLines} 行）")
                        val snippet = FileTextOperations.nearestSnippet(original, oldText)
                        if (snippet.isBlank()) {
                            append("；文件为空，或 old_text 与任何一行都没有公共前缀，请用 read_file 核对")
                        } else {
                            append("。最接近的原文（L 开头是行号）：\n")
                            append(snippet)
                            val difference = FileTextOperations.describeFirstDifference(original, oldText)
                            if (difference.isNotBlank()) append("\n").append(difference)
                            append("\n请按上面的原文修正 old_text 后重试")
                        }
                    },
                )
            is FileTextOperations.ReplaceOutcome.Ambiguous -> JSONObject().put("ok", false)
                .put("code", "EDIT_NOT_UNIQUE")
                .put(
                    "message",
                    buildString {
                        append("old_text 命中 ${outcome.lines.size} 处（行 ${outcome.lines.joinToString("、")}）；")
                        append("请补足上下文使其唯一，或设置 replace_all=true。各命中处上下文（> 为命中行）：\n")
                        append(FileTextOperations.ambiguitySnippet(original, outcome.lines))
                    },
                )
            is FileTextOperations.ReplaceOutcome.Applied -> {
                val bytes = outcome.content.toByteArray()
                require(bytes.size <= FileToolLimits.MAX_WRITE_BYTES) { "替换后内容过大" }
                replaceAtomically(file, bytes)
                JSONObject().put("ok", true).put("tool", "edit_file").put("path", file.absolutePath)
                    .put("replacements", outcome.occurrences)
                    .put("first_line", outcome.firstLine)
                    .put("bytes_written", bytes.size)
                    .put("verified", true)
                    .put("diff", FileTextOperations.diffPreview(original, outcome.content))
            }
        }
    }

    fun searchCode(
        path: String,
        pattern: String,
        glob: String?,
        maxResults: Int,
        contextLines: Int,
        maxChars: Int,
        filesOnly: Boolean,
        ignoreCase: Boolean,
        offset: Int,
        noIgnore: Boolean,
        hidden: Boolean,
    ): String = operation {
        val budget = maxChars.coerceIn(200, 32_000)
        val root = resolve(path)
        PathHints.missingPathMessage(root)?.let {
            return@operation JSONObject().put("ok", false).put("code", "PATH_NOT_FOUND").put("message", it)
        }
        val limit = maxResults.coerceIn(1, FileTextOperations.MAX_SEARCH_RESULTS)
        val skip = offset.coerceAtLeast(0)
        // 多取一条用来判断「还有下一页」，并把 offset 一起算进收集条数。
        val wanted = skip + limit + 1
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = runCatching { Regex(pattern, options) }.getOrNull()
            ?: throw IllegalArgumentException("正则表达式无效")
        val nameFilter = glob?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val base = root.absolutePath.trimEnd('/')
        val results = mutableListOf<String>()
        val perFile = linkedMapOf<String, Int>()
        var truncated = false
        var filteredOut = false
        val files = if (root.isFile) {
            sequenceOf(root)
        } else {
            root.walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .onEnter { directory ->
                    val allowed = directory == root ||
                        ((noIgnore || directory.name !in IGNORED_DIRECTORIES) && (hidden || !directory.name.startsWith('.')))
                    if (!allowed) filteredOut = true
                    allowed
                }
                .filter { it.isFile }
        }
        for (file in files) {
            if (hidden.not() && file.name.startsWith('.')) {
                filteredOut = true
                continue
            }
            if ((if (filesOnly) perFile.size else results.size) >= wanted) {
                truncated = true
                break
            }
            if (nameFilter != null && !nameFilter.matches(file.name)) continue
            if (file.length() > MAX_SCAN_BYTES) continue
            val relative = file.absolutePath.removePrefix("$base/")
            var lineNumber = 0
            var hits = 0
            runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        lineNumber++
                        if (regex.containsMatchIn(line)) {
                            hits++
                            if (!filesOnly) {
                                results += "$relative:$lineNumber:$line"
                                if (results.size >= wanted) return@useLines
                            }
                        }
                    }
                }
            }
            if (filesOnly && hits > 0) perFile[relative] = hits
        }
        val source = if (filesOnly) perFile.map { (file, count) -> "$file:$count" } else results
        val page = FileTextOperations.searchPage(source, skip, limit)
        val builder = StringBuilder()
        var emitted = 0
        for (line in page.lines) {
            if (builder.length + line.length + 1 > budget) break
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
            emitted++
        }
        val clipped = truncated || page.hasMore || emitted < page.lines.size
        JSONObject().put("ok", true).put("tool", "search_code")
            .put("mode", if (filesOnly) "files_only" else "lines")
            .put("path", root.absolutePath)
            .put("pattern", pattern)
            .put("glob", glob?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("offset", skip)
            .put(if (filesOnly) "match_files" else "match_lines", emitted)
            .put("results", builder.toString())
            .put("truncated", clipped)
            .put("next_offset", if (clipped) skip + emitted else JSONObject.NULL)
            .put(
                "hint",
                listOfNotNull(
                    if (clipped) {
                        "结果被截断：可以传 offset=${skip + emitted} 继续读下一批，或先加严 glob／缩小 path。"
                    } else {
                        null
                    },
                    if (source.isEmpty() && filteredOut) {
                        "没有命中：本次遍历跳过了隐藏文件或 build/.git 这类目录，要一起搜就传 no_ignore=true、hidden=true。"
                    } else {
                        null
                    },
                ).takeIf { it.isNotEmpty() }?.joinToString(" ") ?: JSONObject.NULL,
            )
    }

    /** 免 Root 通道的 find_files：与 root 通道返回同一份结构与同一套路径提示。 */
    fun findFiles(path: String, glob: String, limit: Int, noIgnore: Boolean, hidden: Boolean): String = operation {
        val root = resolve(path)
        PathHints.missingPathMessage(root)?.let {
            return@operation JSONObject().put("ok", false).put("tool", "find_files")
                .put("code", "PATH_NOT_FOUND").put("message", it)
        }
        val regex = globToRegex(glob)
        val capped = limit.coerceIn(1, 200)
        val found = mutableListOf<String>()
        var filteredOut = false
        val files = if (root.isFile) {
            sequenceOf(root)
        } else {
            root.walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .onEnter { directory ->
                    val allowed = directory == root ||
                        ((noIgnore || directory.name !in IGNORED_DIRECTORIES) && (hidden || !directory.name.startsWith('.')))
                    if (!allowed) filteredOut = true
                    allowed
                }
                .filter { it.isFile }
        }
        for (file in files) {
            if (hidden.not() && file.name.startsWith('.')) {
                filteredOut = true
                continue
            }
            if (found.size > capped) break
            if (regex.matches(file.name)) found += file.absolutePath
        }
        val truncated = found.size > capped
        JSONObject().put("ok", true).put("tool", "find_files").put("path", root.absolutePath)
            .put("glob", glob).put("count", minOf(found.size, capped))
            .put("files", JSONArray(found.take(capped))).put("truncated", truncated)
            .put(
                "hint",
                listOfNotNull(
                    if (truncated) "文件数超过上限；缩小 path、加严 glob 或提高 limit。" else null,
                    if (found.isEmpty() && filteredOut) {
                        "没有匹配的文件：本次遍历跳过了隐藏文件或 build/.git 这类目录，要一起找就传 no_ignore=true、hidden=true。"
                    } else {
                        null
                    },
                ).takeIf { it.isNotEmpty() }?.joinToString(" ") ?: JSONObject.NULL,
            )
    }

    fun list(path: String, showHidden: Boolean, limit: Int): String = operation {
        val directory = resolve(path)
        if (!directory.exists()) {
            return@operation JSONObject().put("ok", false).put("tool", "list_directory")
                .put("code", "PATH_NOT_FOUND").put("message", PathHints.missingPathMessage(directory))
        }
        val entries = requireNotNull(directory.listFiles()) { "目录不可读取" }
            .filter { showHidden || !it.name.startsWith('.') }.sortedBy { it.name }
        val selected = entries.take(limit.coerceIn(1, 200))
        val text = selected.joinToString("\n") { (if (it.isDirectory) "d " else "- ") + it.name }
        JSONObject().put("ok", true).put("tool", "list_directory").put("path", directory.absolutePath)
            .put("entry_count", entries.size)
            .put("truncated", selected.size < entries.size || text.length > FileToolLimits.MAX_OUTPUT_CHARS)
            .put("entries_text", text.take(FileToolLimits.MAX_OUTPUT_CHARS))
    }

    /**
     * 原子替换：同目录临时文件 → 校验长度与逐字节内容 → 继承原文件权限 → rename 顶替。
     * 任何一步失败都只删临时文件，原文件不动（`FileOutputStream(file, false)` 是「先截断再写」，
     * 中途失败会留下半截文件）。
     */
    private fun replaceAtomically(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: File("/")
        val temp = File(parent, ".${file.name}$TEMP_SUFFIX")
        temp.delete()
        try {
            FileOutputStream(temp, false).use { it.write(bytes) }
            if (temp.length() != bytes.size.toLong() || !temp.readBytes().contentEquals(bytes)) {
                fail("WRITE_VERIFY_SIZE", "落盘内容校验不通过（写入 ${bytes.size} 字节，实际 ${temp.length()} 字节），原文件未改动")
            }
            if (file.exists()) runCatching { copyPermissions(file, temp) }
            if (!temp.renameTo(file) && !(file.delete() && temp.renameTo(file))) {
                fail("WRITE_REPLACE_FAILED", "临时文件就位失败，原文件未改动")
            }
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }

    /**
     * 追加：O_APPEND 写入后按「长度 + 尾部逐字节比对」校验，失败用 setLength 退回原长度。
     * 追加不走临时文件 + rename：那要把整个旧文件复制一遍，往大日志尾巴上追加会白翻一倍磁盘。
     */
    private fun appendVerified(file: File, bytes: ByteArray) {
        val before = if (file.exists()) file.length() else 0L
        FileOutputStream(file, true).use { it.write(bytes) }
        val after = file.length()
        val tailMatches = runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(before)
                val tail = ByteArray(bytes.size)
                input.readFully(tail)
                tail.contentEquals(bytes)
            }
        }.getOrDefault(false)
        if (after != before + bytes.size || !tailMatches) {
            val rolledBack = runCatching {
                RandomAccessFile(file, "rw").use { it.setLength(before) }
            }.isSuccess
            if (rolledBack) {
                fail("WRITE_VERIFY_FAILED_ROLLED_BACK", "追加后校验不通过，已回退到追加前的长度（$before 字节）")
            }
            fail("WRITE_VERIFY_FAILED_ROLLBACK_FAILED", "追加后校验不通过，且回退失败；文件尾部可能有残缺字节，请用 read_file 复核")
        }
    }

    /** 继承原文件的 POSIX 权限；Windows 式文件系统上拿不到权限，失败就跳过。 */
    private fun copyPermissions(source: File, target: File) {
        val permissions = java.nio.file.Files.getPosixFilePermissions(source.toPath())
        java.nio.file.Files.setPosixFilePermissions(target.toPath(), permissions)
    }

    /**
     * 文件名 glob：* 与 ? 通配，其余字符按字面匹配。
     * 逐字符拼接而不是「先 Regex.escape 再替换」：Regex.escape 把整串包进 \Q...\E，
     * 里面的 * 不再是可替换的 "\*"，规则就永远匹配不上真实文件名。
     */
    private fun globToRegex(glob: String): Regex {
        val pattern = buildString {
            append('^')
            for (char in glob) {
                when (char) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(char.toString()))
                }
            }
            append('$')
        }
        return Regex(pattern)
    }

    private inline fun operation(block: () -> JSONObject): String = try {
        block().toString()
    } catch (failure: FileToolFailure) {
        error(failure.code, failure.message ?: "文件操作失败")
    } catch (_: java.io.IOException) {
        error("FILE_ACCESS_DENIED", "文件不可访问，请检查路径和文件授权")
    } catch (_: SecurityException) {
        error("FILE_ACCESS_DENIED", "文件访问未授权")
    } catch (failure: IllegalArgumentException) {
        error("INVALID_PATH", failure.message?.takeIf { it.isNotBlank() } ?: "路径或文件参数不在允许范围内")
    }

    private fun error(code: String, message: String): String = JSONObject().put("ok", false).put("code", code).put("message", message).toString()
}
