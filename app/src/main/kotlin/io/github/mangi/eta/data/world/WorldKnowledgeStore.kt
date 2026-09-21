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
     * 写入一条观测。
     *
     * 同签名且内容未变时不写（见 [WorldKnowledgeLogic.shouldWrite]）；内容变了则覆盖更新，
     * 避免同一条知识堆积多个版本。
     */
    fun write(
        context: Context?,
        entry: Entry,
    ) {
        if (context == null) return
        try {
            // 固定走 IO 调度器：本类的调用点分布在工具执行、事件回收等多条路径上，
            // 不能让某一条恰好落在主线程时把磁盘 IO 带到主线程上。
            runBlocking(Dispatchers.IO) {
                val db = WorldDatabaseProvider.get(context)
                // 查重、写入、过期清理、裁剪放进同一个事务：否则并发写入时
                // “查到不存在→两个都写”会各插一条，去重就失效了。
                db.withTransaction {
                    val dao = db.knowledgeDao()
                    val existing = dao.latestBySignature(entry.kind, entry.signature)
                    if (WorldKnowledgeLogic.shouldWrite(entry.kind, entry.signature, entry.summary, existing)) {
                        dao.upsert(entry.toEntity(existing?.id ?: UUID.randomUUID().toString()))
                    }
                    dao.deleteExpired(System.currentTimeMillis())
                    dao.trim(KEEP_ENTRIES)
                }
            }
        } catch (_: Throwable) {
            // 记录观测不影响本轮运行
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
        } catch (_: Throwable) {
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
}
