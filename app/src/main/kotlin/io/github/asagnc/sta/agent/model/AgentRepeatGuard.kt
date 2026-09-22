package io.github.asagnc.sta.agent.model

/**
 * 连续重复工具调用的检测。
 *
 * 模型偶尔会连着提交完全相同的工具调用（同一工具、同一参数），既拿不到新信息又把上下文越堆越高。
 * 这里只判断“要不要提醒”，是否中断交给调用方：同一批调用连续重复到阈值时返回 true，之后每再满一个
 * 阈值再提醒一次，避免只提醒一次后继续空转。
 */
internal class AgentRepeatGuard(private val threshold: Int = DEFAULT_THRESHOLD) {
    private var lastSignature: String? = null
    private var streak = 0

    fun observe(signature: String): Boolean {
        if (signature == lastSignature) {
            streak++
        } else {
            lastSignature = signature
            streak = 1
            return false
        }
        return streak >= threshold && streak % threshold == 0
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3
    }
}
