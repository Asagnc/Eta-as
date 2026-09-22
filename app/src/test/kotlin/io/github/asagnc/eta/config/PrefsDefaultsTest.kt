package io.github.asagnc.eta.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to false,
                Prefs.Keys.AGENT_CUSTOM_MODEL to true,
                Prefs.Keys.AGENT_REQUIRE_PREFIX to false,
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_SUBAGENTS_ENABLED to true,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
        assertFalse(
            Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
    }

    @Test
    fun numericDefaultsAreDeclaredSeparatelyFromBooleanSwitches() {
        assertEquals(
            mapOf(
                Prefs.Keys.AGENT_PARALLEL_TOOL_LIMIT to 8,
                Prefs.Keys.AGENT_TOOL_RESULT_KEEP to 6,
                Prefs.Keys.AGENT_CONTEXT_NOTICE_PERCENT to 60,
            ),
            Prefs.Keys.INT_DEFAULTS,
        )
        // 数值项与布尔项共用 App 私有配置组，但不参与布尔开关的本地/远程同步协议。
        assertFalse(Prefs.Keys.LOCAL_AGENT_KEYS.contains(Prefs.Keys.AGENT_PARALLEL_TOOL_LIMIT))
    }

    @Test
    fun localAgentKeysMatchRuntimeOwnedSettings() {
        assertEquals(
            setOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.AGENT_BROWSER_TOOLS,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                Prefs.Keys.AGENT_THINKING_ENABLED,
                Prefs.Keys.AGENT_SUBAGENTS_ENABLED,
            ),
            Prefs.Keys.LOCAL_AGENT_KEYS,
        )
    }
}
