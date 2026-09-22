package io.github.asagnc.eta.agent.model

import org.junit.Assert.assertEquals
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
    fun signatureCombinesToolAndCode() {
        val entry = AgentFailureLearningRecord.of(
            "run_command", 1, """{"ok":false,"exit_code":1}""", "ls /nope", now,
        )!!

        // 签名是去重与检索键：同工具同错误码视为同一个坑。
        assertEquals("run_command|EXIT_1", entry.signature)
    }

    @Test
    fun summaryCarriesToolCodeCommandAndDetail() {
        val entry = AgentFailureLearningRecord.of(
            toolName = "run_command",
            round = 3,
            resultContent = """{"ok":false,"exit_code":127,"stderr":"sh: foo: not found"}""",
            command = "foo --bar",
            timestampMs = now,
        )!!

        val summary = entry.summary
        assertTrue(summary.contains("`run_command`"))
        assertTrue(summary.contains("EXIT_127"))
        assertTrue(summary.contains("foo --bar"))
        assertTrue(summary.contains("not found"))
    }

    @Test
    fun summaryIsSingleLineAndBounded() {
        val entry = AgentFailureLearningRecord.of(
            toolName = "run_command",
            round = 1,
            resultContent = """{"ok":false,"exit_code":143,"stderr":"line1\nline2\nline3"}""",
            command = "pkill -f gradle-daemon-main",
            timestampMs = now,
        )!!

        // 注入上下文时一行到底，不能因为原始诊断带换行就撑开多条消息。
        assertTrue(entry.summary.lines().size == 1)
        assertTrue(entry.summary.length < AgentFailureLearningRecord.MAX_DETAIL_CHARS * 2)
    }

    @Test
    fun evidenceUsesCommandArrowOutputForm() {
        val entry = AgentFailureLearningRecord.of(
            toolName = "run_command",
            round = 1,
            resultContent = """{"ok":false,"exit_code":127,"stderr":"sh: foo: not found"}""",
            command = "foo --bar",
            timestampMs = now,
        )!!

        // 证据引用统一成「命令 → 关键输出」，主智能体据此回读原文核对。
        assertTrue(entry.evidence.contains(" → "))
        assertTrue(entry.evidence.startsWith("foo --bar"))
    }

    @Test
    fun evidenceWithoutCommandStillIdentifiesFailure() {
        val entry = AgentFailureLearningRecord.of(
            "read_file", 1, """{"ok":false,"code":"PATH_NOT_FOUND"}""", "", now,
        )!!

        assertTrue(entry.evidence.contains("PATH_NOT_FOUND"))
    }
}
