package io.github.asagnc.sta.data.world

import android.content.Context
import io.github.asagnc.sta.agent.model.AgentSubAgentSummary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 子智能体委派记录的读写入口。
 *
 * 与知识条目（[WorldKnowledgeStore]）分开成两个入口，因为它们回答的问题不同：
 * 知识条目是「结论」，要精炼、要注入、按签名检索；轨迹是「现场」，要完整、不注入、按树检索。
 * 两者用 run id 关联，所以能从一条结论回溯到产生它的那次委派。
 *
 * 正文的存放位置按 SQLite 官方实测的分界线（100KB）决定：小于该值时正文直接入库读取更快，
 * 超过则落文件、库里只留路径。这样小轨迹拿到库的查询能力，大轨迹不会拖慢整张表。
 */
internal object WorldTraceStore {

    /** 保留的委派记录条数，超出后裁掉最旧的（连同其正文文件）。 */
    const val KEEP_TRACES = 300

    /**
     * 正文直接入库的上限，取自 SQLite 官方实测结论：
     * 「BLOB 小于 100KB 时存库读取更快，大于 100KB 时存独立文件更快」。
     */
    const val INLINE_MAX_BYTES = 100 * 1024

    /** 正文落文件时的子目录名。 */
    const val CONTENT_DIR = "world-traces"

    /** 观测库里「子智能体结论」的种类标记。 */
    private const val KIND_FINDING = "finding"

    /**
     * 结论的去重签名。
     *
     * 用「角色 + 结论文本」而不是纯结论：同一句话出自不同角色时依据不同，不该被当成同一条。
     */
    private fun findingSignature(node: Node): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(node.summary.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "${node.role}|$digest"
    }

    /** 一次委派写入所需的全部信息。 */
    data class Node(
        val id: String = UUID.randomUUID().toString(),
        /** 父节点 id；空串表示这是一次 run 的根。 */
        val parentId: String,
        /** 一次 run 对应一棵树；缺省时以 [runId] 充当。 */
        val traceId: String,
        val runId: String,
        val sessionId: String,
        val agent: String,
        val role: String,
        val startedAt: Long,
        val endedAt: Long,
        val status: String,
        val summary: String,
        val content: String,
        /** 工作区根目录，用于把相对路径证据归一成可读的绝对路径。 */
        val workspaceRoot: String = "",
    )

    /**
     * 记录一次委派。写失败不影响本次委派的结果——它是可追溯性的补充，不是产出本身。
     */
    fun record(context: Context?, node: Node) {
        if (context == null) return
        try {
            runBlocking(Dispatchers.IO) {
                val db = WorldDatabaseProvider.get(context)
                val bytes = node.content.toByteArray().size.toLong()
                val overflow = bytes > INLINE_MAX_BYTES
                val path = if (overflow) writeContentFile(context, node.id, node.content) else ""
                // 摘要在这里解析，而不是让调用方传结构化结果：本方法是轨迹的唯一写入点，
                // 在这里解析能保证入库的每一条都拆过三段，不会因为调用方遗漏而留下未解析的记录。
                val parsed = AgentSubAgentSummary.parse(node.summary)
                db.traceDao().upsert(
                    WorldTraceEntity(
                        id = node.id,
                        parentId = node.parentId,
                        traceId = node.traceId.ifBlank { node.runId },
                        runId = node.runId,
                        sessionId = node.sessionId,
                        agent = node.agent,
                        role = node.role,
                        startedAt = node.startedAt,
                        endedAt = node.endedAt,
                        status = node.status,
                        summary = node.summary,
                        conclusion = parsed.conclusion,
                        evidence = parsed.evidence.joinToString("\n") { it.raw },
                        uncertainty = parsed.uncertainty.joinToString("\n"),
                        // 落文件成功才清空正文；写文件失败时宁可把正文留在库里，
                        // 也不要留下一条「有路径但文件不存在」的坏记录。
                        content = if (overflow && path.isNotBlank()) "" else node.content,
                        contentPath = path,
                        contentBytes = bytes,
                        scope = node.workspaceRoot,
                    ),
                )
                prune(db)
                // 委派的结论同时进知识库：轨迹回答「那次看了什么」（现场，不注入），
                // 知识条目回答「这件事的结论是什么」（可注入、可校验）。两者用 run id 关联，
                // 所以能从一条结论回溯到产生它的那次委派。
                // 「查不到」型的空集结论不入库：它没有可复用的知识，写进去只会让它在后续每轮
                // 被当作历史结论注入（实测一条 1900 字的「无法给出结论」报告每轮都在吃注意力预算）。
                if (parsed.conclusion.isNotBlank() &&
                    !WorldKnowledgeLogic.isInconclusiveConclusion(parsed.conclusion) &&
                    !WorldKnowledgeLogic.isRawToolOutputConclusion(parsed.conclusion)
                ) {
                    // 取实际落库 id 而不是自己算的签名：同签名内容未变时不写新行，
                    // 此时库里存在的是**旧行**，边必须连到它身上，否则图上的端点会
                    // 指向一条数据库里不存在的记录，那部分连接在聚类时静默消失。
                    val observationId = WorldKnowledgeStore.write(
                        context,
                        WorldKnowledgeStore.Entry(
                            kind = KIND_FINDING,
                            signature = findingSignature(node),
                            summary = parsed.conclusion,
                            evidence = parsed.evidence.joinToString("\n") { it.raw },
                            uncertainty = parsed.uncertainty.joinToString("\n"),
                            originRun = node.runId,
                            originSession = node.sessionId,
                            originAgent = node.role,
                            payload = org.json.JSONObject()
                                .put("trace_id", node.traceId)
                                .put("trace_node", node.id)
                                .put("status", node.status)
                                .toString(),
                            dependencies = WorldKnowledgeLogic.dependenciesFor(
                                paths = parsed.filePaths.mapNotNull { raw ->
                                    WorldKnowledgeLogic.normalizePath(raw, node.workspaceRoot)
                                },
                                readContent = { path ->
                                    runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
                                },
                            ),
                            scope = node.workspaceRoot,
                            createdAt = node.endedAt,
                        ),
                    )
                    // 一条新的观测同时入图：抽实体、与窗口内的既有观测建确定性边。
                    // 放在知识条目写入之后而不是之前，因为建边要读既有观测（含刚写这条
                    // 之前的全部），顺序反了会把自己和「还不存在的自己」比较。
                    //
                    // 整段仍在这个 try 里：建图失败会走 WorldHealth 留痕，不影响委派结果。
                    // observationId 为空说明这条没真正落库（被裁剪或写失败），此时不建边。
                    if (observationId.isNotBlank()) {
                        WorldGraphStore.ingest(
                            context = context,
                            observationId = observationId,
                            conclusion = parsed.conclusion,
                            evidence = parsed.evidence.joinToString("\n") { it.raw },
                            uncertainty = parsed.uncertainty.joinToString("\n"),
                            scope = node.workspaceRoot,
                            workspaceRoot = node.workspaceRoot,
                            nowMs = node.endedAt,
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            // 记录轨迹不影响本轮运行，但降级要留痕：否则「世界坏了」会被当成「世界上没这条」。
            WorldHealth.recordDegradation("trace.record", error)
        }
    }

    /** 取整棵树，父在子之前。 */
    fun tree(context: Context?, traceId: String): List<WorldTraceEntity> {
        if (context == null || traceId.isBlank()) return emptyList()
        return runCatching {
            runBlocking(Dispatchers.IO) { WorldDatabaseProvider.get(context).traceDao().tree(traceId) }
        }.getOrDefault(emptyList())
    }

    /** 最近若干次委派。 */
    fun recent(context: Context?, limit: Int = 20): List<WorldTraceEntity> {
        if (context == null) return emptyList()
        return runCatching {
            runBlocking(Dispatchers.IO) { WorldDatabaseProvider.get(context).traceDao().recent(limit) }
        }.getOrDefault(emptyList())
    }

    /**
     * 最近若干次委派，按空间距离重排后返回。
     *
     * 与 [recent] 的区别：这里优先返回**与当前工作区同空间**的记录。查委派树时，
     * 「上次在这个项目里派过谁」比「上次随便哪次派过谁」更相关——依据同
     * [WorldScore.spaceScore]（公共路径前缀 / 较深者深度）。
     *
     * 只按空间排序、不套用完整的四项打分：委派记录没有 kind（重要度无从计算），
     * 而用户主动查委派树时时间与相关度都不是他关心的——他要的是「在这个项目里发生过
     * 什么」。多套两项反而会把真正想看的记录压下去。
     *
     * 先取 [CANDIDATE_MULTIPLIER] 倍候选再排序：否则排序只能在最近 limit 条里做，
     * 同空间的旧记录永远进不了视野（与 `WorldKnowledgeStore.search` 同一考虑）。
     */
    fun recentForScope(
        context: Context?,
        currentScope: String,
        limit: Int = 20,
    ): List<WorldTraceEntity> {
        if (context == null) return emptyList()
        return try {
            val window = (limit * CANDIDATE_MULTIPLIER).coerceAtMost(MAX_CANDIDATE_WINDOW)
            val candidates = runBlocking(Dispatchers.IO) {
                WorldDatabaseProvider.get(context).traceDao().recent(window)
            }
            if (candidates.size <= 1) return candidates.take(limit)
            candidates
                .sortedWith(
                    compareByDescending<WorldTraceEntity> {
                        WorldScore.spaceScore(it.scope, currentScope)
                    }
                        // 同分时按开始时间倒序：空间分相同（如同属一个工作区）时，
                        // 新的排前面才是符合直觉的。
                        .thenByDescending { it.startedAt },
                )
                .take(limit)
        } catch (error: Throwable) {
            WorldHealth.recordDegradation("trace.recentForScope", error)
            emptyList()
        }
    }

    /** 候选窗放大倍数：先多取再按空间排序，避免同空间的旧记录进不了视野。 */
    private const val CANDIDATE_MULTIPLIER = 4

    /** 候选窗上限。 */
    private const val MAX_CANDIDATE_WINDOW = 80

    /**
     * 取一条记录的正文：库里没有就读文件。
     *
     * 读不到时返回空串而不是抛错——轨迹是排查用的补充材料，缺失不该打断调用方。
     */
    fun contentOf(context: Context?, entity: WorldTraceEntity): String {
        if (entity.content.isNotEmpty()) return entity.content
        if (entity.contentPath.isBlank()) return ""
        return runCatching { File(entity.contentPath).readText() }.getOrDefault("")
    }

    /** 正文文件所在目录。 */
    fun contentDir(context: Context): File = File(context.filesDir, CONTENT_DIR)

    /**
     * 把一棵委派树渲染成可读文本。
     *
     * 输出的是**结构**（谁派的谁、什么时候、结论是什么），不是正文：树可能很大，
     * 一次把全部正文拉进上下文没有意义。需要细节时按节点 id 单独取正文（见 [contentOf]）。
     *
     * 缩进表达层级，父在子之前——顺序由查询保证（按 started_at 升序）。
     */
    fun renderTree(nodes: List<WorldTraceEntity>, nowMs: Long): String {
        if (nodes.isEmpty()) return ""
        val depthOf = mutableMapOf<String, Int>()
        return nodes.joinToString("\n") { node ->
            val depth = node.parentId.takeIf { it.isNotBlank() }
                ?.let { parent -> (depthOf[parent] ?: 0) + 1 }
                ?: 0
            depthOf[node.id] = depth
            buildString {
                append("  ".repeat(depth))
                append("- [").append(node.role).append("] ")
                append(WorldKnowledgeLogic.humanAge(nowMs - node.startedAt)).append("前")
                if (node.status != TRACE_STATUS_OK) append("，").append(node.status)
                append("｜").append(node.id)
                if (node.summary.isNotBlank()) {
                    append("｜").append(oneLine(node.summary))
                }
                if (node.contentPath.isNotBlank()) {
                    append("｜正文较大，已存文件")
                } else if (node.content.isNotBlank()) {
                    append("｜正文 ").append(node.contentBytes).append(" 字节")
                }
            }
        }
    }

    private fun oneLine(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(MAX_TRACE_SUMMARY_CHARS)

    /** 树里每条摘要渲染时的字符上限。 */
    const val MAX_TRACE_SUMMARY_CHARS = 160

    /**
     * 节点正常结束的状态标记，与写入方（AgentSubAgentRunner）约定一致。
     *
     * 在这里重中一份而不是引用 agent 层的常量：数据层不该依赖模型层的类型，
     * 两者只靠存进库里的字符串值约定。
     */
    const val TRACE_STATUS_OK = "ok"

    private fun writeContentFile(context: Context, id: String, content: String): String {
        val directory = contentDir(context)
        if (!directory.exists() && !directory.mkdirs()) return ""
        val file = File(directory, "$id.txt")
        return runCatching {
            file.writeText(content)
            file.absolutePath
        }.getOrDefault("")
    }

    /**
     * 裁剪：超出保留条数的旧记录连同其正文文件一起删除。
     *
     * 库里记的是「有什么」，文件是「是什么」，所以裁记录必须连带删文件，
     * 否则文件目录会只涨不降。
     */
    private suspend fun prune(db: WorldDatabase) {
        val overflow = db.traceDao().overflow(KEEP_TRACES)
        if (overflow.isEmpty()) return
        overflow.forEach { row ->
            if (row.contentPath.isNotBlank()) runCatching { File(row.contentPath).delete() }
        }
        db.traceDao().deleteByIds(overflow.map { it.id })
    }
}
