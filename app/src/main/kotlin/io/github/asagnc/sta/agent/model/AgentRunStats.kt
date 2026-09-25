package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.runtime.AgentTokenUsage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 run 的工具调用与 token 度量。
 *
 * 只读白名单里的工具会并发执行，因此计数按工具名分桶并用原子量累加；每次读取都是快照，
 * 不重置累计值，同一 run 内可以反复取用。
 */
internal class AgentRunStats {
    private class Bucket {
        val calls = AtomicInteger()
        val failures = AtomicInteger()
        val totalMs = AtomicLong()
        val maxMs = AtomicLong()
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val rounds = AtomicInteger()
    private val parallelBatches = AtomicInteger()
    private val parallelCalls = AtomicInteger()
    private val inputTokens = AtomicLong()
    private val outputTokens = AtomicLong()
    private val cachedTokens = AtomicLong()
    private val reasoningTokens = AtomicLong()
    private val contextTokens = AtomicInteger()
    private val prunedToolResults = AtomicInteger()
    private val contextNotices = AtomicInteger()
    private val requestSystemTokens = AtomicInteger()
    private val requestToolsTokens = AtomicInteger()
    private val requestToolResultTokens = AtomicInteger()
    private val requestHistoryTokens = AtomicInteger()

    val isEmpty: Boolean
        get() = rounds.get() == 0 && buckets.isEmpty()

    fun roundStarted() {
        rounds.incrementAndGet()
    }

    fun recordTool(name: String, durationMs: Long, success: Boolean) {
        val bucket = buckets.computeIfAbsent(name) { Bucket() }
        bucket.calls.incrementAndGet()
        if (!success) bucket.failures.incrementAndGet()
        val elapsed = durationMs.coerceAtLeast(0)
        bucket.totalMs.addAndGet(elapsed)
        bucket.maxMs.accumulateAndGet(elapsed) { current, value -> maxOf(current, value) }
    }

    fun recordParallelBatch(size: Int) {
        if (size <= 1) return
        parallelBatches.incrementAndGet()
        parallelCalls.addAndGet(size)
    }

    /**
     * 最近一次请求视图里被压成占位的工具结果条数。
     * 注意是覆盖而不是累加：同一个结果在后续每一轮都会被重新统计，累加会把数字放大成没有意义的值。
     */
    fun updatePrunedToolResults(count: Int) {
        prunedToolResults.set(count)
    }

    /**
     * 最近一次请求视图的 token 构成（估算）。
     *
     * 同样是覆盖而不是累加：要看的是「现在这一发请求长什么样」，累加只会得到一个跟轮数
     * 绑定的噪音值。它与 [recordUsage] 的服务端真实用量互补——那个说明实际花了多少，
     * 这个说明钱花在哪。
     */
    fun updateRequestComposition(composition: AgentRequestComposition) {
        requestSystemTokens.set(composition.system)
        requestToolsTokens.set(composition.tools)
        requestToolResultTokens.set(composition.toolResults)
        requestHistoryTokens.set(composition.history)
    }

    /** 上下文占用提示实际触发的次数；策略是否值得保留要看它。 */
    fun recordContextNotice() {
        contextNotices.incrementAndGet()
    }

    fun recordUsage(usage: AgentTokenUsage) {
        usage.inputTokens?.let { inputTokens.addAndGet(it.toLong()) }
        usage.outputTokens?.let { outputTokens.addAndGet(it.toLong()) }
        usage.cachedTokens?.let { cachedTokens.addAndGet(it.toLong()) }
        usage.reasoningTokens?.let { reasoningTokens.addAndGet(it.toLong()) }
        usage.contextTokens?.let(contextTokens::set)
    }

    /**
     * 运行结束时只报告确实异常的信号。
     *
     * 调用次数多、裁剪条数、上下文提示次数都不算异常——它们随任务规模自然增长，
     * 按需查 run_stats 即可，主动推送只会制造噪音（之前那版就报了"edit_file、terminal 被反复调用"）。
     * 真正值得说的是某些工具本轮反复失败：那意味着任务多半没真正跑通。
     */
    fun selfReview(): String? {
        val failing = buckets.entries
            .filter { it.value.failures.get() >= 2 }
            .map { "${it.key}（${it.value.failures.get()} 次）" }
            .sorted()
        if (failing.isEmpty()) return null
        return "这些工具本轮反复失败，任务可能没有真正跑通：" + failing.joinToString("、")
    }

    fun snapshot(): JSONObject {
        val tools = JSONArray()
        var calls = 0
        var failures = 0
        var totalMs = 0L
        val sorted = buckets.entries.sortedWith(
            compareByDescending<Map.Entry<String, Bucket>> { it.value.calls.get() }.thenBy { it.key },
        )
        sorted.forEach { (name, bucket) ->
            val bucketCalls = bucket.calls.get()
            calls += bucketCalls
            failures += bucket.failures.get()
            totalMs += bucket.totalMs.get()
            if (tools.length() >= MAX_LISTED_TOOLS) return@forEach
            tools.put(
                JSONObject()
                    .put("name", name)
                    .put("calls", bucketCalls)
                    .put("failed", bucket.failures.get())
                    .put("total_ms", bucket.totalMs.get())
                    .put("max_ms", bucket.maxMs.get()),
            )
        }
        return JSONObject()
            .put("rounds", rounds.get())
            .put("tool_calls", calls)
            .put("tool_failures", failures)
            .put("tool_total_ms", totalMs)
            .put("parallel_batches", parallelBatches.get())
            .put("parallel_calls", parallelCalls.get())
            .put("pruned_tool_results", prunedToolResults.get())
            .put("context_notices", contextNotices.get())
            .put("distinct_tools", buckets.size)
            .put("listed_tools", tools.length())
            .put("tools", tools)
            .put(
                "tokens",
                JSONObject()
                    .put("input", inputTokens.get())
                    .put("output", outputTokens.get())
                    .put("cached", cachedTokens.get())
                    .put("reasoning", reasoningTokens.get())
                    .put("context", contextTokens.get()),
            )
            .put(
                "request_composition",
                JSONObject()
                    .put("system", requestSystemTokens.get())
                    .put("tools", requestToolsTokens.get())
                    .put("history", requestHistoryTokens.get())
                    .put("tool_results", requestToolResultTokens.get()),
            )
    }

    fun summaryText(): String = buildString {
        append("本次 run：").append(rounds.get()).append(" 轮，")
        append(buckets.values.sumOf { it.calls.get() }).append(" 次工具调用")
        val failures = buckets.values.sumOf { it.failures.get() }
        if (failures > 0) append("（失败 ").append(failures).append(" 次）")
        append("，耗时合计 ").append(seconds(buckets.values.sumOf { it.totalMs.get() })).append("；")
        append("并发批次 ").append(parallelBatches.get())
            .append("（覆盖 ").append(parallelCalls.get()).append(" 次调用）。\n")
        if (prunedToolResults.get() > 0) {
            append("最近一次请求压成占位的工具结果 ").append(prunedToolResults.get()).append(" 条；")
        }
        if (contextNotices.get() > 0) {
            append("上下文压力提示生效 ").append(contextNotices.get()).append(" 轮；")
        }
        if (prunedToolResults.get() > 0 || contextNotices.get() > 0) append("\n")
        append("token：输入 ").append(inputTokens.get())
            .append("、输出 ").append(outputTokens.get())
            .append("、缓存命中 ").append(cachedTokens.get())
        val totalInput = inputTokens.get()
        if (totalInput > 0) {
            val cached = cachedTokens.get()
            append("（").append(cached * 100 / totalInput).append("% 命中，未命中 ")
                .append(totalInput - cached).append("）")
        }
        append("、思考 ").append(reasoningTokens.get())
            .append("，最近上下文 ").append(contextTokens.get()).append("。")
        val requestTotal = requestSystemTokens.get() + requestToolsTokens.get() +
            requestToolResultTokens.get() + requestHistoryTokens.get()
        if (requestTotal > 0) {
            append("\n本轮请求构成（估算）：system ").append(requestSystemTokens.get())
                .append("、工具定义 ").append(requestToolsTokens.get())
                .append("、历史 ").append(requestHistoryTokens.get())
                .append("、工具结果 ").append(requestToolResultTokens.get())
                .append("，合计 ").append(requestTotal).append("。")
        }
        val sorted = buckets.entries
            .sortedWith(compareByDescending<Map.Entry<String, Bucket>> { it.value.calls.get() }.thenBy { it.key })
            .take(MAX_LISTED_TOOLS)
        if (sorted.isNotEmpty()) {
            append("\n按调用次数：")
            sorted.forEach { (name, bucket) ->
                append("\n- ").append(name).append(" ×").append(bucket.calls.get())
                if (bucket.failures.get() > 0) append("（失败 ").append(bucket.failures.get()).append(" 次）")
                append("，合计 ").append(seconds(bucket.totalMs.get()))
                    .append("，单次最长 ").append(seconds(bucket.maxMs.get()))
            }
        }
    }

    private fun seconds(millis: Long): String =
        if (millis < 1_000) "${millis}ms" else String.format(java.util.Locale.US, "%.1fs", millis / 1_000.0)

    private companion object {
        const val MAX_LISTED_TOOLS = 8
    }
}
