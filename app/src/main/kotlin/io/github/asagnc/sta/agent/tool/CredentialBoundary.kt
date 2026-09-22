package io.github.asagnc.sta.agent.tool

/**
 * 凭据路径的读取边界。
 *
 * provider API key 明文存在 App 私有的 `databases/sta.db` 里，而 Agent 同时拥有 Root 与网络工具：
 * 一次提示注入就足以让它读出来并外发。这里把"凭据所在路径"与文件类工具的可见范围切开，
 * 拦下最直接的读取路径。
 *
 * 这是降低概率的措施，不是安全边界：Root Shell、终端命令与其它进程通道不受此限制，
 * 真正彻底的隔离需要把密钥移出可读文件（见凭据隔离的后续步骤）。
 */
internal object CredentialBoundary {
    /** 被拒绝的相对路径（相对 App 私有数据目录）。 */
    private val DENIED_PREFIXES = listOf(
        "databases/",
        "shared_prefs/",
        "files/datastore/",
    )

    /** 目录本身也被拒绝：列出 databases 目录同样等于给出凭据文件位置。 */
    private val DENIED_DIRECTORIES = setOf("databases", "shared_prefs")

    private val DENIED_FILE_NAMES = listOf("sta.db", "sta.db-wal", "sta.db-shm", "sta.db-journal")

    fun denies(dataDir: String, rawPath: String): Boolean {
        val normalizedDataDir = dataDir.trimEnd('/')
        if (normalizedDataDir.isBlank() || rawPath.isBlank()) return false
        val path = rawPath.trim().removePrefix("file://")
        val relative = when {
            path == normalizedDataDir -> ""
            path.startsWith("$normalizedDataDir/") -> path.removePrefix("$normalizedDataDir/")
            else -> return false
        }
        if (relative.isBlank()) return false
        if (DENIED_DIRECTORIES.any { directory -> relative == directory || relative.startsWith("$directory/") }) {
            return true
        }
        if (DENIED_PREFIXES.any { prefix -> relative.startsWith(prefix) }) return true
        return DENIED_FILE_NAMES.any { name -> relative.substringAfterLast('/') == name }
    }

    /** 路径未规范化时先按字面判断，避免 `..` 绕过前缀匹配。 */
    fun deniesLoosely(dataDir: String, rawPath: String): Boolean =
        denies(dataDir, rawPath) || rawPath.contains("..") && rawPath.contains("sta.db")

    fun message(): String =
        "凭据文件不在读取工具可见范围内；需要确认存储方式时请查看设置页，不要读取密钥文件"
}
