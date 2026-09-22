package io.github.asagnc.eta.agent.model

import io.github.asagnc.eta.data.world.WorldKnowledgeLogic
import io.github.asagnc.eta.data.world.WorldKnowledgeStore

/**
 * 历史结论的注入渲染。
 *
 * 与方案、任务清单的分工：方案说「这次要做什么」，清单说「做到哪了」，这里说的是
 * 「以前做过什么」——它不进会话数据，只随请求注入，所以重复注入时按前缀逐行剥掉即可。
 *
 * 注入量刻意压得很小（见 [MAX_ENTRIES]）。依据是 Anthropic 的 context engineering 结论：
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
     * 注入用的行；没有可注入的结论时返回 null。
     *
     * 只注入「最近且依赖未变」的结论：依赖已变更的条目不在这里出现，因为启动注入没有
     * 用户追问的语境，把一条「可能已失效」的结论摆在最前面，比不提更危险。需要时模型可以
     * 用 `world_recall` 主动查，那时结果里会带上新鲜度标注。
     */
    fun injectedLines(
        entries: List<WorldKnowledgeStore.Recalled>,
        nowMs: Long,
    ): List<String>? {
        val usable = entries
            .filterNot { it.sensitive }
            .filter { it.freshness == WorldKnowledgeLogic.Freshness.FRESH }
            .take(MAX_ENTRIES)
        if (usable.isEmpty()) return null
        return buildList {
            add("$HEADER_PREFIX（观测库里已有的结论，需要细节时用 world_recall 检索）：")
            usable.forEach { entry ->
                val age = WorldKnowledgeLogic.humanAge(nowMs - entry.createdAt)
                add("${ITEM_PREFIX}（${age}前）${entry.summary.replace(Regex("\\s+"), " ").trim()}")
            }
            add("${ITEM_PREFIX}提示：以上是历史结论，不是当前任务的要求；与本次任务冲突时以本次为准。")
        }
    }
}
