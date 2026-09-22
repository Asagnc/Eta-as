package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.data.db.StaDatabase
import io.github.asagnc.sta.data.db.SubAgentMailboxEntity

/**
 * 共享信箱的读写门面。
 *
 * 存在的意义是把"限流、去重、裁剪"三件事收在一处：
 * 投递方（子智能体的工具、主智能体）只管说"我要留一条"，
 * 读方只管说"给我上次之后的新内容"，规则不会在调用点各写一遍。
 *
 * 全部方法都是 `suspend`：并行角色会从各自线程投递，串行化交给 Room 处理。
 */
internal class SubAgentMailbox(
    private val database: StaDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = database.subAgentMailboxDao()

    /** 投递结果，调用方据此告诉模型"到底存进去没有"。 */
    sealed interface Post {
        data class Stored(val id: Long) : Post

        /** 与已有留言重复，没有落库。 */
        data object Duplicate : Post

        /** 内容为空，没有落库。 */
        data object Empty : Post

        /** note 条数已达上限；结论（result）不会走到这里。 */
        data object Throttled : Post
    }

    suspend fun post(runId: String, author: String, kind: String, summary: String, body: String): Post {
        if (runId.isBlank()) return Post.Empty
        val sanitized = SubAgentMailboxPolicy.sanitize(kind, summary, body) ?: return Post.Empty
        val isResult = sanitized.kind == SubAgentMailboxPolicy.KIND_RESULT
        if (!isResult) {
            val existing = runCatching { dao.count(runId) }.getOrDefault(0)
            if (!SubAgentMailboxPolicy.canAcceptNote(existing)) return Post.Throttled
        }
        val key = SubAgentMailboxPolicy.dedupeKey(sanitized.summary)
        if (key.isNotEmpty()) {
            val duplicated = runCatching { dao.countBySummary(runId, sanitized.summary) }.getOrDefault(0)
            if (duplicated > 0) return Post.Duplicate
        }
        val id = runCatching {
            dao.insert(
                SubAgentMailboxEntity(
                    runId = runId,
                    author = author.take(MAX_AUTHOR_CHARS),
                    kind = sanitized.kind,
                    summary = sanitized.summary,
                    body = sanitized.body,
                    createdAt = now(),
                ),
            )
        }.getOrNull() ?: return Post.Empty
        // 裁剪失败不影响投递结果：投递已经成功，裁剪只是防止异常路径下的堆积。
        runCatching { dao.trim(runId, SubAgentMailboxPolicy.KEEP_PER_RUN) }
        return Post.Stored(id)
    }

    /**
     * 读 [sinceId] 之后的新留言，渲染成可直接注入提示的文本。
     *
     * 返回渲染文本与"读到哪里"，调用方保存游标即可实现增量读取。
     */
    suspend fun readSince(runId: String, sinceId: Long): Read {
        if (runId.isBlank()) return Read("", sinceId)
        val rows = runCatching {
            dao.since(runId, sinceId, SubAgentMailboxPolicy.MAX_READ_ENTRIES)
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return Read("", sinceId)
        val entries = rows.map { row ->
            SubAgentMailboxPolicy.Entry(
                author = row.author,
                kind = row.kind,
                summary = row.summary,
                body = row.body,
            )
        }
        return Read(SubAgentMailboxPolicy.render(entries), rows.last().id)
    }

    /** run 结束时清空，避免留言长期占用空间。 */
    suspend fun clear(runId: String) {
        if (runId.isBlank()) return
        runCatching { dao.clearRun(runId) }
    }

    data class Read(val text: String, val cursor: Long)

    private companion object {
        const val MAX_AUTHOR_CHARS = 60
    }
}
