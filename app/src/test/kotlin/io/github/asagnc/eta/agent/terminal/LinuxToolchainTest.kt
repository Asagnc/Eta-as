package io.github.asagnc.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxToolchainTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readinessUsesInstallerMarkerAndTracksCommonToolsSeparately() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val ready = File(rootfs, LinuxEnvironmentPaths.READY_MARKER)

        assertFalse(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        ready.writeText("version=13.0\n")
        assertTrue(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        assertFalse(LinuxEnvironmentPaths.commonToolsReady(rootfs.absolutePath))

        File(rootfs, LinuxEnvironmentPaths.COMMON_TOOLS_MARKER).writeText("13.0\n")
        assertFalse(LinuxEnvironmentPaths.commonToolsReady(rootfs.absolutePath))

        File(rootfs, LinuxEnvironmentPaths.COMMON_TOOLS_MARKER).writeText(
            "debian=13.0\ntoolset=${LinuxEnvironmentPaths.TOOLSET_REVISION}\nprofiles=agent\n",
        )
        assertTrue(LinuxEnvironmentPaths.commonToolsReady(rootfs.absolutePath))
    }

    @Test
    fun packageProfilesCoverExpectedToolchains() {
        val debianPython = LinuxPackageProfiles.PYTHON.spec(LinuxDistribution.DEBIAN)
        assertTrue(debianPython.packages.isEmpty())
        assertEquals(ManagedLinuxTool.UV, debianPython.managedTool)
        assertTrue(debianPython.setupScript.orEmpty().contains("UV_PYTHON_BIN_DIR=/usr/local/bin"))
        assertEquals(
            ManagedLinuxTool.NODE,
            LinuxPackageProfiles.NODE.spec(LinuxDistribution.DEBIAN).managedTool,
        )
        // Node 官方 arm64 二进制依赖 libatomic.so.1，Debian 规格必须补装 libatomic1。
        assertTrue(
            LinuxPackageProfiles.NODE.spec(LinuxDistribution.DEBIAN).packages.contains("libatomic1"),
        )
        LinuxDistribution.entries.forEach { distribution ->
            assertTrue(LinuxPackageProfiles.SSH.spec(distribution).packages.isNotEmpty())
        }
        LinuxPackageProfiles.ALL.forEach { profile ->
            assertTrue(profile.markerName.startsWith(".eta-"))
            profile.specs.values.forEach { spec ->
                assertEquals(spec.packages.distinct(), spec.packages)
            }
        }
        assertEquals(LinuxPackageProfiles.ALL.map { it.id }.distinct(), LinuxPackageProfiles.ALL.map { it.id })
    }

    @Test
    fun packageProfileReadinessUsesItsOwnMarker() {
        val rootfs = temporaryFolder.newFolder("profile-rootfs")
        val profile = LinuxPackageProfiles.PYTHON

        assertFalse(linuxPackageProfileReady(rootfs, profile))
        File(rootfs, profile.markerName).writeText("profile=0\n")
        assertFalse(linuxPackageProfileReady(rootfs, profile))
        File(rootfs, profile.markerName).writeText("profile=${profile.revision}\n")
        assertTrue(linuxPackageProfileReady(rootfs, profile))
    }

    @Test
    fun pinnedUvAndNodeArtifactsUseLatestStableReleaseMetadata() {
        val debianUv = PinnedLinuxToolArtifacts.artifactFor(
            ManagedLinuxTool.UV,
            LinuxDistribution.DEBIAN,
            listOf("arm64-v8a"),
        )
        val debianNode = PinnedLinuxToolArtifacts.artifactFor(
            ManagedLinuxTool.NODE,
            LinuxDistribution.DEBIAN,
            listOf("arm64-v8a"),
        )

        requireNotNull(debianUv)
        requireNotNull(debianNode)
        assertEquals("0.12.7", debianUv.version)
        assertTrue(debianUv.fileName.contains("gnu"))
        assertEquals("26.8.1", debianNode.version)
        assertTrue(debianNode.url.startsWith("https://nodejs.org/"))
        assertTrue(debianNode.preferredUrls.single().startsWith("https://cdn.npmmirror.com/"))
    }

    @Test
    fun apkAnalysisReadinessUsesCurrentMarker() {
        val rootfs = temporaryFolder.newFolder("analysis-rootfs")

        File(rootfs, LinuxEnvironmentPaths.APK_ANALYSIS_MARKER).writeText("profile=0\n")
        assertFalse(linuxApkAnalysisReady(rootfs))

        File(rootfs, LinuxEnvironmentPaths.APK_ANALYSIS_MARKER).writeText(
            "profile=${LinuxEnvironmentPaths.APK_ANALYSIS_REVISION}\n",
        )
        assertTrue(linuxApkAnalysisReady(rootfs))
    }
}
