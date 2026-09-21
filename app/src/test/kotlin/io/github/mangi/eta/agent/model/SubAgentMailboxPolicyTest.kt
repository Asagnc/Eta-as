package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentMailboxPolicyTest {

    @Test
    fun `摘要与正文都为空时不落库`() {
        assertNull(SubAgentMailboxPolicy.sanitize("note", "   ", ""))
        assertNull(SubAgentMailboxPolicy.sanitize("note", "", "  \n "))
    }

    @Test
    fun `只有正文也算有效投递`() {
        val entry = SubAgentMailboxPolicy.sanitize("note", "", "函数改名了")
        assertEquals("函数改名了", entry?.body)
    }

    @Test
    fun `摘要压缩空白并截断`() {
        val entry = SubAgentMailboxPolicy.sanitize("note", "a\n\n  b   c", "")
        assertEquals("a b c", entry?.summary)
        val long = SubAgentMailboxPolicy.sanitize("note", "x".repeat(500), "")
        assertEquals(SubAgentMailboxPolicy.MAX_SUMMARY_CHARS, long?.summary?.length)
    }

    @Test
    fun `正文截断`() {
        val entry = SubAgentMailboxPolicy.sanitize("note", "s", "y".repeat(5000))
        assertEquals(SubAgentMailboxPolicy.MAX_BODY_CHARS, entry?.body?.length)
    }

    @Test
    fun `kind 只认 result，其余归为 note`() {
        assertEquals(
            SubAgentMailboxPolicy.KIND_RESULT,
            SubAgentMailboxPolicy.sanitize("RESULT", "s", "")?.kind,
        )
        assertEquals(
            SubAgentMailboxPolicy.KIND_NOTE,
            SubAgentMailboxPolicy.sanitize("随便写的", "s", "")?.kind,
        )
        assertEquals(
            SubAgentMailboxPolicy.KIND_NOTE,
            SubAgentMailboxPolicy.sanitize("", "s", "")?.kind,
        )
    }

    @Test
    fun `note 有条数上限，result 不受限`() {
        assertTrue(SubAgentMailboxPolicy.canAcceptNote(0))
        assertTrue(SubAgentMailboxPolicy.canAcceptNote(SubAgentMailboxPolicy.MAX_NOTES_PER_RUN - 1))
        assertFalse(SubAgentMailboxPolicy.canAcceptNote(SubAgentMailboxPolicy.MAX_NOTES_PER_RUN))
        assertFalse(SubAgentMailboxPolicy.canAcceptNote(SubAgentMailboxPolicy.MAX_NOTES_PER_RUN + 5))
    }

    @Test
    fun `去重键忽略大小写与空白`() {
        assertEquals(
            SubAgentMailboxPolicy.dedupeKey("Foo  Bar"),
            SubAgentMailboxPolicy.dedupeKey("foo bar"),
        )
        assertEquals(
            SubAgentMailboxPolicy.dedupeKey("函数\n改名"),
            SubAgentMailboxPolicy.dedupeKey("函数 改名"),
        )
    }

    @Test
    fun `空列表渲染为空串`() {
        assertEquals("", SubAgentMailboxPolicy.render(emptyList()))
    }

    @Test
    fun `渲染带作者与摘要，并明确要求不要重复验证`() {
        val text = SubAgentMailboxPolicy.render(
            listOf(
                SubAgentMailboxPolicy.Entry("检索", "note", "常量在 A.kt:42", "已确认三处引用"),
                SubAgentMailboxPolicy.Entry("审查", "note", "缺少边界检查", ""),
            ),
        )
        assertTrue(text.contains("不要重复验证"))
        assertTrue(text.contains("[检索] 常量在 A.kt:42：已确认三处引用"))
        assertTrue(text.contains("[审查] 缺少边界检查"))
    }

    @Test
    fun `渲染时摘要为空则退回正文`() {
        val text = SubAgentMailboxPolicy.render(
            listOf(SubAgentMailboxPolicy.Entry("检索", "note", "", "只有正文")),
        )
        assertTrue(text.contains("只有正文"))
    }

    @Test
    fun `渲染时正文过长会截断`() {
        val text = SubAgentMailboxPolicy.render(
            listOf(
                SubAgentMailboxPolicy.Entry("检索", "note", "摘要", "z".repeat(5_000)),
            ),
        )
        assertTrue(text.length < SubAgentMailboxPolicy.MAX_READ_BODY_CHARS + 200)
    }
}
