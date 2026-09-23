package io.github.asagnc.sta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathHintsTest {
    @Test
    fun `probe output splits into nearest directory and entries`() {
        val probe = PathHints.parseProbe("NEAREST=/data/local/tmp/sta\nSta-src/\nprobe-dir/\n")

        assertEquals("/data/local/tmp/sta", probe.nearest)
        assertEquals(listOf("Sta-src/", "probe-dir/"), probe.entries)
    }

    @Test
    fun `probe output without a nearest line keeps everything as entries`() {
        val probe = PathHints.parseProbe("ls: cannot access '/nope': No such file or directory\n")

        assertNull(probe.nearest)
        assertEquals(listOf("ls: cannot access '/nope': No such file or directory"), probe.entries)
    }

    @Test
    fun `probe output with an empty nearest has no directory`() {
        val probe = PathHints.parseProbe("NEAREST=\n")

        assertNull(probe.nearest)
        assertTrue(probe.entries.isEmpty())
    }

    @Test
    fun `message names the path, the nearest directory and its entries`() {
        val message = PathHints.message("/a/b/c.kt", "/a", listOf("b/"))

        assertTrue(message, message.contains("路径不存在：/a/b/c.kt"))
        assertTrue(message, message.contains("最近可用的是 /a"))
        assertTrue(message, message.contains("其下：b/"))
    }

    @Test
    fun `message stays short when nothing could be probed`() {
        assertEquals("路径不存在：/a/b", PathHints.message("/a/b", null, emptyList()))
        assertEquals("路径不存在：/a/b", PathHints.message("/a/b", " ", emptyList()))
    }

    @Test
    fun `probe script quotes the path and caps the listing`() {
        val script = PathHints.probeScript("/a/b c")

        assertTrue(script, script.contains("p='/a/b c'"))
        assertTrue(script, script.contains("head -n ${PathHints.MAX_ENTRIES}"))
    }
}
