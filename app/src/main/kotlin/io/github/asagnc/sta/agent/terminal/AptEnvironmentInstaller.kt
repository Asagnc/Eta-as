package io.github.asagnc.sta.agent.terminal

import android.content.Context
import android.os.Build
import io.github.asagnc.sta.core.AndroidAgentLogger
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal enum class AptEnvironmentState {
    NOT_INSTALLED,
    BASE_READY,
    READY,
}

internal data class AptEnvironmentStatus(
    val state: AptEnvironmentState,
    val version: String? = null,
)

internal enum class AptInstallStage {
    CHECKING,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING_TOOLS,
    COMPLETE,
}

internal data class AptInstallProgress(
    val stage: AptInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface AptInstallResult {
    data object AlreadyReady : AptInstallResult
    data class BaseInstalled(val version: String) : AptInstallResult
    data class ToolsInstalled(val version: String) : AptInstallResult
    data object BaseNotInstalled : AptInstallResult
    data class UnsupportedAbi(val abi: String) : AptInstallResult
    data object RootUnavailable : AptInstallResult
    data object BusyBoxUnavailable : AptInstallResult
    data object EnvironmentUnavailable : AptInstallResult
    data class Failed(val stage: AptInstallStage, val code: String? = null, val message: String? = null) : AptInstallResult
}

/** 下载固定版本的 apt 系 rootfs（Debian / Ubuntu / Kali）；Android 内核、挂载与会话仍由 Sta 复用。 */
internal class AptEnvironmentInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    httpClient: OkHttpClient = VerifiedArtifactDownloader.defaultHttpClient(),
) {
    private val artifactDownloader = VerifiedArtifactDownloader(httpClient)

    fun status(): AptEnvironmentStatus {
        val rootfs = rootfsDir()
        val version = readInstalledVersion(rootfs)
        val state = when {
            commonToolsReady(rootfs) -> AptEnvironmentState.READY
            baseRootfsReady(rootfs) -> AptEnvironmentState.BASE_READY
            else -> AptEnvironmentState.NOT_INSTALLED
        }
        return AptEnvironmentStatus(state, version)
    }

    suspend fun installBase(
        onProgress: suspend (AptInstallProgress) -> Unit = {},
    ): AptInstallResult {
        installMutex.lock()
        return try {
            installBaseLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    suspend fun installTools(
        onProgress: suspend (AptInstallProgress) -> Unit = {},
    ): AptInstallResult {
        installMutex.lock()
        return try {
            installToolsLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installBaseLocked(
        onProgress: suspend (AptInstallProgress) -> Unit,
    ): AptInstallResult = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir()
        if (baseRootfsReady(rootfs)) {
            return@withContext AptInstallResult.AlreadyReady
        }
        io.github.asagnc.sta.data.repository.LinuxEnvironmentSettingsRepository.selectBackend(
            distribution, LinuxEnvironmentPaths.backendOf(rootfs.absolutePath),
        )
        val artifact = artifactForAbis(distribution, Build.SUPPORTED_ABIS.toList())
            ?: return@withContext AptInstallResult.UnsupportedAbi(
                Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            )

        onProgress(AptInstallProgress(AptInstallStage.CHECKING))
        preflightFailure()?.let { return@withContext it }

        val archive = File(context.cacheDir, artifact.fileName + ".download")
        try {
            onProgress(AptInstallProgress(AptInstallStage.DOWNLOADING))
            val downloaded = artifactDownloader.download(artifact, archive) { downloadedBytes, totalBytes ->
                onProgress(AptInstallProgress(AptInstallStage.DOWNLOADING, downloadedBytes, totalBytes))
            }
            if (!downloaded) return@withContext AptInstallResult.Failed(AptInstallStage.DOWNLOADING)
            coroutineContext.ensureActive()
            onProgress(AptInstallProgress(AptInstallStage.EXTRACTING))
            if (!installRootfs(artifact, archive, rootfs)) {
                return@withContext AptInstallResult.Failed(AptInstallStage.EXTRACTING)
            }
        } catch (failure: RootlessInstallFailure) {
            return@withContext AptInstallResult.Failed(AptInstallStage.EXTRACTING, failure.code, failure.message)
        } catch (_: java.io.IOException) {
            return@withContext AptInstallResult.Failed(AptInstallStage.EXTRACTING, "INSTALL_IO_FAILED", "安装文件无法读写，请检查内部存储空间并重试")
        } catch (_: IllegalArgumentException) {
            return@withContext AptInstallResult.Failed(AptInstallStage.EXTRACTING, "INVALID_ARCHIVE", "环境归档无效或包含不安全路径，请重新下载后重试")
        } finally {
            archive.delete()
        }

        onProgress(AptInstallProgress(AptInstallStage.COMPLETE))
        AptInstallResult.BaseInstalled(artifact.version)
    }

    private suspend fun installToolsLocked(
        onProgress: suspend (AptInstallProgress) -> Unit,
    ): AptInstallResult = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir()
        if (!baseRootfsReady(rootfs)) return@withContext AptInstallResult.BaseNotInstalled
        if (commonToolsReady(rootfs)) return@withContext AptInstallResult.AlreadyReady
        onProgress(AptInstallProgress(AptInstallStage.CHECKING))
        preflightFailure()?.let { return@withContext it }
        onProgress(AptInstallProgress(AptInstallStage.INSTALLING_TOOLS))
        if (!installCommonTools(rootfs)) {
            return@withContext AptInstallResult.Failed(AptInstallStage.INSTALLING_TOOLS)
        }
        onProgress(AptInstallProgress(AptInstallStage.COMPLETE))
        AptInstallResult.ToolsInstalled(readInstalledVersion(rootfs) ?: AptDistributionSpecs.versionOf(distribution))
    }

    private suspend fun preflightFailure(): AptInstallResult? = when (runPreflight().exitCode) {
        0 -> null
        PREFLIGHT_ROOT_UNAVAILABLE -> AptInstallResult.RootUnavailable
        PREFLIGHT_BUSYBOX_UNAVAILABLE, PREFLIGHT_BUSYBOX_INCOMPLETE ->
            AptInstallResult.BusyBoxUnavailable
        PREFLIGHT_ENVIRONMENT_UNAVAILABLE -> AptInstallResult.EnvironmentUnavailable
        else -> AptInstallResult.Failed(AptInstallStage.CHECKING)
    }

    private suspend fun runPreflight(): InstallerCommandResult {
        if (LinuxEnvironmentPaths.backendOf(rootfsDir().absolutePath) == LinuxExecutionBackend.PROOT) {
            return InstallerCommandResult(if (ProotCommandBuilder.available()) 0 else PREFLIGHT_ENVIRONMENT_UNAVAILABLE, "")
        }
        if (!TerminalRuntime.rootAvailable) return InstallerCommandResult(PREFLIGHT_ROOT_UNAVAILABLE, "")
        val requiredApplets = listOf(
            "ash", "chroot", "grep", "gzip", "mount", "sha256sum", "tar", "unshare", "xz",
        ).joinToString(" ")
        val command = """
            if [ "${'$'}(id -u)" != 0 ]; then exit $PREFLIGHT_ROOT_UNAVAILABLE; fi
            ${AndroidBusyBox.discoveryScript()}
            if [ -z "${'$'}sta_busybox" ]; then exit $PREFLIGHT_BUSYBOX_UNAVAILABLE; fi
            for sta_applet in $requiredApplets; do
              "${'$'}sta_busybox" --list | "${'$'}sta_busybox" grep -qx "${'$'}sta_applet" || exit $PREFLIGHT_BUSYBOX_INCOMPLETE
            done
            "${'$'}sta_busybox" unshare -m --propagation private \
              "${'$'}sta_busybox" chroot / /system/bin/sh -c ':' || exit $PREFLIGHT_ENVIRONMENT_UNAVAILABLE
        """.trimIndent()
        return InstallerShellRunner.run(command, 15, TerminalEnvironment.ANDROID)
    }

    private suspend fun installRootfs(artifact: VerifiedArtifact, archive: File, rootfs: File): Boolean {
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return RootlessLinuxInstaller.installBase(artifact, archive, rootfs, distribution)
        }
        val parent = rootfs.parentFile ?: return false
        val temporaryRootfs = File(parent, "rootfs.installing")
        val staSources = AptDistributionSpecs.mirrorsOf(distribution).first().sources
            .joinToString(" ") { line -> shellQuote(line) }
        val markerBody = "version=${artifact.version}\\ndistribution=${distribution.wireName}\\nsha256=${artifact.sha256}\\n"
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}sta_busybox" ] || exit 127
            sta_archive=${shellQuote(archive.absolutePath)}
            sta_parent=${shellQuote(parent.absolutePath)}
            sta_rootfs=${shellQuote(rootfs.absolutePath)}
            sta_temporary=${shellQuote(temporaryRootfs.absolutePath)}
            sta_actual_sha=${'$'}("${'$'}sta_busybox" sha256sum "${'$'}sta_archive" | "${'$'}sta_busybox" awk '{print ${'$'}1}')
            [ "${'$'}sta_actual_sha" = ${shellQuote(artifact.sha256)} ] || exit 65
            "${'$'}sta_busybox" mkdir -p "${'$'}sta_parent" || exit 66
            "${'$'}sta_busybox" rm -rf "${'$'}sta_temporary"
            "${'$'}sta_busybox" mkdir -p "${'$'}sta_temporary" || exit 66
            "${'$'}sta_busybox" tar -xJf "${'$'}sta_archive" -C "${'$'}sta_temporary" || exit 67
            # 归档顶层形状不一致：proot-distro 的制品带 "./" 前缀，解包后直接落在根；Ubuntu 官方 cloud
            # 镜像的 root tar 没有顶层目录。这里统一成"根就是文件系统"：解包后确实只剩一个顶层目录时，
            # 才把它提上来。
            sta_top_count=${'$'}("${'$'}sta_busybox" ls -A "${'$'}sta_temporary" | "${'$'}sta_busybox" wc -l)
            sta_top_name=${'$'}("${'$'}sta_busybox" ls -A "${'$'}sta_temporary")
            if [ "${'$'}sta_top_count" -eq 1 ] && [ -d "${'$'}sta_temporary/${'$'}sta_top_name" ]; then
              for sta_child in "${'$'}sta_temporary/${'$'}sta_top_name"/* "${'$'}sta_temporary/${'$'}sta_top_name"/.[!.]* "${'$'}sta_temporary/${'$'}sta_top_name"/..?*; do
                [ -e "${'$'}sta_child" ] || continue
                "${'$'}sta_busybox" mv "${'$'}sta_child" "${'$'}sta_temporary/" || exit 67
              done
              "${'$'}sta_busybox" rmdir "${'$'}sta_temporary/${'$'}sta_top_name" || exit 67
            fi
            "${'$'}sta_busybox" mkdir -p \
              "${'$'}sta_temporary/proc" \
              "${'$'}sta_temporary/sys" \
              "${'$'}sta_temporary/dev" \
              "${'$'}sta_temporary/workspace" \
              "${'$'}sta_temporary/storage/emulated/0" \
              "${'$'}sta_temporary/data/local/tmp" \
              "${'$'}sta_temporary/tmp"
            "${'$'}sta_busybox" chmod 1777 "${'$'}sta_temporary/tmp"
            "${'$'}sta_busybox" rm -f "${'$'}sta_temporary/sdcard"
            "${'$'}sta_busybox" ln -s /storage/emulated/0 "${'$'}sta_temporary/sdcard"
            # Ubuntu 云镜像把 /etc/resolv.conf 做成指向 /run/systemd/resolve/stub-resolv.conf 的符号链接，
            # 而 rootfs 里没有那个目录，直接写会跟着链接落到不存在的路径上，容器内 DNS 随之全废。
            "${'$'}sta_busybox" rm -f "${'$'}sta_temporary/etc/resolv.conf"
            cat > "${'$'}sta_temporary/etc/resolv.conf" <<'STA_RESOLV_EOF'
            nameserver 223.5.5.5
            nameserver 119.29.29.29
            nameserver 1.1.1.1
            STA_RESOLV_EOF
            # 官方 cloud 镜像自带 deb822 源文件，清掉以免与接下来写入的 sources.list 重复
            "${'$'}sta_busybox" rm -rf "${'$'}sta_temporary/etc/apt/sources.list.d"
            "${'$'}sta_busybox" mkdir -p "${'$'}sta_temporary/etc/apt/sources.list.d"
            "${'$'}sta_busybox" mkdir -p "${'$'}sta_temporary/etc/apt/apt.conf.d" "${'$'}sta_temporary/usr/local/bin"
            cat > "${'$'}sta_temporary/etc/apt/apt.conf.d/99sta-network" <<'STA_APT_CONFIG_EOF'
            Acquire::Retries "2";
            Acquire::http::Pipeline-Depth "0";
            Acquire::https::Pipeline-Depth "0";
            STA_APT_CONFIG_EOF
            printf '%s\n' ${staSources} > "${'$'}sta_temporary/etc/apt/sources.list"
            printf '%s\n' '#!/bin/sh' > "${'$'}sta_temporary/usr/local/bin/sta-apt"
            printf %s ${shellQuote(aptMirrorScriptBody(distribution))} >> "${'$'}sta_temporary/usr/local/bin/sta-apt"
            "${'$'}sta_busybox" chmod 0755 "${'$'}sta_temporary/usr/local/bin/sta-apt"
            printf ${shellQuote(markerBody)} > "${'$'}sta_temporary/${LinuxEnvironmentPaths.READY_MARKER}"
            "${'$'}sta_busybox" chmod 0644 "${'$'}sta_temporary/${LinuxEnvironmentPaths.READY_MARKER}"
            "${'$'}sta_busybox" rm -rf "${'$'}sta_rootfs"
            "${'$'}sta_busybox" mv "${'$'}sta_temporary" "${'$'}sta_rootfs" || exit 69
        """.trimIndent()
        val result = InstallerShellRunner.run(command, 180, TerminalEnvironment.ANDROID)
        AndroidAgentLogger.info(
            "Linux environment action=extract outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun installCommonTools(rootfs: File): Boolean {
        val packages = AGENT_PACKAGES.joinToString(" ")
        val command = """
            export DEBIAN_FRONTEND=noninteractive
            mkdir -p /usr/local/bin
            printf '%s\n' '#!/bin/sh' > /usr/local/bin/sta-apt
            printf %s ${shellQuote(aptMirrorScriptBody(distribution))} >> /usr/local/bin/sta-apt
            chmod 0755 /usr/local/bin/sta-apt
            /usr/local/bin/sta-apt install $packages || exit 70
            if command -v fdfind >/dev/null 2>&1; then ln -sf /usr/bin/fdfind /usr/local/bin/fd; fi
            cat > /${LinuxEnvironmentPaths.COMMON_TOOLS_MARKER} <<'STA_TOOLSET_EOF'
            ${distribution.wireName}=${AptDistributionSpecs.versionOf(distribution)}
            toolset=${LinuxEnvironmentPaths.TOOLSET_REVISION}
            profiles=agent
            STA_TOOLSET_EOF
            chmod 0644 /${LinuxEnvironmentPaths.COMMON_TOOLS_MARKER}
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command,
            COMMON_TOOLS_TIMEOUT_SECONDS,
            distribution.terminalEnvironment,
            rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Linux environment action=install_tools outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private fun rootfsDir(): File = LinuxEnvironmentPaths.rootfsDir(context, distribution)

    /**
     * 删除该发行版的 rootfs，让环境回到未安装状态。该环境下的后台任务由终端页负责停止，
     * 这里不代管；rootfs 消失后它们读不到环境内的文件。
     */
    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        installMutex.lock()
        try {
            val rootfs = rootfsDir()
            if (!rootfs.exists()) return@withContext true
            if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
                return@withContext rootfs.deleteRecursively()
            }
            val result = InstallerShellRunner.run(
                command = "rm -rf ${shellQuote(rootfs.absolutePath)}",
                timeoutSeconds = UNINSTALL_TIMEOUT_SECONDS,
                environment = TerminalEnvironment.ANDROID,
            )
            result.exitCode == 0 && !rootfs.exists()
        } finally {
            installMutex.unlock()
        }
    }

    private fun commonToolsReady(rootfs: File): Boolean {
        val marker = File(rootfs, LinuxEnvironmentPaths.COMMON_TOOLS_MARKER)
        if (!baseRootfsReady(rootfs) || !marker.isFile) return false
        return runCatching {
            marker.useLines { lines ->
                lines.any { it.trim() == "toolset=${LinuxEnvironmentPaths.TOOLSET_REVISION}" }
            }
        }.getOrDefault(false)
    }

    private fun readInstalledVersion(rootfs: File): String? = runCatching {
        File(rootfs, LinuxEnvironmentPaths.READY_MARKER).readLines()
            .firstOrNull { it.startsWith("version=") }
            ?.substringAfter('=')?.trim()
            ?.takeIf { it.matches(Regex("[0-9]+")) }
    }.getOrNull()

    companion object {
        private const val COMMON_TOOLS_TIMEOUT_SECONDS = 900L
        private const val UNINSTALL_TIMEOUT_SECONDS = 180L
        private const val PREFLIGHT_ROOT_UNAVAILABLE = 40
        private const val PREFLIGHT_BUSYBOX_UNAVAILABLE = 41
        private const val PREFLIGHT_BUSYBOX_INCOMPLETE = 42
        private const val PREFLIGHT_ENVIRONMENT_UNAVAILABLE = 43
        private val installMutex = Mutex()

        internal fun baseRootfsReady(rootfs: File): Boolean =
            LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)

        internal val AGENT_PACKAGES = listOf(
            "bash", "ca-certificates", "coreutils", "curl", "diffutils", "file", "findutils",
            "gawk", "git", "grep", "gzip", "jq", "less", "openssl", "openssh-client",
            "patch", "procps", "ripgrep", "rsync", "sed", "sqlite3", "tar", "unzip", "util-linux", "wget",
            "xz-utils", "zip", "zstd", "fd-find",
        )

        /** 逐个尝试镜像并把成功者写回 sources.list，后续 apt 操作复用它。 */
        internal fun aptMirrorScript(distribution: LinuxDistribution): String =
            "#!/bin/sh\n${aptMirrorScriptBody(distribution)}"

        private fun aptMirrorScriptBody(distribution: LinuxDistribution): String {
            val mirrors = AptDistributionSpecs.mirrorsOf(distribution)
            val mirrorIds = mirrors.joinToString(" ") { it.id }
            return buildString {
                append("set -u; ")
                append("sta_apt_write_sources() { ")
                append("case \"${'$'}1\" in ")
                mirrors.forEach { mirror ->
                    append("${mirror.id}) ")
                    append("printf '%s\\n' ")
                    mirror.sources.forEach { line -> append(shellQuote(line)).append(' ') }
                    append("> /etc/apt/sources.list;; ")
                }
                append("*) return 64;; esac; ")
                append("}; ")
                append("case \"${'$'}{1:-}\" in ")
                append("install) shift; [ \"${'$'}#\" -gt 0 ] || exit 64; ")
                append("for sta_apt_mirror in $mirrorIds; do ")
                append("sta_apt_write_sources \"${'$'}sta_apt_mirror\" || exit 65; ")
                append("if apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 update && ")
                append("apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 install -y --no-install-recommends \"${'$'}@\"; ")
                append("then exit 0; fi; ")
                append("done; exit 1;; ")
                append("update) for sta_apt_mirror in $mirrorIds; do ")
                append("sta_apt_write_sources \"${'$'}sta_apt_mirror\" || exit 65; ")
                append("apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 update && exit 0; done; exit 1;; ")
                append("*) echo \"usage: sta-apt install PACKAGE... | update\" >&2; exit 64;; esac")
            }
        }

        internal fun artifactForAbis(distribution: LinuxDistribution, abis: List<String>): VerifiedArtifact? =
            abis.firstNotNullOfOrNull { abi -> AptDistributionSpecs.artifactOf(distribution, abi) }

        private val GITHUB_PROXY_PREFIXES = listOf(
            "https://gh-proxy.com/",
        )
    }
}
