package io.github.asagnc.sta.agent.terminal

import io.github.asagnc.sta.core.AgentLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxApkAnalysisInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactManifestPinsOfficialHttpsDownloadsAndIntegrityMetadata() {
        val artifacts = LinuxApkAnalysisInstaller.ARTIFACTS

        assertEquals(listOf("jadx", "apktool", "smali", "baksmali", "aapt2"), artifacts.map { it.id })
        assertTrue(artifacts.all { artifact -> artifact.url.startsWith("https://") })
        val githubArtifacts = artifacts.filter { artifact -> artifact.id != "aapt2" }
        assertEquals(4, githubArtifacts.size)
        assertTrue(githubArtifacts.all { artifact -> artifact.preferredUrls.size == 1 })
        assertTrue(
            githubArtifacts.all { artifact ->
                artifact.preferredUrls.single().startsWith("https://gh-proxy.com/")
            },
        )
        assertTrue(
            githubArtifacts.all { artifact ->
                artifact.preferredUrls.all { preferred -> preferred.endsWith(artifact.url) }
            },
        )
        // Google Maven 制品不经镜像前缀，且只以 x86-64 发布，由 qemu-user 转译执行。
        val aapt2 = artifacts.single { artifact -> artifact.id == "aapt2" }
        assertTrue(aapt2.preferredUrls.isEmpty())
        assertEquals(
            "https://dl.google.com/android/maven2/com/android/tools/build/aapt2/" +
                "9.4.1-15978811/aapt2-9.4.1-15978811-linux.jar",
            aapt2.url,
        )
        assertTrue(artifacts.all { artifact -> artifact.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(artifacts.all { artifact -> artifact.sizeBytes > 1_000_000L })
        assertEquals(100_342_355L, artifacts.sumOf { artifact -> artifact.sizeBytes })
        assertTrue(LinuxApkAnalysisInstaller.MIN_AVAILABLE_BYTES > artifacts.sumOf { it.sizeBytes } * 2)
    }

    @Test
    fun cachedArtifactVerificationRejectsWrongSizeAndDigest() {
        val file = temporaryFolder.newFile("artifact.bin")
        val artifact = VerifiedArtifact(
            id = "fixture",
            version = "1",
            fileName = file.name,
            url = "https://example.invalid/artifact.bin",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 3,
        )
        val downloader = VerifiedArtifactDownloader()

        file.writeText("ab")
        assertFalse(downloader.verify(artifact, file))
        file.writeText("abd")
        assertFalse(downloader.verify(artifact, file))
        file.writeText("abc")
        assertTrue(downloader.verify(artifact, file))
    }

    @Test
    fun apktoolWrapperBuildsThroughOfficialAapt2() {
        val wrapper = LinuxApkAnalysisInstaller.APKTOOL_WRAPPER

        // build 不再被拦截：apktool 直接调用 java，aapt2 由档案提供的官方版本承担。
        assertFalse(wrapper.contains("APKTOOL_BUILD_UNAVAILABLE"))
        assertFalse(wrapper.contains("exit 64"))
        assertTrue(wrapper.contains("exec java -jar"))
    }

    @Test
    fun aapt2WrapperRunsOfficialBinaryThroughQemuUser() {
        val wrapper = LinuxApkAnalysisInstaller.AAPT2_WRAPPER

        assertTrue(wrapper.contains("/usr/bin/qemu-x86_64-static"))
        // -L 必须指向 x86-64 动态库目录，否则加载器找不到 libc 直接失败。
        assertTrue(wrapper.contains("-L /usr/lib/x86_64-linux-gnu"))
        assertTrue(wrapper.contains("/opt/sta/apk-analysis/current/bin/aapt2"))
    }

    @Test
    fun aapt2ArtifactPinsOfficialGoogleMavenRelease() {
        val artifact = LinuxApkAnalysisInstaller.AAPT2_ARTIFACT

        assertTrue(artifact.url.startsWith("https://dl.google.com/android/maven2/"))
        assertTrue(artifact.fileName == "aapt2-9.4.1-15978811-linux.jar")
    }

    @Test
    fun javaWrapperUsesDistributionJavaAlternative() {
        val wrapper = LinuxApkAnalysisInstaller.JAVA_WRAPPER

        assertTrue(wrapper.contains("exec /usr/bin/java"))
    }

    @Test
    fun javaRuntimeUsesSelectedDistributionStablePackage() {
        assertEquals(
            "/usr/local/bin/sta-apt install openjdk-25-jdk-headless qemu-user-static",
            linuxApkJavaInstallCommand(LinuxDistribution.DEBIAN),
        )
    }

    @Test
    fun downloaderUsesPinnedFallbackAfterOfficialEndpointFails() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedHosts += request.url.host
                if (request.url.host == "official.invalid") {
                    response(request, 502, ByteArray(0))
                } else {
                    response(request, 200, "abc".encodeToByteArray())
                }
            }
            .build()
        val artifact = VerifiedArtifact(
            id = "fixture",
            version = "1",
            fileName = "fixture.bin",
            url = "https://official.invalid/fixture.bin",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 3,
            fallbackUrls = listOf("https://fallback.invalid/fixture.bin"),
        )
        val target = File(temporaryFolder.root, artifact.fileName)

        assertTrue(VerifiedArtifactDownloader(client, NoopLogger).download(artifact, target))
        assertEquals(listOf("official.invalid", "fallback.invalid"), requestedHosts)
        assertEquals("abc", target.readText())
    }

    @Test
    fun downloaderUsesPreferredMirrorBeforeOfficialEndpoint() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedHosts += request.url.host
                response(request, 200, "abc".encodeToByteArray())
            }
            .build()
        val artifact = VerifiedArtifact(
            id = "fixture",
            version = "1",
            fileName = "preferred-fixture.bin",
            url = "https://official.invalid/fixture.bin",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 3,
            preferredUrls = listOf("https://mirror.invalid/fixture.bin"),
        )
        val target = File(temporaryFolder.root, artifact.fileName)

        assertTrue(VerifiedArtifactDownloader(client, NoopLogger).download(artifact, target))
        assertEquals(listOf("mirror.invalid"), requestedHosts)
    }

    private fun response(request: Request, code: Int, body: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Failed")
            .body(body.toResponseBody())
            .build()

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
