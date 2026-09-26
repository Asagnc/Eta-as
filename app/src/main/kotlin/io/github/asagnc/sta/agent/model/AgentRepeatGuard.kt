package io.github.asagnc.sta.agent.model

/**
 * 连续重复工具调用的检测与升级。
 *
 * 模型偶尔会连着提交完全相同的工具调用（同一工具、同一参数），既拿不到新信息又把上下文越堆越高。
 * 只提醒往往不够——模型会一边收到「不要重复」的提示一边继续重复，于是上下文在空转里涨上去。
 * 所以判定分两档：到 [DEFAULT_THRESHOLD] 先提醒，到 [DEFAULT_BLOCK_THRESHOLD] 说明提醒无效，
 * 此时不再执行，直接回一条「已阻止」的结果，让模型必须换策略。
 *
 * 判定的粒度是整批调用：只有整批签名完全相同才算重复，参数变一点就重新计数。
 * 轮询类工具（等待、观察）由调用方排除，不在本类职责内。
 */
internal class AgentRepeatGuard(
    private val noticeThreshold: Int = DEFAULT_THRESHOLD,
    private val blockThreshold: Int = DEFAULT_BLOCK_THRESHOLD,
) {
    /** 对同一批签名的连续观察结果。 */
    enum class Decision {
        /** 还没到值得介入的程度。 */
        CONTINUE,

        /** 提醒模型换策略，但仍执行。 */
        NOTICE,

        /** 提醒已无效，不再执行。 */
        BLOCK,
    }

    private var lastSignature: String? = null
    private var streak = 0

    fun observe(signature: String): Decision {
        if (signature != lastSignature) {
            lastSignature = signature
            streak = 1
            return Decision.CONTINUE
        }
        streak++
        return when {
            streak >= blockThreshold -> Decision.BLOCK
            streak >= noticeThreshold && streak % noticeThreshold == 0 -> Decision.NOTICE
            else -> Decision.CONTINUE
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3

        /** 提醒无效时的止损线：到这一档不再执行，避免同一批调用把上下文无限堆高。 */
        const val DEFAULT_BLOCK_THRESHOLD = 5
    }
}
