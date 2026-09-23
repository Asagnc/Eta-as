package io.github.asagnc.sta.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sta 数据库的 schema 声明与建库辅助。
 *
 * **这里没有迁移链**，这是刻意的：Sta 从未正式发版，任何旧库都不存在，
 * 按用户要求「不考虑兼容和迁移」。所以：
 * - [CURRENT_VERSION] 恒为 1，schema 一旦不匹配就由
 *   `fallbackToDestructiveMigration(dropAllTables = true)` 整库重建；
 * - 上游积累的 24 条 `MIGRATION_*`（v6→v30）全部删除，它们永远不会被执行；
 * - 数据格式迁移 `HistoryPayloadMigration` 也一并删除——它只被 `MIGRATION_19_20` 调用。
 *
 * 留在这里的是 [createTextChunkCleanup]，它**不是迁移**，而是每次建库/开库都要
 * 执行的触发器安装：`agent_text_chunks` 是按 `owner_id` 关联的分块存储，删主记录时
 * 必须连带清掉分块，否则会留孤儿数据。所以它在 `onCreate` 与 `onOpen` 两处都要跑
 * （见 [StaDatabase.get]）。
 */
internal object StaSchema {

    /**
     * 当前 schema 版本。
     *
     * 恒为 1：没有历史版本可迁移，任何不匹配都由破坏性重建兜底。
     * 改 schema 时不需要动这个数字，Room 启动时发现声明与实际不符会直接重建库。
     */
    const val CURRENT_VERSION = 1

    /**
     * 为所有「主记录 + `agent_text_chunks` 分块」的表建删除清理触发器。
     *
     * 用触发器而不是在 DAO 里手写删除，是因为分块表的清理必须**跟随主记录的每一种
     * 删除路径**（用户删除、级联删除、清理任务）。在应用层逐条补删除语句，
     * 漏掉一条就留一片孤儿；触发器由 SQLite 保证，路径无关。
     *
     * 幂等：全部使用 `CREATE TRIGGER IF NOT EXISTS`，重复调用安全，所以
     * `onCreate` 与 `onOpen` 都可以无脑调用。
     *
     * 注意：触发器只覆盖「删除」。分块的生命周期是「写入时先 `DELETE` 再 `INSERT`」，
     * 更新场景由写入方自己保证，不需要 UPDATE 触发器。
     */
    fun createTextChunkCleanup(database: SupportSQLiteDatabase) {
        mapOf(
            "runtime_results" to "run_id",
            "runtime_archive_runs" to "archive_run_id",
            "runtime_inflight_runs" to "run_id",
            "conversation_context_checkpoints" to "conversation_id",
            "conversation_messages" to "id",
            "conversations" to "id",
            "roleplay_characters" to "id",
            "roleplay_user_persona" to "id",
        ).forEach { (table, key) ->
            database.execSQL(
                "CREATE TRIGGER IF NOT EXISTS ${table}_text_cleanup AFTER DELETE ON $table " +
                    "BEGIN DELETE FROM agent_text_chunks WHERE owner_table = '$table' AND owner_id = OLD.$key; END",
            )
        }
    }
}