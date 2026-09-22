package io.github.asagnc.sta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 并行子智能体之间的共享信箱。
 *
 * 为什么需要它：并行角色最大的浪费是各自重复发现同一件事（同一个函数被三个人各查一遍）。
 * 允许投递中间发现，能让后来的角色直接站在前人的结论上，省下的是重复取证的开销。
 *
 * 两条设计约束：
 * - **按 run 隔离**：`run_id` 参与主键。上一轮 run 的留言不能影响这一轮，
 *   否则"共享"会变成"污染"，而且很难排查。
 * - **投递要限流**：写侧由 [SubAgentMailboxPolicy] 限制条数与单条长度；
 *   表本身再加一层保留窗口（见 `trim`），防止异常路径下无限增长。
 */
@Entity(
    tableName = "sub_agent_mailbox",
    indices = [Index(value = ["run_id", "created_at"])],
)
internal data class SubAgentMailboxEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "run_id") val runId: String,
    /** 投递者角色；`main` 表示主智能体。 */
    @ColumnInfo(name = "author") val author: String,
    /** `note`（中间发现）或 `result`（收尾结论）。 */
    @ColumnInfo(name = "kind") val kind: String,
    /** 一句话摘要，用于列表展示与去重。 */
    @ColumnInfo(name = "summary") val summary: String,
    /** 正文；可为空，只有摘要时省空间。 */
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 读取时只需要这几个字段，避免把整行搬进内存。 */
internal data class SubAgentMailboxRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "author") val author: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
internal interface SubAgentMailboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SubAgentMailboxEntity): Long

    /**
     * 读某个 run 的留言。
     *
     * `sinceId` 让角色只读自己上次读到之后的新内容——不重读旧留言，
     * 既省 token 也避免同一段内容在上下文里出现两遍。
     */
    @Query(
        "SELECT id, author, kind, summary, body, created_at FROM sub_agent_mailbox " +
            "WHERE run_id = :runId AND id > :sinceId ORDER BY id ASC LIMIT :limit"
    )
    suspend fun since(runId: String, sinceId: Long, limit: Int): List<SubAgentMailboxRow>

    /** 同一 run 内的条数，用于限流判断。 */
    @Query("SELECT COUNT(*) FROM sub_agent_mailbox WHERE run_id = :runId")
    suspend fun count(runId: String): Int

    /** 摘要去重：同一句话不必存两遍（不同角色可能各自发现同一件事）。 */
    @Query("SELECT COUNT(*) FROM sub_agent_mailbox WHERE run_id = :runId AND summary = :summary")
    suspend fun countBySummary(runId: String, summary: String): Int

    /** 保留窗口裁剪；`sub_agent_runs` 的同类做法。 */
    @Query(
        "DELETE FROM sub_agent_mailbox WHERE run_id = :runId AND id NOT IN " +
            "(SELECT id FROM sub_agent_mailbox WHERE run_id = :runId ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun trim(runId: String, keep: Int)

    /** run 结束时清空，避免留言长期占用空间。 */
    @Query("DELETE FROM sub_agent_mailbox WHERE run_id = :runId")
    suspend fun clearRun(runId: String)
}
