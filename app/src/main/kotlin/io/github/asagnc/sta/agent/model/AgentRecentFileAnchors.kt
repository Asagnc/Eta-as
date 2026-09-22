package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 被压缩掉的历史里最近访问过的文件。
 *
 * 摘要会丢掉“具体在哪些文件上工作”这类细节，这里只留路径当锚点：模型据此知道该回去看哪里，
 * 而不用把文件内容重新塞进摘要。上限取 5 个，与主流客户端（Claude Code）的做法一致。
 */
internal object AgentRecentFileAnchors {
    const val MAX_ANCHORS = 5

    /** 会被记入锚点的文件类工具：只认带 path 参数的那些。 */
    private val FILE_TOOL_NAMES = setOf(
        "read_file",
        "edit_file",
        "write_file",
        "search_code",
        "list_directory",
        "find_files",
    )

    /**
     * 从新到旧扫描 [messages] 里的工具调用，收集最近访问过的文件路径并去重。
     * 参数不是合法 JSON、或调用的不是文件类工具时直接跳过。
     */
    fun collect(messages: JSONArray, limit: Int = MAX_ANCHORS): List<String> {
        if (limit <= 0) return emptyList()
        val paths = LinkedHashSet<String>()
        for (index in messages.length() - 1 downTo 0) {
            val toolCalls = messages.optJSONObject(index)?.optJSONArray("tool_calls") ?: continue
            for (callIndex in toolCalls.length() - 1 downTo 0) {
                val function = toolCalls.optJSONObject(callIndex)?.optJSONObject("function") ?: continue
                if (function.optString("name").trim() !in FILE_TOOL_NAMES) continue
                val path = pathOf(function.opt("arguments"))
                if (path.isNotBlank()) paths += path
                if (paths.size >= limit) return paths.toList()
            }
        }
        return paths.toList()
    }

    /** 渲染成摘要末尾的一段锚点文本；没有访问过文件时返回空串。 */
    fun render(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        return "\n\n[最近访问的文件（摘要前）：${paths.joinToString("、")}；需要细节时重新调用 read_file。]"
    }

    private fun pathOf(arguments: Any?): String = when (arguments) {
        is JSONObject -> arguments.optString("path").trim()
        is String -> runCatching { JSONObject(arguments).optString("path").trim() }.getOrDefault("")
        else -> ""
    }
}
