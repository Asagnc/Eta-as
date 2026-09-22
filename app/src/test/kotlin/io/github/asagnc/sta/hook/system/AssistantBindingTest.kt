package io.github.asagnc.sta.hook.system

import io.github.asagnc.sta.config.PowerAssistantTarget
import io.github.asagnc.sta.core.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBindingTest {
    @Test
    fun `OEM target has no managed assistant binding`() {
        assertNull(assistantBindingFor(PowerAssistantTarget.OEM))
        assertFalse(
            shouldConfigureAssistant(
                autoConfigEnabled = true,
                target = PowerAssistantTarget.OEM,
            ),
        )
    }

    @Test
    fun `Sta uses its own package and component`() {
        val eta = requireNotNull(assistantBindingFor(PowerAssistantTarget.ETA))

        assertEquals(ModuleConfig.ETA_PACKAGE, eta.packageName)
        assertEquals(ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT, eta.componentName)
    }

    @Test
    fun `automatic configuration requires enabled switch and unchanged target`() {
        assertTrue(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = true,
                expectedTarget = PowerAssistantTarget.ETA,
                currentTarget = PowerAssistantTarget.ETA,
            ),
        )
        assertFalse(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = false,
                expectedTarget = PowerAssistantTarget.ETA,
                currentTarget = PowerAssistantTarget.ETA,
            ),
        )
        assertFalse(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = true,
                expectedTarget = PowerAssistantTarget.ETA,
                currentTarget = PowerAssistantTarget.OEM,
            ),
        )
    }

    @Test
    fun `preference changes configure managed targets and restore OEM`() {
        assertEquals(
            AssistantSelectionAction.CONFIGURE_MANAGED,
            assistantSelectionAction(true, PowerAssistantTarget.ETA),
        )
        assertEquals(
            AssistantSelectionAction.NONE,
            assistantSelectionAction(false, PowerAssistantTarget.ETA),
        )
        assertEquals(
            AssistantSelectionAction.RESTORE_OEM,
            assistantSelectionAction(false, PowerAssistantTarget.OEM),
        )
    }
}
