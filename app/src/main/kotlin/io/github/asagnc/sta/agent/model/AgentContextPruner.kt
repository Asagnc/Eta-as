package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 请求视图的确定性投影：工具结果按长度截断，历史助手推理按长度折叠。
 *
 * ## 为什么必须「确定性」
 *
 * 提示缓存按完整匹配的前缀单元生效：历史里任何一条消息的内容变化，都会让从它开始往后的
 * 缓存全部失效，那些 token 要按未命中的全价重算（DeepSeek 上命中 0.02 元/M、未命中
 * 1 元/M，差 50 倍）。所以一条工具结果怎么处置，必须在它进入请求视图时就定下来，
 * 并且此后每轮算出的结果逐字节相同——历史才是 append-only 的。
 *
 * 早先的做法是「保留最近 N 条完整、其余整条换成占位符」。它按**相对位置**决定裁谁，
 * 边界随轮次不断前移，于是每轮都有一条历史中部的消息被改写。实测：把一个 20 条消息的
 * 请求里中间一条改掉，命中率从 97% 掉到 43%，且其后全部按未命中计价。
 * 这里改成**纯长度判据**：超过 [MAX_CHARS] 就截成「头 [HEAD_CHARS] + 省略标记 +
 * 尾 [TAIL_CHARS]」。于是每条结果只剩两种稳定形态（完整 / 截断），
 * 不再有「今天完整、明天被抹掉」的中间态。
 *
 * ## 历史推理：折叠，不是丢弃
 *
 * thinking 模式下回传的 reasoning_content 会随轮次不断累积：实测一次 30 轮的会话里，
 * 「历史」占 335k 上下文的 54%，其中大部分是各轮推理草稿。但推理本身是有用的——
 * 它是任务进展的理由，直接删掉会让模型重复已完成的分析。
 *
 * 所以这里按长度折叠：超过 [MAX_REASONING_CHARS] 就只保留头 [REASONING_HEAD_CHARS]
 * 与尾 [REASONING_TAIL_CHARS]。头部是意图（「我需要先确认…」），尾部是结论
 * （「所以应该…」），被丢掉的是中间的论证过程。实测（带 tools 的请求）折叠后的推理
 * 仍被接受，且模型能据此正确接着回答。
 *
 * 全部历史一律按同一规则折叠，不做「最近的保留完整」：位置相关的规则会随轮次移动，
 * 每轮改写一条历史中部的消息，提示缓存的前缀就跟着失效。全文仍在会话记录与归档里，
 * 需要细节时用 conversation_history 检索。
 *
 * ## 其它约束
 *
 * - 只作用于请求视图：会话记录与归档仍保留完整的工具结果与推理。
 * - [truncate] 必须幂等：输出长度不超过阈值，否则下一轮会继续截，稳定性又没了。
 * - content 为分片数组（图片等）或瞬时观察消息不参与截断。
 */
internal object AgentContextPruner {

    /** 超过这个字符数的工具结果会被截断。默认对齐 dsh 的 8192。 */
    const val MAX_CHARS = 8192

    /** 截断后保留的头部字符数。 */
    const val HEAD_CHARS = 4096

    /** 截断后保留的尾部字符数；命令输出的结论与报错通常在尾部。 */
    const val TAIL_CHARS = 1024

    /** 历史助手推理的折叠阈值；超过就只保留头尾。 */
    const val MAX_REASONING_CHARS = 600

    /** 折叠后保留的推理头部字符数；推理开头是意图。 */
    const val REASONING_HEAD_CHARS = 400

    /** 折叠后保留的推理尾部字符数；推理结论在结尾。 */
    const val REASONING_TAIL_CHARS = 200

    /** 中间被换掉的文本模板，%d 是省略的字符数。 */
    private const val ELLIPSIS = "\n\n[... 此处省略 %d 个字符 ...]\n\n"

    /** 截断后的 JSON 里追加的说明键，让模型知道这是被截断的版本。 */
    private const val TRUNCATED_KEY = "_sta_truncated_chars"

    /** 截断后告知模型去哪里找完整内容；重读工具会重复付出一次完整调用。 */
    private const val NOTE_KEY = "_sta_note"

    private const val TRUNCATION_NOTE = "原始内容可用 conversation_history 检索"

    /**
     * 请求视图的构造结果：新的消息数组 + 本次截断的条数。
     *
     * 返回对象而不是「改全局变量记录条数」：`AgentLoop` 可能并发跑，可变共享状态会串数据。
     */
    data class PruneResult(
        val messages: JSONArray,
        val prunedCount: Int,
    )

    /**
     * 返回请求视图：把超过 [maxChars] 字符的工具结果截断。
     *
     * [maxChars] 小于等于 0 表示不截断工具结果；历史推理的归一与它无关，始终执行。
     *
     * 返回值是新数组，但**只有被改写的消息是克隆的**：其余消息对象与入参共享，因为
     * 调用方 [AgentLoop.requestMessagesFor] 之后只会改最后一条消息，而它由
     * [AgentRequestContext.attach] 单独克隆。
     */
    fun prune(messages: JSONArray, maxChars: Int): PruneResult {
        val result = JSONArray()
        var truncatedCount = 0
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index)
            if (message == null) {
                result.put(messages.opt(index))
                continue
            }
            val truncated = truncatedContent(message, maxChars)
            if (truncated != null) {
                result.put(copyWith(message, "content", truncated))
                truncatedCount++
                continue
            }
            if (needsReasoningFold(message)) {
                result.put(copyWith(message, "reasoning_content", foldedReasoning(message)))
                continue
            }
            if (isTransient(message)) {
                // 瞬时观察（工具截图）不能共享：它在下一轮会被从历史里按引用摘掉
                // （见 AgentLoop.discardPendingToolImageMessage），视图若持有同一个对象，
                // 截图就会在请求里多留一轮。克隆一份让视图与历史彻底解耦。
                result.put(cloneOf(message))
                continue
            }
            result.put(message)
        }
        return PruneResult(result, truncatedCount)
    }

    /** 需要截断的工具结果内容；不满足条件时返回 null。判据只看内容长度，与位置无关。 */
    private fun truncatedContent(message: JSONObject, maxChars: Int): String? {
        if (maxChars <= 0) return null
        if (message.optString("role") != "tool") return null
        if (isTransient(message)) return null
        val content = message.opt("content")
        if (content !is String) return null
        return truncate(content, maxChars)
    }

    /**
     * 该条是否带着需要折叠的助手推理。
     *
     * 只看内容长度，不看它在历史里的位置：位置相关的规则会随轮次移动，
     * 每轮改写一条历史中部的消息，缓存前缀就跟着失效。
     */
    private fun needsReasoningFold(message: JSONObject): Boolean =
        message.optString("role") == "assistant" &&
            message.optString("reasoning_content").length > MAX_REASONING_CHARS

    /** 折叠后的助手推理：头（意图）+ 省略标记 + 尾（结论）。 */
    private fun foldedReasoning(message: JSONObject): String =
        headTail(
            message.optString("reasoning_content"),
            MAX_REASONING_CHARS,
            REASONING_HEAD_CHARS,
            REASONING_TAIL_CHARS,
        )

    /** 浅拷贝一条消息并改写其中一个字段；其余字段的值对象仍与入参共享。 */
    private fun copyWith(message: JSONObject, key: String, value: Any): JSONObject {
        val copy = JSONObject()
        message.keys().forEach { field -> copy.put(field, message.get(field)) }
        copy.put(key, value)
        return copy
    }

    /**
     * 确定性截断：同一个输入永远返回同一个输出，且输出长度不超过 [maxChars]。
     *
     * 工具结果的载荷几乎都在一个字符串字段里（`content`、`output` 之类），直接对整串做
     * 头尾截断会切出无效 JSON，模型难以解析。所以先尝试解析成对象，只截最长的那个字符串
     * 字段，其余字段（`ok`、`path` 等状态信息）原样保留；解析不出来再退化成整串截断。
     *
     * @return 截断后的内容；不需要截断时返回 null。
     */
    private fun truncate(content: String, maxChars: Int): String? {
        if (content.length <= maxChars) return null
        val parsed = runCatching { JSONObject(content) }.getOrNull()
        if (parsed != null) {
            val longest = parsed.keys().asSequence()
                .filter { parsed.opt(it) is String }
                .maxByOrNull { parsed.optString(it).length }
            if (longest != null && parsed.optString(longest).length > maxChars) {
                val text = parsed.optString(longest)
                parsed.put(longest, headTail(text, maxChars, HEAD_CHARS, TAIL_CHARS))
                parsed.put(TRUNCATED_KEY, text.length)
                parsed.put(NOTE_KEY, TRUNCATION_NOTE)
                val rebuilt = parsed.toString()
                // 其余字段可能仍占空间，整体仍超阈值时退化成整串截断，
                // 否则下一轮会再截一次，稳定性就没了。
                if (rebuilt.length <= maxChars) return rebuilt
            }
        }
        return headTail(content, maxChars, HEAD_CHARS, TAIL_CHARS)
    }

    /**
     * 头 + 省略标记 + 尾。
     *
     * 预算按标记的实际长度倒推，保证 `head + marker + tail <= maxChars`——
     * 这是折叠与截断幂等的前提。
     */
    private fun headTail(text: String, maxChars: Int, headChars: Int, tailChars: Int): String {
        val omitted = maxOf(text.length - headChars - tailChars, 0)
        val marker = ELLIPSIS.format(omitted)
        // 阈值小到连省略标记都放不下时只能硬截：输出若超过阈值，下一轮会再截一次，
        // 稳定性就没了。
        if (maxChars <= marker.length) return text.take(maxChars)
        val budget = maxChars - marker.length
        // 头部优先取满，剩余才给尾部：头部通常承载路径、参数等定位信息，
        // 尾部是结论与报错，中间才是可以丢的部分。
        val head = minOf(headChars, budget)
        val tail = minOf(tailChars, budget - head)
        return text.take(head) + marker + text.takeLast(tail)
    }

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
