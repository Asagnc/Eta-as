package io.github.asagnc.sta.agent.model

/**
 * 子智能体的复杂度档位与预算评估。
 *
 * 预算不写死：每次子代理跑完都把实际消耗记进数据库，下一次同档位的委派按历史分位数估算；
 * 样本不足时退回档位默认值。档位划分参考多智能体工程实践（简单查找不值得派生、对比类
 * 2-4 个子代理、复杂研究才放开），避免给简单任务起一堆子代理把成本放大十几倍。
 */
internal enum class SubAgentScope(
    val wire: String,
    val defaultRounds: Int,
    val defaultTokens: Int,
    val minTokens: Int,
    val maxTokens: Int,
    val label: String,
) {
    QUICK("quick", 4, 18_000, 10_000, 30_000, "单点查找、单文件定位"),
    COMPARE("compare", 8, 35_000, 20_000, 60_000, "多方向对比、需要翻若干文件"),
    DEEP("deep", 12, 55_000, 30_000, 90_000, "大范围检索、需要交叉验证"),
    ;

    companion object {
        /** 未给出或无法识别时按中间档处理，宁可多给一点预算也不要让子代理半途断掉。 */
        fun fromWire(value: String?): SubAgentScope {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == normalized } ?: COMPARE
        }
    }
}

/** 一条历史样本：某次子代理运行的实际消耗。 */
internal data class SubAgentSample(val tokens: Int, val rounds: Int, val ok: Boolean)

/** 本次委派使用的预算，以及它的来源（结果里会交代给模型，便于它判断要不要收敛）。 */
internal data class SubAgentPlan(
    val scope: SubAgentScope,
    val maxRounds: Int,
    val tokenBudget: Int,
    val sampleCount: Int,
    val fromHistory: Boolean,
)

internal object AgentSubAgentBudget {
    /** 少于这么多条样本时统计没有意义，直接用档位默认值。 */
    private const val MIN_SAMPLES = 4

    /** 在 P75 之上留的余量：多数任务能跑完，又不至于一路撑到档位上限。 */
    private const val HEADROOM_PERCENT = 125

    fun plan(scope: SubAgentScope, samples: List<SubAgentSample>): SubAgentPlan {
        val usable = samples.filter { it.tokens > 0 }
        if (usable.size < MIN_SAMPLES) {
            return SubAgentPlan(
                scope = scope,
                maxRounds = scope.defaultRounds,
                tokenBudget = scope.defaultTokens,
                sampleCount = usable.size,
                fromHistory = false,
            )
        }
        // 失败样本多半是中途断掉的，会低估真实需求，所以优先用成功样本拟合。
        val reference = usable.filter { it.ok }.ifEmpty { usable }
        val tokens = percentile(reference.map { it.tokens }, 75) * HEADROOM_PERCENT / 100
        val rounds = percentile(reference.map { it.rounds }.filter { it > 0 }, 75)
        return SubAgentPlan(
            scope = scope,
            maxRounds = rounds.coerceIn(2, scope.defaultRounds * 2),
            tokenBudget = tokens.coerceIn(scope.minTokens, scope.maxTokens),
            sampleCount = usable.size,
            fromHistory = true,
        )
    }

    /** 最近优先的样本由调用方按时间倒序传入；空集合返回 0，由上层回退到默认值。 */
    fun percentile(values: List<Int>, percent: Int): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val rank = (percent.coerceIn(0, 100) / 100.0 * sorted.size).toInt()
        return sorted[rank.coerceIn(0, sorted.size - 1)]
    }
}
