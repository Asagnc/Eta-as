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

@Database(
    entities = [WorldKnowledgeEntity::class],
    version = WorldDatabase.VERSION,
    exportSchema = false,
)
internal abstract class WorldDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): WorldKnowledgeDao

    companion object {
        /** schema 版本。与主库无关：版本不匹配时本库直接重建，不写迁移。 */
        const val VERSION = 1

        const val FILE_NAME = "world.db"
    }
}
