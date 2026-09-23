package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 较早期工具结果的清理。
 *
 * 深处的工具原始结果模型通常不再需要，把它们换成占位内容是最安全、最轻量的上下文压缩方式：
 * 它不动历史里的助手推理与用户输入，也不改写摘要，只让请求体变短。清理只作用于请求视图，
 * 会话记录与归档仍保留完整结果。
 *
 * 这是每轮请求的必经路径，且开销随对话长度线性增长，所以拷贝策略要按"只碰要改的消息"来写：
 * 早先的实现用 `JSONArray(messages.toString())` 整体深拷贝，在 120 轮 / 每条 4000 字的
 * 规模下实测 33ms 一次，而其中真正需要改写的通常只有几条。
 */
internal object AgentContextPruner {
    const val PRUNED_NOTE = "较早的工具结果已清理；需要原始内容时请重新调用对应工具。"

    /** 已经是占位大小以下的结果不再处理，避免反复改写同一处。 */
    private const val MIN_PRUNABLE_CHARS = 200

    /** 最近这些 token 内的工具结果一律不裁剪。 */
    const val PROTECTED_TOKENS = 40_000

    /**
     * 请求视图的构造结果：新的消息数组 + 本次裁掉的条数。
     *
     * 返回对象而不是"改全局变量记录条数"：`AgentLoop` 可能并发跑，可变共享状态会串数据。
     */
    data class PruneResult(
        val messages: JSONArray,
        val prunedCount: Int,
    )

    /**
     * 返回请求视图：除最近 [keepRecentToolResults] 条以外的工具结果替换为占位内容。
     *
     * [keepRecentToolResults] 小于 0 表示不清理，此时直接返回原数组（调用方不应改写它）。
     * 返回值是新数组，但**只有被改写的消息是克隆的**：其余消息对象与入参共享，因为
     * 调用方 [AgentLoop.requestMessagesFor] 之后只会改最后一条消息，而它由
     * [AgentRequestContext.attach] 单独克隆。
     */
    fun prune(messages: JSONArray, keepRecentToolResults: Int): PruneResult {
        if (keepRecentToolResults < 0) return PruneResult(copyShallow(messages), 0)
        val protectedIndexes = protectedIndexes(messages)
        val toolIndexes = (0 until messages.length()).filter { index ->
            messages.optJSONObject(index)?.optString("role") == "tool"
        }
        val prunable = toolIndexes
            .dropLast(keepRecentToolResults)
            .filterNot { index -> index in protectedIndexes }
            .filter { index ->
                val message = messages.optJSONObject(index) ?: return@filter false
                message.optString("content").length > MIN_PRUNABLE_CHARS
            }
            .toHashSet()
        val placeholder = placeholder()
        val result = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index)
            if (message == null) {
                result.put(messages.opt(index))
                continue
            }
            if (index in prunable) {
                // 只有这一条需要改写：浅拷贝字段后替换 content，其余消息保持共享。
                val copy = JSONObject()
                message.keys().forEach { key -> copy.put(key, message.get(key)) }
                copy.put("content", placeholder)
                result.put(copy)
            } else if (isTransient(message)) {
                // 瞬时观察（工具截图）不能共享：它在下一轮会被从历史里按引用摘掉
                // （见 AgentLoop.discardPendingToolImageMessage），视图若持有同一个对象，
                // 截图就会在请求里多留一轮。克隆一份让视图与历史彻底解耦。
                result.put(cloneOf(message))
            } else {
                result.put(message)
            }
        }
        return PruneResult(result, prunable.size)
    }

    /**
     * 顶层浅拷贝：元素仍与入参共享，但数组本身是新的。
     *
     * 视图必须是独立的顶层数组，注入逻辑才能在它上面替换元素而不动到历史
     * ——历史对象一旦被替换，`AgentLoop` 里按引用相等定位瞬时观察消息的删除就会失效。
     */
    private fun copyShallow(messages: JSONArray): JSONArray = JSONArray().also { copy ->
        for (index in 0 until messages.length()) copy.put(messages.opt(index))
    }

    /**
     * 最近的 [PROTECTED_TOKENS] token 是工作区，落到这个范围内的工具结果一律不裁剪。
     * 只按条数保留会在大结果场景下失效：一条几万 token 的输出可能刚好被裁掉，
     * 而模型正在用它推进任务。主流客户端（OpenCode 等）同样按 token 划分保护区。
     *
     * 从尾部往前累加，越线即停，所以实际遍历的条数由保护区大小决定，不随对话总长增长。
     */
    private fun protectedIndexes(messages: JSONArray): Set<Int> {
        val result = mutableSetOf<Int>()
        var accumulated = 0
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            accumulated += AgentContextBudget.messageTokens(message)
            if (accumulated > PROTECTED_TOKENS) break
            result += index
        }
        return result
    }

    fun placeholder(): String = JSONObject()
        .put("ok", true)
        .put("pruned", true)
        .put("note", PRUNED_NOTE)
        .toString()

    /**
     * 瞬时观察：工具截图所在的用户消息。
     *
     * 这类消息的生命周期只有一轮——下一次思考消费后就会被从历史里摘掉
     * （见 `AgentLoop.discardPendingToolImageMessage`，它按引用相等定位）。视图里
     * 若沿用同一个对象，历史虽然摘掉了，视图那份仍会把截图带进后续请求。
     */
    private fun isTransient(message: JSONObject): Boolean = message.optBoolean(TRANSIENT_KEY)

    /** 浅拷贝一条消息：`content` 若是分片数组要单独复制，避免后续改写写回历史。 */
    private fun cloneOf(message: JSONObject): JSONObject {
        val copy = JSONObject()
        message.keys().forEach { key ->
            val value = message.get(key)
            copy.put(
                key,
                if (key == "content" && value is JSONArray) JSONArray(value.toString()) else value,
            )
        }
        return copy
    }

    /** 瞬时观察消息上的标记键，与 `AgentLoop` 追加图片消息时写入的键一致。 */
    private const val TRANSIENT_KEY = "_sta_observation"
}
