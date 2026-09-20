package io.github.mangi.eta.agent.terminal

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONArray
import org.json.JSONObject

/** 普通身份只访问终端工作区、免 Root 环境和 Android 已授权的共享存储。 */
internal object UserFileAccess {
    private const val MAX_SCAN_BYTES = 512 * 1024L
    private const val MAX_SCAN_DEPTH = 8

    /**
     * 检索时跳过的目录：免 Root 通道是纯 Kotlin 遍历，不像 root 通道那样能用 ripgrep
     * 自动尊重 .gitignore，不剪枝就会把构建产物和依赖目录整棵读一遍。
     */
    private val IGNORED_DIRECTORIES = setOf(
        ".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", "dist",
    )

    /** Linux 工具环境工作目录的别名；普通身份下它与 userWorkspacePath 指向同一份目录。 */
    private const val WORKSPACE_ALIAS = "/workspace"

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
        require(file.isFile && file.canRead()) { "文件不可读取" }
        val offset = offsetBytes.coerceAtLeast(0)
        val limit = maxBytes.coerceIn(1, 16_000)
        val bytes = RandomAccessFile(file, "r").use { input ->
            input.seek(offset.toLong())
            ByteArray(minOf(limit.toLong(), (input.length() - offset).coerceAtLeast(0)).toInt()).also { input.readFully(it) }
        }
        val content = bytes.decodeToString()
        JSONObject().put("ok", true).put("tool", "read_file").put("path", file.absolutePath)
            .put("offset_bytes", offset).put("bytes_read", bytes.size).put("truncated", file.length() > offset.toLong() + bytes.size || content.length > 16_000)
            .put("content", content.take(16_000))
    }

    fun write(path: String, content: String, append: Boolean): String = operation {
        val file = resolve(path)
        val bytes = content.toByteArray()
        require(bytes.size <= 512 * 1024) { "写入内容过大" }
        require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) { "目录不可创建" }
        require(!file.exists() || file.isFile) { "目标不是普通文件" }
        java.io.FileOutputStream(file, append).use { it.write(bytes) }
        JSONObject().put("ok", true).put("tool", "write_file").put("path", file.absolutePath)
            .put("mode", if (append) "append" else "overwrite").put("bytes_written", bytes.size)
    }

    fun readLines(path: String, startLine: Int, endLine: Int?, maxChars: Int): String = operation {
        val file = resolve(path)
        require(file.isFile && file.canRead()) { "文件不可读取" }
        val start = startLine.coerceAtLeast(1)
        val limit = maxChars.coerceIn(1, 16_000)
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
        JSONObject().put("ok", true).put("tool", "read_file").put("path", file.absolutePath)
            .put("start_line", start)
            .put("end_line", start + emitted - 1)
            .put("total_lines", totalLines)
            .put("content", builder.toString().trimEnd('\n'))
            .put("truncated", truncated || (endLine != null && endLine < totalLines))
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
                require(bytes.size <= 512 * 1024) { "替换后内容过大" }
                java.io.FileOutputStream(file, false).use { it.write(bytes) }
                JSONObject().put("ok", true).put("tool", "edit_file").put("path", file.absolutePath)
                    .put("replacements", outcome.occurrences)
                    .put("first_line", outcome.firstLine)
                    .put("bytes_written", bytes.size)
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
    ): String = operation {
        val budget = maxChars.coerceIn(200, 32_000)
        val root = resolve(path)
        PathHints.missingPathMessage(root)?.let {
            return@operation JSONObject().put("ok", false).put("code", "PATH_NOT_FOUND").put("message", it)
        }
        val limit = maxResults.coerceIn(1, FileTextOperations.MAX_SEARCH_RESULTS)
        val regex = runCatching { Regex(pattern) }.getOrNull()
            ?: throw IllegalArgumentException("正则表达式无效")
        val nameFilter = glob?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val base = root.absolutePath.trimEnd('/')
        val results = mutableListOf<String>()
        val perFile = linkedMapOf<String, Int>()
        var truncated = false
        val files = if (root.isFile) {
            sequenceOf(root)
        } else {
            root.walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .onEnter { directory -> directory == root || directory.name !in IGNORED_DIRECTORIES }
                .filter { it.isFile }
        }
        for (file in files) {
            if (!filesOnly && results.size > limit) {
                truncated = true
                break
            }
            if (filesOnly && perFile.size > limit) {
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
                                if (results.size > limit) return@useLines
                            }
                        }
                    }
                }
            }
            if (filesOnly && hits > 0) perFile[relative] = hits
        }
        val source = if (filesOnly) perFile.map { (file, count) -> "$file:$count" } else results
        val builder = StringBuilder()
        var emitted = 0
        for (line in source.take(limit)) {
            if (builder.length + line.length + 1 > budget) break
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
            emitted++
        }
        val clipped = truncated || source.size > limit || emitted < source.take(limit).size
        JSONObject().put("ok", true).put("tool", "search_code")
            .put("mode", if (filesOnly) "files_only" else "lines")
            .put("path", root.absolutePath)
            .put("pattern", pattern)
            .put("glob", glob?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put(if (filesOnly) "match_files" else "match_lines", emitted)
            .put("results", builder.toString())
            .put("truncated", clipped)
            .put(
                "hint",
                if (clipped) "结果被截断：先 files_only=true 看命中分布，或缩小 path／加 glob／把 pattern 写具体。" else JSONObject.NULL,
            )
    }

    /** 免 Root 通道的 find_files：与 root 通道返回同一份结构与同一套路径提示。 */
    fun findFiles(path: String, glob: String, limit: Int): String = operation {
        val root = resolve(path)
        PathHints.missingPathMessage(root)?.let {
            return@operation JSONObject().put("ok", false).put("tool", "find_files")
                .put("code", "PATH_NOT_FOUND").put("message", it)
        }
        val regex = globToRegex(glob)
        val capped = limit.coerceIn(1, 200)
        val found = mutableListOf<String>()
        val files = if (root.isFile) {
            sequenceOf(root)
        } else {
            root.walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .onEnter { directory -> directory == root || directory.name !in IGNORED_DIRECTORIES }
                .filter { it.isFile }
        }
        for (file in files) {
            if (found.size > capped) break
            if (regex.matches(file.name)) found += file.absolutePath
        }
        val truncated = found.size > capped
        JSONObject().put("ok", true).put("tool", "find_files").put("path", root.absolutePath)
            .put("glob", glob).put("count", minOf(found.size, capped))
            .put("files", JSONArray(found.take(capped))).put("truncated", truncated)
            .put(
                "hint",
                if (truncated) "文件数超过上限；缩小 path、加严 glob 或提高 limit。" else JSONObject.NULL,
            )
    }

    fun list(path: String, showHidden: Boolean, limit: Int): String = operation {
        val directory = resolve(path)
        val entries = requireNotNull(directory.listFiles()) { "目录不可读取" }
            .filter { showHidden || !it.name.startsWith('.') }.sortedBy { it.name }
        val selected = entries.take(limit.coerceIn(1, 200))
        val text = selected.joinToString("\n") { (if (it.isDirectory) "d " else "- ") + it.name }
        JSONObject().put("ok", true).put("tool", "list_directory").put("path", directory.absolutePath)
            .put("exit_code", 0).put("stderr", "").put("truncated", selected.size < entries.size || text.length > 16_000)
            .put("entries_text", text.take(16_000))
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
    } catch (_: java.io.IOException) {
        error("FILE_ACCESS_DENIED", "文件不可访问，请检查路径和文件授权")
    } catch (_: SecurityException) {
        error("FILE_ACCESS_DENIED", "文件访问未授权")
    } catch (failure: IllegalArgumentException) {
        error("INVALID_PATH", failure.message?.takeIf { it.isNotBlank() } ?: "路径或文件参数不在允许范围内")
    }

    private fun error(code: String, message: String): String = JSONObject().put("ok", false).put("code", code).put("message", message).toString()
}
