package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubAgentProjectorTest {

    private fun event(
        id: String = "sub-检索-1",
        role: String = "检索",
        phase: String = AgentEvent.SubAgentUpdated.PHASE_STARTED,
        summaryChars: Int = 0,
        errorCode: String = "",
    ) = AgentEvent.SubAgentUpdated(
        id = id,
        role = role,
        phase = phase,
        summaryChars = summaryChars,
        errorCode = errorCode,
    )

    @Test
    fun `started 映射为进行中`() {
        val item = AgentSubAgentProjector.fromEvent(event())
        assertEquals(AgentSubAgentPhase.RUNNING, item?.phase)
        assertEquals("检索", item?.role)
    }

    @Test
    fun `finished 映射为已完成并带上摘要字数`() {
        val item = AgentSubAgentProjector.fromEvent(
            event(phase = AgentEvent.SubAgentUpdated.PHASE_FINISHED, summaryChars = 128),
        )
        assertEquals(AgentSubAgentPhase.FINISHED, item?.phase)
        assertEquals(128, item?.summaryChars)
    }

    @Test
    fun `未知 phase 归为失败`() {
        assertEquals(
            AgentSubAgentPhase.FAILED,
            AgentSubAgentProjector.fromEvent(event(phase = "failed"))?.phase,
        )
        assertEquals(
            AgentSubAgentPhase.FAILED,
            AgentSubAgentProjector.fromEvent(event(phase = "别的值"))?.phase,
        )
    }

    @Test
    fun `空 id 的事件被丢弃`() {
        // 没有 id 就无法按 id 合并，界面会退化成每次事件新增一行。
        assertNull(AgentSubAgentProjector.fromEvent(event(id = "   ")))
    }

    @Test
    fun `角色为空时用兜底名`() {
        assertEquals("子智能体", AgentSubAgentProjector.fromEvent(event(role = " "))?.role)
    }

    @Test
    fun `负数摘要字数归零`() {
        assertEquals(0, AgentSubAgentProjector.fromEvent(event(summaryChars = -5))?.summaryChars)
    }

    @Test
    fun `同一 id 的事件更新原项而不是新增`() {
        val running = AgentSubAgentProjector.fromEvent(event())!!
        val finished = AgentSubAgentProjector.fromEvent(
            event(phase = AgentEvent.SubAgentUpdated.PHASE_FINISHED, summaryChars = 64),
        )!!
        val once = AgentSubAgentProjector.upsert(emptyList(), running)
        val twice = AgentSubAgentProjector.upsert(once, finished)
        assertEquals(1, twice.size)
        assertEquals(AgentSubAgentPhase.FINISHED, twice.single().phase)
        assertEquals(64, twice.single().summaryChars)
    }

    @Test
    fun `不同 id 并排保留`() {
        val list = AgentSubAgentProjector.upsert(
            AgentSubAgentProjector.upsert(emptyList(), AgentSubAgentProjector.fromEvent(event(id = "a"))!!),
            AgentSubAgentProjector.fromEvent(event(id = "b", role = "审查"))!!,
        )
        assertEquals(listOf("a", "b"), list.map { it.id })
    }

    @Test
    fun `upsert 保持原有顺序`() {
        val first = AgentSubAgentProjector.fromEvent(event(id = "a"))!!
        val second = AgentSubAgentProjector.fromEvent(event(id = "b"))!!
        val list = AgentSubAgentProjector.upsert(
            AgentSubAgentProjector.upsert(emptyList(), first),
            second,
        )
        val updated = AgentSubAgentProjector.upsert(
            list,
            AgentSubAgentProjector.fromEvent(event(id = "a", phase = "finished"))!!,
        )
        assertEquals(listOf("a", "b"), updated.map { it.id })
    }

    @Test
    fun `hasRunning 只认进行中的项`() {
        assertFalse(AgentSubAgentProjector.hasRunning(emptyList()))
        assertTrue(AgentSubAgentProjector.hasRunning(listOf(AgentSubAgentProjector.fromEvent(event())!!)))
        assertFalse(
            AgentSubAgentProjector.hasRunning(
                listOf(AgentSubAgentProjector.fromEvent(event(phase = "finished"))!!),
            ),
        )
    }
}
