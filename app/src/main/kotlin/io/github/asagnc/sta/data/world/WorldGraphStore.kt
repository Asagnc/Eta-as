package io.github.asagnc.sta.data.world

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 世界图（实体 + 边 + 社区）的读写入口。
 *
 * 三种数据的职责：
 * - **实体**：观测涉及的东西（文件 / 符号 / 概念）。
 * - **边**：观测之间的连接，按 `layer` 区分来源（确定性三层 + LLM 语义边）。
 * - **社区**：「星系」，一批强连通观测的聚类与其高层摘要。
 *
 * 与 [WorldKnowledgeStore] / [WorldTraceStore] 分开成独立入口，而不是塞进前者：
 * 前者回答「这条结论是什么」，本类回答「这些结论之间的关系是什么」。后者的写入
 * 由前者触发（每条新观测进来时增量建边），但读路径完全独立——查图的人不会因此
 * 被知识条目的注入逻辑牵住。
 *
 * **所有失败一律降级并留痕**（同 [WorldHealth] 的原则）：图是运行副产物，建图失败
 * 不该让正在跑的 run 失败，但必须能从 [WorldHealth] 看出来，否则「图建不起来」会
 * 表现为「聚类结果很少」，而那是完全不同的两件事。
 */
internal object WorldGraphStore {

    /** 保留的实体数上限。 */
    const val KEEP_ENTITIES = 2000

    /** 保留的边数上限。 */
    const val KEEP_EDGES = 8000

    /**
     * 建边时参考的既有观测数量上限。
     *
     * 不取全量：建边是 O(新观测 × 既有观测) 的两两比较，全量在几百条时已经不划算，
     * 而且「与很久以前的观测共享一个关键词」本来也不是有用的连接。只与最近的
     * [EDGE_WINDOW] 条比较，既限制成本，又让图自然地偏向当前活跃的领域。
     */
    const val EDGE_WINDOW = 120

    /**
     * 一条新观测入图。
     *
     * 做三件事：① 抽取实体（文件 / 符号）；② 与窗口内的既有观测建确定性的边；
     * ③ 入库。整个过程是增量的——不做全量重建（见 [WorldEdgeBuilder.planFor]）。
     *
     * [observationId] 用观测条目自己的 id：边的两端指向观测，因为聚类聚的是
     * 「哪些工作记录属于同一个领域」，不是「哪些概念相关」。
     */
    fun ingest(
        context: Context?,
        observationId: String,
        conclusion: String,
        evidence: String,
        uncertainty: String = "",
        scope: String = "",
        workspaceRoot: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (context == null || observationId.isBlank()) return
        try {
            runBlocking(Dispatchers.IO) {
                val db = WorldDatabaseProvider.get(context)
                val bundle = WorldEntityExtractor.extract(
                    conclusion = conclusion,
                    evidence = evidence,
                    uncertainty = uncertainty,
                    workspaceRoot = workspaceRoot,
                )
                val node = WorldEdgeBuilder.Node(
                    id = observationId,
                    files = bundle.files.toSet(),
                    symbols = bundle.symbols.toSet(),
                    keywords = WorldEdgeBuilder.keywordsOf(conclusion),
                )

                // 实体先落库：边引用的两端必须先存在，否则图里会出现悬空引用。
                val entityRows = bundle.all().map { extracted ->
                    WorldEntityRow(
                        // 实体 id 由 kind + name 决定而不是随机：同一个文件被多条观测提到时
                        // 必须落到同一行，否则「共享文件」这个判断在库里根本体现不出来。
                        id = entityId(extracted.kind, extracted.name),
                        kind = extracted.kind.value,
                        name = extracted.name,
                        scope = scope,
                        createdAt = nowMs,
                        updatedAt = nowMs,
                    )
                }

                val existingNodes = recentNodes(db, EDGE_WINDOW)
                val planned = WorldEdgeBuilder.planFor(node, existingNodes)

                db.withTransaction {
                    if (entityRows.isNotEmpty()) db.entityDao().upsert(entityRows)
                    if (planned.isNotEmpty()) {
                        db.edgeDao().upsert(
                            planned.map { edge ->
                                WorldEdgeRow(
                                    id = edgeId(edge.srcId, edge.dstId, edge.layer),
                                    srcId = edge.srcId,
                                    dstId = edge.dstId,
                                    layer = edge.layer.value,
                                    weight = edge.weight,
                                    createdAt = nowMs,
                                )
                            },
                        )
                    }
                    db.entityDao().trim(KEEP_ENTITIES)
                    db.edgeDao().trim(KEEP_EDGES)
                }
            }
        } catch (error: Throwable) {
            WorldHealth.recordDegradation("graph.ingest", error)
        }
    }

    /**
     * 取最近若干条观测在图里的坐标。
     *
     * 坐标从**知识条目**重建而不是另存一张表：知识条目里已经有结论与证据（实体由它们
     * 抽出来），另存一份等于把同一份信息写两遍，两边一旦不同步，图就会建立在过时的
     * 理解上。
     */
    private suspend fun recentNodes(db: WorldDatabase, limit: Int): List<WorldEdgeBuilder.Node> {
        val rows = db.knowledgeDao().recentAny(limit)
        return rows.map { row ->
            val workspaceRoot = row.scope
            val bundle = WorldEntityExtractor.extract(
                conclusion = row.summary,
                evidence = row.evidence,
                uncertainty = row.uncertainty,
                workspaceRoot = workspaceRoot,
            )
            WorldEdgeBuilder.Node(
                id = row.id,
                files = bundle.files.toSet(),
                symbols = bundle.symbols.toSet(),
                keywords = WorldEdgeBuilder.keywordsOf(row.summary),
            )
        }
    }

    /** 实体 id：由种类与名字决定，保证同一实体只有一行。 */
    fun entityId(kind: WorldEntityExtractor.EntityKind, name: String): String =
        "${kind.value}:$name"

    /** 边 id：由两端与层次决定；两端按字典序排，保证无向边不重复。 */
    fun edgeId(srcId: String, dstId: String, layer: WorldEdgeBuilder.Layer): String =
        "${minOf(srcId, dstId)}|${maxOf(srcId, dstId)}|${layer.value}"

    /** 图中某个节点连出去的所有边（含作为 dst 的）。 */
    fun edgesTouching(context: Context?, nodeId: String): List<WorldEdgeRow> {
        if (context == null || nodeId.isBlank()) return emptyList()
        return try {
            runBlocking(Dispatchers.IO) { WorldDatabaseProvider.get(context).edgeDao().touching(nodeId) }
        } catch (error: Throwable) {
            WorldHealth.recordDegradation("graph.edgesTouching", error)
            emptyList()
        }
    }

    /** 只读计数：读失败同样留痕，否则「图是空的」会被当成「世界里没有关联」。 */
    private fun countOf(context: Context?, block: suspend (WorldDatabase) -> Int): Int {
        if (context == null) return 0
        return try {
            runBlocking(Dispatchers.IO) { block(WorldDatabaseProvider.get(context)) }
        } catch (error: Throwable) {
            WorldHealth.recordDegradation("graph.count", error)
            0
        }
    }

    /** 供测试重置单例。 */
    internal fun closeForTests() {
        WorldDatabaseProvider.closeForTests()
    }

    /**
     * 把坐标未知的历史观测回填成当前工作区根。
     *
     * 只在 schema 升级后的首次访问跑一次：scope 从无到有，历史条目全是空串，而空串在
     * 检索里按"最远"处理。若不回填，用户升级后会发现「刚写的观测查得到、之前的查不到」，
     * 看起来像检索坏了，实际是这批数据缺坐标。
     *
     * 幂等：只更新 `scope = ''` 的行，第二次调用影响 0 行。所以在启动路径上直接调用
     * 是安全的，不需要额外的"已回填"标记位。
     */
    fun backfillScope(context: Context?, scope: String) {
        if (context == null || scope.isBlank()) return
        try {
            runBlocking(Dispatchers.IO) {
                val db = WorldDatabaseProvider.get(context)
                db.withTransaction {
                    db.knowledgeDao().backfillScope(scope)
                    db.traceDao().backfillScope(scope)
                }
            }
        } catch (error: Throwable) {
            WorldHealth.recordDegradation("graph.backfillScope", error)
        }
    }
}