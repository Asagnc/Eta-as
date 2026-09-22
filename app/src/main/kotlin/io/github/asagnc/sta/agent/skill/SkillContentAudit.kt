package io.github.asagnc.sta.agent.skill

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Skill 内容的静态特征审计：可执行脚本、二进制，以及脚本文本里出现的网络命令。
 *
 * 判定依据只有文件名后缀和脚本文本里的命令名，因此它是一份"提醒"而不是安全边界：
 * 既拦不住被混淆或改写过的脚本，也不保证脚本没有网络之外的副作用。它的用途是让安装前
 * 的确认有据可依，而不是替代人工审计。
 */
internal object SkillContentAudit {
    enum class Kind { SCRIPT, BINARY, DOCUMENT, OTHER }

    data class FileEntry(val relativePath: String, val sizeBytes: Long)

    data class Summary(
        val scripts: List<String>,
        val binaries: List<String>,
        val documents: Int,
        val others: Int,
        val totalBytes: Long,
        /** 脚本相对路径 → 命中的网络命令名，只包含实际读到的脚本文件。 */
        val networkMarkers: Map<String, List<String>> = emptyMap(),
    ) {
        val hasExecutableContent: Boolean get() = scripts.isNotEmpty() || binaries.isNotEmpty()

        val requiresConfirmation: Boolean get() = hasExecutableContent || networkMarkers.isNotEmpty()

        fun toJson(): JSONObject = JSONObject()
            .put("requires_confirmation", requiresConfirmation)
            .put("scripts", JSONArray(scripts))
            .put("binaries", JSONArray(binaries))
            .put("documents", documents)
            .put("other_files", others)
            .put("total_bytes", totalBytes)
            .put(
                "network_scripts",
                JSONObject().also { result ->
                    networkMarkers.forEach { (path, markers) -> result.put(path, JSONArray(markers)) }
                },
            )

        fun describe(): String {
            val parts = mutableListOf<String>()
            if (scripts.isNotEmpty()) parts += "可执行脚本 ${scripts.size} 个"
            if (binaries.isNotEmpty()) parts += "二进制或压缩包 ${binaries.size} 个"
            if (networkMarkers.isNotEmpty()) parts += "含网络命令的脚本 ${networkMarkers.size} 个"
            return if (parts.isEmpty()) "未发现脚本或二进制" else parts.joinToString("、")
        }
    }

    fun kind(relativePath: String): Kind {
        val name = relativePath.substringAfterLast('/').lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            extension in SCRIPT_EXTENSIONS -> Kind.SCRIPT
            extension in BINARY_EXTENSIONS -> Kind.BINARY
            extension in DOCUMENT_EXTENSIONS -> Kind.DOCUMENT
            else -> Kind.OTHER
        }
    }

    /** 命中的网络命令名，按固定顺序返回，便于比对与测试。 */
    fun networkMarkers(text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        return NETWORK_MARKERS.filter { lower.contains(it) }
    }

    /**
     * 汇总一个 Skill 根目录下的文件清单。[root] 使用仓库相对路径，`.` 表示仓库根目录；
     * [files] 里的路径同样是仓库相对路径。
     */
    fun summarize(root: String?, files: List<FileEntry>): Summary {
        val normalizedRoot = root?.trim()?.removeSuffix("/")?.takeUnless { it.isBlank() } ?: "."
        val prefix = normalizedRoot.takeUnless { it == "." }?.let { "$it/" }
        val scripts = mutableListOf<String>()
        val binaries = mutableListOf<String>()
        var documents = 0
        var others = 0
        var totalBytes = 0L
        files.forEach { file ->
            if (!belongsTo(file.relativePath, normalizedRoot)) return@forEach
            val relative = prefix?.let { file.relativePath.removePrefix(it) } ?: file.relativePath
            totalBytes += file.sizeBytes
            when (kind(relative)) {
                Kind.SCRIPT -> if (scripts.size < MAX_LISTED) scripts += relative
                Kind.BINARY -> if (binaries.size < MAX_LISTED) binaries += relative
                Kind.DOCUMENT -> documents += 1
                Kind.OTHER -> others += 1
            }
        }
        return Summary(
            scripts = scripts,
            binaries = binaries,
            documents = documents,
            others = others,
            totalBytes = totalBytes,
        )
    }

    /**
     * 对已下载的仓库归档按选中的 Skill 根目录做审计。
     *
     * GitHub 归档多一层顶层目录，安装器会剥掉它；这里用同样的规则处理，
     * 保证审计看到的相对路径与安装后的目录结构一致。
     */
    fun auditArchive(archive: File, selectedRoots: List<String>): Map<String, Summary> {
        val roots = selectedRoots.map(::normalizeRoot).distinct()
        if (roots.isEmpty()) return emptyMap()
        return runCatching {
            ZipFile(archive).use { zip ->
                val raw = mutableListOf<Pair<String, Long>>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    raw += entry.name to entry.size
                }
                val stripPrefix = commonRootPrefix(raw.map { it.first })
                val files = raw.map { (name, size) ->
                    ArchiveEntry(
                        name = name,
                        path = stripPrefix?.let(name::removePrefix) ?: name,
                        sizeBytes = size,
                    )
                }
                roots.associateWith { root ->
                    val scoped = files.filter { belongsTo(it.path, root) }
                    summarize(root = root, files = scoped.map { FileEntry(it.path, it.sizeBytes) })
                        .copy(networkMarkers = scanScripts(zip, scoped, root))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun scanScripts(
        zip: ZipFile,
        scoped: List<ArchiveEntry>,
        root: String,
    ): Map<String, List<String>> {
        val markers = linkedMapOf<String, List<String>>()
        var scannedBytes = 0L
        var scannedFiles = 0
        scoped.forEach { entry ->
            if (scannedFiles >= MAX_SCANNED_FILES || scannedBytes >= MAX_SCAN_BYTES_TOTAL) return@forEach
            val relative = relativeTo(entry.path, root)
            if (kind(relative) != Kind.SCRIPT) return@forEach
            val text = readText(zip, entry.name, MAX_SCAN_BYTES_PER_FILE) ?: return@forEach
            scannedFiles += 1
            scannedBytes += text.length
            val hits = networkMarkers(text)
            if (hits.isNotEmpty() && markers.size < MAX_LISTED) markers[relative] = hits
        }
        return markers
    }

    private fun readText(zip: ZipFile, name: String, limit: Int): String? = runCatching {
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { stream ->
            val buffer = ByteArray(limit)
            var read = 0
            while (read < limit) {
                val count = stream.read(buffer, read, limit - read)
                if (count <= 0) break
                read += count
            }
            String(buffer, 0, read, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun belongsTo(path: String, root: String): Boolean =
        root == "." || path == root || path.startsWith("$root/")

    private fun relativeTo(path: String, root: String): String =
        if (root == ".") path else path.removePrefix("$root/")

    private fun normalizeRoot(raw: String): String =
        raw.trim().removeSuffix("/").ifBlank { "." }

    /** GitHub 归档的顶层目录名，形如 `repo-branch`；顶层出现文件时不剥离。 */
    private fun commonRootPrefix(names: List<String>): String? {
        if (names.isEmpty()) return null
        val roots = names.mapNotNull { name ->
            val separator = name.indexOf('/')
            if (separator <= 0) null else name.substring(0, separator)
        }
        if (roots.size != names.size) return null
        val single = roots.distinct().singleOrNull() ?: return null
        return "$single/"
    }

    private data class ArchiveEntry(val name: String, val path: String, val sizeBytes: Long)

    private const val MAX_LISTED = 8
    private const val MAX_SCANNED_FILES = 40
    private const val MAX_SCAN_BYTES_PER_FILE = 128 * 1024
    private const val MAX_SCAN_BYTES_TOTAL = 2L * 1024 * 1024

    private val SCRIPT_EXTENSIONS = setOf(
        "sh", "bash", "zsh", "ksh", "py", "py3", "rb", "pl", "lua", "js", "mjs", "cjs", "ts",
        "ps1", "bat", "cmd", "expect", "fish",
    )

    private val BINARY_EXTENSIONS = setOf(
        "so", "dex", "jar", "apk", "bin", "exe", "dll", "class", "o", "a", "wasm", "elf", "ko",
        "zip", "tar", "gz", "xz", "bz2", "7z", "rar", "deb", "img", "iso",
    )

    private val DOCUMENT_EXTENSIONS = setOf(
        "md", "txt", "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "csv", "xml", "html",
    )

    private val NETWORK_MARKERS = listOf(
        "curl ", "wget ", "httpx", "requests.get", "requests.post", "urllib.request",
        "http.client", "fetch(", "xmlhttprequest", "axios.", "nc -", "ncat ", "socat ",
        "openssl s_client", "ssh ", "scp ", "rsync ", "git clone", "pip install", "npm install",
        "invoke-webrequest", "socket.connect", "net/http", "http://", "https://",
    )
}
