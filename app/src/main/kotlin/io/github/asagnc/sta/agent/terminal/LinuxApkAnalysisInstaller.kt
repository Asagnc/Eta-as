package io.github.asagnc.sta.agent.terminal

import android.content.Context
import io.github.asagnc.sta.core.AndroidAgentLogger
import io.github.asagnc.sta.core.safeLogType
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class ApkAnalysisInstallStage {
    CHECKING,
    DOWNLOADING,
    PREPARING,
    INSTALLING_JAVA,
    ACTIVATING,
    VERIFYING,
    COMPLETE,
}

internal data class ApkAnalysisInstallProgress(
    val stage: ApkAnalysisInstallStage,
    val artifactName: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface ApkAnalysisInstallResult {
    data object AlreadyReady : ApkAnalysisInstallResult
    data object EnvironmentNotReady : ApkAnalysisInstallResult
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : ApkAnalysisInstallResult
    data object Installed : ApkAnalysisInstallResult
    data class Failed(val stage: ApkAnalysisInstallStage) : ApkAnalysisInstallResult
}

internal fun linuxApkAnalysisReady(rootfs: File): Boolean {
    val marker = File(rootfs, LinuxEnvironmentPaths.APK_ANALYSIS_MARKER)
    if (!marker.isFile) return false
    return marker.useLines { lines ->
        lines.any { line -> line.trim() == "profile=${LinuxEnvironmentPaths.APK_ANALYSIS_REVISION}" }
    }
}

internal fun linuxApkJavaInstallCommand(distribution: LinuxDistribution): String =
    when (distribution) {
        LinuxDistribution.DEBIAN ->
            "/usr/local/bin/sta-apt install openjdk-25-jdk-headless qemu-user-static"
    }

/** 为当前 Linux 发行版安装 Java 分析与资源编译工具链；官方 aapt2 以 x86-64 发布，经 qemu-user 转译运行。 */
internal class LinuxApkAnalysisInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val artifactDownloader: VerifiedArtifactDownloader = VerifiedArtifactDownloader(),
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)

    fun isReady(): Boolean = linuxApkAnalysisReady(rootfs)

    suspend fun install(
        onProgress: suspend (ApkAnalysisInstallProgress) -> Unit = {},
    ): ApkAnalysisInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (ApkAnalysisInstallProgress) -> Unit,
    ): ApkAnalysisInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext ApkAnalysisInstallResult.AlreadyReady
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, LinuxEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext ApkAnalysisInstallResult.EnvironmentNotReady
        }
        val availableBytes = rootfs.parentFile?.usableSpace ?: context.filesDir.usableSpace
        if (availableBytes < MIN_AVAILABLE_BYTES) {
            return@withContext ApkAnalysisInstallResult.InsufficientSpace(
                requiredBytes = MIN_AVAILABLE_BYTES,
                availableBytes = availableBytes,
            )
        }

        val downloadedArtifacts = linkedMapOf<VerifiedArtifact, File>()
        for (artifact in ARTIFACTS) {
            coroutineContext.ensureActive()
            val target = File(LinuxEnvironmentPaths.artifactDir(context), artifact.fileName)
            onProgress(
                ApkAnalysisInstallProgress(
                    stage = ApkAnalysisInstallStage.DOWNLOADING,
                    artifactName = artifact.id,
                    totalBytes = artifact.sizeBytes,
                ),
            )
            val downloaded = artifactDownloader.download(artifact, target) { downloadedBytes, totalBytes ->
                onProgress(
                    ApkAnalysisInstallProgress(
                        stage = ApkAnalysisInstallStage.DOWNLOADING,
                        artifactName = artifact.id,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!downloaded) {
                return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.DOWNLOADING)
            }
            downloadedArtifacts[artifact] = target
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.PREPARING))
        val staging = LinuxEnvironmentPaths.profileStagingDir(context, PROFILE_ID)
        if (!prepareStaging(staging, downloadedArtifacts)) {
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.PREPARING)
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.INSTALLING_JAVA))
        if (!installJava(rootfs)) {
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.INSTALLING_JAVA)
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.ACTIVATING))
        if (!activateStaging(rootfs, staging)) {
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.ACTIVATING)
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.VERIFYING))
        if (!verifyAndMark(rootfs)) {
            rollback(rootfs)
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.VERIFYING)
        }

        cleanupAfterSuccess(rootfs, downloadedArtifacts.values)
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.COMPLETE))
        ApkAnalysisInstallResult.Installed
    }

    private fun prepareStaging(
        staging: File,
        artifacts: Map<VerifiedArtifact, File>,
    ): Boolean = try {
        staging.deleteRecursively()
        check(staging.mkdirs())
        val jadxArchive = artifacts.getValue(JADX_ARTIFACT)
        check(extractJadx(jadxArchive, staging))
        val libraryDir = File(staging, "lib").apply { check(mkdirs()) }
        artifacts.getValue(APKTOOL_ARTIFACT).copyTo(File(libraryDir, "apktool.jar"), overwrite = true)
        artifacts.getValue(SMALI_ARTIFACT).copyTo(File(libraryDir, "smali.jar"), overwrite = true)
        artifacts.getValue(BAKSMALI_ARTIFACT).copyTo(File(libraryDir, "baksmali.jar"), overwrite = true)
        val binDir = File(staging, "bin").apply { check(mkdirs()) }
        check(extractAapt2(artifacts.getValue(AAPT2_ARTIFACT), File(binDir, "aapt2")))
        File(binDir, "java").writeText(JAVA_WRAPPER)
        File(binDir, "apktool").writeText(APKTOOL_WRAPPER)
        File(binDir, "smali").writeText(javaJarWrapper("smali.jar"))
        File(binDir, "baksmali").writeText(javaJarWrapper("baksmali.jar"))
        File(binDir, "aapt2-qemu").writeText(AAPT2_WRAPPER)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        AndroidAgentLogger.warn(
            "APK analysis profile action=prepare outcome=failed errorType=${throwable.safeLogType()}",
        )
        staging.deleteRecursively()
        false
    }

    private fun extractJadx(archive: File, staging: File): Boolean {
        val targets = mapOf(
            "bin/jadx" to File(staging, "jadx/bin/jadx"),
            "lib/jadx-$JADX_VERSION-all.jar" to File(staging, "jadx/lib/jadx-$JADX_VERSION-all.jar"),
            "LICENSE" to File(staging, "licenses/jadx-LICENSE"),
        )
        val extracted = mutableSetOf<String>()
        var extractedBytes = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = targets[entry.name]
                if (target != null) {
                    check(!entry.isDirectory && extracted.add(entry.name))
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extractedBytes += count.toLong()
                            check(extractedBytes <= MAX_JADX_EXTRACTED_BYTES)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return extracted == targets.keys
    }

    /** 官方 aapt2 以 x86-64 发布，这里只从制品 jar 中取出该二进制，执行交给 aapt2-qemu wrapper。 */
    private fun extractAapt2(archive: File, target: File): Boolean {
        val extracted = runCatching {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "aapt2" && !entry.isDirectory) {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> zip.copyTo(output) }
                        return@use true
                    }
                    zip.closeEntry()
                }
                false
            }
        }.getOrDefault(false)
        check(extracted && target.isFile)
        return extracted
    }

    private suspend fun installJava(rootfs: File): Boolean {
        val result = InstallerShellRunner.run(
            command = linuxApkJavaInstallCommand(distribution),
            timeoutSeconds = 900,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=install_java " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun activateStaging(rootfs: File, staging: File): Boolean {
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return activateRootless(rootfs, staging)
        }
        val profileRoot = File(rootfs, "opt/sta/apk-analysis")
        val current = File(profileRoot, "current")
        val installing = File(profileRoot, "current.installing")
        val previous = File(profileRoot, "previous")
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}sta_busybox" ] || exit 127
            "${'$'}sta_busybox" mkdir -p ${shellQuote(profileRoot.absolutePath)} || exit 70
            "${'$'}sta_busybox" rm -rf ${shellQuote(installing.absolutePath)} ${shellQuote(previous.absolutePath)}
            "${'$'}sta_busybox" mv ${shellQuote(staging.absolutePath)} ${shellQuote(installing.absolutePath)} || exit 71
            "${'$'}sta_busybox" chmod 0755 \
              ${shellQuote(File(installing, "jadx/bin/jadx").absolutePath)} \
              ${shellQuote(File(installing, "bin/java").absolutePath)} \
              ${shellQuote(File(installing, "bin/apktool").absolutePath)} \
              ${shellQuote(File(installing, "bin/smali").absolutePath)} \
              ${shellQuote(File(installing, "bin/baksmali").absolutePath)} \
              ${shellQuote(File(installing, "bin/aapt2").absolutePath)} \
              ${shellQuote(File(installing, "bin/aapt2-qemu").absolutePath)} || exit 72
            sta_link_commands() {
              "${'$'}sta_busybox" mkdir -p ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)} || return 1
              for sta_command in java jadx apktool smali baksmali aapt2; do
                "${'$'}sta_busybox" rm -f ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)}/"${'$'}sta_command"
              done
              "${'$'}sta_busybox" ln -s ../../../opt/sta/apk-analysis/current/bin/java \
                ${shellQuote(File(rootfs, "usr/local/bin/java").absolutePath)} || return 1
              "${'$'}sta_busybox" ln -s ../../../opt/sta/apk-analysis/current/jadx/bin/jadx \
                ${shellQuote(File(rootfs, "usr/local/bin/jadx").absolutePath)} || return 1
              for sta_command in apktool smali baksmali; do
                "${'$'}sta_busybox" ln -s ../../../opt/sta/apk-analysis/current/bin/"${'$'}sta_command" \
                  ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)}/"${'$'}sta_command" || return 1
              done
              "${'$'}sta_busybox" ln -s ../../../opt/sta/apk-analysis/current/bin/aapt2-qemu \
                ${shellQuote(File(rootfs, "usr/local/bin/aapt2").absolutePath)} || return 1
            }
            sta_restore_previous() {
              "${'$'}sta_busybox" rm -rf ${shellQuote(current.absolutePath)}
              if [ -d ${shellQuote(previous.absolutePath)} ]; then
                "${'$'}sta_busybox" mv ${shellQuote(previous.absolutePath)} ${shellQuote(current.absolutePath)}
                sta_link_commands || true
              fi
            }
            if [ -d ${shellQuote(current.absolutePath)} ]; then
              "${'$'}sta_busybox" mv ${shellQuote(current.absolutePath)} ${shellQuote(previous.absolutePath)} || exit 73
            fi
            "${'$'}sta_busybox" mv ${shellQuote(installing.absolutePath)} ${shellQuote(current.absolutePath)} || {
              sta_restore_previous
              exit 74
            }
            sta_link_commands || {
              sta_restore_previous
              exit 76
            }
            "${'$'}sta_busybox" rm -f ${shellQuote(File(rootfs, LinuxEnvironmentPaths.APK_ANALYSIS_MARKER).absolutePath)}
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 60,
            environment = TerminalEnvironment.ANDROID,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=activate " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} exitCode=${result.exitCode}",
        )
        return result.exitCode == 0
    }

    private suspend fun verifyAndMark(rootfs: File): Boolean {
        val command = """
            rm -f /${LinuxEnvironmentPaths.APK_ANALYSIS_MARKER}
            java -version >/dev/null 2>&1 || exit 81
            jadx --version >/dev/null 2>&1 || exit 82
            apktool --version >/dev/null 2>&1 || exit 83
            smali --version >/dev/null 2>&1 || exit 84
            baksmali --version >/dev/null 2>&1 || exit 85
            rm -rf /tmp/sta-aapt2-verify && mkdir -p /tmp/sta-aapt2-verify/res/values || exit 87
            printf '<resources><string name="sta">ok</string></resources>' \
              > /tmp/sta-aapt2-verify/res/values/strings.xml || exit 87
            aapt2 compile --dir /tmp/sta-aapt2-verify/res -o /tmp/sta-aapt2-verify/out.zip || exit 88
            [ -s /tmp/sta-aapt2-verify/out.zip ] || exit 89
            rm -rf /tmp/sta-aapt2-verify
            cat > /${LinuxEnvironmentPaths.APK_ANALYSIS_MARKER} <<'STA_APK_ANALYSIS_EOF'
            profile=${LinuxEnvironmentPaths.APK_ANALYSIS_REVISION}
            jadx=$JADX_VERSION
            apktool=$APKTOOL_VERSION
            smali=$SMALI_VERSION
            aapt2=$AAPT2_VERSION
            STA_APK_ANALYSIS_EOF
            chmod 0644 /${LinuxEnvironmentPaths.APK_ANALYSIS_MARKER} || exit 86
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 90,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=verify " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun rollback(rootfs: File) {
        val profileRoot = File(rootfs, "opt/sta/apk-analysis")
        val current = File(profileRoot, "current")
        val previous = File(profileRoot, "previous")
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            current.deleteRecursively()
            File(rootfs, LinuxEnvironmentPaths.APK_ANALYSIS_MARKER).delete()
            if (previous.exists()) previous.renameTo(current)
            return
        }
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}sta_busybox" ] || exit 127
            "${'$'}sta_busybox" rm -rf ${shellQuote(current.absolutePath)}
            "${'$'}sta_busybox" rm -f ${shellQuote(File(rootfs, LinuxEnvironmentPaths.APK_ANALYSIS_MARKER).absolutePath)}
            if [ -d ${shellQuote(previous.absolutePath)} ]; then
              "${'$'}sta_busybox" mv ${shellQuote(previous.absolutePath)} ${shellQuote(current.absolutePath)}
            fi
        """.trimIndent()
        InstallerShellRunner.run(command, 30, TerminalEnvironment.ANDROID)
    }

    private suspend fun cleanupAfterSuccess(rootfs: File, artifacts: Collection<File>) {
        artifacts.forEach(File::delete)
        val previous = File(rootfs, "opt/sta/apk-analysis/previous")
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            previous.deleteRecursively()
            return
        }
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}sta_busybox" ] || exit 127
            "${'$'}sta_busybox" rm -rf ${shellQuote(previous.absolutePath)}
        """.trimIndent()
        InstallerShellRunner.run(command, 30, TerminalEnvironment.ANDROID)
    }

    private fun activateRootless(rootfs: File, staging: File): Boolean {
        val profileRoot = File(rootfs, "opt/sta/apk-analysis").apply { mkdirs() }
        val current = File(profileRoot, "current")
        val previous = File(profileRoot, "previous")
        if (previous.exists() && !previous.deleteRecursively()) return false
        if (current.exists() && !current.renameTo(previous)) return false
        try {
            if (!staging.renameTo(current)) throw java.io.IOException("无法激活工具目录")
            listOf(
                "jadx/bin/jadx",
                "bin/java",
                "bin/apktool",
                "bin/smali",
                "bin/baksmali",
                "bin/aapt2",
                "bin/aapt2-qemu",
            ).forEach {
                if (!File(current, it).setExecutable(true, false)) throw java.io.IOException("无法设置工具权限")
            }
            val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
            listOf("java", "jadx", "apktool", "smali", "baksmali", "aapt2").forEach { name ->
                val path = File(localBin, name).toPath()
                java.nio.file.Files.deleteIfExists(path)
                val relative = when (name) {
                    "jadx" -> "jadx/bin/jadx"
                    "aapt2" -> "bin/aapt2-qemu"
                    else -> "bin/$name"
                }
                java.nio.file.Files.createSymbolicLink(path, java.nio.file.Path.of("../../../opt/sta/apk-analysis/current/$relative"))
            }
            File(rootfs, LinuxEnvironmentPaths.APK_ANALYSIS_MARKER).delete()
            return true
        } catch (_: java.io.IOException) {
            current.deleteRecursively()
            if (previous.exists()) previous.renameTo(current)
            return false
        }
    }

    companion object {
        private const val PROFILE_ID = "apk-analysis"
        private const val JADX_VERSION = "1.5.6"
        private const val APKTOOL_VERSION = "3.0.3"
        private const val SMALI_VERSION = "3.0.10"
        private const val AAPT2_VERSION = "9.4.1-15978811"
        private const val MAX_JADX_EXTRACTED_BYTES = 128L * 1024L * 1024L
        internal const val MIN_AVAILABLE_BYTES = 768L * 1024L * 1024L

        private val installMutex = Mutex()
        private val GITHUB_PROXY_PREFIXES = listOf(
            "https://gh-proxy.com/",
        )

        internal val JADX_ARTIFACT = githubReleaseArtifact(
            id = "jadx",
            version = JADX_VERSION,
            fileName = "jadx-$JADX_VERSION.zip",
            repository = "skylot/jadx",
            tag = "v$JADX_VERSION",
            sha256 = "545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974",
            sizeBytes = 72_646_741L,
        )
        internal val APKTOOL_ARTIFACT = githubReleaseArtifact(
            id = "apktool",
            version = APKTOOL_VERSION,
            fileName = "apktool_$APKTOOL_VERSION.jar",
            repository = "iBotPeaches/Apktool",
            tag = "v$APKTOOL_VERSION",
            sha256 = "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423",
            sizeBytes = 15_478_013L,
        )
        internal val SMALI_ARTIFACT = githubReleaseArtifact(
            id = "smali",
            version = SMALI_VERSION,
            fileName = "smali-$SMALI_VERSION-fat-release.jar",
            repository = "baksmali/smali",
            tag = SMALI_VERSION,
            sha256 = "32fa0e88a6c397b3922201adf5f3e534fbaed5a663c71d0c558c3ddce0af844a",
            sizeBytes = 5_384_623L,
        )
        internal val BAKSMALI_ARTIFACT = githubReleaseArtifact(
            id = "baksmali",
            version = SMALI_VERSION,
            fileName = "baksmali-$SMALI_VERSION-fat-release.jar",
            repository = "baksmali/smali",
            tag = SMALI_VERSION,
            sha256 = "37ae4a41a8886e15c20b8362fa4250f96bbdb55e1a608199ad8b5dff068b588f",
            sizeBytes = 4_447_943L,
        )
        internal val AAPT2_ARTIFACT = googleMavenArtifact(
            id = "aapt2",
            version = AAPT2_VERSION,
            fileName = "aapt2-$AAPT2_VERSION-linux.jar",
            groupPath = "com/android/tools/build/aapt2",
            sha256 = "f5bebd466ecf14d341fd465f2756a16d86052f29eb4532003d5ff7bcffd08de5",
            sizeBytes = 2_385_035L,
        )
        internal val ARTIFACTS = listOf(
            JADX_ARTIFACT,
            APKTOOL_ARTIFACT,
            SMALI_ARTIFACT,
            BAKSMALI_ARTIFACT,
            AAPT2_ARTIFACT,
        )

        private fun githubReleaseArtifact(
            id: String,
            version: String,
            fileName: String,
            repository: String,
            tag: String,
            sha256: String,
            sizeBytes: Long,
        ): VerifiedArtifact {
            val officialUrl = "https://github.com/$repository/releases/download/$tag/$fileName"
            return VerifiedArtifact(
                id = id,
                version = version,
                fileName = fileName,
                url = officialUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                preferredUrls = GITHUB_PROXY_PREFIXES.map { prefix -> prefix + officialUrl },
            )
        }

        /** Google Maven 只发布 x86-64 aapt2；ARM64 环境经 qemu-user 转译执行，不使用镜像前缀。 */
        private fun googleMavenArtifact(
            id: String,
            version: String,
            fileName: String,
            groupPath: String,
            sha256: String,
            sizeBytes: Long,
        ): VerifiedArtifact = VerifiedArtifact(
            id = id,
            version = version,
            fileName = fileName,
            url = "https://dl.google.com/android/maven2/$groupPath/$version/$fileName",
            sha256 = sha256,
            sizeBytes = sizeBytes,
        )

        /**
         * 官方 aapt2 是 x86-64 动态可执行文件，在 ARM64 rootfs 内经 qemu-user 转译运行。
         * `-L` 必须指向 x86-64 加载器与动态库所在目录，否则加载器找不到 libc 会直接失败。
         */
        internal val AAPT2_WRAPPER = """
            #!/bin/sh
            exec /usr/bin/qemu-x86_64-static -L /usr/lib/x86_64-linux-gnu \
              /opt/sta/apk-analysis/current/bin/aapt2 "${'$'}@"
        """.trimIndent() + "\n"

        internal val APKTOOL_WRAPPER = """
            #!/bin/sh
            exec java -jar /opt/sta/apk-analysis/current/lib/apktool.jar "${'$'}@"
        """.trimIndent() + "\n"

        internal val JAVA_WRAPPER = """
            #!/bin/sh
            exec /usr/bin/java "${'$'}@"
        """.trimIndent() + "\n"

        private fun javaJarWrapper(fileName: String): String =
            "#!/bin/sh\nexec java -jar /opt/sta/apk-analysis/current/lib/$fileName \"${'$'}@\"\n"
    }
}
