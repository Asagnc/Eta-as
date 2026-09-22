package io.github.mangi.eta.data.world

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 观测层的读写入口。
 *
 * 所有异常都吞掉并降级为"没有数据"：本库记录的是运行副产物，读不到或写不进都不该让
 * 正在跑的 run 失败——这与它要替代的失败学习文件存储是同一条原则。
 */
internal object WorldKnowledgeStore {

    /** 条目数上限，超出后按时间裁掉最旧的。 */
    const val KEEP_ENTRIES = 500

    /** 默认有效期：7 天。超过这个时间的观测结论默认不再注入。 */
    const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 观测库文件，供排查与清理使用。 */
    fun fileFor(context: Context): File = WorldDatabaseProvider.fileFor(context)

    /**
     * 写入一条观测，返回**实际落库的条目 id**（写失败或不需要写时为空串）。
     *
     * 返回值不是可有可无的便利：同签名且内容未变时不写新行，此时实际存在的是**旧行的
     * id**，与调用方自己算出来的签名并不相同。图里的边必须以这个 id 为端点，否则
     * 「共享文件」会指向一个数据库里根本不存在的节点，聚类时那部分连接会静默丢失。
     *
     * 同签名且内容未变时不写（见 [WorldKnowledgeLogic.shouldWrite]）；内容变了则覆盖更新，
     * 避免同一条知识堆积多个版本。
     */
    fun write(
        context: Context?,
        entry: Entry,
    ): String {
        if (context == null) return ""
        return try {
            // 固定走 IO 调度器：本类的调用点分布在工具执行、事件回收等多条路径上，
            // 不能让某一条恰好落在主线程时把磁盘 IO 带到主线程上。
            runBlocking(Dispatchers.IO) {
                val db = WorldDatabaseProvider.get(context)
                // 查重、写入、过期清理、裁剪放进同一个事务：否则并发写入时
                // “查到不存在→两个都写”会各插一条，去重就失效了。
                db.withTransaction {
                    val dao = db.knowledgeDao()
                    val existing = dao.latestBySignature(entry.kind, entry.signature)
                    // 内容没变时沿用旧行 id：这条观测已经存在，边应当连到它身上。
                    val targetId = existing?.id ?: UUID.randomUUID().toString()
                    if (WorldKnowledgeLogic.shouldWrite(entry.kind, entry.signature, entry.summary, existing)) {
                        dao.upsert(entry.toEntity(targetId))
                    }
                    dao.deleteExpired(System.currentTimeMillis())
                    dao.trim(KEEP_ENTRIES)
                    // 已有旧行且内容未变时，旧行仍在（未被 trim 掉的），照常返回它的 id；
                    // 但若刚才的 trim 把它裁掉了，返回空串以免图上留下悬空引用。
                    if (dao.exists(targetId) > 0) targetId else ""
                }
            }
        } catch (error: Throwable) {
            // 记录观测不影响本轮运行，但降级要留痕。
            WorldHealth.recordDegradation("knowledge.write(${entry.kind})", error)
            ""
        }
    }

    /**
     * 按签名取历史观测，并逐条做新鲜度校验。
     *
     * 返回的文本已经带好"这条结论是否仍然成立"的标注——不做这一步，历史结论会变成
     * 幻觉温床：模型看不出它引用的文件早已改过。
     */
    fun recall(
        context: Context?,
        kind: String,
        signature: String,
        readContent: (String) -> String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Recalled? {
        if (context == null || signature.isBlank()) return null
        return try {
            val entity = runBlocking(Dispatchers.IO) {
                WorldDatabaseProvider.get(context).knowledgeDao().latestBySignature(kind, signature)
            } ?: return null
            if (entity.expiresAt != 0L && entity.expiresAt <= nowMs) return null
            val dependencies = WorldKnowledgeLogic.decodeDependencies(entity.dependencies)
            val freshness = WorldKnowledgeLogic.checkFreshness(dependencies, readContent)
            Recalled(
                summary = entity.summary,
                evidence = entity.evidence,
                uncertainty = entity.uncertainty,
                createdAt = entity.createdAt,
                freshness = freshness,
                sensitive = entity.sensitive,
            )
        } catch (error: Throwable) {
            // 读失败必须留痕：返回 null 会被上层理解成「没有这条历史」，
            // 而真实原因可能是库坏了——两者对模型的含义完全不同。
            WorldHealth.recordDegradation("knowledge.recall($kind)", error)
            null
        }
    }

    /** 供测试重置单例。 */
    internal fun closeForTests() {
        WorldDatabaseProvider.closeForTests()
    }

    /** 写入时用的条目；字段与 [WorldKnowledgeEntity] 一一对应。 */
    data class Entry(
        val kind: String,
        val signature: String,
        val summary: String,
        val evidence: String = "",
        val uncertainty: String = "",
        val originRun: String = "",
        val originSession: String = "",
        val originAgent: String = "",
        val payload: String = "",
        val dependencies: List<WorldKnowledgeLogic.Dependency> = emptyList(),
        val sensitive: Boolean = false,
        /**
         * 该条观测的空间坐标（工作区根路径）。
         *
         * 空串表示「坐标未知」：调用方（如失败学习这类不依附工作区的条目）可能拿不到
         * 工作区根，此时按最远处理而不是拒绝写入——不知道坐标的观测仍然发生过。
         */
        val scope: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = DEFAULT_TTL_MS,
    ) {
        fun toEntity(id: String): WorldKnowledgeEntity = WorldKnowledgeEntity(
            id = id,
            kind = kind,
            signature = signature,
            createdAt = createdAt,
            expiresAt = if (ttlMs <= 0) 0 else createdAt + ttlMs,
            originRun = originRun,
            originSession = originSession,
            originAgent = originAgent,
            summary = summary,
            evidence = evidence,
            uncertainty = uncertainty,
            payload = payload,
            dependencies = WorldKnowledgeLogic.encodeDependencies(dependencies),
            sensitive = sensitive,
            scope = scope,
        )
    }

    /** 读回的一条观测，附带新鲜度判定。 */
    data class Recalled(
        val summary: String,
        val evidence: String,
        val uncertainty: String,
        val createdAt: Long,
        val freshness: WorldKnowledgeLogic.Freshness,
        val sensitive: Boolean,
    )

    /**
     * 按关键词检索历史结论。
     *
     * 检索同时覆盖结论、证据与不确定项，并逐条做新鲜度校验：读回来的是「可以拿去做判断的
     * 结论」，不是一段未经核实的旧文本——依赖已变更的条目会被标注出来（见 [formatRecall]），
     * 而不是静默当作事实。
     *
     * 关键词用 `LIKE %词%` 而不是全文索引：本库是运行副观察，规模在几百条量级，
     * 全文索引带来的维护成本（额外的影子表与同步逻辑）大于收益。
     */
    fun search(
        context: Context?,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        readContent: (String) -> String? = { null },
        nowMs: Long = System.currentTimeMillis(),
    ): List<Recalled> {
        if (context == null || query.isBlank()) return emptyList()
        return try {
            val pattern = "%${escapeLike(query.trim())}%"
            runBlocking(Dispatchers.IO) {
                WorldDatabaseProvider.get(context).knowledgeDao()
                    .search(nowMs, pattern, limit)
            }.map { entity -> entity.toRecalled(readContent) }
        } catch (error: Throwable) {
            // 返回空列表与「真的没查到」无法区分，必须留痕。
            WorldHealth.recordDegradation("knowledge.search", error)
            emptyList()
        }
    }

    /**
     * 取最近的历史结论，供 run 启动时做一次轻量注入。
     *
     * 只取少量（见 [DEFAULT_INJECT_LIMIT]）：按 Anthropic 的 context engineering 结论，
     * 记忆的正确用法是「just in time 按需取」而非「预先全量加载」，启动注入只负责让模型
     * 知道「世界里有这些东西」，细节留给它自己用工具查。
     */
    fun recentFindings(
        context: Context?,
        limit: Int = DEFAULT_INJECT_LIMIT,
        readContent: (String) -> String? = { null },
        nowMs: Long = System.currentTimeMillis(),
    ): List<Recalled> {
        if (context == null) return emptyList()
        return try {
            runBlocking(Dispatchers.IO) {
                WorldDatabaseProvider.get(context).knowledgeDao()
                    .recentByKind(KIND_FINDING, nowMs, limit)
            }.map { entity -> entity.toRecalled(readContent) }
        } catch (error: Throwable) {
            // 启动注入拿不到历史结论时表现为「以前什么都没做过」，同样需要留痕。
            WorldHealth.recordDegradation("knowledge.recentFindings", error)
            emptyList()
        }
    }

    private fun WorldKnowledgeEntity.toRecalled(readContent: (String) -> String?): Recalled {
        val dependencies = WorldKnowledgeLogic.decodeDependencies(dependencies)
        return Recalled(
            summary = summary,
            evidence = evidence,
            uncertainty = uncertainty,
            createdAt = createdAt,
            freshness = WorldKnowledgeLogic.checkFreshness(dependencies, readContent),
            sensitive = sensitive,
        )
    }

    /** `LIKE` 的通配符要转义，否则查询词里的 `%` 会变成通配。 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 一次检索最多返回多少条。 */
    const val DEFAULT_SEARCH_LIMIT = 8

    /** 启动时最多注入多少条历史结论。 */
    const val DEFAULT_INJECT_LIMIT = 5

    /** 观测库里「子智能体结论」的种类标记，与 [WorldTraceStore] 写入时一致。 */
    const val KIND_FINDING = "finding"
}
