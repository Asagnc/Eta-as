package io.github.asagnc.sta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRepeatGuardTest {
    @Test
    fun `warning fires on every threshold multiple`() {
        val guard = AgentRepeatGuard(threshold = 3)

        assertFalse(guard.observe("a"))
        assertFalse(guard.observe("a"))
        assertTrue(guard.observe("a"))
        assertFalse(guard.observe("a"))
        assertFalse(guard.observe("a"))
        assertTrue(guard.observe("a"))
    }

    @Test
    fun `a different signature resets the streak`() {
        val guard = AgentRepeatGuard(threshold = 2)

        assertFalse(guard.observe("a"))
        assertTrue(guard.observe("a"))
        assertFalse(guard.observe("b"))
        assertTrue(guard.observe("b"))
        assertFalse(guard.observe("a|b"))
        assertTrue(guard.observe("a|b"))
    }
}
