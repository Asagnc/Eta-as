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
            if [ -z "${'$'}eta_busybox" ]; then exit $PREFLIGHT_BUSYBOX_UNAVAILABLE; fi
            for eta_applet in $requiredApplets; do
              "${'$'}eta_busybox" --list | "${'$'}eta_busybox" grep -qx "${'$'}eta_applet" || exit $PREFLIGHT_BUSYBOX_INCOMPLETE
            done
            "${'$'}eta_busybox" unshare -m --propagation private \
              "${'$'}eta_busybox" chroot / /system/bin/sh -c ':' || exit $PREFLIGHT_ENVIRONMENT_UNAVAILABLE
        """.trimIndent()
        return InstallerShellRunner.run(command, 15, TerminalEnvironment.ANDROID)
    }

    private suspend fun installRootfs(artifact: VerifiedArtifact, archive: File, rootfs: File): Boolean {
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return RootlessLinuxInstaller.installBase(artifact, archive, rootfs, distribution)
        }
        val parent = rootfs.parentFile ?: return false
        val temporaryRootfs = File(parent, "rootfs.installing")
        val etaSources = AptDistributionSpecs.mirrorsOf(distribution).first().sources
            .joinToString(" ") { line -> shellQuote(line) }
        val markerBody = "version=${artifact.version}\\ndistribution=${distribution.wireName}\\nsha256=${artifact.sha256}\\n"
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            eta_archive=${shellQuote(archive.absolutePath)}
            eta_parent=${shellQuote(parent.absolutePath)}
            eta_rootfs=${shellQuote(rootfs.absolutePath)}
            eta_temporary=${shellQuote(temporaryRootfs.absolutePath)}
            eta_actual_sha=${'$'}("${'$'}eta_busybox" sha256sum "${'$'}eta_archive" | "${'$'}eta_busybox" awk '{print ${'$'}1}')
            [ "${'$'}eta_actual_sha" = ${shellQuote(artifact.sha256)} ] || exit 65
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_parent" || exit 66
            "${'$'}eta_busybox" rm -rf "${'$'}eta_temporary"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary" || exit 66
            "${'$'}eta_busybox" tar -xJf "${'$'}eta_archive" -C "${'$'}eta_temporary" || exit 67
            # 归档顶层形状不一致：proot-distro 的制品带 "./" 前缀，解包后直接落在根；Ubuntu 官方 cloud
            # 镜像的 root tar 没有顶层目录。这里统一成"根就是文件系统"：解包后确实只剩一个顶层目录时，
            # 才把它提上来。
            eta_top_count=${'$'}("${'$'}eta_busybox" ls -A "${'$'}eta_temporary" | "${'$'}eta_busybox" wc -l)
            eta_top_name=${'$'}("${'$'}eta_busybox" ls -A "${'$'}eta_temporary")
            if [ "${'$'}eta_top_count" -eq 1 ] && [ -d "${'$'}eta_temporary/${'$'}eta_top_name" ]; then
              for eta_child in "${'$'}eta_temporary/${'$'}eta_top_name"/* "${'$'}eta_temporary/${'$'}eta_top_name"/.[!.]* "${'$'}eta_temporary/${'$'}eta_top_name"/..?*; do
                [ -e "${'$'}eta_child" ] || continue
                "${'$'}eta_busybox" mv "${'$'}eta_child" "${'$'}eta_temporary/" || exit 67
              done
              "${'$'}eta_busybox" rmdir "${'$'}eta_temporary/${'$'}eta_top_name" || exit 67
            fi
            "${'$'}eta_busybox" mkdir -p \
              "${'$'}eta_temporary/proc" \
              "${'$'}eta_temporary/sys" \
              "${'$'}eta_temporary/dev" \
              "${'$'}eta_temporary/workspace" \
              "${'$'}eta_temporary/storage/emulated/0" \
              "${'$'}eta_temporary/data/local/tmp" \
              "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" chmod 1777 "${'$'}eta_temporary/tmp"
            "${'$'}eta_busybox" rm -f "${'$'}eta_temporary/sdcard"
            "${'$'}eta_busybox" ln -s /storage/emulated/0 "${'$'}eta_temporary/sdcard"
            # Ubuntu 云镜像把 /etc/resolv.conf 做成指向 /run/systemd/resolve/stub-resolv.conf 的符号链接，
            # 而 rootfs 里没有那个目录，直接写会跟着链接落到不存在的路径上，容器内 DNS 随之全废。
            "${'$'}eta_busybox" rm -f "${'$'}eta_temporary/etc/resolv.conf"
            cat > "${'$'}eta_temporary/etc/resolv.conf" <<'ETA_RESOLV_EOF'
            nameserver 223.5.5.5
            nameserver 119.29.29.29
            nameserver 1.1.1.1
            ETA_RESOLV_EOF
            # 官方 cloud 镜像自带 deb822 源文件，清掉以免与接下来写入的 sources.list 重复
            "${'$'}eta_busybox" rm -rf "${'$'}eta_temporary/etc/apt/sources.list.d"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary/etc/apt/sources.list.d"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_temporary/etc/apt/apt.conf.d" "${'$'}eta_temporary/usr/local/bin"
            cat > "${'$'}eta_temporary/etc/apt/apt.conf.d/99eta-network" <<'ETA_APT_CONFIG_EOF'
            Acquire::Retries "2";
            Acquire::http::Pipeline-Depth "0";
            Acquire::https::Pipeline-Depth "0";
            ETA_APT_CONFIG_EOF
            printf '%s\n' ${etaSources} > "${'$'}eta_temporary/etc/apt/sources.list"
            printf '%s\n' '#!/bin/sh' > "${'$'}eta_temporary/usr/local/bin/eta-apt"
            printf %s ${shellQuote(aptMirrorScriptBody(distribution))} >> "${'$'}eta_temporary/usr/local/bin/eta-apt"
            "${'$'}eta_busybox" chmod 0755 "${'$'}eta_temporary/usr/local/bin/eta-apt"
            printf ${shellQuote(markerBody)} > "${'$'}eta_temporary/${LinuxEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" chmod 0644 "${'$'}eta_temporary/${LinuxEnvironmentPaths.READY_MARKER}"
            "${'$'}eta_busybox" rm -rf "${'$'}eta_rootfs"
            "${'$'}eta_busybox" mv "${'$'}eta_temporary" "${'$'}eta_rootfs" || exit 69
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
            printf '%s\n' '#!/bin/sh' > /usr/local/bin/eta-apt
            printf %s ${shellQuote(aptMirrorScriptBody(distribution))} >> /usr/local/bin/eta-apt
            chmod 0755 /usr/local/bin/eta-apt
            /usr/local/bin/eta-apt install $packages || exit 70
            if command -v fdfind >/dev/null 2>&1; then ln -sf /usr/bin/fdfind /usr/local/bin/fd; fi
            cat > /${COMMON_TOOLS_MARKER} <<'ETA_TOOLSET_EOF'
            ${distribution.wireName}=${AptDistributionSpecs.versionOf(distribution)}
            toolset=$TOOLSET_REVISION
            profiles=agent
            ETA_TOOLSET_EOF
            chmod 0644 /${COMMON_TOOLS_MARKER}
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
        val marker = File(rootfs, COMMON_TOOLS_MARKER)
        if (!baseRootfsReady(rootfs) || !marker.isFile) return false
        return runCatching {
            marker.useLines { lines -> lines.any { it.trim() == "toolset=$TOOLSET_REVISION" } }
        }.getOrDefault(false)
    }

    private fun readInstalledVersion(rootfs: File): String? = runCatching {
        File(rootfs, LinuxEnvironmentPaths.READY_MARKER).readLines()
            .firstOrNull { it.startsWith("version=") }
            ?.substringAfter('=')?.trim()
            ?.takeIf { it.matches(Regex("[0-9]+")) }
    }.getOrNull()

    companion object {
        private const val COMMON_TOOLS_MARKER = ".eta-common-tools-ready"
        private const val TOOLSET_REVISION = 1
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
                append("eta_apt_write_sources() { ")
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
                append("for eta_apt_mirror in $mirrorIds; do ")
                append("eta_apt_write_sources \"${'$'}eta_apt_mirror\" || exit 65; ")
                append("if apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 update && ")
                append("apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 install -y --no-install-recommends \"${'$'}@\"; ")
                append("then exit 0; fi; ")
                append("done; exit 1;; ")
                append("update) for eta_apt_mirror in $mirrorIds; do ")
                append("eta_apt_write_sources \"${'$'}eta_apt_mirror\" || exit 65; ")
                append("apt-get -o Acquire::Retries=2 -o Acquire::http::Pipeline-Depth=0 update && exit 0; done; exit 1;; ")
                append("*) echo \"usage: eta-apt install PACKAGE... | update\" >&2; exit 64;; esac")
            }
        }

        internal fun artifactForAbis(distribution: LinuxDistribution, abis: List<String>): VerifiedArtifact? =
            abis.firstNotNullOfOrNull { abi -> AptDistributionSpecs.artifactOf(distribution, abi) }

        private val GITHUB_PROXY_PREFIXES = listOf(
            "https://gh-proxy.com/",
        )
    }
}
