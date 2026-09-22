package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.data.world.WorldKnowledgeLogic
import io.github.asagnc.sta.data.world.WorldKnowledgeStore

/**
 * 历史结论的注入渲染。
 *
 * 与方案、任务清单的分工：方案说「这次要做什么」，清单说「做到哪了」，这里说的是
 * 「以前做过什么」——它不进会话数据，只随请求注入，所以重复注入时按前缀逐行剥掉即可。
 *
 * 注入量刻意压得很小（见 [MAX_ENTRIES]、[MAX_SUMMARY_CHARS]）。依据是 Anthropic 的 context engineering 结论：
 * 记忆应当 just-in-time 按需取用，而不是预先全量加载——预先塞满上下文会挤占注意力预算，
 * 而模型真正需要的往往只是其中一小部分。这里的注入只负责让模型知道「世界里有这些东西」，
 * 细节交给它自己用 `world_recall` 查。
 */
internal object AgentRecallFormat {

    /** 表头行前缀：重复注入时按它整行剥掉。 */
    const val HEADER_PREFIX = "历史结论"

    /** 条目行前缀：唯一到不可能出现在正常内容里。 */
    const val ITEM_PREFIX = "[world] "

    /** 注入的条目数上限。 */
    const val MAX_ENTRIES = 3

    /**
     * 单条注入的字符上限。
     *
     * 写库侧的 summary 本该是一句结论，但子智能体可能把整份报告塞进去（实测一条 1900 字，
     * 内含证据、不确定、已排除范围与结尾备注）。这里做最后一道闸：超限就截断并指向
     * `world_recall`——宁可少给，也不能让一条历史结论每轮吃掉上千字的注意力预算。
     */
    const val MAX_SUMMARY_CHARS = 200

    /** 「已在会话里出现过」判定用的前缀长度。 */
    const val PRESENCE_PROBE_CHARS = 48

    /** 截断尾巴：说明细节还在，别让模型以为结论只有这么点。 */
    private const val TRUNCATED_SUFFIX = "……（已截断，细节用 world_recall 查）"

    /**
     * 注入用的行；没有可注入的结论时返回 null。
     *
     * 只注入「最近且依赖未变」的结论：依赖已变更的条目不在这里出现，因为启动注入没有
     * 用户追问的语境，把一条「可能已失效」的结论摆在最前面，比不提更危险。需要时模型可以
     * 用 `world_recall` 主动查，那时结果里会带上新鲜度标注。
     */
    fun injectedLines(
        entries: List<WorldKnowledgeStore.Recalled>,
        nowMs: Long,
        alreadyInContext: (String) -> Boolean = { false },
    ): List<String>? {
        val usable = entries
            .filterNot { it.sensitive }
            // 空集结论（「查不到」型）不进注入：它没有可复用的知识。写库侧已经拦了新的，
            // 这里再拦一次是为了兜住修复之前就已入库的历史脏数据。
            .filterNot { WorldKnowledgeLogic.isInconclusiveConclusion(it.summary) }
            .filter { it.freshness == WorldKnowledgeLogic.Freshness.FRESH }
            // 会话里已经出现过的结论不再注入：模型已经从工具结果里读到过它，再摆一遍只是
            // 重复占预算（依据同上：just-in-time 优于预先全量加载）。
            .filterNot { alreadyInContext(probeOf(it.summary)) }
            .take(MAX_ENTRIES)
        if (usable.isEmpty()) return null
        return buildList {
            add("$HEADER_PREFIX（观测库里已有的结论，需要细节时用 world_recall 检索）：")
            usable.forEach { entry ->
                val age = WorldKnowledgeLogic.humanAge(nowMs - entry.createdAt)
                val summary = entry.summary.replace(Regex("\\s+"), " ").trim().let { text ->
                    if (text.length <= MAX_SUMMARY_CHARS) {
                        text
                    } else {
                        text.take(MAX_SUMMARY_CHARS) + TRUNCATED_SUFFIX
                    }
                }
                add("${ITEM_PREFIX}（${age}前）$summary")
            }
            add("${ITEM_PREFIX}提示：以上是历史结论，不是当前任务的要求；与本次任务冲突时以本次为准。")
        }
    }

    /**
     * 「这条结论是否已经在会话里出现过」的探针：取结论开头一段做子串匹配。
     *
     * 用内容而不是 id 或时间：会话被压缩过之后，早先的工具结果已经不在上下文里，那时同一条
     * 结论就该重新注入——内容匹配天然处理了这种情况。
     */
    fun probeOf(summary: String): String =
        summary.replace(Regex("\\s+"), " ").trim().take(PRESENCE_PROBE_CHARS)
}
