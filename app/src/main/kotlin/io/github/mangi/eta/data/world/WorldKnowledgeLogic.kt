package io.github.mangi.eta.data.world

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 知识条目的纯逻辑部分：序列化、去重判据、新鲜度校验。
 *
 * 单独成文件而不是塞进 [WorldKnowledgeStore]，是为了让这些判据能在纯 JVM 单测里覆盖
 * ——它们决定"读回来的历史结论还算不算数"，错了会静默污染后续推理，必须有测试守着。
 */
internal object WorldKnowledgeLogic {

    /** 依赖文件在入库时的指纹。 */
    data class Dependency(
        val path: String,
        val fingerprint: String,
    )

    /** 新鲜度校验结果。 */
    enum class Freshness {
        /** 依赖没变，结论仍然成立。 */
        FRESH,

        /** 依赖已变更，结论可能失效。 */
        STALE,

        /** 依赖文件已不存在。 */
        MISSING,
    }

    /**
     * 把依赖列表编码成 JSON 数组。
     *
     * 空列表编码成空串而不是 `[]`：数据库里空串代表"没有依赖"，读回时不用区分
     * "空数组"和"没有"两种情况。
     */
    fun encodeDependencies(dependencies: List<Dependency>): String {
        if (dependencies.isEmpty()) return ""
        val array = JSONArray()
        dependencies.forEach { dependency ->
            array.put(
                JSONObject()
                    .put("path", dependency.path)
                    .put("fingerprint", dependency.fingerprint),
            )
        }
        return array.toString()
    }

    /** 解码依赖列表。解不开的条目跳过而不是整段丢弃——单条坏数据不该让整个结论失效。 */
    fun decodeDependencies(encoded: String): List<Dependency> {
        if (encoded.isBlank()) return emptyList()
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<Dependency>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val path = item.optString("path")
            if (path.isBlank()) continue
            result.add(Dependency(path = path, fingerprint = item.optString("fingerprint")))
        }
        return result
    }

    /**
     * 文件指纹：内容长度 + SHA-256 前 16 位十六进制。
     *
     * 不用 `mtime` 而用内容：`git checkout`、格式化、复制都会改 mtime 但内容不变，
     * 那会误判为"结论失效"；反过来 mtime 精度也可能让真改动看不出来。
     */
    fun fingerprint(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        val hex = digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
        return "${content.length}:$hex"
    }

    /**
     * 判断一条历史结论现在还算不算数。
     *
     * 依赖为空时恒为 [Freshness.FRESH]——没有依赖的结论（例如"这个 provider 不支持某字段"）
     * 不受文件变更影响。
     */
    fun checkFreshness(
        dependencies: List<Dependency>,
        readContent: (String) -> String?,
    ): Freshness {
        if (dependencies.isEmpty()) return Freshness.FRESH
        dependencies.forEach { dependency ->
            val content = readContent(dependency.path) ?: return Freshness.MISSING
            if (fingerprint(content) != dependency.fingerprint) return Freshness.STALE
        }
        return Freshness.FRESH
    }

    /**
     * 把证据里的文件路径变成依赖指纹。
     *
     * 这一步是「世界可信」的关键：没有依赖指纹，历史结论只是一条不可验证的笔记；
     * 有了它，回读时能判断「这条结论引用的文件是否已改」，过期结论不会被当作事实注入。
     *
     * 读不到的文件直接跳过，而不是记一条空指纹——空指纹会让新鲜度校验永远判为 STALE，
     * 那等于把这条结论永久作废，比不记更糟。
     */
    fun dependenciesFor(
        paths: List<String>,
        readContent: (String) -> String?,
    ): List<Dependency> = paths.mapNotNull { path ->
        val content = readContent(path) ?: return@mapNotNull null
        Dependency(path = path, fingerprint = fingerprint(content))
    }

    /**
     * 归一证据里的文件路径，使其可用于读取。
     *
     * 子智能体按提示可能写 `/workspace/x`（Linux 侧）或 `/data/local/tmp/eta/x`（Android 侧），
     * 两者是同一个文件。相对路径按工作区根拼。返回 null 表示不是可读的绝对/相对文件引用。
     */
    fun normalizePath(path: String, workspaceRoot: String): String? {
        val trimmed = path.trim().trim('`')
        if (trimmed.isEmpty()) return null
        val android = trimmed.replace(LINUX_WORKSPACE_PREFIX, ANDROID_WORKSPACE_PREFIX)
        return when {
            android.startsWith(ANDROID_WORKSPACE_PREFIX) -> android
            android.startsWith("/") -> android
            else -> "$workspaceRoot/$android"
        }
    }

    private const val LINUX_WORKSPACE_PREFIX = "/workspace"
    private const val ANDROID_WORKSPACE_PREFIX = "/data/local/tmp/eta"

    /**
     * 判断一条新观测是否值得落库。
     *
     * 同签名已存在且内容未变时不重复写——重复观测不带来新信息，只会挤占查询窗口。
     */
    fun shouldWrite(
        kind: String,
        signature: String,
        summary: String,
        existing: WorldKnowledgeEntity?,
    ): Boolean {
        if (existing == null) return true
        if (existing.kind != kind || existing.signature != signature) return true
        return existing.summary != summary
    }
}
