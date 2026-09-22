package io.github.asagnc.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubAgentBudgetTest {

    @Test
    fun `样本不足时回退到档位默认值`() {
        val plan = AgentSubAgentBudget.plan(
            SubAgentScope.DEEP,
            listOf(SubAgentSample(tokens = 90_000, rounds = 20, ok = true)),
        )

        assertFalse(plan.fromHistory)
        assertEquals(SubAgentScope.DEEP.defaultTokens, plan.tokenBudget)
        assertEquals(SubAgentScope.DEEP.defaultRounds, plan.maxRounds)
        assertEquals(1, plan.sampleCount)
    }

    @Test
    fun `样本充足时按 P75 加余量估算并受档位上限约束`() {
        val samples = listOf(20_000, 24_000, 30_000, 36_000, 40_000, 80_000)
            .map { SubAgentSample(tokens = it, rounds = 6, ok = true) }

        val plan = AgentSubAgentBudget.plan(SubAgentScope.COMPARE, samples)

        assertTrue(plan.fromHistory)
        assertEquals(6, plan.sampleCount)
        // P75 = 40_000，加 25% 余量 = 50_000，仍在 compare 的 [20_000, 60_000] 内
        assertEquals(50_000, plan.tokenBudget)
    }

    @Test
    fun `失败样本不会把下一次预算压低`() {
        val samples = listOf(
            SubAgentSample(tokens = 3_000, rounds = 1, ok = false),
            SubAgentSample(tokens = 4_000, rounds = 1, ok = false),
            SubAgentSample(tokens = 28_000, rounds = 6, ok = true),
            SubAgentSample(tokens = 30_000, rounds = 7, ok = true),
        )

        val plan = AgentSubAgentBudget.plan(SubAgentScope.COMPARE, samples)

        assertTrue(plan.fromHistory)
        // 只按成功样本拟合：P75 = 30_000 → 37_500
        assertEquals(37_500, plan.tokenBudget)
    }

    @Test
    fun `预算与轮数被夹在档位区间内`() {
        val tiny = listOf(600, 700, 800, 900).map { SubAgentSample(tokens = it, rounds = 1, ok = true) }

        val plan = AgentSubAgentBudget.plan(SubAgentScope.QUICK, tiny)

        assertEquals(SubAgentScope.QUICK.minTokens, plan.tokenBudget)
        assertTrue(plan.maxRounds >= 2)
    }

    @Test
    fun `无法识别的档位回退到 compare`() {
        assertEquals(SubAgentScope.COMPARE, SubAgentScope.fromWire("whatever"))
        assertEquals(SubAgentScope.DEEP, SubAgentScope.fromWire(" DEEP "))
        assertEquals(SubAgentScope.COMPARE, SubAgentScope.fromWire(null))
    }
}
