package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.model.AgentRepeatGuard.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重复调用的分档判定。
 *
 * 先提醒、再阻断的两档设计：只提醒时模型可能一边收到提示一边继续重复，上下文就在空转里涨上去；
 * 到阻断线时不再执行，模型必须换策略。
 */
class AgentRepeatGuardTest {
    @Test
    fun `notice fires at the notice threshold and block at the block threshold`() {
        val guard = AgentRepeatGuard(noticeThreshold = 3, blockThreshold = 5)

        assertEquals(Decision.CONTINUE, guard.observe("a"))
        assertEquals(Decision.CONTINUE, guard.observe("a"))
        assertEquals(Decision.NOTICE, guard.observe("a"))
        assertEquals(Decision.CONTINUE, guard.observe("a"))
        assertEquals(Decision.BLOCK, guard.observe("a"))
        // 到阻断线之后持续阻断：否则模型等一两次又会重新提交同一批调用。
        assertEquals(Decision.BLOCK, guard.observe("a"))
    }

    @Test
    fun `a different signature resets the streak`() {
        val guard = AgentRepeatGuard(noticeThreshold = 2, blockThreshold = 4)

        assertEquals(Decision.CONTINUE, guard.observe("a"))
        assertEquals(Decision.NOTICE, guard.observe("a"))
        // 换签名等于换参数重试，不算重复。
        assertEquals(Decision.CONTINUE, guard.observe("b"))
        assertEquals(Decision.NOTICE, guard.observe("b"))
        assertEquals(Decision.CONTINUE, guard.observe("a"))
    }

    @Test
    fun `notice repeats on every multiple while below the block threshold`() {
        val guard = AgentRepeatGuard(noticeThreshold = 3, blockThreshold = 99)

        assertEquals(Decision.CONTINUE, guard.observe("a"))
        assertEquals(Decision.CONTINUE, guard.observe("a"))
        assertEquals(Decision.NOTICE, guard.observe("a"))
        assertEquals(Decision.CONTINUE, guard.observe("a"))
        assertEquals(Decision.CONTINUE, guard.observe("a"))
        // 第二次提醒：只提一次容易被后续上下文淹没。
        assertEquals(Decision.NOTICE, guard.observe("a"))
    }
}
