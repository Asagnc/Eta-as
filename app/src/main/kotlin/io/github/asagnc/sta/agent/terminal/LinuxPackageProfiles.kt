package io.github.asagnc.sta.agent.terminal

import android.content.Context
import io.github.asagnc.sta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class PackageProfileInstallStage {
    CHECKING,
    DOWNLOADING,
    INSTALLING,
    COMPLETE,
}

internal data class PackageProfileInstallProgress(
    val stage: PackageProfileInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface PackageProfileInstallResult {
    data object AlreadyReady : PackageProfileInstallResult
    data object EnvironmentNotReady : PackageProfileInstallResult

    /** 依赖的 profile 尚未安装，按依赖链先装它。 */
    data class DependencyMissing(val profileId: String) : PackageProfileInstallResult
    data object Installed : PackageProfileInstallResult
    data class Failed(val stage: PackageProfileInstallStage) : PackageProfileInstallResult
}

internal data class LinuxPackageSpec(
    val packages: List<String> = emptyList(),
    val managedTool: ManagedLinuxTool? = null,
    val setupScript: String? = null,
)

internal data class LinuxPackageProfile(
    val id: String,
    val markerName: String,
    val revision: Int,
    val specs: Map<LinuxDistribution, LinuxPackageSpec>,
    /** 安装前必须就绪的前置 profile。 */
    val dependsOn: LinuxPackageProfile? = null,
) {
    /** apt 系发行版的包名一致，未单独列出的发行版复用 Debian 规格。 */
    fun spec(distribution: LinuxDistribution): LinuxPackageSpec =
        specs[distribution] ?: requireNotNull(specs[LinuxDistribution.DEBIAN])
}

internal object LinuxPackageProfiles {
    val PYTHON = LinuxPackageProfile(
        id = "python",
        markerName = LinuxEnvironmentPaths.PYTHON_TOOLS_MARKER,
        revision = LinuxEnvironmentPaths.PYTHON_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
        ),
    )
    val NODE = LinuxPackageProfile(
        id = "node",
        markerName = LinuxEnvironmentPaths.NODE_TOOLS_MARKER,
        revision = LinuxEnvironmentPaths.NODE_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                // Node 官方 arm64 二进制链接 libatomic.so.1，归档安装不含系统依赖，需补装。
                packages = listOf("libatomic1"),
                managedTool = ManagedLinuxTool.NODE,
            ),
        ),
    )
    val SSH = LinuxPackageProfile(
        id = "ssh",
        markerName = LinuxEnvironmentPaths.SSH_TOOLS_MARKER,
        revision = LinuxEnvironmentPaths.SSH_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("openssh-client", "openssh-server"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
        ),
    )

    /** 版本控制与证书；拉取、提交代码时使用。 */
    val GIT = LinuxPackageProfile(
        id = "git",
        markerName = LinuxEnvironmentPaths.GIT_TOOLS_MARKER,
        revision = LinuxEnvironmentPaths.GIT_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("git", "ca-certificates"),
            ),
        ),
    )
    /** 搜索与文本处理命令：ripgrep、fd、fzf、jq。 */
    val CLI_TOOLS = LinuxPackageProfile(
        id = "cli-tools",
        markerName = LinuxEnvironmentPaths.CLI_TOOLS_MARKER,
        revision = LinuxEnvironmentPaths.CLI_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("ripgrep", "fd-find", "fzf", "jq"),
            ),
        ),
    )
    /** 本地编译源码所需的最小工具链。 */
    val BUILD_TOOLS = LinuxPackageProfile(
        id = "build-tools",
        markerName = LinuxEnvironmentPaths.BUILD_TOOLS_MARKER,
        revision = LinuxEnvironmentPaths.BUILD_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("build-essential", "cmake", "pkg-config"),
            ),
        ),
    )
    /** 网络排查与制品分析常用命令：抓包、连接测试、规则扫描、固件提取。 */
    val SECURITY_TOOLS = LinuxPackageProfile(
        id = "security-tools",
        markerName = LinuxEnvironmentPaths.SECURITY_TOOLS_MARKER,
        revision = LinuxEnvironmentPaths.SECURITY_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("nmap", "sqlmap", "tcpdump", "netcat-openbsd", "yara", "binwalk"),
            ),
        ),
    )
    /**
     * CTF 与二进制分析常用的 Python 工具。装进独立 venv，避免与 Python profile 的
     * 默认环境互相污染；只把命令行入口链接到 /usr/local/bin，不整目录链接，
     * 否则会覆盖同名的 python 等命令。
     *
     * venv 固定用 CPython 3.13：pwntools 的依赖声明排除 unicorn 2.1.3 与 2.1.4，
     * 解析只能落到 unicorn 2.1.2，而该版本只发布到 cp313 的预编译 wheel。换用更新的
     * 解释器时 uv 会转为源码编译 unicorn，在设备上耗时超过安装时限，整个 profile 装不上。
     */
    val CTF_TOOLS = LinuxPackageProfile(
        id = "ctf-tools",
        markerName = LinuxEnvironmentPaths.CTF_TOOLS_MARKER,
        revision = LinuxEnvironmentPaths.CTF_TOOLS_REVISION,
        dependsOn = PYTHON,
        specs = mapOf(
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python uv python install 3.13
                    # 重建 venv：旧目录可能仍绑定着别的解释器版本
                    rm -rf /opt/eta/ctf
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python uv venv --python 3.13 /opt/eta/ctf
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python uv pip install --python /opt/eta/ctf/bin/python --upgrade pwntools z3-solver capstone ROPgadget pycryptodome
                    for tool in pwn checksec cyclic asm disasm ROPgadget; do
                        if [ -x "/opt/eta/ctf/bin/${'$'}tool" ]; then ln -sf "/opt/eta/ctf/bin/${'$'}tool" "/usr/local/bin/${'$'}tool"; fi
                    done
                """.trimIndent(),
            ),
        ),
    )
    val ALL = listOf(PYTHON, NODE, SSH, GIT, CLI_TOOLS, BUILD_TOOLS, SECURITY_TOOLS, CTF_TOOLS)
}

internal fun linuxPackageProfileReady(rootfs: File, profile: LinuxPackageProfile): Boolean {
    val marker = File(rootfs, profile.markerName)
    if (!marker.isFile) return false
    return marker.useLines { lines ->
        lines.any { line -> line.trim() == "profile=${profile.revision}" }
    }
}

/** 为当前选中的发行版按需安装单个工具 profile；成功后只写对应完成标记。 */
internal class LinuxPackageProfileInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val profile: LinuxPackageProfile,
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
    private val managedToolInstaller = PinnedLinuxToolInstaller(context)

    fun isReady(): Boolean = linuxPackageProfileReady(rootfs, profile)

    suspend fun install(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit = {},
    ): PackageProfileInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PackageProfileInstallResult.AlreadyReady
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, LinuxEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext PackageProfileInstallResult.EnvironmentNotReady
        }
        profile.dependsOn?.let { dependency ->
            if (!linuxPackageProfileReady(rootfs, dependency)) {
                return@withContext PackageProfileInstallResult.DependencyMissing(dependency.id)
            }
        }

        val spec = profile.spec(distribution)
        spec.managedTool?.let { tool ->
            val installed = managedToolInstaller.install(
                tool = tool,
                distribution = distribution,
                rootfs = rootfs,
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    PackageProfileInstallProgress(
                        stage = PackageProfileInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!installed) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.DOWNLOADING,
                )
            }
        }

        val packageHelper = "/usr/local/bin/eta-apt"
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.INSTALLING))
        if (spec.packages.isNotEmpty()) {
            val installResult = InstallerShellRunner.run(
                command = "$packageHelper install ${spec.packages.joinToString(" ")}",
                timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
            if (installResult.exitCode != 0) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.INSTALLING,
                )
            }
        }

        val activateCommand = buildString {
            append("set -e\n")
            spec.setupScript?.let { script -> append(script).append('\n') }
            append("cat > /").append(profile.markerName).append(" <<'ETA_PROFILE_EOF'\n")
            append("profile=").append(profile.revision).append('\n')
            append("ETA_PROFILE_EOF\n")
            append("chmod 0644 /").append(profile.markerName).append(" || exit 71")
        }
        val result = InstallerShellRunner.run(
            command = activateCommand,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Package profile action=activate distribution=${distribution.wireName} profile=${profile.id} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        if (result.exitCode != 0) {
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.INSTALLING)
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.COMPLETE))
        PackageProfileInstallResult.Installed
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L
        private val installMutex = Mutex()
    }
}
