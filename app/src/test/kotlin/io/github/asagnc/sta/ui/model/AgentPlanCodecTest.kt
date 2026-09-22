package io.github.asagnc.sta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanCodecTest {
    private val plan = AgentPlanUi(
        title = "给会话加方案层",
        digest = "先数据层，再工具，最后界面。",
        content = "# 方案\n正文",
        steps = listOf(AgentPlanStepUi("step-1", "改数据层")),
        alternatives = listOf(AgentPlanAlternativeUi("只做工具", "不接界面", "快，但看不到")),
        status = AgentPlanStatus.APPROVED,
    )

    @Test
    fun `round trip keeps every field`() {
        assertEquals(plan, AgentPlanCodec.decode(AgentPlanCodec.encode(plan)))
    }

    @Test
    fun `empty snapshot decodes to no plan`() {
        assertNull(AgentPlanCodec.decode(""))
        assertNull(AgentPlanCodec.decode("   "))
        assertNull(AgentPlanCodec.decode("{}"))
        assertNull(AgentPlanCodec.decode("not json"))
        assertEquals("", AgentPlanCodec.encode(null))
    }

    @Test
    fun `unknown status falls back to pending`() {
        val decoded = AgentPlanCodec.decode("""{"title":"t","content":"c","status":"whatever"}""")

        assertEquals(AgentPlanStatus.PENDING, decoded!!.status)
    }

    @Test
    fun `steps and alternatives are optional`() {
        val decoded = AgentPlanCodec.decode("""{"title":"t","content":"c"}""")!!

        assertTrue(decoded.steps.isEmpty())
        assertTrue(decoded.alternatives.isEmpty())
        assertEquals(AgentPlanStatus.PENDING, decoded.status)
    }
}
