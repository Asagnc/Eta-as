package io.github.asagnc.eta.data.db

import androidx.room.migration.Migration

/**
 * Eta 数据库的迁移链。
 *
 * 独立成文件而不是塞在 [EtaDatabase] 里，有两个实际理由：
 * - **可本地验证**：只依赖 `androidx.room.migration.Migration`，不需要 Robolectric，
 *   所以"版本号涨了但迁移没接上"这类错误能在本地单测里被拦住（see `EtaMigrationsChainTest`）。
 * - **清单只有一份**：`ALL` 是唯一来源，生产构建与迁移测试都从它取，
 *   不会再出现"加了迁移但测试清单没跟着加"的漂移。
 */
internal object EtaMigrations {

    /** 支持的最老可迁移版本；比它更老的库只能重建。 */
    const val MIN_SUPPORTED_VERSION = 6

    /**
     * 当前 schema 版本。
     *
     * `@Database(version = ...)` 取这里，所以"改版本号"与"改迁移链"不可能分头进行：
     * 只要 [ALL] 没跟着接到新版本，`EtaMigrationsChainTest` 就会失败。
     */
    const val CURRENT_VERSION = 29

    /** 子智能体的消耗样本：用于按历史分位数评估后续委派的 token 预算。 */
    internal val MIGRATION_24_25 = Migration(24, 25) { database ->
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `sub_agent_runs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`scope` TEXT NOT NULL, " +
                "`tokens` INTEGER NOT NULL, " +
                "`rounds` INTEGER NOT NULL, " +
                "`ok` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL)",
        )
    }

    /** 消息时间戳：历史数据留 0，界面据此不展示时间。 */
    internal val MIGRATION_25_26 = Migration(25, 26) { database ->
        database.execSQL(
            "ALTER TABLE `conversation_messages` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0",
        )
    }

    /** provider 余额查询配置：历史数据留 'null'，解码时回落到默认关闭。 */
    internal val MIGRATION_26_27 = Migration(26, 27) { database ->
        database.execSQL(
            "ALTER TABLE `model_providers` ADD COLUMN `balance_option_json` TEXT NOT NULL DEFAULT 'null'",
        )
    }

    /**
     * 并行子智能体的共享信箱。
     *
     * `id` 自增主键 + `run_id` 普通列（而不是复合主键）：读取用 `id > :sinceId` 做增量游标，
     * 复合主键下自增语义不清晰，容易写出漏读或重读的查询。
     * 索引建在 `(run_id, created_at)` 上，覆盖"某轮 run 内按时间取"这个唯一读法。
     */
    internal val MIGRATION_27_28 = Migration(27, 28) { database ->
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `sub_agent_mailbox` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`run_id` TEXT NOT NULL, " +
                "`author` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sub_agent_mailbox_run_id_created_at` " +
                "ON `sub_agent_mailbox` (`run_id`, `created_at`)",
        )
    }

    /**
     * 方案文档（submit_plan 的产出）。
     *
     * 挂在会话行上而不是落成工作区文件：方案的消费者是手机上的聊天界面，它得跟对话一起
     * 出现、一起消失；需要归档时再由用户显式导出。历史数据留空串，解码时视为「没有方案」。
     */
    internal val MIGRATION_28_29 = Migration(28, 29) { database ->
        database.execSQL(
            "ALTER TABLE `conversations` ADD COLUMN `plan_json` TEXT NOT NULL DEFAULT ''",
        )
    }

    internal val MIGRATION_19_20 = Migration(19, 20) { database ->
        database.execSQL("ALTER TABLE conversation_context_checkpoints ADD COLUMN journal_json TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE runtime_inflight_runs ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'")
        database.execSQL("CREATE TABLE IF NOT EXISTS agent_text_chunks (" +
            "owner_table TEXT NOT NULL, owner_id TEXT NOT NULL, field TEXT NOT NULL, " +
            "chunk_index INTEGER NOT NULL, content TEXT NOT NULL, " +
            "PRIMARY KEY(owner_table, owner_id, field, chunk_index))")
        database.execSQL("UPDATE conversation_context_checkpoints SET journal_json = history_json")
        HistoryPayloadMigration.migrate(database)
        createTextChunkCleanup(database)
    }

    internal val MIGRATION_20_21 = Migration(20, 21) { database ->
        database.execSQL("ALTER TABLE conversations ADD COLUMN roleplay_json TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE conversations ADD COLUMN revisions_json TEXT NOT NULL DEFAULT ''")
        listOf("runtime_results", "runtime_archive_runs", "runtime_inflight_runs").forEach { table ->
            database.execSQL("ALTER TABLE $table ADD COLUMN rewrite_target_message_id TEXT")
        }
        database.execSQL("CREATE TABLE IF NOT EXISTS roleplay_characters (" +
            "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, card_json TEXT NOT NULL, " +
            "avatar_path TEXT, archived INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        database.execSQL("CREATE TABLE IF NOT EXISTS roleplay_user_persona (" +
            "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL)")
        createTextChunkCleanup(database)
    }

    internal val MIGRATION_23_24 = Migration(23, 24) { database ->
        database.execSQL(
            "ALTER TABLE conversations ADD COLUMN task_plan_json TEXT NOT NULL DEFAULT ''",
        )
    }

    internal val MIGRATION_22_23 = Migration(22, 23) { database ->
        database.execSQL(
            "ALTER TABLE model_providers ADD COLUMN context_editing_enabled INTEGER NOT NULL DEFAULT 0"
        )
    }

    internal val MIGRATION_21_22 = Migration(21, 22) { database ->
        database.execSQL(
            "ALTER TABLE model_providers ADD COLUMN prompt_cache_enabled INTEGER NOT NULL DEFAULT 0"
        )
    }

    internal fun createTextChunkCleanup(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        mapOf("runtime_results" to "run_id", "runtime_archive_runs" to "archive_run_id",
            "runtime_inflight_runs" to "run_id", "conversation_context_checkpoints" to "conversation_id",
            "conversation_messages" to "id", "conversations" to "id")
            .plus(if (database.version >= 21 || database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'roleplay_characters'"
            ).use { it.moveToFirst() }) mapOf("roleplay_characters" to "id", "roleplay_user_persona" to "id") else emptyMap())
            .forEach { (table, key) ->
                database.execSQL("CREATE TRIGGER IF NOT EXISTS ${table}_text_cleanup AFTER DELETE ON $table " +
                    "BEGIN DELETE FROM agent_text_chunks WHERE owner_table = '$table' AND owner_id = OLD.$key; END")
            }
    }

    internal val MIGRATION_18_19 = Migration(18, 19) { database ->
        listOf("runtime_results", "runtime_archive_runs", "runtime_inflight_runs").forEach { table ->
            database.execSQL("ALTER TABLE $table ADD COLUMN context_snapshot_json TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE $table ADD COLUMN operation TEXT NOT NULL DEFAULT 'chat'")
        }
    }

    internal val MIGRATION_6_7 = Migration(6, 7) { database ->
        database.execSQL(
            "ALTER TABLE runtime_results ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
        )
        database.execSQL(
            "ALTER TABLE runtime_archive_runs ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
        )
    }

    internal val MIGRATION_16_17 = Migration(16, 17) { database ->
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS mcp_servers (" +
                "id TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "url TEXT NOT NULL, " +
                "enabled INTEGER NOT NULL, " +
                "protocol_mode TEXT NOT NULL, " +
                "authorization_type TEXT NOT NULL, " +
                "tools_json TEXT NOT NULL, " +
                "enabled_tool_names_json TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "sort_order INTEGER NOT NULL, " +
                "last_refreshed_at INTEGER, " +
                "last_protocol_version TEXT, " +
                "PRIMARY KEY(id))"
        )
    }

    internal val MIGRATION_17_18 = Migration(17, 18) { database ->
        database.execSQL("ALTER TABLE mcp_servers ADD COLUMN tools_expire_at INTEGER")
    }

    internal val MIGRATION_7_8 = Migration(7, 8) { database ->
        database.execSQL(
            "ALTER TABLE conversations ADD COLUMN " +
                "applied_runtime_run_ids_json TEXT NOT NULL DEFAULT '[]'"
        )
    }

    internal val MIGRATION_8_9 = Migration(8, 9) { database ->
        database.execSQL(
            "ALTER TABLE provider_models ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'"
        )
        database.execSQL(
            "UPDATE provider_models SET source = 'catalog' WHERE is_built_in = 1"
        )
        // 旧版“添加自定义模型”会在打开编辑框时提前落下一条空记录。
        database.execSQL("DELETE FROM provider_models WHERE TRIM(model_id) = ''")
        // 只清理由旧版“新建对话”产生、且用户从未真正使用或命名过的占位记录。
        database.execSQL(
            "DELETE FROM conversations " +
                "WHERE title = '新对话' " +
                "AND TRIM(history_json) = '[]' " +
                "AND TRIM(applied_runtime_run_ids_json) = '[]' " +
                "AND NOT EXISTS (" +
                "SELECT 1 FROM conversation_messages " +
                "WHERE conversation_messages.conversation_id = conversations.id)"
        )
        database.execSQL(
            "DELETE FROM conversation_state WHERE selected_conversation_id NOT IN " +
                "(SELECT id FROM conversations)"
        )
    }

    internal val MIGRATION_9_10 = Migration(9, 10) { database ->
        database.execSQL(
            "ALTER TABLE conversations ADD COLUMN " +
                "reasoning_effort TEXT NOT NULL DEFAULT 'default'"
        )
        database.execSQL(
            "UPDATE conversations SET reasoning_effort = " +
                "CASE WHEN thinking_enabled = 1 THEN 'default' ELSE 'off' END"
        )
        database.execSQL(
            "ALTER TABLE provider_models ADD COLUMN " +
                "reasoning_capabilities_json TEXT NOT NULL DEFAULT 'null'"
        )
    }

    internal val MIGRATION_10_11 = Migration(10, 11) { database ->
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS conversation_context_checkpoints (" +
                "conversation_id TEXT NOT NULL, " +
                "history_json TEXT NOT NULL, " +
                "PRIMARY KEY(conversation_id), " +
                "FOREIGN KEY(conversation_id) REFERENCES conversations(id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "INSERT INTO conversation_context_checkpoints (conversation_id, history_json) " +
                "SELECT id, history_json FROM conversations"
        )
        // SQL 内搬移完整正文；后续分块迁移负责行大小，不能因旧字段过大丢弃历史。
        database.execSQL("UPDATE conversations SET history_json = '[]'")
    }

    internal val MIGRATION_11_12 = Migration(11, 12) { database ->
        database.execSQL(
            "ALTER TABLE conversation_messages ADD COLUMN " +
                "is_edited INTEGER NOT NULL DEFAULT 0"
        )
    }

    internal val MIGRATION_12_13 = Migration(12, 13) { database ->
        database.execSQL(
            "ALTER TABLE model_providers ADD COLUMN " +
                "hosted_web_search_enabled INTEGER NOT NULL DEFAULT 0"
        )
    }

    internal val MIGRATION_13_14 = Migration(13, 14) { database ->
        database.execSQL(
            "ALTER TABLE runtime_archive_runs ADD COLUMN " +
                "user_image_previews_json TEXT NOT NULL DEFAULT '[]'"
        )
    }

    internal val MIGRATION_14_15 = Migration(14, 15) { database ->
        database.execSQL(
            "ALTER TABLE provider_models ADD COLUMN context_window_override INTEGER"
        )
        database.execSQL(
            "ALTER TABLE provider_models ADD COLUMN reasoning_override INTEGER"
        )
        database.execSQL(
            "ALTER TABLE provider_models ADD COLUMN " +
                "reasoning_capabilities_override_json TEXT NOT NULL DEFAULT 'null'"
        )
    }

    internal val MIGRATION_15_16 = Migration(15, 16) { database ->
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS runtime_inflight_runs (" +
                "run_id TEXT NOT NULL, " +
                "owner_instance_id TEXT NOT NULL, " +
                "handoff_id TEXT NOT NULL, " +
                "handoff_source TEXT NOT NULL, " +
                "handoff_payload TEXT NOT NULL, " +
                "dismiss_entry_surface INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY(run_id))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS runtime_inflight_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "run_id TEXT NOT NULL, " +
                "sort_index INTEGER NOT NULL, " +
                "event_json TEXT NOT NULL, " +
                "FOREIGN KEY(run_id) REFERENCES runtime_inflight_runs(run_id) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_runtime_inflight_events_run_id " +
                "ON runtime_inflight_events(run_id)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "index_runtime_inflight_events_run_id_sort_index " +
                "ON runtime_inflight_events(run_id, sort_index)"
        )
    }

    /**
     * 迁移链的唯一来源。
     *
     * 注意：**必须声明在所有 `MIGRATION_*` 之后**——Kotlin object 的属性按声明顺序初始化，
     * 提前引用会读到未初始化的 null。
     */
    val ALL: List<Migration> = listOf(
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_23_24,
        MIGRATION_22_23,
        MIGRATION_21_22,
        MIGRATION_18_19,
        MIGRATION_6_7,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
    )
}
