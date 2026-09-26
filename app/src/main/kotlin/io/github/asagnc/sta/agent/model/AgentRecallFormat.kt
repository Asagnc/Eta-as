package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.data.world.WorldKnowledgeLogic
import io.github.asagnc.sta.data.world.WorldKnowledgeStore

/**
 * 历史结论的注入渲染。
 *
 * 与方案、任务清单的分工：方案说「这次要做什么」，清单说「做到哪了」，这里说的是
 * 「以前做过什么」——它不进会话数据，只随请求注入，所以重复注入时按前缀逐行剥掉即可。
 *
 * 注入量刻意压得很小（见 [MAX_ENTRIES]）。依据是 Anthropic 的 context engineering 结论：
 * 记忆应当 just-in-time 按需取用，而不是预先全量加载——预先塞满上下文会挤占注意力预算，
 * 而模型真正需要的往往只是其中一小部分。所以这里只列结论标题，让模型知道
 * 「世界里有这些东西」，正文交给它自己用 `world_recall` 查。
 */
internal object AgentRecallFormat {

    /** 表头行前缀：重复注入时按它整行剥掉。 */
    const val HEADER_PREFIX = "历史结论"

    /** 条目行前缀：唯一到不可能出现在正常内容里。 */
    const val ITEM_PREFIX = "[world] "

    /**
     * 注入的条目数上限。
     *
     * 只列标题、不列结论正文：正文按需用 `world_recall` 取。给足条数让模型知道
     * 「世界里有哪几个方向的东西」，比只报一个总数有用，而标题是单行的，总量仍然有界。
     */
    const val MAX_ENTRIES = 8

    /** 「已在会话里出现过」判定用的前缀长度。 */
    const val PRESENCE_PROBE_CHARS = 48

    /** 标题行末尾点明完整结论的取回方式。 */
    private const val DETAIL_HINT = "（完整结论用 world_recall 按关键词检索）"

    /**
     * 注入用的行；没有可注入的结论时返回 null。
     *
     * 只注入「最近、依赖未变、形态可复用」的结论标题，不注入正文：正文按需用
     * `world_recall` 取。这样注入量不随结论长短变化，也不会因为存量里混着形态异常的
     * 历史数据而把无关内容摆到模型面前。
     */
    fun injectedLines(
        entries: List<WorldKnowledgeStore.Recalled>,
        nowMs: Long,
        alreadyInContext: (String) -> Boolean = { false },
    ): List<String>? {
        val usable = entries
            .filterNot { it.sensitive }
            // 形态不可复用的存量条目不但不该注入正文，连标题也不该占位：它没有可复用知识。
            .filter { WorldKnowledgeLogic.isUsableStoredSummary(it.summary) }
            .filter { it.freshness == WorldKnowledgeLogic.Freshness.FRESH }
            // 会话里已经出现过的结论不再注入：模型已经从工具结果里读到过它，再摆一遍只是
            // 重复占预算（依据同上：just-in-time 优于预先全量加载）。
            .filterNot { alreadyInContext(probeOf(it.summary)) }
            .take(MAX_ENTRIES)
        if (usable.isEmpty()) return null
        return buildList {
            add("$HEADER_PREFIX（观测库里可复用的结论，此处只列标题，正文按需检索）：")
            usable.forEach { entry ->
                val age = WorldKnowledgeLogic.humanAge(nowMs - entry.createdAt)
                add("${ITEM_PREFIX}（${age}前）${WorldKnowledgeLogic.titleOf(entry.summary)}")
            }
            add("${ITEM_PREFIX}提示：以上是历史结论，不是本次任务的要求；与本次任务冲突时以本次为准。$DETAIL_HINT")
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
