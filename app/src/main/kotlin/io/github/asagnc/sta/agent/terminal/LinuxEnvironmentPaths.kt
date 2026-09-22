package io.github.asagnc.sta.agent.terminal

import android.content.Context
import io.github.asagnc.sta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File

/** 各 Linux rootfs 共用的磁盘布局和就绪判定。 */
internal object LinuxEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"
    const val COMMON_TOOLS_MARKER = ".eta-common-tools-ready"
    const val APK_ANALYSIS_MARKER = ".eta-apk-analysis-ready"
    const val PYTHON_TOOLS_MARKER = ".eta-python-tools-ready"
    const val NODE_TOOLS_MARKER = ".eta-node-tools-ready"
    const val SSH_TOOLS_MARKER = ".eta-ssh-tools-ready"
    const val GIT_TOOLS_MARKER = ".eta-git-tools-ready"
    const val CLI_TOOLS_MARKER = ".eta-cli-tools-ready"
    const val BUILD_TOOLS_MARKER = ".eta-build-tools-ready"
    const val SECURITY_TOOLS_MARKER = ".eta-security-tools-ready"
    const val CTF_TOOLS_MARKER = ".eta-ctf-tools-ready"
    const val TOOLSET_REVISION = 1
    const val APK_ANALYSIS_REVISION = 1
    const val PYTHON_TOOLS_REVISION = 1
    // revision 2：Debian 规格补装 libatomic1，已就绪环境需重走安装补齐依赖。
    const val NODE_TOOLS_REVISION = 2
    const val SSH_TOOLS_REVISION = 1
    const val GIT_TOOLS_REVISION = 1
    const val CLI_TOOLS_REVISION = 1
    const val BUILD_TOOLS_REVISION = 1
    const val SECURITY_TOOLS_REVISION = 1
    // revision 2：venv 改用 CPython 3.13，避免 unicorn 只能源码编译导致安装超时。
    const val CTF_TOOLS_REVISION = 2

    fun environmentDir(context: Context, distribution: LinuxDistribution): File =
        environmentDir(context, distribution, LinuxEnvironmentSettingsRepository.backend(context, distribution))

    fun environmentDir(context: Context, distribution: LinuxDistribution, backend: LinuxExecutionBackend): File =
        if (backend == LinuxExecutionBackend.CHROOT) {
            File(context.filesDir, "terminal/${distribution.wireName}")
        } else {
            TerminalPrivateStorage.prootEnvironment(context.filesDir, distribution)
        }

    fun rootfsDir(context: Context, distribution: LinuxDistribution): File =
        File(environmentDir(context, distribution), "rootfs")

    fun rootfsDir(context: Context, distribution: LinuxDistribution, backend: LinuxExecutionBackend): File =
        File(environmentDir(context, distribution, backend), "rootfs")

    fun legacyRootfsDir(context: Context, distribution: LinuxDistribution): File =
        rootfsDir(context, distribution, LinuxExecutionBackend.CHROOT)

    fun backendOf(rootfsPath: String?): LinuxExecutionBackend =
        if (TerminalPrivateStorage.isProotPath(rootfsPath)) LinuxExecutionBackend.PROOT else LinuxExecutionBackend.CHROOT

    fun rootfsReady(rootfsPath: String?): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        return File(rootfsPath, READY_MARKER).isFile
    }

    fun artifactDir(context: Context): File =
        File(context.cacheDir, "linux-installer/artifacts")

    fun profileStagingDir(context: Context, profile: String): File =
        File(context.cacheDir, "linux-installer/profiles/$profile.installing")

    fun commonToolsReady(rootfsPath: String?): Boolean {
        if (!rootfsReady(rootfsPath)) return false
        val marker = File(rootfsPath, COMMON_TOOLS_MARKER)
        if (!marker.isFile) return false
        return runCatching {
            marker.useLines { lines ->
                lines.any { line -> line.trim() == "toolset=$TOOLSET_REVISION" }
            }
        }.getOrDefault(false)
    }
}
