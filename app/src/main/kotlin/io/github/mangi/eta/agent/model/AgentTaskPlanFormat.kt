package io.github.mangi.eta.agent.model

import org.json.JSONArray

/**
 * task_plan 快照的纯文本渲染，用于把「当前计划」随请求注入（见 [AgentRequestContext]）。
 *
 * 计划此前只存在对话状态和 UI 里：压缩之后模型就看不到自己排的清单了，跨轮更看不到，
 * 于是会出现用户说「按计划继续」而模型不知道计划是什么的情况。注入成固定前缀的若干行，
 * 既能随时看到，也能在重复注入时按前缀逐行去重。
 */
internal object AgentTaskPlanFormat {
    /** 表头行前缀：重复注入时按它整行剥掉。 */
    const val HEADER_PREFIX = "当前任务清单"

    /** 条目行前缀：唯一到不可能出现在正常内容里，去重时按它逐行剥掉。 */
    const val ITEM_PREFIX = "[task_plan] "

    /** 注入的条目上限：清单再长也不该把请求撑爆，超出部分只报个数。 */
    const val MAX_INJECTED_ITEMS = 20

    data class Item(val id: String, val content: String, val status: String)

    /** 解析 task_plan 的 JSON 快照；格式不对或字段缺失时跳过该条，整体不可解析时返回空列表。 */
    fun parse(planJson: String?): List<Item> {
        val raw = planJson?.takeIf { it.isNotBlank() } ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val content = item.optString("content").trim()
            if (id.isEmpty() || content.isEmpty()) return@mapNotNull null
            Item(id = id, content = content, status = item.optString("status").trim().ifBlank { "pending" })
        }
    }

    /** 注入用的行；计划为空、或已经没有未完成项时返回 null——做完的计划不该继续占上下文。 */
    fun injectedLines(planJson: String?): List<String>? {
        val items = parse(planJson)
        if (items.isEmpty()) return null
        val open = items.count { it.status != "completed" }
        if (open == 0) return null
        val shown = items.take(MAX_INJECTED_ITEMS)
        val activeIndex = items.indexOfFirst { it.isActive() }
        return buildList {
            add("$HEADER_PREFIX（${items.size - open}/${items.size} 完成，$open 项未完成）：")
            // 断点提示：上一轮结束时停在哪一项，决定了「继续」该从哪开始。没有这一行，
            // 模型会把进行中的项当成还没开始，于是重做一遍、或者干脆不动。
            if (activeIndex >= 0) {
                add(
                    "$ITEM_PREFIX" + "提示：上次运行停在第 ${activeIndex + 1} 步，" +
                        "先把那一项标回 in_progress 再继续，已经完成的项不要重做。",
                )
            }
            shown.forEach { add("$ITEM_PREFIX${marker(it.status)} ${it.id} ${it.content}") }
            if (items.size > shown.size) add("$ITEM_PREFIX…其余 ${items.size - shown.size} 项略")
        }
    }

    /** 仍停在某一步的项：新数据写 in_progress，旧数据写的是 interrupted。 */
    private fun Item.isActive(): Boolean = status == "in_progress" || status == "interrupted"

    private fun marker(status: String): String = when (status) {
        "completed" -> "[x]"
        // interrupted 是旧版本写入的第四态，语义就是「停在这一步」，按进行中渲染。
        "in_progress", "interrupted" -> "[>]"
        else -> "[ ]"
    }
}
