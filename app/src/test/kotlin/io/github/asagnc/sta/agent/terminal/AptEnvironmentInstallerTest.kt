package io.github.asagnc.sta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AptEnvironmentInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactSelectionUsesPinnedIntegrityMetadata() {
        val debian = AptEnvironmentInstaller.artifactForAbis(
            LinuxDistribution.DEBIAN,
            listOf("armeabi-v7a", "arm64-v8a", "x86_64"),
        )

        requireNotNull(debian)
        assertEquals("13", debian.version)
        assertEquals("debian-trixie-aarch64-pd-v4.29.0.tar.xz", debian.fileName)
        assertTrue(debian.url.contains("termux/proot-distro/releases/download/v4.29.0"))
        assertEquals(1, debian.preferredUrls.size)
        assertTrue(debian.preferredUrls.single().startsWith("https://gh-proxy.com/"))
        assertEquals(64, debian.sha256.length)
        assertEquals(35_409_704L, debian.sizeBytes)

        val ubuntu = AptEnvironmentInstaller.artifactForAbis(LinuxDistribution.UBUNTU, listOf("arm64-v8a"))
        requireNotNull(ubuntu)
        assertEquals("26.04", ubuntu.version)
        assertEquals(102_536_744L, ubuntu.sizeBytes)
        assertTrue(ubuntu.url.contains("cloud-images.ubuntu.com/minimal/releases/resolute/"))
        // 归档地址必须由模板拼出真实文件名，写成转义后的 $ 会变成字面量并导致 404
        assertEquals("ubuntu-26.04-minimal-cloudimg-arm64-root.tar.xz", ubuntu.url.substringAfterLast("/"))
        assertEquals("ubuntu-26.04-minimal-cloudimg-arm64-root.tar.xz",
            ubuntu.preferredUrls.single().substringAfterLast("/"))

        val kali = AptEnvironmentInstaller.artifactForAbis(LinuxDistribution.KALI, listOf("arm64-v8a"))
        requireNotNull(kali)
        assertEquals("2026.2", kali.version)
        assertTrue(kali.url.contains("kali.download/nethunter-images/kali-2026.2/rootfs/"))
        assertTrue(kali.preferredUrls.isEmpty())
        assertEquals(137_313_840L, kali.sizeBytes)
    }

    @Test
    fun unsupportedAbiDoesNotGuessAnArtifact() {
        LinuxDistribution.entries.forEach { distribution ->
            assertNull(AptEnvironmentInstaller.artifactForAbis(distribution, listOf("armeabi-v7a", "x86")))
        }
    }

    @Test
    fun readinessUsesInstallerMarkerOnly() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val marker = File(rootfs, LinuxEnvironmentPaths.READY_MARKER)

        assertFalse(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        marker.writeText("version=13\n")
        assertTrue(LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath))
    }

    @Test
    fun baseToolsetContainsGlibcAndAgentEssentials() {
        val packages = AptEnvironmentInstaller.AGENT_PACKAGES

        assertTrue(packages.containsAll(listOf("bash", "coreutils", "git", "jq", "ripgrep", "sqlite3", "xz-utils")))
        assertFalse(packages.contains("nodejs"))
        assertFalse(packages.contains("npm"))
        assertFalse(packages.contains("python3"))
        assertFalse(packages.contains("python3-pip"))
        assertEquals(packages.distinct(), packages)
    }

    @Test
    fun aptMirrorsKeepDomesticCandidatesBeforeOfficialFallback() {
        val mirrors = AptDistributionSpecs.mirrorsOf(LinuxDistribution.DEBIAN)
        assertEquals(listOf("ustc", "official"), mirrors.map { it.id })
        assertTrue(mirrors.first().sources.any { it.contains("mirrors.ustc.edu.cn") })
        assertTrue(mirrors.last().sources.any { it.contains("deb.debian.org") })
        val script = AptEnvironmentInstaller.aptMirrorScript(LinuxDistribution.DEBIAN)
        assertTrue(script.contains("apt-get -o Acquire::Retries=2"))
        assertFalse(script.contains("mirrors.tuna.tsinghua.edu.cn"))
    }

    @Test
    fun aptMirrorScriptIsValidPosixShellForEveryDistribution() {
        LinuxDistribution.entries.forEach { distribution ->
            assertTrue(AptDistributionSpecs.mirrorsOf(distribution).isNotEmpty())
            assertTrue(AptDistributionSpecs.versionOf(distribution).isNotBlank())
            val process = ProcessBuilder("sh", "-n").start()
            process.outputStream.use { output ->
                output.write(AptEnvironmentInstaller.aptMirrorScript(distribution).toByteArray())
            }
            assertEquals(0, process.waitFor())
        }
    }
}
