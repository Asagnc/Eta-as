package io.github.asagnc.sta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPatternTest {

    @Test
    fun `PCRE 简写被翻成 POSIX 字符类`() {
        assertEquals("[[:space:]]", SearchPattern.toPosix("\\s"))
        assertEquals("[0-9]+", SearchPattern.toPosix("\\d+"))
        assertEquals("[[:alnum:]_]+", SearchPattern.toPosix("\\w+"))
        assertEquals("[^[:space:]]", SearchPattern.toPosix("\\S"))
        assertEquals("a[[:space:]]*b", SearchPattern.toPosix("a\\s*b"))
    }

    @Test
    fun `实际踩过坑的写法能翻对`() {
        // 之前写过 appContext\s* 导致 grep: bad regex
        assertEquals("appContext[[:space:]]*", SearchPattern.toPosix("appContext\\s*"))
        assertEquals(
            "grep[[:space:]]+-E|grep[[:space:]]+-r",
            SearchPattern.toPosix("grep\\s+-E|grep\\s+-r"),
        )
    }

    @Test
    fun `方括号内部改用不带外层方括号的写法`() {
        assertEquals("[[:space:]]", SearchPattern.toPosix("[\\s]"))
        assertEquals("[0-9_]", SearchPattern.toPosix("[\\d_]"))
    }

    @Test
    fun `否定简写出现在方括号内时保持原样`() {
        assertEquals("[\\S]", SearchPattern.toPosix("[\\S]"))
    }

    @Test
    fun `已经转义的反斜杠不被改写`() {
        assertEquals("a\\\\s", SearchPattern.toPosix("a\\\\s"))
    }

    @Test
    fun `普通写法与转义字符原样保留`() {
        assertEquals("a\\.b", SearchPattern.toPosix("a\\.b"))
        assertEquals("fun main", SearchPattern.toPosix("fun main"))
        assertEquals("", SearchPattern.toPosix(""))
    }
}
