package io.github.asagnc.sta.agent.model

/**
 * 信箱投递的限流与清洗规则。
 *
 * 为什么需要限流：允许子智能体随时投递中间发现，等于给了它一个"每轮都能写"的出口。
 * 不设闸门的话，一个话多的角色能靠留言把别人的上下文塞满——省下的重复取证开销
 * 会被重新花掉，甚至更多。
 *
 * 纯函数，不碰数据库与文件系统。
 */
internal object SubAgentMailboxPolicy {

    /** 单个 run 的留言总数上限；超过后只接受 [KIND_RESULT]（收尾结论必须能落库）。 */
    const val MAX_NOTES_PER_RUN = 12

    /** 单条摘要长度上限；摘要进列表展示，太长就失去"一眼看清"的意义。 */
    const val MAX_SUMMARY_CHARS = 160

    /** 单条正文长度上限。 */
    const val MAX_BODY_CHARS = 1_200

    /** 一次读取最多注入多少条，避免信箱本身变成上下文负担。 */
    const val MAX_READ_ENTRIES = 6

    /** 读取时每条正文的截断长度。 */
    const val MAX_READ_BODY_CHARS = 400

    /** 保留窗口：表里最多留这么多条，异常路径下也不会无限增长。 */
    const val KEEP_PER_RUN = 40

    const val KIND_NOTE = "note"
    const val KIND_RESULT = "result"

    /** 主智能体投递时用的作者名。 */
    const val AUTHOR_MAIN = "main"

    /** 投递请求的清洗结果。 */
    data class Sanitized(
        val kind: String,
        val summary: String,
        val body: String,
    ) {
        val isEmpty: Boolean get() = summary.isEmpty() && body.isEmpty()
    }

    /**
     * 清洗投递内容。
     *
     * 摘要与正文都为空时返回 null：一条什么都没说的留言没有价值，
     * 存进去只会让后来的角色多读一段噪音。
     */
    fun sanitize(kind: String, summary: String, body: String): Sanitized? {
        val normalizedKind = if (kind.trim().equals(KIND_RESULT, ignoreCase = true)) {
            KIND_RESULT
        } else {
            KIND_NOTE
        }
        val cleanSummary = summary.trim().replace(WHITESPACE_RUN, " ").take(MAX_SUMMARY_CHARS)
        val cleanBody = body.trim().take(MAX_BODY_CHARS)
        val result = Sanitized(normalizedKind, cleanSummary, cleanBody)
        return result.takeUnless { it.isEmpty }
    }

    /** 是否还能接受新的 note。result 不受条数限制——结论丢了比噪音更糟。 */
    fun canAcceptNote(existingCount: Int): Boolean = existingCount < MAX_NOTES_PER_RUN

    /**
     * 摘要去重键：忽略大小写与多余空白后比较。
     *
     * 不同角色各自发现同一件事是常态（"这个函数在 v2 改名了"），
     * 存两遍对读的人没有增量价值。
     */
    fun dedupeKey(summary: String): String =
        summary.trim().lowercase().replace(WHITESPACE_RUN, " ")

    /**
     * 把信箱条目渲染成给子智能体看的文本。
     *
     * 明确标注"这是别人已经查过的结论，不要重复验证"——否则角色会出于谨慎
     * 把每条留言再核实一遍，反而更慢。
     */
    fun render(entries: List<Entry>): String {
        if (entries.isEmpty()) return ""
        return buildString {
            append("\n【同伴的已有发现】以下是同一轮里其它角色投递的内容，")
            append("直接用它们，不要重复验证：\n")
            entries.forEach { entry ->
                append("- [").append(entry.author).append("] ")
                append(entry.summary.ifBlank { entry.body.take(MAX_SUMMARY_CHARS) })
                if (entry.summary.isNotBlank() && entry.body.isNotBlank()) {
                    append("：").append(entry.body.take(MAX_READ_BODY_CHARS))
                }
                append('\n')
            }
        }
    }

    /** 渲染用的最小结构，与 DB 行解耦，方便单测。 */
    data class Entry(val author: String, val kind: String, val summary: String, val body: String)

    private val WHITESPACE_RUN = Regex("\\s+")
}
