package io.github.asagnc.sta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 子智能体每次运行的消耗样本。
 *
 * 只用于评估下一次同档位委派的 token 预算与轮数，属于滑动窗口数据，不做长期归档，
 * 所以插入后会按条数裁剪，避免无限增长。
 */
@Entity(tableName = "sub_agent_runs")
internal data class SubAgentRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "tokens") val tokens: Int,
    @ColumnInfo(name = "rounds") val rounds: Int,
    @ColumnInfo(name = "ok") val ok: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** 预算评估只需要这三个字段，避免把整行读进内存。 */
internal data class SubAgentRunRow(
    @ColumnInfo(name = "tokens") val tokens: Int,
    @ColumnInfo(name = "rounds") val rounds: Int,
    @ColumnInfo(name = "ok") val ok: Boolean,
)

@Dao
internal interface SubAgentRunDao {
    @Insert
    suspend fun insert(entity: SubAgentRunEntity)

    @Query("SELECT tokens, rounds, ok FROM sub_agent_runs WHERE scope = :scope ORDER BY id DESC LIMIT :limit")
    suspend fun recent(scope: String, limit: Int): List<SubAgentRunRow>

    @Query("DELETE FROM sub_agent_runs WHERE id NOT IN (SELECT id FROM sub_agent_runs ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
