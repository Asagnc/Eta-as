package io.github.asagnc.sta.data.repository

import io.github.asagnc.sta.agent.terminal.LinuxExecutionBackend
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxBackendSelectionTest {
    @Test fun existingRootEnvironmentRemainsSelectedAfterPermissionIsRevoked() {
        assertEquals(LinuxExecutionBackend.CHROOT, LinuxEnvironmentSettingsRepository.resolveBackend(true, false, false))
        assertEquals(LinuxExecutionBackend.CHROOT, LinuxEnvironmentSettingsRepository.resolveBackend(true, true, false))
    }

    @Test fun existingOrdinaryEnvironmentRemainsSelectedAfterPermissionIsGranted() {
        assertEquals(LinuxExecutionBackend.PROOT, LinuxEnvironmentSettingsRepository.resolveBackend(false, true, true))
    }

    @Test fun firstInstallUsesAvailableIdentity() {
        assertEquals(LinuxExecutionBackend.PROOT, LinuxEnvironmentSettingsRepository.resolveBackend(false, false, false))
        assertEquals(LinuxExecutionBackend.CHROOT, LinuxEnvironmentSettingsRepository.resolveBackend(false, false, true))
    }
}
