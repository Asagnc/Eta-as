package io.github.asagnc.sta.agent.model

/**
 * 连续/反复失败模式的检测。
 *
 * 模型有时会拿同一个工具按同样的参数反复试，每次都撞同一堵墙：错误码和消息一模一样，却还在原地重试。
 * 这是可以确定的外部反馈（错误来自真实执行，不是模型自己的猜测），所以在运行中就该提醒它换做法，
 * 而不是等运行结束再写一句总结给用户看。
 *
 * 两种情况都要抓：
 * - 连续同因失败：一直在原地撞同一堵墙，最典型的卡死；
 * - 同一原因反复出现（中间换过别的方式）：绕了一圈又回来，问题从没真正解决。
 * 只按连续判定会漏掉后者，而后者在实际使用里更常见。
 */
internal class AgentFailureGuard(private val threshold: Int = DEFAULT_THRESHOLD) {

    enum class Kind { NONE, CONSECUTIVE, REPEATED }

    data class Verdict(
        val kind: Kind,
        val consecutive: Int,
        val total: Int,
    ) {
        val shouldNudge: Boolean get() = kind != Kind.NONE
    }

    private var lastSignature: String? = null
    private var streak = 0

    /** 本次运行里每个失败签名出现过的总次数，不因中间成功或换做法而清空。 */
    private val totals = mutableMapOf<String, Int>()

    fun observe(signature: String): Verdict {
        streak = if (signature == lastSignature) streak + 1 else 1
        lastSignature = signature
        val total = (totals[signature] ?: 0) + 1
        totals[signature] = total
        val kind = when {
            streak >= threshold && streak % threshold == 0 -> Kind.CONSECUTIVE
            total % threshold == 0 -> Kind.REPEATED
            else -> Kind.NONE
        }
        return Verdict(kind = kind, consecutive = streak, total = total)
    }

    /** 成功后调用：断了连续失败，计数归零；累计次数保留，因为"这个坑出现过几次"是整轮的事实。 */
    fun reset() {
        lastSignature = null
        streak = 0
    }

    companion object {
        /** 连续两次同样的失败就值得提醒：一次可能是偶发，两次说明原样重试没有意义。 */
        const val DEFAULT_THRESHOLD = 2
    }
}
