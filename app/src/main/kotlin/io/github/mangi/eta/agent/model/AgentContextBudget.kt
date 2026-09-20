package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** usage 只校准同一模型的请求估算，不把累计计费用量当作窗口占用。 */
internal class AgentContextBudget(
    private val window: Int?,
    /** 上次学到的实测上限；由持久化层传入，避免每次新会话都要先撞一次墙。 */
    initialCeiling: Int? = null,
    /** 学到更小的上限时回调，供持久化层记录。 */
    private val onCeilingLearned: ((Int) -> Unit)? = null,
) {
    /** 模型上下文窗口；为空表示当前 provider 没有声明窗口，此时不做占用提示。 */
    val windowTokens: Int?
        get() = window

    /**
     * 服务端实际拒收过的上下文规模。中转站与自建网关常虚标窗口（声明 1M，几百 K 就报
     * 上下文超限），只信声明值会让「显示不到一半就压缩」反复出现；这里记下实测上限，
     * 之后的触发判断与占用提示都按 声明窗口 与 实测上限 的较小值算。
     */
    private var overflowCeiling: Int? = initialCeiling?.takeIf { it > 0 }

    /** 触发判断与占用提示实际使用的窗口。 */
    val effectiveWindow: Int?
        get() {
            val declared = window?.takeIf { it > 0 }
            val ceiling = overflowCeiling?.takeIf { it > 0 }
            return when {
                declared == null -> ceiling
                ceiling == null -> declared
                else -> minOf(declared, ceiling)
            }
        }

    /** 服务端报告上下文超限时记下当时的规模，多次取最小。 */
    fun noteOverflow(tokens: Int) {
        if (tokens <= 0) return
        val current = overflowCeiling
        val updated = if (current == null) tokens else minOf(current, tokens)
        if (updated != current) {
            overflowCeiling = updated
            onCeilingLearned?.invoke(updated)
        }
    }

    private var calibration = 1.0
    private var observedInputTokens = 0

    /**
     * 用服务端回报的真实 input token 校准本地字符估算。
     *
     * 校准系数双向生效：中文与 JSON 密集的会话里，字符估算（非 ASCII 每字符按 1 token）
     * 明显高于真实 token 数，如果只允许放大系数，估算就会长期虚高，
     * 远未触及真实窗口就触发占用提示与压缩——「1M 窗口只用了一半就说快满了」即来自这里。
     */
    fun observe(usage: AgentTokenUsage?, requestEstimate: Int) {
        val input = usage?.inputTokens ?: usage?.contextTokens ?: return
        if (input > 0 && requestEstimate > 0) {
            calibration = (input.toDouble() / requestEstimate).coerceIn(MIN_CALIBRATION, MAX_CALIBRATION)
            observedInputTokens = input
        }
    }

    /**
     * 估算只负责增量：结果不会低于上一次请求由服务端回报的真实 input token 数，
     * 避免估算虚低时把已经发生的上下文占用当成可用空间。
     */
    fun estimate(messages: JSONArray, tools: JSONArray): Int {
        val estimated = ceil(rawEstimate(messages, tools) * calibration).toInt()
        return maxOf(estimated, observedInputTokens)
    }

    /**
     * 只用于「压缩后有没有变小」的比较：不带 [observedInputTokens] 地板。
     *
     * 带地板的 [estimate] 恒 ≥ 上一次服务端回报的真实 input，而溢出恢复时 effectiveWindow
     * 也被压到同一量级，于是 `shouldCompact` 永远为真：压缩循环每轮白烧一次摘要调用，
     * 走满上限后抛 CONTEXT_NO_REDUCTION。比较压缩前后的缩减量必须用这个视图。
     */
    fun estimateForReduction(messages: JSONArray, tools: JSONArray): Int =
        ceil(rawEstimate(messages, tools) * calibration).toInt()

    fun shouldCompact(tokens: Int): Boolean =
        effectiveWindow?.let { tokens >= it * TRIGGER_RATIO } == true

    fun exceedsWindow(tokens: Int): Boolean = effectiveWindow?.let { tokens >= it } == true

    companion object {
        /**
         * 触发压缩的窗口占比。
         *
         * 留 25% 余量：压缩本身要再发一次大请求（把整段历史读进去做摘要），贴着上限才压
         * 很容易连锁溢出；而且溢出后服务端会直接拒绝请求，比提前压缩的代价大得多。
         * 主流实现里 Codex CLI 把生效窗口上限定在 90%、社区实测的最优区间是 85–90%，
         * 这里更保守一些。
         */
        const val TRIGGER_RATIO = 0.75
        /** 估算校准的下限与上限：单次异常 usage 不应让估算失控。 */
        const val MIN_CALIBRATION = 0.25
        const val MAX_CALIBRATION = 8.0
        const val RECENT_MESSAGES = 4
        const val RECENT_RATIO = 0.20
        const val MAX_OVERFLOW_ATTEMPTS = 3

        fun textTokens(text: String): Int {
            var ascii = 0
            var other = 0
            text.codePoints().forEach { if (it < 128) ascii++ else other++ }
            return (ascii + 2) / 3 + other
        }

        fun rawEstimate(messages: JSONArray, tools: JSONArray = JSONArray()): Int {
            var tokens = textTokens(tools.toString()) + 16
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val copy = JSONObject()
                message.keys().forEach { key -> if (key != "content") copy.put(key, message.get(key)) }
                val parts = message.optJSONArray("content")
                if (parts != null) {
                    val text = JSONArray()
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") in setOf("image_url", "input_image", "image")) {
                            tokens += 4096
                        } else text.put(part)
                    }
                    copy.put("content", text)
                } else copy.put("content", message.opt("content"))
                tokens += textTokens(copy.toString()) + 8
            }
            return tokens
        }
    }
}
