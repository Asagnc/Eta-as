package io.github.asagnc.sta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskPlanCodecTest {
    @Test
    fun `decodes a plan snapshot in order`() {
        val items = AgentTaskPlanCodec.decode(
            """[{"id":"inspect","content":"查看结构","status":"completed"},{"id":"edit","content":"改代码","status":"in_progress"}]""",
        )

        assertEquals(2, items.size)
        assertEquals("inspect", items[0].id)
        assertEquals(AgentTaskPlanStatus.COMPLETED, items[0].status)
        assertEquals("edit", items[1].id)
        assertEquals(AgentTaskPlanStatus.IN_PROGRESS, items[1].status)
    }

    @Test
    fun `missing id or content drops that item only`() {
        val items = AgentTaskPlanCodec.decode(
            """[{"id":"","content":"没有 id","status":"pending"},{"id":"ok","content":"保留","status":"pending"}]""",
        )

        assertEquals(1, items.size)
        assertEquals("ok", items[0].id)
    }

    @Test
    fun `unknown status falls back to pending`() {
        val items = AgentTaskPlanCodec.decode("""[{"id":"a","content":"x","status":"weird"}]""")

        assertEquals(AgentTaskPlanStatus.PENDING, items.single().status)
    }

    @Test
    fun `malformed payload yields an empty plan`() {
        assertTrue(AgentTaskPlanCodec.decode("not json").isEmpty())
        assertTrue(AgentTaskPlanCodec.decode("").isEmpty())
    }

    @Test
    fun `empty snapshot clears the plan`() {
        assertTrue(AgentTaskPlanCodec.decode("[]").isEmpty())
    }

    @Test
    fun `the legacy interrupted status reads back as in progress`() {
        val items = AgentTaskPlanCodec.decode("""[{"id":"a","content":"x","status":"interrupted"}]""")

        assertEquals(AgentTaskPlanStatus.IN_PROGRESS, items.single().status)
    }

    @Test
    fun `encode keeps the per item counters and failure`() {
        val encoded = AgentTaskPlanCodec.encode(
            listOf(
                AgentTaskPlanItemUi(
                    id = "a",
                    content = "x",
                    status = AgentTaskPlanStatus.IN_PROGRESS,
                    toolCalls = 3,
                    elapsedMillis = 12_000L,
                    failure = "超时",
                ),
            ),
        )

        val restored = AgentTaskPlanCodec.decode(encoded).single()
        assertEquals(3, restored.toolCalls)
        assertEquals(12_000L, restored.elapsedMillis)
        assertEquals("超时", restored.failure)
    }
}
