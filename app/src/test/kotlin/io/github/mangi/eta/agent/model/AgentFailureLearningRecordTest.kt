package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFailureLearningRecordTest {

    private val now = 1_700_000_000_000L

    @Test
    fun extractsEntryFromTerminalFailure() {
        val entry = AgentFailureLearningRecord.of(
            toolName = "run_command",
            round = 3,
            resultContent = """{"ok":false,"exit_code":127,"stderr":"sh: foo: not found"}""",
            command = "foo --bar",
            timestampMs = now,
        )

        assertEquals("run_command", entry?.toolName)
        assertEquals("EXIT_127", entry?.code)
        assertTrue(entry?.detail?.contains("not found") == true)
        assertEquals("foo --bar", entry?.command)
    }

    @Test
    fun extractsEntryFromLocalToolFailure() {
        val entry = AgentFailureLearningRecord.of(
            toolName = "read_file",
            round = 1,
            resultContent = """{"ok":false,"code":"PATH_NOT_FOUND","message":"最近可用的是 /x"}""",
            command = "",
            timestampMs = now,
        )

        assertEquals("PATH_NOT_FOUND", entry?.code)
    }

    @Test
    fun ignoresSuccessfulAndNonJsonResults() {
        assertNull(
            AgentFailureLearningRecord.of("read_file", 1, """{"ok":true}""", "", now)
        )
        assertNull(AgentFailureLearningRecord.of("run_command", 1, "plain text", "", now))
    }

    @Test
    fun deduplicatesSameSignatureWithinWindow() {
        val entry = AgentFailureLearningRecord.of(
            "run_command", 2, """{"ok":false,"exit_code":1}""", "ls /nope", now,
        )!!
        val recent = AgentFailureLearningRecord.parseRecentSignatures(
            "- 2023-11-14 22:13 · `run_command` · EXIT_1 · round 1\n",
            now,
        )

        assertTrue(recent.isNotEmpty())
        assertFalse(AgentFailureLearningRecord.shouldRecord(entry, recent))
    }

    @Test
    fun recordsDifferentSignatureOrOldEntry() {
        val entry = AgentFailureLearningRecord.of(
            "read_file", 2, """{"ok":false,"code":"PATH_NOT_FOUND"}""", "", now,
        )!!
        val otherTool = AgentFailureLearningRecord.parseRecentSignatures(
            "- 2023-11-14 22:13 · `run_command` · EXIT_1 · round 1\n",
            now,
        )
        val oldEntry = AgentFailureLearningRecord.parseRecentSignatures(
            "- 2023-11-14 21:00 · `read_file` · PATH_NOT_FOUND · round 1\n",
            now,
        )

        assertTrue(AgentFailureLearningRecord.shouldRecord(entry, otherTool))
        // 窗口之外的旧记录不参与去重，否则同一个坑隔天再踩就不会被记下来
        assertTrue(AgentFailureLearningRecord.shouldRecord(entry, oldEntry))
    }

    @Test
    fun markdownKeepsToolCodeAndRoundOnOneLine() {
        val entry = AgentFailureLearningRecord.Entry(
            toolName = "run_command",
            code = "EXIT_143",
            detail = "Terminated\n第二行应被折叠",
            command = "pkill -f gradle-daemon-main",
            round = 7,
            timestampMs = now,
        )

        val markdown = entry.toMarkdown()
        assertTrue(markdown.startsWith("- "))
        assertTrue(markdown.contains("`run_command` · EXIT_143 · round 7"))
        assertTrue(markdown.contains("pkill -f gradle-daemon-main"))
        // 多行诊断被折叠成一行，避免一次失败在文件里占十几行
        assertTrue(markdown.lines().size <= 4)
    }
}
