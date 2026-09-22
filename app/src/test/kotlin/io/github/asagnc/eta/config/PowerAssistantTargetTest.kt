package io.github.asagnc.eta.config

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerAssistantTargetTest {
    @Test
    fun `valid persisted values select matching target`() {
        assertEquals(
            PowerAssistantTarget.OEM,
            PowerAssistantTarget.resolve("oem"),
        )
        assertEquals(
            PowerAssistantTarget.ETA,
            PowerAssistantTarget.resolve("eta"),
        )
    }

    @Test
    fun `missing persisted value falls back to OEM`() {
        assertEquals(
            PowerAssistantTarget.OEM,
            PowerAssistantTarget.resolve(null),
        )
    }

    @Test
    fun `unknown persisted value falls back to OEM`() {
        assertEquals(
            PowerAssistantTarget.OEM,
            PowerAssistantTarget.resolve("unknown"),
        )
    }
}
