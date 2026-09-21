package io.github.mangi.eta.agent.model

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanFormatTest {
    private val pendingPlan = """
        {"title":"给会话加方案层","digest":"先做数据层，再做工具，最后接界面。","content":"# 方案","steps":[{"id":"step-1","content":"改数据层"},{"id":"step-2","content":"写工具"}],"status":"pending"}
    """.trimIndent()

    private val approvedPlan = pendingPlan.replace("\"pending\"", "\"approved\"")

    private val settledTaskPlan = """[{"id":"1","content":"改代码","status":"completed"}]"""
    private val openTaskPlan = """[{"id":"1","content":"改代码","status":"in_progress"}]"""

    @Test
    fun `pending plan always injects and forbids touching anything`() {
        val lines = AgentPlanFormat.injectedLines(pendingPlan, null)!!

        assertTrue(lines.first(), lines.first().startsWith(AgentPlanFormat.HEADER_PREFIX))
        assertTrue(lines.toString(), lines.any { it.contains("待确认") })
        assertTrue(lines.toString(), lines.any { it.contains("不要修改任何文件") })
        assertTrue(lines.toString(), lines.any { it.contains("1. 改数据层；2. 写工具") })
    }

    @Test
    fun `approved plan drops out once the task plan is settled`() {
        assertNull(AgentPlanFormat.injectedLines(approvedPlan, settledTaskPlan))
        // 清单还没做完，方案仍是方向；清单为空说明执行还没开始，同样要注入。
        assertTrue(AgentPlanFormat.injectedLines(approvedPlan, openTaskPlan)!!.isNotEmpty())
        assertTrue(AgentPlanFormat.injectedLines(approvedPlan, null)!!.isNotEmpty())
    }

    @Test
    fun `missing or malformed plan produces no lines`() {
        assertNull(AgentPlanFormat.injectedLines(null, null))
        assertNull(AgentPlanFormat.injectedLines("", null))
        assertNull(AgentPlanFormat.injectedLines("{}", null))
        assertNull(AgentPlanFormat.injectedLines("not json", null))
    }

    @Test
    fun `every injected line carries the item prefix`() {
        val lines = AgentPlanFormat.injectedLines(pendingPlan, null)!!

        // 去重靠前缀逐行剥掉，漏一行就会在下一轮重复注入。
        assertTrue(lines.drop(1).all { it.startsWith(AgentPlanFormat.ITEM_PREFIX) })
    }
}
