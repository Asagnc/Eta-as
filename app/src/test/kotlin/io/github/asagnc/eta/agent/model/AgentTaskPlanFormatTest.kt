package io.github.asagnc.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskPlanFormatTest {
    private val plan =
        """[{"id":"1","content":"定位","status":"completed"},{"id":"2","content":"改代码","status":"in_progress"}]"""

    @Test
    fun `injected lines carry the header, markers and ids`() {
        val lines = AgentTaskPlanFormat.injectedLines(plan)

        assertEquals("当前任务清单（1/2 完成，1 项未完成）：", lines?.get(0))
        assertEquals(
            "[task_plan] 提示：上次运行停在第 2 步，先把那一项标回 in_progress 再继续，已经完成的项不要重做。",
            lines?.get(1),
        )
        assertEquals("[task_plan] [x] 1 定位", lines?.get(2))
        assertEquals("[task_plan] [>] 2 改代码", lines?.get(3))
    }

    @Test
    fun `a finished plan is not injected`() {
        val done = """[{"id":"1","content":"a","status":"completed"}]"""

        assertNull(AgentTaskPlanFormat.injectedLines(done))
    }

    @Test
    fun `blank or broken plans are not injected`() {
        assertNull(AgentTaskPlanFormat.injectedLines(null))
        assertNull(AgentTaskPlanFormat.injectedLines(""))
        assertNull(AgentTaskPlanFormat.injectedLines("   "))
        assertNull(AgentTaskPlanFormat.injectedLines("not json"))
        assertNull(AgentTaskPlanFormat.injectedLines("{}"))
        assertNull(AgentTaskPlanFormat.injectedLines("[]"))
    }

    @Test
    fun `entries without an id or content are skipped`() {
        val items = """[{"id":"","content":"x","status":"pending"},{"id":"2","content":"  ","status":"pending"}]"""

        assertNull(AgentTaskPlanFormat.injectedLines(items))
    }

    @Test
    fun `a missing status counts as pending`() {
        val lines = AgentTaskPlanFormat.injectedLines("""[{"id":"1","content":"a"}]""")

        assertEquals("[task_plan] [ ] 1 a", lines?.get(1))
    }

    @Test
    fun `long plans are capped and report the remainder`() {
        val items = (1..30).joinToString(",") { """{"id":"$it","content":"item $it","status":"pending"}""" }

        val lines = AgentTaskPlanFormat.injectedLines("[$items]")

        assertEquals(1 + AgentTaskPlanFormat.MAX_INJECTED_ITEMS + 1, lines?.size)
        assertTrue(lines!!.last().contains("其余 10 项略"))
    }

    @Test
    fun `a plan without an active step carries no resume hint`() {
        val lines = AgentTaskPlanFormat.injectedLines("""[{"id":"1","content":"a","status":"pending"}]""")

        assertEquals(2, lines?.size)
        assertEquals("[task_plan] [ ] 1 a", lines?.get(1))
    }

    @Test
    fun `the legacy interrupted status renders as in progress and keeps the breakpoint`() {
        val legacy =
            """[{"id":"1","content":"a","status":"completed"},{"id":"2","content":"b","status":"interrupted"}]"""

        val lines = AgentTaskPlanFormat.injectedLines(legacy)

        assertTrue(lines?.get(1)?.contains("停在第 2 步") == true)
        assertEquals("[task_plan] [>] 2 b", lines?.get(3))
    }
}
