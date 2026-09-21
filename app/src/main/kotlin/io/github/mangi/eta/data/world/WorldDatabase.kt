package io.github.mangi.eta.data.world

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Eta 的观测层。
 *
 * 和 `EtaDatabase`（用户资产：会话、方案、清单、provider 配置）分开成两个库，理由是两者的
 * 生命周期不同：用户数据要长期保留、要跟随备份；这里存的是运行副产物（工具失败教训、
 * 子智能体调研结论），可清可重建。
 *
 * 分开的第二个好处是**不需要迁移链**：本库 schema 变动时直接 [dropAllTables] 重建，
 * 不需要写 `MIGRATION_x_y`，也就不可能出现"迁移写错静默清掉用户数据"。
 */
@Entity(
    tableName = "world_knowledge",
    indices = [
        Index(value = ["kind", "signature", "created_at"]),
        Index(value = ["expires_at"]),
    ],
)
internal data class WorldKnowledgeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** 知识种类。新种类只增加取值，不改表结构。 */
    @ColumnInfo(name = "kind") val kind: String,
    /** 去重与检索键：同类同签名视为同一条知识。 */
    @ColumnInfo(name = "signature") val signature: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** 过期时刻；0 表示不过期。 */
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "origin_run") val originRun: String,
    @ColumnInfo(name = "origin_session") val originSession: String,
    @ColumnInfo(name = "origin_agent") val originAgent: String,
    /** 结论正文，注入时用的精炼文本。 */
    @ColumnInfo(name = "summary") val summary: String,
    /** 证据引用，每行一条（`路径:行号` 或 `命令 → 输出片段`）。 */
    @ColumnInfo(name = "evidence") val evidence: String,
    @ColumnInfo(name = "uncertainty") val uncertainty: String,
    /** 该 kind 特有的字段，JSON 对象；空串表示没有。 */
    @ColumnInfo(name = "payload") val payload: String,
    /** 依赖文件及其指纹，JSON 数组；读回时据此判断结论是否仍然成立。 */
    @ColumnInfo(name = "dependencies") val dependencies: String,
    @ColumnInfo(name = "sensitive") val sensitive: Boolean,
)

@Dao
internal interface WorldKnowledgeDao {
    /** 同 id 覆盖写：同一条知识被重新观测到时更新而不是堆积。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldKnowledgeEntity)

    /** 同签名是否已存在（去重）。 */
    @Query("SELECT COUNT(*) FROM world_knowledge WHERE kind = :kind AND signature = :signature")
    suspend fun countBySignature(kind: String, signature: String): Int

    /** 按签名取最近一条，用于"同类问题先查历史"。 */
    @Query(
        "SELECT * FROM world_knowledge WHERE kind = :kind AND signature = :signature " +
            "ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun latestBySignature(kind: String, signature: String): WorldKnowledgeEntity?

    /** 取未过期的条目，最近优先。 */
    @Query(
        "SELECT * FROM world_knowledge WHERE expires_at = 0 OR expires_at > :now " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun recent(now: Long, limit: Int): List<WorldKnowledgeEntity>

    /**
     * 按关键词检索未过期的条目，最近优先。
     *
     * 关键词同时匹配结论、证据与不确定项：模型给的查询词可能出现在任一处，
     * 只搜结论会漏掉「证据里提到过这个文件」这类高价值命中。
     */
    @Query(
        "SELECT * FROM world_knowledge WHERE (expires_at = 0 OR expires_at > :now) " +
            "AND (summary LIKE :pattern OR evidence LIKE :pattern OR uncertainty LIKE :pattern) " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun search(now: Long, pattern: String, limit: Int): List<WorldKnowledgeEntity>

    /** 按种类取未过期条目，最近优先。 */
    @Query(
        "SELECT * FROM world_knowledge WHERE kind = :kind AND (expires_at = 0 OR expires_at > :now) " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun recentByKind(kind: String, now: Long, limit: Int): List<WorldKnowledgeEntity>

    /** 清理已过期条目，返回删除条数。 */
    @Query("DELETE FROM world_knowledge WHERE expires_at != 0 AND expires_at <= :now")
    suspend fun deleteExpired(now: Long): Int

    /** 按条数裁剪：只保留最近的 [keep] 条，避免无限增长。 */
    @Query(
        "DELETE FROM world_knowledge WHERE id NOT IN " +
            "(SELECT id FROM world_knowledge ORDER BY created_at DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)
}

/**
 * 子智能体的一次委派记录，形如一棵树。
 *
 * 字段取自 OpenTelemetry 的 span 模型（name / parent span id / 起止时间 / attributes）：
 * 一次 run 对应一棵树（[traceId]），主循环派出的委派挂在根下，委派内部再派生的节点用
 * [parentId] 挂在它下面。这样「这条结论是哪一轮、谁派的、下面还做了什么」都能回溯，
 * 而不是只能按 run 平铺着查。
 *
 * 正文的存放位置按 SQLite 官方实测的分界线（100KB）决定：小于该值时正文直接入库读取更快，
 * 超过则落文件、库里只留路径，避免大文本拖慢整张表的查询与分页。
 */
@Entity(
    tableName = "world_trace",
    indices = [
        Index(value = ["trace_id", "parent_id"]),
        Index(value = ["run_id"]),
        Index(value = ["started_at"]),
    ],
)
internal data class WorldTraceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** 父节点 id；空串表示这是一次 run 的根。 */
    @ColumnInfo(name = "parent_id") val parentId: String,
    /** 一次 run 对应一棵树。 */
    @ColumnInfo(name = "trace_id") val traceId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** 发起者：主循环，或某个子智能体角色名。 */
    @ColumnInfo(name = "agent") val agent: String,
    /** 本次委派的角色名。 */
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    /** 结束状态：ok / failed；失败时同时记错误码。 */
    @ColumnInfo(name = "status") val status: String,
    /** 子智能体的摘要全文，也就是主循环看到的那一份。 */
    @ColumnInfo(name = "summary") val summary: String,
    /** 摘要解析出的结论（三段格式的第一段）；解析不出时退化为全文。 */
    @ColumnInfo(name = "conclusion") val conclusion: String,
    /** 证据行，每行一条，与摘要里的原样一致。 */
    @ColumnInfo(name = "evidence") val evidence: String,
    /** 不确定项，每行一条。 */
    @ColumnInfo(name = "uncertainty") val uncertainty: String,
    /** 正文（完整消息流）；超阈值时为空，改由 [contentPath] 指向文件。 */
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "content_path") val contentPath: String,
    @ColumnInfo(name = "content_bytes") val contentBytes: Long,
)

@Dao
internal interface WorldTraceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldTraceEntity)

    /** 取整棵树，按开始时间排列，父必然排在子之前。 */
    @Query("SELECT * FROM world_trace WHERE trace_id = :traceId ORDER BY started_at ASC")
    suspend fun tree(traceId: String): List<WorldTraceEntity>

    /** 取某个节点的直接子节点。 */
    @Query("SELECT * FROM world_trace WHERE parent_id = :parentId ORDER BY started_at ASC")
    suspend fun children(parentId: String): List<WorldTraceEntity>

    /** 按 run 取记录。 */
    @Query("SELECT * FROM world_trace WHERE run_id = :runId ORDER BY started_at ASC")
    suspend fun byRun(runId: String): List<WorldTraceEntity>

    /** 最近若干次委派，用于回答「上一次排查到底看了什么」。 */
    @Query("SELECT * FROM world_trace ORDER BY started_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WorldTraceEntity>

    /** 超出保留条数的旧记录；正文文件由调用方连带删除。 */
    @Query(
        "SELECT id, content_path FROM world_trace WHERE id NOT IN " +
            "(SELECT id FROM world_trace ORDER BY started_at DESC LIMIT :keep)",
    )
    suspend fun overflow(keep: Int): List<TraceOverflowRow>

    @Query("DELETE FROM world_trace WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

/** 裁剪时需要连带删除的正文文件路径。 */
internal data class TraceOverflowRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "content_path") val contentPath: String,
)

@Database(
    entities = [WorldKnowledgeEntity::class, WorldTraceEntity::class],
    version = WorldDatabase.VERSION,
    exportSchema = false,
)
internal abstract class WorldDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): WorldKnowledgeDao

    abstract fun traceDao(): WorldTraceDao

    companion object {
        /**
         * schema 版本。与主库无关：版本不匹配时本库直接重建，不写迁移。
         *
         * 2：新增 world_trace（子智能体委派树）。
         * 3：world_trace 增加 conclusion / evidence / uncertainty，摘要按三段格式拆开存，
         *    证据行与不确定项因此可查询，不必把摘要当自由文本重新解析。
         */
        const val VERSION = 3

        const val FILE_NAME = "world.db"
    }
}
