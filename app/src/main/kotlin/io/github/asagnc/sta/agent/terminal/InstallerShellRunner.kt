package io.github.asagnc.sta.agent.terminal

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal data class InstallerCommandResult(
    val exitCode: Int,
    val output: String,
)

/** 按指定身份与运行环境执行一次安装器命令，并收集有界输出。 */
internal object InstallerShellRunner {
    private const val MAX_OUTPUT_BYTES = 64 * 1024

    suspend fun run(
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
        linuxRootfsPath: String? = null,
        identity: String? = null,
    ): InstallerCommandResult = runInterruptible(Dispatchers.IO) {
        val supervisor = ShellProcessSupervisor()
        val process = supervisor.startShellProcess(
            identity = identity ?: TerminalRuntime.defaultIdentity(environment, linuxRootfsPath),
            command = command,
            mergeStderr = true,
            environment = environment,
            linuxRootfsPath = linuxRootfsPath,
        ) ?: return@runInterruptible InstallerCommandResult(exitCode = -1, output = "")
        val output = ByteArrayOutputStream()
        val reader = thread(name = "sta-installer-output", isDaemon = true) {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = (MAX_OUTPUT_BYTES - output.size()).coerceAtLeast(0)
                            if (remaining > 0) output.write(buffer, 0, count.coerceAtMost(remaining))
                        }
                    }
                }
            }
        }
        runCatching { process.outputStream.close() }
        try {
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                supervisor.terminateProcessTree(process)
                reader.join(1_000)
                InstallerCommandResult(exitCode = -2, output = output.text())
            } else {
                reader.join(1_000)
                InstallerCommandResult(exitCode = process.exitValue(), output = output.text())
            }
        } finally {
            if (process.isAlive) {
                supervisor.terminateAndReap(process)
            } else {
                supervisor.reapProcess(process)
            }
            supervisor.unregisterProcess(process)
        }
    }

    private fun ByteArrayOutputStream.text(): String =
        synchronized(this) { toByteArray().decodeToString().trimEnd() }
}
