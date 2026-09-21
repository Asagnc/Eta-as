package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class AgentFailureLearningHistoryTest {

    private val now = time("2026-09-21 14:00")
    private val signature = "terminal|EXIT_137"

    private fun time(text: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(text)!!.time

    private fun entry(
        at: String,
        tool: String = "terminal",
        code: String = "EXIT_137",
        command: String = "bash gw.sh test",
        detail: String = "命令被超时终止",
    ) = """
        - $at · `$tool` · $code · round 3
          - 命令：`$command`
          - 表现：$detail
    """.trimIndent()

    @Test
    fun `回注窗口外的同签名条目`() {
        val content = entry("2026-09-21 13:00")
        val history = AgentFailureLearningRecord.historyFor(content, signature, now)
        assertTrue(history!!.contains("命令被超时终止"))
    }

    @Test
    fun `细节行要跟着标题一起回注`() {
        val history = AgentFailureLearningRecord.historyFor(entry("2026-09-21 13:00"), signature, now)
        assertTrue("缺了细节就说不出当时怎么了", history!!.contains("bash gw.sh test"))
        assertTrue(history.contains("round 3"))
    }

    @Test
    fun `窗口内的条目不回注`() {
        // 窗口内属"当前这起事故"，该说的是连续失败次数，而不是把刚写的记录念回去。
        val content = entry("2026-09-21 13:55")
        assertNull(AgentFailureLearningRecord.historyFor(content, signature, now))
    }

    @Test
    fun `不同签名不回注`() {
        val content = entry("2026-09-21 13:00", code = "EXIT_1")
        assertNull(AgentFailureLearningRecord.historyFor(content, signature, now))
    }

    @Test
    fun `多条同签名取最近一条`() {
        val content = entry("2026-09-21 12:00", detail = "旧的表现") + "\n" +
            entry("2026-09-21 13:00", detail = "较新的表现")
        val history = AgentFailureLearningRecord.historyFor(content, signature, now)
        assertTrue(history!!.contains("较新的表现"))
        assertFalse(history.contains("旧的表现"))
    }

    @Test
    fun `空内容返回 null`() {
        assertNull(AgentFailureLearningRecord.historyFor("", signature, now))
        assertNull(AgentFailureLearningRecord.historyFor("# 标题\n", signature, now))
    }

    @Test
    fun `回注长度有上限`() {
        val content = entry("2026-09-21 13:00", detail = "长".repeat(5_000))
        val history = AgentFailureLearningRecord.historyFor(content, signature, now)
        assertTrue(history!!.length <= AgentFailureLearningRecord.MAX_HISTORY_CHARS)
    }

    @Test
    fun `条目块的标题与细节归属正确`() {
        val content = entry("2026-09-21 12:00", detail = "第一条") + "\n" +
            entry("2026-09-21 13:00", code = "EXIT_1", detail = "第二条")
        val entries = AgentFailureLearningRecord.parseEntries(content)
        assertEquals(2, entries.size)
        assertEquals("第一条", entries[0].markdown.lines().last().substringAfter("表现："))
        assertEquals("EXIT_1", entries[1].code)
    }

    @Test
    fun `没有细节行的条目也能解析`() {
        val content = "- 2026-09-21 13:00 · `terminal` · EXIT_137 · round 1"
        val entries = AgentFailureLearningRecord.parseEntries(content)
        assertEquals(1, entries.size)
        assertEquals("terminal", entries[0].toolName)
    }

    @Test
    fun `忽略格式不合法的标题行_不误判为条目`() {
        val content = "- 这不是时间 · 缺字段\n- 2026-09-21 13:00 · `terminal` · EXIT_137 · round 1"
        val entries = AgentFailureLearningRecord.parseEntries(content)
        assertEquals(1, entries.size)
    }

    @Test
    fun `parseRecentSignatures 只取窗口内的签名`() {
        val content = entry("2026-09-21 13:55") + "\n" + entry("2026-09-21 11:00")
        val recent = AgentFailureLearningRecord.parseRecentSignatures(content, now)
        assertEquals(1, recent.size)
        assertEquals(signature, recent.single().signature)
    }
}
