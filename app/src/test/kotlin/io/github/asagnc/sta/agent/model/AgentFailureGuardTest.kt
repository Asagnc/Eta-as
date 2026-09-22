package io.github.asagnc.sta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败模式检测：连续撞墙和"绕一圈又回来"都要抓到，但正常的偶发失败不该报警。
 */
class AgentFailureGuardTest {

    private val alpha = "terminal:EXIT_1:boom:1"
    private val beta = "terminal:EXIT_127:not found:127"

    @Test
    fun firstFailureDoesNotNudge() {
        val guard = AgentFailureGuard()
        val verdict = guard.observe(alpha)
        assertEquals(1, verdict.consecutive)
        assertEquals(1, verdict.total)
        assertFalse(verdict.shouldNudge)
    }

    @Test
    fun secondConsecutiveFailureNudges() {
        val guard = AgentFailureGuard()
        guard.observe(alpha)
        val verdict = guard.observe(alpha)
        assertEquals(AgentFailureGuard.Kind.CONSECUTIVE, verdict.kind)
        assertEquals(2, verdict.consecutive)
        assertTrue(verdict.shouldNudge)
    }

    @Test
    fun unrelatedFailureBreaksStreakButKeepsTotal() {
        val guard = AgentFailureGuard()
        guard.observe(alpha)
        val other = guard.observe(beta)
        assertEquals(1, other.consecutive)
        assertEquals(1, other.total)
        assertFalse(other.shouldNudge)
    }

    @Test
    fun returningToTheSameFailureAfterDetourStillNudges() {
        val guard = AgentFailureGuard()
        guard.observe(alpha)
        guard.observe(beta)
        val back = guard.observe(alpha)
        // 连续计数只有 1，但整轮已经第 2 次撞同一个错误——只按连续判定会漏掉这种情况。
        assertEquals(1, back.consecutive)
        assertEquals(2, back.total)
        assertEquals(AgentFailureGuard.Kind.REPEATED, back.kind)
        assertTrue(back.shouldNudge)
    }

    @Test
    fun successResetsStreakButKeepsTotalHistory() {
        val guard = AgentFailureGuard()
        guard.observe(alpha)
        guard.reset()
        val again = guard.observe(alpha)
        assertEquals(1, again.consecutive)
        assertEquals(2, again.total)
        assertEquals(AgentFailureGuard.Kind.REPEATED, again.kind)
    }

    @Test
    fun nudgesAtEveryThresholdInsteadOfOnlyOnce() {
        val guard = AgentFailureGuard()
        val verdicts = (1..6).map { guard.observe(alpha) }
        val nudged = verdicts.filter { it.shouldNudge }.map { it.consecutive }
        // 只在 2、4、6 次提醒：提醒一次后放着不管，和每次都刷屏都不合适。
        assertEquals(listOf(2, 4, 6), nudged)
    }

    @Test
    fun consecutiveHitWinsOverRepeatedSoTheMessageIsPrecise() {
        val guard = AgentFailureGuard()
        guard.observe(beta)
        guard.observe(beta)
        val fourth = guard.observe(beta)
        assertEquals(3, fourth.consecutive)
        assertEquals(3, fourth.total)
        // 第 3 次不提醒（避免每次都刷），第 4 次由连续分支提醒。
        assertFalse(fourth.shouldNudge)
    }
}
