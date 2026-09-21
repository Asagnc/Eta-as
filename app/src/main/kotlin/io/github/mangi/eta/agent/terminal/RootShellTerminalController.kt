package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal class RootShellTerminalController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val detachedSupervisor: DetachedTaskSupervisor? = null,
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val selectedLinuxEnvironmentProvider: () -> TerminalEnvironment = {
        TerminalEnvironment.DEBIAN
    },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
) : AutoCloseable {
    private companion object {
        const val DEFAULT_CWD = "/data/local/tmp/eta"

        /** Linux 工具环境的工作目录；它与 DEFAULT_CWD 指向同一份目录（chroot 里 bind 过去）。 */
        const val LINUX_DEFAULT_CWD = "/workspace"

        /** 随包分发的 ripgrep：jniLibs/arm64-v8a/librg.so，安装时解到 nativeLibraryDir。 */
        const val RIPGREP_LIBRARY_NAME = "librg.so"
        const val USER_STORAGE = "/storage/emulated/0"
        const val DEFAULT_TIMEOUT_SECONDS = 30
        // 编译、下载这类命令经常超过三分钟；更久的后台服务应交给 daemon 任务，那条路径不受这里约束。
        const val MAX_TIMEOUT_SECONDS = 600
        const val MAX_COMMAND_CHARS = 4_000
        const val MAX_OUTPUT_CHARS = FileToolLimits.MAX_OUTPUT_CHARS
        const val MAX_READ_BYTES = FileToolLimits.MAX_READ_BYTES
        const val MAX_WRITE_BYTES = FileToolLimits.MAX_WRITE_BYTES
        const val MAX_LIST_ENTRIES = 200
        const val MAX_ASYNC_OUTPUT_CHARS = 64_000

        /** 写文件的同目录临时文件后缀：写完立即被 rename 顶替，失败则删掉。 */
        const val WRITE_TEMP_SUFFIX = FileToolLimits.WRITE_TEMP_SUFFIX

        /** 错误文案上限，见 [FileToolLimits.MAX_ERROR_CHARS]。 */
        const val MAX_ERROR_CHARS = FileToolLimits.MAX_ERROR_CHARS
    }

    private val sessions = linkedMapOf<String, TerminalSession>()
    private val asyncJobs = linkedMapOf<String, AsyncCommand>()
    private val cleanupStarted = AtomicBoolean(false)

    /** grep 是否支持 GNU 的 --include；null 表示尚未探测。 */
    private var grepIncludeSupport: Boolean? = null

    /** 设备上静态 ripgrep 的绝对路径；空串表示已探测过但不存在。 */
    private var ripgrepPathCache: String? = null

    /**
     * 在指定环境里跑一条命令，返回原始退出码与输出。
     *
     * 与 [runCommand] 的区别：不走工具层（不做 exit 前缀与错误码包装），
     * 供内部调用方按自己的方式处理失败——例如 worktree 创建要拿退出码判断成败。
     */
    fun execRaw(
        command: String,
        cwd: String?,
        environment: TerminalEnvironment,
        timeoutSeconds: Int,
    ): RawCommandResult = runCommandRaw(
        command = command,
        cwd = cwd,
        timeoutSeconds = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS),
        identity = defaultIdentity(environment),
        environment = environment,
        mergeStderr = false,
    )

    fun runCommand(command: String, cwd: String?, timeoutSeconds: Int): String {
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = defaultIdentity(TerminalEnvironment.ANDROID),
            environment = TerminalEnvironment.ANDROID,
            mergeStderr = false,
            toolName = "run_command"
        )
    }

    fun terminalOpenAndExec(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        environment: String = TerminalEnvironment.ANDROID.wireName,
    ): String {
        val timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = identity.ifBlank { defaultIdentity(normalizeEnvironment(environment)) },
            environment = normalizeEnvironment(environment),
            mergeStderr = mergeStderr,
            toolName = "terminal"
        )
    }

    fun terminalAction(
        action: String,
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        sessionId: String?,
        jobId: String?,
        async: Boolean,
        offsetChars: Int,
        maxChars: Int,
        closeIfDone: Boolean,
        environment: String = TerminalEnvironment.ANDROID.wireName,
        taskId: String? = null,
    ): String {
        return when (action.lowercase()) {
            "open" -> openSession(identity = identity, cwd = cwd, environment = environment)
            "exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                environment = environment,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "open_and_exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                environment = environment,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "read_async_result" -> readAsyncResult(
                jobId = jobId.orEmpty(),
                offsetChars = offsetChars,
                maxChars = maxChars,
                closeIfDone = closeIfDone
            )
            "close" -> closeTerminal(sessionId = sessionId, jobId = jobId)
            "daemon_start" -> daemonStart(
                command = command,
                cwd = cwd,
                identity = identity,
                environment = environment,
            )
            "daemon_list" -> daemonList()
            "daemon_logs" -> daemonLogs(taskId = taskId.orEmpty())
            "daemon_stop" -> daemonStop(taskId = taskId.orEmpty())
            "tasks_list" -> taskList()
            else -> errorJson(
                "UNSUPPORTED_TERMINAL_ACTION",
                "terminal action 仅支持 open/exec/open_and_exec/read_async_result/close/daemon_start/daemon_list/daemon_logs/daemon_stop"
            )
        }
    }

    private fun openSession(identity: String, cwd: String?, environment: String): String {
        val normalizedEnvironment = normalizeEnvironment(environment)
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { defaultIdentity(normalizedEnvironment) })
        val sessionRootfs = rootfsPathFor(normalizedEnvironment)
        environmentPreflight(normalizedIdentity, normalizedEnvironment, sessionRootfs)?.let { return it }
        val safeCwd = normalizeCwd(cwd, normalizedEnvironment, normalizedIdentity)
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val process = startSessionProcess(normalizedIdentity, normalizedEnvironment, sessionRootfs)
            ?: return errorJson(
                "PROCESS_START_FAILED",
                "无法启动 ${normalizedEnvironment.wireName}/$normalizedIdentity terminal session",
            )
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val session = TerminalSession(
            id = id,
            identity = normalizedIdentity,
            environment = normalizedEnvironment,
            rootfsPath = sessionRootfs,
            cwd = safeCwd,
            createdAt = System.currentTimeMillis(),
            process = process,
            stdout = stdout,
            stderr = stderr
        )
        session.stdoutThread = thread(name = "agent-terminal-session-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input) }
        }
        session.stderrThread = thread(name = "agent-terminal-session-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input) }
        }
        session.waiterThread = thread(name = "agent-terminal-session-waiter-$id", isDaemon = true) {
            runCatching { process.waitFor() }
            processSupervisor.retireExitedProcess(process)
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(sessions) { sessions[id] = session }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }

        val mkdirDefault = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val setup = "${mkdirDefault}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1"
        val setupResult = runSessionCommand(session, setup, timeoutMs = 5_000)
        if (setupResult.exitCode != 0 || setupResult.timedOut) {
            closeSession(id)
            return errorJson("SESSION_OPEN_FAILED", setupResult.stderr.ifBlank { "exit=${setupResult.exitCode}" })
        }
        session.cwd = setupResult.cwd ?: safeCwd
        session.stdout.clear()
        session.stderr.clear()
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open")
            .put("session_id", id)
            .put("identity", normalizedIdentity)
            .put("environment", normalizedEnvironment.wireName)
            .put("cwd", session.cwd)
            .toString()
    }

    private fun execInTerminal(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: String,
        mergeStderr: Boolean,
        sessionId: String?,
        async: Boolean
    ): String {
        val resolvedSessionId = resolveAlias(sessionId, "session", orderedSessionIds())
        val session = resolvedSessionId?.takeIf { it.isNotBlank() }?.let { id ->
            synchronized(sessions) { sessions[id] }
                ?: return errorJson("SESSION_NOT_FOUND", "未找到 terminal session：$id")
        }
        val effectiveEnvironment = session?.environment ?: normalizeEnvironment(environment)
        val effectiveIdentity = session?.identity ?: normalizeIdentity(identity.ifBlank { defaultIdentity(effectiveEnvironment) })
        environmentPreflight(effectiveIdentity, effectiveEnvironment, session?.rootfsPath ?: rootfsPathFor(effectiveEnvironment))?.let { return it }
        val effectiveCwd = cwd?.takeIf { it.isNotBlank() } ?: session?.cwd
        if (async) {
            if (session != null) {
                return errorJson(
                    "ASYNC_SESSION_UNSUPPORTED",
                    "async terminal job 不复用持久 session；请省略 session_id，并用 cwd/identity 启动后台命令"
                )
            }
            return startAsyncCommand(
                command = command,
                cwd = effectiveCwd,
                timeoutMs = timeoutMs,
                identity = effectiveIdentity,
                environment = effectiveEnvironment,
                mergeStderr = mergeStderr,
                sessionId = session?.id
            )
        }
        if (session != null) {
            return execInSession(
                session = session,
                command = command,
                timeoutMs = timeoutMs,
                mergeStderr = mergeStderr
            )
        }
        val result = runCommand(
            command = command,
            cwd = effectiveCwd,
            timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
                .coerceIn(1, MAX_TIMEOUT_SECONDS),
            identity = effectiveIdentity,
            environment = effectiveEnvironment,
            mergeStderr = mergeStderr,
            toolName = "terminal"
        )
        return result
    }

    private fun startAsyncCommand(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
        sessionId: String?
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, environment, normalizedIdentity)
        val setup = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val process = processSupervisor.startShellProcess(
            identity = normalizedIdentity,
            command = fullCommand,
            mergeStderr = mergeStderr,
            environment = environment,
            linuxRootfsPath = rootfsPathFor(environment),
            linuxSharedMounts = sharedMountsFor(environment),
        ) ?: return errorJson(
            if (processSupervisor.isClosing) "TERMINAL_CLOSED" else "PROCESS_START_FAILED",
            if (processSupervisor.isClosing) "terminal controller 已关闭" else "无法启动 terminal process",
        )
        val id = "job_" + UUID.randomUUID().toString().take(8)
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val job = AsyncCommand(
            id = id,
            process = process,
            stdout = stdout,
            stderr = stderr,
            command = trimmed,
            cwd = safeCwd,
            identity = normalizedIdentity,
            environment = environment,
            mergeStderr = mergeStderr,
            sessionId = sessionId,
            startedAt = System.currentTimeMillis(),
            timeoutMs = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        )
        job.stdoutThread = thread(name = "agent-terminal-async-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input, MAX_ASYNC_OUTPUT_CHARS) }
        }
        job.stderrThread = thread(name = "agent-terminal-async-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input, MAX_ASYNC_OUTPUT_CHARS) }
        }
        job.waiterThread = thread(name = "agent-terminal-async-waiter-$id", isDaemon = true) {
            try {
                val finished = process.waitFor(job.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                if (!finished) {
                    job.timedOut = true
                    processSupervisor.terminateProcessTree(process)
                }
                job.exitCode = runCatching { process.exitValue() }.getOrDefault(-2)
                job.completedAt = System.currentTimeMillis()
            } finally {
                processSupervisor.retireExitedProcess(process)
            }
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(asyncJobs) { asyncJobs[id] = job }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }
        logger.info(
            "Agent terminal action=open_and_exec outcome=started async=true " +
                "identity=$normalizedIdentity environment=${environment.wireName} " +
                "timeoutMs=${job.timeoutMs} commandChars=${trimmed.length}"
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open_and_exec")
            .put("async", true)
            .put("job_id", id)
            .put("session_id", sessionId ?: JSONObject.NULL)
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("running", true)
            .toString()
    }

    private fun readAsyncResult(
        jobId: String,
        offsetChars: Int,
        maxChars: Int,
        closeIfDone: Boolean
    ): String {
        val resolvedJobId = resolveAlias(jobId, "job", orderedJobIds()) ?: jobId
        val job = synchronized(asyncJobs) { asyncJobs[resolvedJobId] }
            ?: return errorJson("JOB_NOT_FOUND", "未找到 async terminal job：$jobId")
        if (job.identity == "root" && !rootAvailable()) return errorJson("ROOT_REQUIRED", "Root 授权不可用")
        val stdoutRaw = job.stdout.text()
        val stderrRaw = job.stderr.text()
        val merged = stdoutRaw
        val offset = offsetChars.coerceAtLeast(0).coerceAtMost(merged.length)
        val limit = maxChars.coerceIn(1, MAX_OUTPUT_CHARS)
        val slice = merged.substring(offset, (offset + limit).coerceAtMost(merged.length))
        val done = job.exitCode != null
        if (done && closeIfDone) {
            synchronized(asyncJobs) { asyncJobs.remove(jobId) }?.let(::closeJob)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "read_async_result")
            .put("job_id", job.id)
            .put("session_id", job.sessionId ?: JSONObject.NULL)
            .put("environment", job.environment.wireName)
            .put("running", !done)
            .put("exit_code", job.exitCode ?: JSONObject.NULL)
            .put("timed_out", job.timedOut)
            .put("stdout", slice)
            .put("next_offset_chars", offset + slice.length)
            .put("total_chars", merged.length)
            .put("retained_chars", merged.length)
            .put("stdout_total_bytes", job.stdout.totalBytesRead())
            .put("stderr_total_bytes", job.stderr.totalBytesRead())
            .put("truncated", offset + slice.length < merged.length)
            .put("output_truncated", job.stdout.isTruncated() || job.stderr.isTruncated())
            .put("stderr", if (job.mergeStderr) "" else stderrRaw.truncateForJson())
            .put("stdout_truncated", job.stdout.isTruncated())
            .put("stderr_truncated", !job.mergeStderr && job.stderr.isTruncated())
            .toString()
    }

    private fun daemonStart(
        command: String,
        cwd: String?,
        identity: String,
        environment: String,
    ): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedEnvironment = normalizeEnvironment(environment)
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { defaultIdentity(normalizedEnvironment) })
        environmentPreflight(normalizedIdentity, normalizedEnvironment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, normalizedEnvironment, normalizedIdentity)
        return when (val result = supervisor.start(trimmed, safeCwd, normalizedIdentity, normalizedEnvironment)) {
            is DaemonStartResult.Started -> JSONObject()
                .put("ok", true)
                .put("tool", "terminal")
                .put("action", "daemon_start")
                .put("task_id", result.task.id)
                .put("pid", result.task.pid)
                .put("identity", result.task.identity)
                .put("environment", result.task.environment.wireName)
                .put("cwd", result.task.cwd)
                .toString()
            is DaemonStartResult.Failed -> errorJson(result.code, result.message)
        }
    }

    /** 一次列出会话、异步任务与守护任务，省去按 id 逐个查询的往返。 */
    /** 任务列表里给出的短别名（session-1 / job-2 / daemon-3）解析回真实 id，模型就不用抄长 id 了。 */
    private fun resolveAlias(id: String?, prefix: String, ordered: List<String>): String? {
        val value = id?.takeIf { it.isNotBlank() } ?: return null
        if (!value.startsWith("$prefix-")) return value
        val index = value.removePrefix("$prefix-").toIntOrNull() ?: return value
        return ordered.getOrNull(index - 1) ?: value
    }

    private fun orderedSessionIds(): List<String> =
        synchronized(sessions) { sessions.values.sortedBy { it.createdAt }.map { it.id } }

    private fun orderedJobIds(): List<String> =
        synchronized(asyncJobs) { asyncJobs.values.sortedBy { it.startedAt }.map { it.id } }

    private fun orderedDaemonIds(): List<String> {
        val statuses = runCatching { detachedSupervisor?.list() }.getOrNull() ?: return emptyList()
        return statuses.sortedBy { it.task.startedAt }.map { it.task.id }
    }

    private fun taskList(): String {
        val sessionItems = JSONArray()
        val jobItems = JSONArray()
        synchronized(sessions) {
            sessions.values.sortedBy { it.createdAt }.forEachIndexed { index, session ->
                sessionItems.put(
                    JSONObject()
                        .put("session_id", session.id)
                        .put("alias", "session-${index + 1}")
                        .put("identity", session.identity)
                        .put("environment", session.environment.wireName)
                        .put("cwd", session.cwd)
                        .put("started_at", session.createdAt)
                )
            }
        }
        synchronized(asyncJobs) {
            asyncJobs.values.sortedBy { it.startedAt }.forEachIndexed { index, job ->
                jobItems.put(
                    JSONObject()
                        .put("job_id", job.id)
                        .put("alias", "job-${index + 1}")
                        .put("session_id", job.sessionId ?: JSONObject.NULL)
                        .put("environment", job.environment.wireName)
                        .put("running", job.exitCode == null)
                        .put("exit_code", job.exitCode ?: JSONObject.NULL)
                        .put("timed_out", job.timedOut)
                        .put("stdout_chars", job.stdout.text().length)
                        .put("stdout_tail", job.stdout.text().takeLast(200))
                        .put("started_at", job.startedAt)
                )
            }
        }
        val daemonItems = JSONArray()
        detachedSupervisor?.list()?.sortedBy { it.task.startedAt }?.forEachIndexed { index, status ->
            daemonItems.put(
                JSONObject()
                    .put("task_id", status.task.id)
                    .put("alias", "daemon-${index + 1}")
                    .put("running", status.running)
                    .put("command", status.task.command)
                    .put("environment", status.task.environment.wireName)
                    .put("started_at", status.task.startedAt)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "tasks_list")
            .put("session_count", sessionItems.length())
            .put("async_count", jobItems.length())
            .put("daemon_count", daemonItems.length())
            .put("sessions", sessionItems)
            .put("async_jobs", jobItems)
            .put("daemons", daemonItems)
            .toString()
    }

    private fun daemonList(): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        val statuses = supervisor.list()
        val tasks = JSONArray()
        statuses.forEach { status ->
            tasks.put(
                JSONObject()
                    .put("task_id", status.task.id)
                    .put("pid", status.task.pid)
                    .put("running", status.running)
                    .put("command", status.task.command)
                    .put("cwd", status.task.cwd)
                    .put("identity", status.task.identity)
                    .put("environment", status.task.environment.wireName)
                    .put("started_at", status.task.startedAt)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_list")
            .put("task_count", statuses.size)
            .put("running_count", statuses.count { it.running })
            .put("tasks", tasks)
            .toString()
    }

    private fun daemonLogs(taskId: String): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        if (taskId.isBlank()) return errorJson("INVALID_ARGUMENT", "task_id 不能为空")
        val resolvedTaskId = resolveAlias(taskId, "daemon", orderedDaemonIds()) ?: taskId
        val result = supervisor.readLogs(resolvedTaskId)
        if (!result.ok) {
            return errorJson(result.code.ifBlank { "LOGS_UNAVAILABLE" }, result.message)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_logs")
            .put("task_id", taskId)
            .put("log", result.text.truncateForJson())
            .put("log_truncated", result.truncated || result.text.length > MAX_OUTPUT_CHARS)
            .toString()
    }

    private fun daemonStop(taskId: String): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        if (taskId.isBlank()) return errorJson("INVALID_ARGUMENT", "task_id 不能为空")
        val resolvedTaskId = resolveAlias(taskId, "daemon", orderedDaemonIds()) ?: taskId
        val task = supervisor.findTask(resolvedTaskId) ?: return errorJson("TASK_NOT_FOUND", "未找到守护任务：$taskId")
        if (task.identity == "root" && !rootAvailable()) return errorJson("ROOT_REQUIRED", "Root 授权不可用")
        if (!supervisor.stop(resolvedTaskId)) {
            return errorJson("DAEMON_STOP_FAILED", "守护任务停止失败，请重试")
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_stop")
            .put("task_id", taskId)
            .toString()
    }

    private fun closeTerminal(sessionId: String?, jobId: String?): String {
        var closedSession = false
        var closedJob = false
        resolveAlias(sessionId, "session", orderedSessionIds())?.takeIf { it.isNotBlank() }?.let { id ->
            closedSession = closeSession(id)
        }
        resolveAlias(jobId, "job", orderedJobIds())?.takeIf { it.isNotBlank() }?.let { id ->
            closedJob = closeJob(id)
        }
        return JSONObject()
            .put("ok", closedSession || closedJob)
            .put("tool", "terminal")
            .put("action", "close")
            .put("closed_session", closedSession)
            .put("closed_job", closedJob)
            .toString()
    }

    override fun close() {
        closeAll()
    }

    /** 取消热路径只封闭新进程接纳；进程树终止和 reader/waiter 回收在后台完成。 */
    fun interruptAll() {
        beginClosing()
        if (cleanupStarted.compareAndSet(false, true)) {
            thread(name = "agent-terminal-cleanup", isDaemon = true) {
                closeAllInternal()
            }
        }
    }

    fun closeAll() {
        beginClosing()
        cleanupStarted.set(true)
        closeAllInternal()
    }

    private fun beginClosing() {
        processSupervisor.beginClosing()
        synchronized(sessions) {
            sessions.values.forEach { session -> session.closed = true }
        }
    }

    private fun closeAllInternal() {
        val sessionIds = synchronized(sessions) { sessions.keys.toList() }
        sessionIds.forEach(::closeSession)

        val jobs = synchronized(asyncJobs) {
            asyncJobs.values.toList().also { asyncJobs.clear() }
        }
        jobs.forEach(::closeJob)

        val remainingProcesses = processSupervisor.takeRemainingProcesses()
        remainingProcesses.forEach { process ->
            processSupervisor.terminateAndReap(process)
            processSupervisor.unregisterProcess(process)
        }
    }

    private fun closeSession(id: String): Boolean {
        val session = synchronized(sessions) { sessions.remove(id) } ?: return false
        session.closed = true
        runCatching { session.process.outputStream.close() }
        processSupervisor.terminateAndReap(session.process)
        runCatching { session.stdoutThread.join(500) }
        runCatching { session.stderrThread.join(500) }
        runCatching { session.waiterThread.join(500) }
        processSupervisor.unregisterProcess(session.process)
        return true
    }

    private fun closeJob(id: String): Boolean {
        val job = synchronized(asyncJobs) { asyncJobs.remove(id) } ?: return false
        closeJob(job)
        return true
    }

    private fun closeJob(job: AsyncCommand) {
        processSupervisor.terminateAndReap(job.process)
        runCatching { job.stdoutThread.join(500) }
        runCatching { job.stderrThread.join(500) }
        runCatching { job.waiterThread.join(500) }
        processSupervisor.unregisterProcess(job.process)
    }

    private fun execInSession(
        session: TerminalSession,
        command: String,
        timeoutMs: Int,
        mergeStderr: Boolean
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val timeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        val result = runSessionCommand(session, trimmed, timeout)
        val outcome = when {
            result.timedOut -> "timed_out"
            result.exitCode == 0 -> "succeeded"
            else -> "failed"
        }
        val logMessage =
            "Agent terminal action=exec outcome=$outcome session=true " +
                "identity=${session.identity} environment=${session.environment.wireName} " +
                "timeoutMs=$timeout commandChars=${trimmed.length} " +
                "exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        if (result.cwd != null) session.cwd = result.cwd
        if (result.timedOut) {
            closeSession(session.id)
        }
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            result.stdout + "\n[stderr]\n" + result.stderr
        } else {
            result.stdout
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", "terminal")
            .put("action", "exec")
            .put("session_id", session.id)
            .put("identity", session.identity)
            .put("environment", session.environment.wireName)
            .put("cwd", session.cwd)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", rawStdout.length > stdout.length)
            .put("stderr_truncated", !mergeStderr && result.stderr.length > stderr.length)
            .put("session_closed", result.timedOut || session.closed)
            .toString()
    }

    private fun runSessionCommand(
        session: TerminalSession,
        command: String,
        timeoutMs: Int
    ): SessionCommandResult {
        synchronized(session.lock) {
            if (session.closed || !session.process.isAlive) {
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "terminal session 已关闭",
                    cwd = session.cwd,
                    timedOut = false
                )
            }
            val marker = SessionStatusProtocol.newMarker()
            val stdoutStart = session.stdout.text().length
            val stderrStart = session.stderr.text().length
            val commandBlock = buildString {
                append(command)
                append('\n')
                append(SessionStatusProtocol.statusCommand(marker))
                append('\n')
            }
            runCatching {
                session.process.outputStream.write(commandBlock.toByteArray(Charsets.UTF_8))
                session.process.outputStream.flush()
            }.getOrElse {
                session.closed = true
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                    stderr = it.message ?: it.javaClass.simpleName,
                    cwd = session.cwd,
                    timedOut = false
                )
            }

            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
            while (System.currentTimeMillis() < deadline) {
                val stdoutDelta = session.stdout.text().drop(stdoutStart)
                if (session.closed || !session.process.isAlive) {
                    return SessionCommandResult(
                        exitCode = -1,
                        stdout = stdoutDelta.trimEnd(),
                        stderr = session.stderr.text().drop(stderrStart).ifBlank { "terminal session 已关闭" }.trimEnd(),
                        cwd = session.cwd,
                        timedOut = false
                    )
                }
                val status = stdoutDelta.lineSequence()
                    .firstOrNull { SessionStatusProtocol.isStatusLine(it, marker) }
                    ?.let { SessionStatusProtocol.parseStatusLine(it, marker) }
                if (status != null) {
                    val exitCode = status.exitCode
                    val cwd = status.cwd ?: session.cwd
                    val cleanedStdout = stdoutDelta
                        .lineSequence()
                        .filterNot { SessionStatusProtocol.isStatusLine(it, marker) }
                        .joinToString("\n")
                        .trimEnd()
                    val stderrDelta = session.stderr.text().drop(stderrStart).trimEnd()
                    session.stdout.clear()
                    session.stderr.clear()
                    return SessionCommandResult(
                        exitCode = exitCode,
                        stdout = cleanedStdout,
                        stderr = stderrDelta,
                        cwd = cwd,
                        timedOut = false
                    )
                }
                Thread.sleep(50)
            }

            session.closed = true
            processSupervisor.terminateProcessTree(session.process)
            return SessionCommandResult(
                exitCode = -2,
                stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                stderr = session.stderr.text().drop(stderrStart).ifBlank { "命令执行超时" }.trimEnd(),
                cwd = session.cwd,
                timedOut = true
            )
        }
    }

    private fun runCommand(
        command: String,
        cwd: String?,
        timeoutSeconds: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
        toolName: String
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, environment, normalizedIdentity)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val setup = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val result = runText(
            identity = normalizedIdentity,
            command = fullCommand,
            timeoutSeconds = timeout.toLong(),
            environment = environment,
        )
        val outcome = when (result.exitCode) {
            0 -> "succeeded"
            -2 -> "timed_out"
            else -> "failed"
        }
        val action = if (toolName == "terminal") "open_and_exec" else "run_command"
        val logMessage =
            "Agent terminal action=$action outcome=$outcome identity=$normalizedIdentity " +
                "environment=${environment.wireName} " +
                "timeoutSeconds=$timeout commandChars=${trimmed.length} exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            result.output + "\n[stderr]\n" + result.stderr
        } else {
            result.output
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", toolName)
            .put("action", if (toolName == "terminal") "open_and_exec" else JSONObject.NULL)
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.exitCode == -2)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", rawStdout.length > stdout.length)
            .put("stderr_truncated", !mergeStderr && result.stderr.length > stderr.length)
            .toString()
    }

    /**
     * 按行读取文本：行号从 1 开始，[endLine] 为空表示读到文件末尾。
     * 行号在 Kotlin 侧补，命令里不需要嵌套 awk 之类的引号。
     */
    fun readFileLines(path: String, startLine: Int, endLine: Int?, maxChars: Int): String {
        if (!rootAvailable()) return UserFileAccess.readLines(path, startLine, endLine, maxChars)
        val safePath = normalizePath(path)
        val limit = maxChars.coerceIn(1, MAX_OUTPUT_CHARS)
        val start = startLine.coerceAtLeast(1)
        // sed 的 $= 输出最后一行行号，空文件无输出；与按读取器逐行计数一致（末尾换行不计一空行）。
        val countResult = runSuText("sed -n '\$=' ${shellQuote(safePath)}", timeoutSeconds = 15)
        if (countResult.exitCode != 0) {
            return pathFailureJson(safePath, countResult.exitCode, countResult.stderr)
        }
        val totalLines = countResult.output.trim().toIntOrNull() ?: 0
        if (totalLines == 0) {
            logger.info("Agent terminal action=read_file mode=lines outcome=succeeded totalLines=0")
            return JSONObject()
                .put("ok", true)
                .put("tool", "read_file")
                .put("path", safePath)
                .put("start_line", start)
                .put("total_lines", 0)
                .put("content", "")
                .put("truncated", false)
                .put("message", "文件为空或不存在文本行")
                .toString()
        }
        if (start > totalLines) {
            return errorJson("LINE_OUT_OF_RANGE", "起始行 $start 超出文件总行数 $totalLines")
        }
        val end = (endLine ?: totalLines).coerceAtLeast(start).coerceAtMost(totalLines)
        val readResult = runSuBytes("sed -n \"$start,${end}p\" ${shellQuote(safePath)}", timeoutSeconds = 20)
        if (readResult.exitCode != 0) {
            return pathFailureJson(safePath, readResult.exitCode, readResult.stderr)
        }
        val rawLines = FileTextOperations.linesOf(readResult.output.decodeToString())
        val builder = StringBuilder()
        var emitted = 0
        var truncated = false
        for (offset in rawLines.indices) {
            val rendered = "${start + offset}\t${rawLines[offset]}\n"
            if (builder.length + rendered.length > limit) {
                truncated = true
                break
            }
            builder.append(rendered)
            emitted++
        }
        logger.info(
            "Agent terminal action=read_file mode=lines outcome=succeeded startLine=$start " +
                "endLine=$end totalLines=$totalLines emitted=$emitted"
        )
        val hasMore = truncated || end < totalLines
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "read_file")
            .put("path", safePath)
            .put("start_line", start)
            .put("end_line", start + emitted - 1)
            .put("total_lines", totalLines)
            .put("content", builder.toString().trimEnd('\n'))
            .put("truncated", hasMore)
            .put("next_start_line", if (hasMore) start + emitted else JSONObject.NULL)
        if (emitted == 0 && rawLines.isNotEmpty()) {
            json.put(
                "warning",
                "单行长度超过 max_chars=$limit，本轮没有输出任何行；请调大 max_chars，或改用字节模式 offset_bytes 续读",
            )
        }
        return json.toString()
    }

    /**
     * 定点替换：old_text 必须在文件中唯一命中，除非 replace_all 为 true；不满足条件时文件保持不变。
     */
    fun editFile(path: String, oldText: String, newText: String, replaceAll: Boolean): String {
        if (!rootAvailable()) return UserFileAccess.editFile(path, oldText, newText, replaceAll)
        val safePath = normalizePath(path)
        if (oldText.isEmpty()) return errorJson("INVALID_ARGUMENT", "old_text 不能为空")
        val sizeResult = runSuText("wc -c < ${shellQuote(safePath)}", timeoutSeconds = 15)
        if (sizeResult.exitCode != 0) {
            return pathFailureJson(safePath, sizeResult.exitCode, sizeResult.stderr)
        }
        val size = sizeResult.output.trim().toLongOrNull()
            ?: return errorJson("EDIT_FAILED", "无法读取文件大小")
        if (size > FileTextOperations.MAX_EDIT_BYTES) {
            return errorJson(
                "FILE_TOO_LARGE",
                "文件 $size 字节，超过定点替换上限 ${FileTextOperations.MAX_EDIT_BYTES} 字节；请改用 terminal 通道处理",
            )
        }
        val readResult = runSuBytes("cat ${shellQuote(safePath)}", timeoutSeconds = 20)
        if (readResult.exitCode != 0) {
            return pathFailureJson(safePath, readResult.exitCode, readResult.stderr)
        }
        val original = readResult.output.decodeToString()
        return when (val outcome = FileTextOperations.replace(original, oldText, newText, replaceAll)) {
            is FileTextOperations.ReplaceOutcome.NotFound -> errorJson(
                "EDIT_NOT_FOUND",
                buildString {
                    append("没有匹配 old_text 的文本（文件共 ${outcome.totalLines} 行）")
                    val snippet = FileTextOperations.nearestSnippet(original, oldText)
                    if (snippet.isBlank()) {
                        append("；文件为空，或 old_text 与任何一行都没有公共前缀，请用 read_file 核对")
                    } else {
                        append("。最接近的原文（L 开头是行号）：\n")
                        append(snippet)
                        val difference = FileTextOperations.describeFirstDifference(original, oldText)
                        if (difference.isNotBlank()) append("\n").append(difference)
                        append("\n请按上面的原文修正 old_text 后重试")
                    }
                },
            )
            is FileTextOperations.ReplaceOutcome.Ambiguous -> errorJson(
                "EDIT_NOT_UNIQUE",
                buildString {
                    append("old_text 命中 ${outcome.lines.size} 处（行 ${outcome.lines.joinToString("、")}）；")
                    append("请补足上下文使其唯一，或设置 replace_all=true。各命中处上下文（> 为命中行）：\n")
                    append(FileTextOperations.ambiguitySnippet(original, outcome.lines))
                },
            )
            is FileTextOperations.ReplaceOutcome.Applied -> {
                val bytes = outcome.content.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_WRITE_BYTES) {
                    return errorJson("FILE_TOO_LARGE", "替换后内容 ${bytes.size} 字节，超过写入上限 $MAX_WRITE_BYTES 字节")
                }
                val writeResult = runSuTextWithStdin(
                    atomicOverwriteScript(safePath, bytes.size, sha256Hex(bytes)),
                    bytes,
                    timeoutSeconds = 30,
                )
                if (writeResult.exitCode != 0) {
                    logger.warn(
                        "Agent terminal action=edit_file outcome=failed exitCode=${writeResult.exitCode} " +
                            "inputBytes=${bytes.size} errorChars=${writeResult.stderr.length}"
                    )
                    errorJson(
                        writeFailureCode(writeResult.exitCode),
                        writeFailureMessage(safePath, append = false, writeResult),
                    )
                } else {
                    logger.info(
                        "Agent terminal action=edit_file outcome=succeeded replacements=${outcome.occurrences} " +
                            "firstLine=${outcome.firstLine} bytesWritten=${bytes.size} verified=true"
                    )
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "edit_file")
                        .put("path", safePath)
                        .put("replacements", outcome.occurrences)
                        .put("first_line", outcome.firstLine)
                        .put("bytes_written", bytes.size)
                        .put("verified", true)
                        .put("diff", FileTextOperations.diffPreview(original, outcome.content).truncateForJson())
                        .toString()
                }
            }
        }
    }

    /**
     * 批量按行读取：一次取回多个文件，总字符预算在文件之间共享。
     *
     * 单个文件失败（不存在、是目录、超行数上限）不影响其它文件，失败条目照样占一段，
     * 段内写明原因——批量读取的价值就在于一轮拿到全部结论，不能因为其中一个路径写错就整批作废。
     */
    fun readFiles(paths: List<String>, maxChars: Int): String {
        if (paths.isEmpty()) return errorJson("INVALID_ARGUMENT", "paths 不能为空")
        if (!rootAvailable()) return UserFileAccess.readFiles(paths, maxChars)
        val budget = maxChars.coerceIn(200, FileToolLimits.MAX_OUTPUT_CHARS)
        val sections = mutableListOf<Pair<String, FileTextOperations.LineSlice>>()
        val failures = mutableListOf<JSONObject>()
        for (raw in paths) {
            val single = readFileLines(raw, startLine = 1, endLine = null, maxChars = budget)
            val parsed = runCatching { JSONObject(single) }.getOrNull()
            if (parsed == null || !parsed.optBoolean("ok")) {
                failures += JSONObject()
                    .put("path", raw)
                    .put("code", parsed?.optString("code")?.takeIf { it.isNotBlank() } ?: "READ_FAILED")
                    .put("message", parsed?.optString("message").orEmpty().ifBlank { "读取失败" })
                continue
            }
            val content = parsed.optString("content")
            val totalLines = parsed.optInt("total_lines")
            val start = parsed.optInt("start_line", 1)
            sections += parsed.optString("path", raw) to FileTextOperations.LineSlice(
                text = content,
                totalLines = totalLines,
                firstLine = start,
                lastLine = start + content.split('\n').let { if (content.isEmpty()) 0 else it.size } - 1,
                truncated = parsed.optBoolean("truncated"),
            )
        }
        val (text, emitted) = FileTextOperations.joinSections(sections, budget)
        logger.info(
            "Agent terminal action=read_files outcome=succeeded requested=${paths.size} " +
                "loaded=${emitted.size} failed=${failures.size}"
        )
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "read_files")
            .put("requested", paths.size)
            .put("loaded", emitted.size)
            .put("content", text)
        val sectionArray = JSONArray()
        for (section in emitted) {
            sectionArray.put(
                JSONObject()
                    .put("path", section.path)
                    .put("total_lines", section.totalLines)
                    .put("first_line", section.firstLine)
                    .put("last_line", section.lastLine)
                    .put("truncated", section.truncated)
                    .put("next_start_line", section.nextStartLine ?: JSONObject.NULL),
            )
        }
        json.put("sections", sectionArray)
        if (failures.isNotEmpty()) {
            json.put("failures", JSONArray(failures))
            json.put(
                "hint",
                "有 ${failures.size} 个路径没读到（见 failures）：路径写错或文件过大，不影响其余文件。" +
                    "被截断的段可传 next_start_line 单独续读。",
            )
        }
        return json.toString()
    }

    /**
     * 批量定点替换：先对全部条目做校验（读全文 + 匹配判定），全部通过才逐个写盘。
     *
     * 任一条失败就整批放弃、一个字节都不写：批量改动写一半留下的中间状态最难排查，
     * 调用方看到"部分成功"却拿不到可靠的已改清单。写盘阶段仍用与单文件相同的原子写，
     * 且每个文件写前用当时的内容重新校验一次匹配，避免校验与写盘之间被外部改动。
     */
    fun editFiles(requests: List<FileTextOperations.EditRequest>): String {
        if (requests.isEmpty()) return errorJson("INVALID_ARGUMENT", "edits 不能为空")
        if (!rootAvailable()) {
            return UserFileAccess.editFiles(requests)
        }
        val contents = linkedMapOf<String, String>()
        val loadFailures = mutableMapOf<String, FileTextOperations.EditFailure>()
        for (request in requests) {
            val safePath = normalizePath(request.path)
            if (contents.containsKey(safePath) || loadFailures.containsKey(safePath)) continue
            val sizeResult = runSuText("wc -c < ${shellQuote(safePath)}", timeoutSeconds = 15)
            if (sizeResult.exitCode != 0) {
                loadFailures[safePath] = FileTextOperations.EditFailure(
                    safePath,
                    "PATH_NOT_FOUND",
                    "读取不到文件：${safePath}",
                )
                continue
            }
            val size = sizeResult.output.trim().toLongOrNull()
            if (size == null) {
                loadFailures[safePath] = FileTextOperations.EditFailure(safePath, "EDIT_FAILED", "无法读取文件大小")
                continue
            }
            if (size > FileTextOperations.MAX_EDIT_BYTES) {
                loadFailures[safePath] = FileTextOperations.EditFailure(
                    safePath,
                    "FILE_TOO_LARGE",
                    "文件 $size 字节，超过定点替换上限 ${FileTextOperations.MAX_EDIT_BYTES} 字节；请改用 terminal 通道处理",
                )
                continue
            }
            val readResult = runSuBytes("cat ${shellQuote(safePath)}", timeoutSeconds = 20)
            if (readResult.exitCode != 0) {
                loadFailures[safePath] = FileTextOperations.EditFailure(
                    safePath,
                    "PATH_NOT_FOUND",
                    "读取不到文件：${safePath}",
                )
                continue
            }
            contents[safePath] = readResult.output.decodeToString()
        }
        val normalized = requests.map {
            it.copy(path = normalizePath(it.path))
        }
        val plan = FileTextOperations.planEdits(
            requests = normalized,
            contents = contents,
            loadFailure = { loadFailures[it.path] },
        )
        if (!plan.isComplete) {
            logger.warn(
                "Agent terminal action=edit_files outcome=failed requested=${requests.size} " +
                    "failures=${plan.failures.size} written=0"
            )
            return JSONObject()
                .put("ok", false)
                .put("tool", "edit_files")
                .put("code", "BATCH_ABORTED")
                .put("written", 0)
                .put(
                    "message",
                    "批量替换未执行：${plan.failures.size} 条校验失败，已写入 0 个文件（整批要么全改要么全不改）。" +
                        "按下面的失败原因修正后重试。",
                )
                .put(
                    "failures",
                    JSONArray(
                        plan.failures.map {
                            JSONObject().put("path", it.path).put("code", it.code).put("message", it.message)
                        },
                    ),
                )
                .toString()
        }
        val written = mutableListOf<JSONObject>()
        for (edit in plan.plan) {
            val bytes = edit.content.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_WRITE_BYTES) {
                return errorJson(
                    "FILE_TOO_LARGE",
                    "替换后 ${edit.request.path} 为 ${bytes.size} 字节，超过写入上限 $MAX_WRITE_BYTES 字节",
                )
            }
            val writeResult = runSuTextWithStdin(
                atomicOverwriteScript(edit.request.path, bytes.size, sha256Hex(bytes)),
                bytes,
                timeoutSeconds = 30,
            )
            if (writeResult.exitCode != 0) {
                logger.warn(
                    "Agent terminal action=edit_files outcome=failed path=${edit.request.path} " +
                        "exitCode=${writeResult.exitCode} writtenSoFar=${written.size}"
                )
                return JSONObject()
                    .put("ok", false)
                    .put("tool", "edit_files")
                    .put("code", writeFailureCode(writeResult.exitCode))
                    .put("written", written.size)
                    .put(
                        "message",
                        "写盘阶段失败（${edit.request.path}）：${writeFailureMessage(edit.request.path, append = false, writeResult)}" +
                            "；此前已写入 ${written.size} 个文件，见 results。",
                    )
                    .put("results", JSONArray(written))
                    .toString()
            }
            written += JSONObject()
                .put("path", edit.request.path)
                .put("replacements", edit.occurrences)
                .put("first_line", edit.firstLine)
                .put("bytes_written", bytes.size)
        }
        logger.info(
            "Agent terminal action=edit_files outcome=succeeded requested=${requests.size} written=${written.size}"
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "edit_files")
            .put("requested", requests.size)
            .put("written", written.size)
            .put("results", JSONArray(written))
            .toString()
    }

    /**
     * 按内容检索：递归目录，返回 文件:行号:内容。路径前缀按检索根目录缩写，便于阅读。
     */
    /** 按字符预算拼接结果行，返回文本与真正写入的行数：宁可少给几行，也不要把上下文一次撑爆。 */
    private fun joinWithinBudget(lines: List<String>, budget: Int): Pair<String, Int> {
        val builder = StringBuilder()
        var emitted = 0
        for (line in lines) {
            if (builder.length + line.length + 1 > budget) break
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
            emitted++
        }
        return builder.toString() to emitted
    }

    /**
     * 按文件名 glob 找文件。与 search_code 共用同一条路径归一化与 ripgrep 通道，
     * 这样 /workspace、~、相对路径在两种身份下语义一致；起始目录不存在时直接回可操作的
     * 路径提示，而不是把 find 的原始报错抛给模型（曾把 /workspace/... 当成不存在的目录）。
     */
    fun findFiles(path: String, glob: String, limit: Int, noIgnore: Boolean, hidden: Boolean): String {
        if (!rootAvailable()) return UserFileAccess.findFiles(path, glob, limit, noIgnore, hidden)
        if (glob.isBlank()) return errorJson("INVALID_ARGUMENT", "glob 不能为空")
        val safePath = normalizePath(path.ifBlank { DEFAULT_CWD })
        if (pathMissing(safePath)) return errorJson("PATH_NOT_FOUND", missingPathMessage(safePath))
        val capped = limit.coerceIn(1, 200)
        val rg = ripgrepPath()
        val command = (
            if (rg != null) rgPrefix(rg, glob, noIgnore = noIgnore, hidden = hidden) + " --files " + shellQuote(safePath)
            else "find " + shellQuote(safePath) + " -type f -name " + shellQuote(glob)
            ) + " | head -n ${capped + 1}"
        val result = runSuText(command, timeoutSeconds = 30)
        if (result.output.isBlank() && result.stderr.isNotBlank()) {
            logger.warn("Agent terminal action=find_files outcome=failed errorChars=${result.stderr.length}")
            return errorJson("FIND_FAILED", result.stderr.take(500))
        }
        val found = result.output.removeSuffix("\n")
            .let { if (it.isEmpty()) emptyList() else it.split("\n") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val truncated = found.size > capped
        logger.info(
            "Agent terminal action=find_files outcome=succeeded files=${minOf(found.size, capped)} " +
                "truncated=$truncated",
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "find_files")
            .put("path", safePath)
            .put("glob", glob)
            .put("count", minOf(found.size, capped))
            .put("files", JSONArray(found.take(capped)))
            .put("truncated", truncated)
            .put(
                "hint",
                searchHint(
                    if (truncated) "文件数超过上限；缩小 path、加严 glob 或提高 limit。" else null,
                    if (found.isEmpty()) ignoredFilesHint(rg, glob, safePath, noIgnore, hidden) else null,
                ),
            )
            .toString()
    }

    fun searchCode(
        path: String,
        pattern: String,
        glob: String?,
        maxResults: Int,
        contextLines: Int,
        maxChars: Int,
        filesOnly: Boolean,
        ignoreCase: Boolean,
        offset: Int,
        noIgnore: Boolean,
        hidden: Boolean,
    ): String {
        if (!rootAvailable()) {
            return UserFileAccess.searchCode(
                path, pattern, glob, maxResults, contextLines, maxChars, filesOnly,
                ignoreCase, offset, noIgnore, hidden,
            )
        }
        val safePath = normalizePath(path.ifBlank { DEFAULT_CWD })
        if (pathMissing(safePath)) return errorJson("PATH_NOT_FOUND", missingPathMessage(safePath))
        if (pattern.isEmpty()) return errorJson("INVALID_ARGUMENT", "pattern 不能为空")
        val limit = maxResults.coerceIn(1, FileTextOperations.MAX_SEARCH_RESULTS)
        val budget = maxChars.coerceIn(200, 32_000)
        val skip = offset.coerceAtLeast(0)
        // 多取一条用来判断「还有下一页」，并把 offset 一起算进 head 的条数。
        val fetch = skip + limit + 1
        // 优先用 ripgrep（设备上放了静态版时）：它比 BusyBox grep 快一个量级，且原生支持 glob。
        // 退一步用 GNU grep 的 --include；Android 自带的 BusyBox grep 不认这个选项，
        // 此时退回 find 预筛文件，并用 -H 保持 "路径:行号:内容" 的输出格式。
        val globArg = glob?.takeIf { it.isNotBlank() }
        val rg = ripgrepPath()
        val useFind = rg == null && globArg != null && !grepSupportsInclude()
        val include = if (rg == null && globArg != null && !useFind) {
            " --include=${shellQuote(globArg)}"
        } else {
            ""
        }
        // grep 侧用 -i，rg 侧用 --ignore-case：显式传参，免得只有 rg 通道认 (?i) 这类写法。
        val caseFlag = if (ignoreCase) "i" else ""
        val base = safePath.trimEnd('/')

        if (filesOnly) {
            // 先只回文件名与命中行数：用来决定接下来精读哪个文件，而不是把全部命中行读回来。
            val fileCommand = when {
                rg != null -> rgPrefix(rg, globArg, ignoreCase, noIgnore, hidden) +
                    " --count-matches ${shellQuote(pattern)} ${shellQuote(safePath)}" +
                    " | head -n $fetch"
                useFind -> "find ${shellQuote(safePath)} -type f -name ${shellQuote(checkNotNull(globArg))} -exec " +
                    "grep -H -c -I -E$caseFlag ${shellQuote(pattern)} {} +" +
                    " | grep -v ':0$' | head -n $fetch"
                else -> "grep -rc -I -E$caseFlag$include ${shellQuote(pattern)} ${shellQuote(safePath)}" +
                    " | grep -v ':0$' | head -n $fetch"
            }
            val fileResult = runSuText(fileCommand, timeoutSeconds = 30)
            if (fileResult.output.isBlank() && fileResult.stderr.isNotBlank()) {
                logger.warn(
                    "Agent terminal action=search_code mode=files outcome=failed " +
                        "errorChars=${fileResult.stderr.length}"
                )
                return errorJson("SEARCH_FAILED", fileResult.stderr)
            }
            val entries = fileResult.output.removeSuffix("\n")
                .let { if (it.isEmpty()) emptyList() else it.split("\n") }
                .map { line -> line.removePrefix("$base/").removePrefix("$base:") }
            val page = FileTextOperations.searchPage(entries, skip, limit)
            val budgeted = joinWithinBudget(page.lines, budget)
            val clipped = page.hasMore || budgeted.second < page.lines.size
            val returned = budgeted.second
            logger.info(
                "Agent terminal action=search_code mode=files outcome=succeeded files=$returned " +
                    "offset=$skip truncated=$clipped"
            )
            return JSONObject()
                .put("ok", true)
                .put("tool", "search_code")
                .put("mode", "files_only")
                .put("path", safePath)
                .put("pattern", pattern)
                .put("glob", glob?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("offset", skip)
                .put("match_files", returned)
                .put("results", budgeted.first)
                .put("truncated", clipped)
                .put("next_offset", if (clipped) skip + returned else JSONObject.NULL)
                .put(
                    "hint",
                    searchHint(
                        if (clipped) {
                            "结果被截断：可以传 offset=${skip + returned} 继续读下一批，或加严 glob／缩小 path。"
                        } else {
                            null
                        },
                        if (entries.isEmpty()) {
                            ignoredHitsHint(rg, globArg, pattern, safePath, ignoreCase, noIgnore, hidden)
                        } else {
                            null
                        },
                    ),
                )
                .toString()
        }

        val context = contextLines.coerceIn(0, 5).let { if (it > 0) " -C $it" else "" }
        val command = when {
            rg != null -> rgPrefix(rg, globArg, ignoreCase, noIgnore, hidden) +
                " --line-number$context ${shellQuote(pattern)} ${shellQuote(safePath)}" +
                " | head -n $fetch"
            useFind -> "find ${shellQuote(safePath)} -type f -name ${shellQuote(checkNotNull(globArg))} -exec " +
                "grep -H -n -I -E$caseFlag$context ${shellQuote(pattern)} {} +" +
                " | head -n $fetch"
            else -> "grep -rn -I -E$caseFlag$context$include ${shellQuote(pattern)} ${shellQuote(safePath)}" +
                " | head -n $fetch"
        }
        val result = runSuText(command, timeoutSeconds = 30)
        if (result.output.isBlank() && result.stderr.isNotBlank()) {
            logger.warn("Agent terminal action=search_code outcome=failed errorChars=${result.stderr.length}")
            return errorJson("SEARCH_FAILED", result.stderr)
        }
        val lines = result.output.removeSuffix("\n")
            .let { if (it.isEmpty()) emptyList() else it.split("\n") }
            .map { line -> line.removePrefix("$base/").removePrefix("$base:") }
        val page = FileTextOperations.searchPage(lines, skip, limit)
        val budgeted = joinWithinBudget(page.lines, budget)
        val clipped = page.hasMore || budgeted.second < page.lines.size
        val returned = budgeted.second
        logger.info(
            "Agent terminal action=search_code outcome=succeeded patternChars=${pattern.length} " +
                "matches=$returned offset=$skip truncated=$clipped"
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_code")
            .put("mode", "lines")
            .put("path", safePath)
            .put("pattern", pattern)
            .put("glob", glob?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("offset", skip)
            .put("match_lines", returned)
            .put("results", budgeted.first)
            .put("truncated", clipped)
            .put("next_offset", if (clipped) skip + returned else JSONObject.NULL)
            .put(
                "hint",
                searchHint(
                    if (clipped) {
                        "结果被截断：可以传 offset=${skip + returned} 继续读下一批，" +
                            "或先 files_only=true 看命中分布、缩小 path／加 glob／把 pattern 写具体。"
                    } else {
                        null
                    },
                    if (lines.isEmpty()) {
                        ignoredHitsHint(rg, globArg, pattern, safePath, ignoreCase, noIgnore, hidden)
                    } else {
                        null
                    },
                ),
            )
            .toString()
    }

    /**
     * 定位 ripgrep：优先用随包分发的静态二进制（jniLibs 里的 librg.so，安装时解到
     * nativeLibraryDir），它不依赖设备上有没有人手工放过文件——换机、清 /data/local/tmp
     * 都不会失效。其次才看手工放在工具目录里的那份，最后退回 grep。
     *
     * 之所以保留 grep 兜底：个别加固 ROM 会禁止从 nativeLibraryDir 执行二进制，
     * 那种情况下检索应该是「慢一点」而不是「直接失败」。
     */
    private fun ripgrepPath(): String? {
        ripgrepPathCache?.let { return it.ifBlank { null } }
        val candidates = buildList {
            TerminalRuntime.nativeExecutable(RIPGREP_LIBRARY_NAME)?.let { add(it.absolutePath) }
            add("$DEFAULT_CWD/tools/rg")
        }
        val path = candidates.firstOrNull { candidate ->
            runSuText("test -x ${shellQuote(candidate)} && echo yes || echo no", timeoutSeconds = 10)
                .output.trim() == "yes"
        }
        ripgrepPathCache = path.orEmpty()
        logger.info(
            "Agent terminal action=search_code capability=ripgrep available=${path != null} " +
                "path=${path ?: "-"}",
        )
        return path
    }

    /**
     * rg 的公共参数：关掉会干扰「路径:行号:内容」解析的输出格式，glob 交给 rg 自己处理。
     *
     * 默认保持 rg 的忽略语义（遵守 .gitignore、跳过隐藏文件）——在代码库里这是想要的；但它让
     * 「0 命中」不再等于「不存在」，所以 search_code 在 0 命中时会用 `--no-ignore --hidden`
     * 再探一次并把结论写进 hint。要主动全搜就传 noIgnore / hidden。
     */
    private fun rgPrefix(
        rg: String,
        glob: String?,
        ignoreCase: Boolean = false,
        noIgnore: Boolean = false,
        hidden: Boolean = false,
    ): String {
        val globFlag = glob?.let { " --glob ${shellQuote(it)}" }.orEmpty()
        val caseFlag = if (ignoreCase) " --ignore-case" else ""
        val scopeFlag = (if (noIgnore) " --no-ignore" else "") + (if (hidden) " --hidden" else "")
        return "${shellQuote(rg)} --no-heading --color never$globFlag$caseFlag$scopeFlag"
    }

    /**
     * 路径是否真的不存在。App 进程的 File.exists() 对 /data/data、/data/adb 这类它无权 stat 的
     * 目录一律返回 false，直接拿它判断会把「root 能进、App 看不见」的路径误报成不存在，
     * 所以只有 App 看不见时才回 shell 确认一次。
     */
    private fun pathMissing(path: String): Boolean {
        if (File(path).exists()) return false
        val probe = runSuText("[ -e ${shellQuote(path)} ] && echo yes || echo no", timeoutSeconds = 10)
        return probe.output.trim().lines().lastOrNull()?.trim() != "yes"
    }

    /** 把若干条提示拼成一条；全为空时回 JSONObject.NULL（避免空字符串被当成「有提示」）。 */
    private fun searchHint(vararg parts: String?): Any =
        parts.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" ") ?: JSONObject.NULL

    /**
     * 0 命中时补一次「放宽忽略规则」的探测：rg 默认遵守 .gitignore、跳过隐藏文件，命中为 0
     * 很可能只是被过滤掉了，直接回「没有命中」会让调用方得出错误结论。
     * 只在确实用着 rg 且当前没有放宽过滤时跑，代价是一次额外检索。
     */
    private fun ignoredHitsHint(
        rg: String?,
        globArg: String?,
        pattern: String,
        path: String,
        ignoreCase: Boolean,
        noIgnore: Boolean,
        hidden: Boolean,
    ): String? {
        if (rg == null || noIgnore || hidden) return null
        val probe = runSuText(
            rgPrefix(rg, globArg, ignoreCase, noIgnore = true, hidden = true) +
                " --count-matches ${shellQuote(pattern)} ${shellQuote(path)} | head -n 5",
            timeoutSeconds = 30,
        )
        val rows = probe.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        return "没有命中：但被 .gitignore 或隐藏规则挡掉的文件里有 ${rows.size} 处（" +
            rows.take(2).joinToString("、") + "）。要连它们一起搜就传 no_ignore=true、hidden=true。"
    }

    /** 与 [ignoredHitsHint] 同理，find_files 的场景：一个文件都没找到时确认是不是被忽略规则挡掉了。 */
    private fun ignoredFilesHint(
        rg: String?,
        glob: String?,
        path: String,
        noIgnore: Boolean,
        hidden: Boolean,
    ): String? {
        if (rg == null || noIgnore || hidden) return null
        val probe = runSuText(
            rgPrefix(rg, glob, noIgnore = true, hidden = true) + " --files " + shellQuote(path) + " | head -n 5",
            timeoutSeconds = 30,
        )
        val rows = probe.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        return "没有匹配的文件：但被 .gitignore 或隐藏规则挡掉的文件里有 ${rows.size} 个（" +
            rows.take(2).joinToString("、") + "）。要连它们一起找就传 no_ignore=true、hidden=true。"
    }

    /**
     * 探测 grep 是否支持 GNU 的 --include，结果缓存一次。
     * 不支持的设备（Android 自带 BusyBox grep）上 search_code 的 glob 会退回 find 预筛，
     * 否则整个检索会直接以 "unrecognized option" 失败。
     */
    private fun grepSupportsInclude(): Boolean {
        grepIncludeSupport?.let { return it }
        val probe = runSuText("grep --help 2>&1 | grep -c -e '--include'", timeoutSeconds = 10)
        val supported = (probe.output.trim().toIntOrNull() ?: 0) > 0
        grepIncludeSupport = supported
        logger.info("Agent terminal action=search_code capability=grep_include supported=$supported")
        return supported
    }

    fun readFile(path: String, offsetBytes: Int, maxBytes: Int): String {
        if (!rootAvailable()) return UserFileAccess.read(path, offsetBytes, maxBytes)
        val safePath = normalizePath(path)
        val offset = offsetBytes.coerceAtLeast(0)
        val limit = maxBytes.coerceIn(1, MAX_READ_BYTES)
        // 多取一个字节：正好读满 limit 时无法区分「刚好读完」与「还有后续」，多这一个字节就能判定。
        val command = "dd if=${shellQuote(safePath)} bs=1 skip=$offset count=${limit + 1} 2>/dev/null"
        val result = runSuBytes(command, timeoutSeconds = 20)
        if (result.exitCode != 0 && result.output.isEmpty()) {
            logger.warn(
                "Agent terminal action=read_file outcome=failed offsetBytes=$offset " +
                    "maxBytes=$limit exitCode=${result.exitCode} errorChars=${result.stderr.length}"
            )
            return pathFailureJson(safePath, result.exitCode, result.stderr)
        }
        val scanned = result.output
        val byteTruncated = scanned.size > limit
        val raw = if (byteTruncated) scanned.copyOf(limit) else scanned
        // 字节上限之内还可能撞上字符预算：两者都要如实回报，否则调用方以为「读了 20000 字节」
        // 却只拿到 16000 字符，续读偏移也无从算起。
        val (text, charTruncated) = FileTextOperations.clipChars(raw.decodeToString(), MAX_OUTPUT_CHARS)
        val bytesRead = text.toByteArray(Charsets.UTF_8).size
        val hasMore = byteTruncated || charTruncated
        logger.info(
            "Agent terminal action=read_file outcome=succeeded offsetBytes=$offset maxBytes=$limit " +
                "bytesRead=$bytesRead truncated=$hasMore exitCode=${result.exitCode}"
        )
        val json = JSONObject()
            .put("ok", true)
            .put("tool", "read_file")
            .put("path", safePath)
            .put("offset_bytes", offset)
            .put("bytes_read", bytesRead)
            .put("truncated", hasMore)
            .put("next_offset_bytes", if (hasMore) offset + bytesRead else JSONObject.NULL)
            // 只在确实被截断时多花一次 stat：未截断时调用方已经从 truncated=false 知道读全了。
            .put("total_bytes", if (hasMore) fileSizeOrNull(safePath) ?: JSONObject.NULL else JSONObject.NULL)
            .put("content", text)
        if (result.exitCode != 0) {
            json.put("warning", "读取被中断（exit=${result.exitCode}），已返回的内容可能不完整")
        }
        return json.toString()
    }

    fun writeFile(path: String, content: String, append: Boolean): String {
        if (!rootAvailable()) return UserFileAccess.write(path, content, append)
        val safePath = normalizePath(path)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) { "写入内容过大：${bytes.size} bytes" }
        val digest = sha256Hex(bytes)
        val script = if (append) {
            atomicAppendScript(safePath, bytes.size, digest)
        } else {
            atomicOverwriteScript(safePath, bytes.size, digest)
        }
        val result = runSuTextWithStdin(script, bytes, timeoutSeconds = 30)
        if (result.exitCode == 0) {
            logger.info(
                "Agent terminal action=write_file outcome=succeeded append=$append " +
                    "bytesWritten=${bytes.size} verified=true exitCode=${result.exitCode}"
            )
            return JSONObject()
                .put("ok", true)
                .put("tool", "write_file")
                .put("path", safePath)
                .put("mode", if (append) "append" else "overwrite")
                .put("bytes_written", bytes.size)
                .put("verified", true)
                .toString()
        }
        logger.warn(
            "Agent terminal action=write_file outcome=failed append=$append " +
                "inputBytes=${bytes.size} exitCode=${result.exitCode} " +
                "outputChars=${result.output.length} errorChars=${result.stderr.length}"
        )
        return errorJson(writeFailureCode(result.exitCode), writeFailureMessage(safePath, append, result))
    }

    fun listDirectory(path: String, showHidden: Boolean, limit: Int): String {
        if (!rootAvailable()) return UserFileAccess.list(path, showHidden, limit)
        val safePath = normalizePath(path.ifBlank { DEFAULT_CWD })
        val maxEntries = limit.coerceIn(1, MAX_LIST_ENTRIES)
        // -A 而不是 -a：`.` 与 `..` 不该占列表名额。
        val flags = if (showHidden) "-lA" else "-l"
        val marker = "@@eta-entry-count"
        // `ls -l` 首行是 `total N`（磁盘块统计，不是条目数），先 tail 掉；再用同一套 flags 数一遍总数，
        // 否则 head 截断之后，「看到的列表」和「目录里到底有多少东西」是两回事。
        val command = "cd ${shellQuote(safePath)} && { ls $flags | tail -n +2 | head -n $maxEntries; " +
            "echo $marker; ls $flags | tail -n +2 | wc -l; }"
        val result = runSuText(command, timeoutSeconds = 15)
        val logMessage =
            "Agent terminal action=list_directory " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "showHidden=$showHidden limit=$maxEntries exitCode=${result.exitCode} " +
                "outputChars=${result.output.length} errorChars=${result.stderr.length}"
        if (result.exitCode != 0) {
            logger.warn(logMessage)
            return pathFailureJson(safePath, result.exitCode, result.stderr, fallbackCode = "LIST_FAILED")
        }
        logger.info(logMessage)
        val markerIndex = result.output.indexOf(marker)
        val entriesText = if (markerIndex >= 0) result.output.take(markerIndex).trimEnd('\n') else result.output
        val countText = if (markerIndex >= 0) result.output.substring(markerIndex + marker.length) else ""
        val entryCount = countText.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.toIntOrNull()
            ?: 0
        val listing = FileTextOperations.directoryListing(entriesText, entryCount, maxEntries)
        return JSONObject()
            .put("ok", true)
            .put("tool", "list_directory")
            .put("path", safePath)
            .put("entry_count", listing.entryCount)
            .put("truncated", listing.truncated)
            .put("entries_text", listing.text.truncateForJson())
            .toString()
    }

    private fun normalizeIdentity(identity: String): String {
        val normalized = identity.ifBlank { "root" }.lowercase()
        require(normalized == "root" || normalized == "user") {
            "identity 仅支持 root/user"
        }
        return normalized
    }

    /** android、linux（当前选中的发行版）与具体发行版名，都归一化为一个执行环境。 */
    private fun normalizeEnvironment(environment: String): TerminalEnvironment {
        val wireName = environment.ifBlank { TerminalEnvironment.ANDROID.wireName }.lowercase()
        if (wireName == TerminalEnvironment.ANDROID.wireName) return TerminalEnvironment.ANDROID
        if (wireName == SELECTED_LINUX_WIRE_NAME) return selectedLinuxEnvironmentProvider()
        return TerminalEnvironment.entries.firstOrNull { it.isLinux && it.wireName == wireName }
            ?: throw IllegalArgumentException(
                "environment 仅支持 android、linux，或发行版名 " +
                    LinuxDistribution.entries.joinToString("/") { it.wireName },
            )
    }

    private fun environmentPreflight(
        identity: String,
        environment: TerminalEnvironment,
        rootfsPath: String? = rootfsPathFor(environment),
    ): String? = when {
        environment.isLinux && identity != "root" && LinuxEnvironmentPaths.backendOf(rootfsPath) != LinuxExecutionBackend.PROOT ->
            errorJson("LINUX_ENVIRONMENT_REQUIRES_ROOT", "Linux 工具环境仅支持 root identity")
        environment.isLinux && !LinuxEnvironmentPaths.rootfsReady(rootfsPath) ->
            errorJson(
                "LINUX_ENVIRONMENT_NOT_READY",
                "${environment.wireName} 工具环境尚未安装，请先在 Linux 工具环境页面安装该发行版",
            )
        identity == "root" && !rootAvailable() -> errorJson("ROOT_REQUIRED", "Root 授权不可用")
        environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT && identity == "root" ->
            errorJson("INVALID_IDENTITY", "免 Root Linux 使用普通应用身份，请使用 identity=user")
        else -> null
    }

    private fun defaultIdentity(environment: TerminalEnvironment): String = when {
        environment.isLinux -> TerminalRuntime.defaultIdentity(environment, rootfsPathFor(environment))
        rootAvailable() -> "root"
        else -> "user"
    }

    private fun normalizeCwd(cwd: String?, environment: TerminalEnvironment, identity: String): String {
        val defaultCwd = if (environment.isLinux) LINUX_DEFAULT_CWD else TerminalRuntime.workspace(identity)
        val requested = cwd?.trim().orEmpty().ifBlank { defaultCwd }
        val environmentPath = when {
            requested == "~" || requested.startsWith("~/") || requested.startsWith("/") -> requested
            else -> "$defaultCwd/$requested"
        }
        return if (environment.isLinux) {
            val value = when { environmentPath == "~" -> "/root"; environmentPath.startsWith("~/") -> "/root/${environmentPath.removePrefix("~/")}"; else -> environmentPath }
            File(value).toPath().normalize().toString()
        } else if (identity == "user") {
            val value = when { environmentPath == "~" -> defaultCwd; environmentPath.startsWith("~/") -> "$defaultCwd/${environmentPath.removePrefix("~/")}"; else -> environmentPath }
            File(value).canonicalPath
        } else normalizePath(environmentPath)
    }

    private fun normalizePath(path: String): String {
        val raw = path.trim()
        require(raw.isNotBlank()) { "path 不能为空" }
        val effective = when {
            raw == "~" -> USER_STORAGE
            raw.startsWith("~/") -> USER_STORAGE + "/" + raw.removePrefix("~/")
            // Linux 工具环境的工作目录与 Android 侧的 DEFAULT_CWD 是同一份目录（chroot 里 bind 过去），
            // 但设备侧文件工具在 Android 命名空间里执行：直接把 /workspace/... 交给 shell 只会得到 can't open。
            raw == LINUX_DEFAULT_CWD -> DEFAULT_CWD
            raw.startsWith("$LINUX_DEFAULT_CWD/") -> DEFAULT_CWD + "/" + raw.removePrefix("$LINUX_DEFAULT_CWD/")
            raw.startsWith("/") -> raw
            else -> "$DEFAULT_CWD/$raw"
        }
        val normalized = File(effective).canonicalPath
        return normalized
    }

    private fun startSessionProcess(
        identity: String,
        environment: TerminalEnvironment,
        rootfsPath: String?,
    ): Process? =
        processSupervisor.startShellProcess(
            identity = identity,
            command = null,
            mergeStderr = false,
            environment = environment,
            linuxRootfsPath = rootfsPath,
            linuxSharedMounts = sharedMountsFor(environment),
        )

    /** 共享挂载只在 Linux 会话建立时解析；Android 环境不涉及。 */
    private fun sharedMountsFor(environment: TerminalEnvironment): List<SharedFolderMount> =
        if (environment.isLinux) linuxSharedMountsProvider() else emptyList()

    private fun rootfsPathFor(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    /**
     * 原始执行：不加工具层的包装，直接给出退出码与输出。
     *
     * 与 [runCommand] 共用同一套环境预检与 cwd 归一，保证"内部调用"和"模型调用"
     * 走的是同一条路径，不会因为绕开工具层而绕过安全约束。
     */
    private fun runCommandRaw(
        command: String,
        cwd: String?,
        timeoutSeconds: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
    ): RawCommandResult {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return RawCommandResult(exitCode = -1, stdout = "", stderr = "command 不能为空")
        }
        if (trimmed.length > MAX_COMMAND_CHARS) {
            return RawCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "command 过长：${trimmed.length} > $MAX_COMMAND_CHARS",
            )
        }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { failure ->
            return RawCommandResult(exitCode = -1, stdout = "", stderr = failure)
        }
        val safeCwd = normalizeCwd(cwd, environment, normalizedIdentity)
        val setup = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) {
            "mkdir -p ${shellQuote(safeCwd)} && "
        } else {
            ""
        }
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val result = runText(
            identity = normalizedIdentity,
            command = fullCommand,
            timeoutSeconds = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS).toLong(),
            environment = environment,
        )
        val output = if (mergeStderr && result.stderr.isNotBlank()) {
            result.output + "\n[stderr]\n" + result.stderr
        } else {
            result.output
        }
        return RawCommandResult(
            exitCode = result.exitCode,
            stdout = output,
            stderr = if (mergeStderr) "" else result.stderr,
        )
    }

    private fun runText(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
    ): ShellTextResult {
        val result = runProcess(
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = environment,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd(),
        )
    }

    private fun runSuText(command: String, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runSuTextWithStdin(command: String, stdin: ByteArray, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = stdin,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runSuBytes(command: String, timeoutSeconds: Long): ShellBytesResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellBytesResult(result.exitCode, result.output, result.stderr.decodeToString().trimEnd())
    }

    private fun runProcess(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        stdin: ByteArray?,
        environment: TerminalEnvironment,
    ): OneShotShellResult =
        runOneShotShell(
            processSupervisor = processSupervisor,
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = stdin,
            environment = environment,
            linuxRootfsPath = rootfsPathFor(environment),
            linuxSharedMounts = sharedMountsFor(environment),
        )

    /**
     * 超长文本包成 JSON 字段时的裁剪。带上原文长度：只写 `...[truncated]` 的话，
     * 调用方既不知道被砍了多少，也没法判断还要不要继续读。
     */
    private fun String.truncateForJson(): String =
        if (length <= MAX_OUTPUT_CHARS) {
            this
        } else {
            take(MAX_OUTPUT_CHARS) + "\n...[truncated: 已保留前 $MAX_OUTPUT_CHARS 字符，原文共 $length 字符]"
        }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(MAX_ERROR_CHARS))
            .toString()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 文件字节数；取不到（不存在、是设备/管道、无权限）时返回 null。 */
    private fun fileSizeOrNull(path: String): Long? =
        runSuText("stat -c %s ${shellQuote(path)} 2>/dev/null", timeoutSeconds = 10).output.trim().toLongOrNull()

    /**
     * 路径类失败的统一诊断：区分「不存在」「是目录」「存在但读不出来」。
     * 前者给出可行动的最近可用目录；后者回传真实报错——此前 dd/sed/cat 的 stderr 被
     * `2>/dev/null` 丢掉，调用方只拿到一句 exit=1，看不出到底哪一步错了。
     */
    private fun pathFailureJson(
        path: String,
        exitCode: Int,
        stderr: String,
        fallbackCode: String = "READ_FAILED",
    ): String {
        val probe = runSuText(
            "if [ -d ${shellQuote(path)} ]; then echo STATE=DIR; " +
                "elif [ -e ${shellQuote(path)} ]; then echo STATE=EXISTS; else echo STATE=MISSING; fi; " +
                "dd if=${shellQuote(path)} bs=1 count=1 2>&1 >/dev/null | head -n 1",
            timeoutSeconds = 10,
        )
        val lines = probe.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val state = lines.firstOrNull { it.startsWith("STATE=") }?.removePrefix("STATE=")
        val reason = lines.lastOrNull { !it.startsWith("STATE=") }.orEmpty()
        return when (state) {
            "MISSING" -> errorJson("PATH_NOT_FOUND", missingPathMessage(path))
            "DIR" -> errorJson(
                "NOT_A_FILE",
                "路径是目录：$path。列目录用 list_directory；要读文件请带上目录里的文件名",
            )
            else -> errorJson(
                fallbackCode,
                listOfNotNull(
                    "操作失败：$path",
                    reason.takeIf { it.isNotBlank() },
                    stderr.trim().takeIf { it.isNotBlank() },
                    "exit=$exitCode",
                ).joinToString("；"),
            )
        }
    }

    /** root 通道的「路径不存在」提示：shell 侧探测，App 进程看不到的目录也能给出准确提示。 */
    private fun missingPathMessage(path: String): String {
        val probe = runSuText(PathHints.probeScript(path), timeoutSeconds = 10)
        val parsed = PathHints.parseProbe(probe.output)
        return PathHints.message(path, parsed.nearest, parsed.entries)
    }

    /**
     * 原子覆盖脚本：写同目录临时文件 → 用大小 + sha256 校验落盘内容 → 补上原文件的权限/属主/
     * SELinux 上下文 → rename 顶替。任何一步失败都只删临时文件，原文件一个字节都不动。
     *
     * 为什么不是 `cat > path`：那是「先截断再写」，su 超时、进程被杀、磁盘写满都会留下半截文件。
     * 目标是符号链接时先 readlink 到真实路径，保持「写穿链接」的语义（直接 rename 会把链接本身
     * 换成普通文件）。
     */
    private fun atomicOverwriteScript(path: String, byteCount: Int, digest: String): String = buildString {
        append("t=").append(shellQuote(path)).append('\n')
        append("d=$(dirname \"\$t\"); mkdir -p \"\$d\" || exit 1\n")
        append("r=$(readlink -f \"\$t\" 2>/dev/null)\n")
        append("[ -n \"\$r\" ] && { t=\"\$r\"; d=$(dirname \"\$t\"); }\n")
        append("n=$(basename \"\$t\"); tmp=\"\$d/.\$n$WRITE_TEMP_SUFFIX\"\n")
        append("rm -f \"\$tmp\"\n")
        append("cat > \"\$tmp\" || { rm -f \"\$tmp\"; exit 2; }\n")
        append("s=$(stat -c %s \"\$tmp\" 2>/dev/null)\n")
        append("[ \"\$s\" = \"$byteCount\" ] || { rm -f \"\$tmp\"; echo \"size=\$s expected=$byteCount\"; exit 3; }\n")
        append("h=$(sha256sum \"\$tmp\" | cut -d' ' -f1)\n")
        append("[ \"\$h\" = \"$digest\" ] || { rm -f \"\$tmp\"; echo \"sha256=\$h\"; exit 4; }\n")
        append("if [ -e \"\$t\" ]; then\n")
        append("  chmod \"$(stat -c %a \"\$t\")\" \"\$tmp\" 2>/dev/null\n")
        append("  chown \"$(stat -c %u:%g \"\$t\")\" \"\$tmp\" 2>/dev/null\n")
        append("  chcon --reference=\"\$t\" \"\$tmp\" 2>/dev/null\n")
        append("fi\n")
        append("mv -f \"\$tmp\" \"\$t\" || { rm -f \"\$tmp\"; exit 5; }\n")
    }

    /**
     * 原子追加脚本：O_APPEND 写入后按「长度 + 尾部 sha256」校验，失败用 truncate 退回原长度。
     * 追加不走「临时文件 + rename」：那要把整个旧文件复制一遍，往大日志尾巴上追加会白翻一倍磁盘。
     */
    private fun atomicAppendScript(path: String, byteCount: Int, digest: String): String = buildString {
        append("t=").append(shellQuote(path)).append('\n')
        append("d=$(dirname \"\$t\"); mkdir -p \"\$d\" || exit 1\n")
        append("r=$(readlink -f \"\$t\" 2>/dev/null)\n")
        append("[ -n \"\$r\" ] && t=\"\$r\"\n")
        append("before=$(stat -c %s \"\$t\" 2>/dev/null || echo 0)\n")
        append("cat >> \"\$t\" || exit 2\n")
        append("after=$(stat -c %s \"\$t\" 2>/dev/null || echo 0)\n")
        append("h=$(tail -c $byteCount \"\$t\" 2>/dev/null | sha256sum | cut -d' ' -f1)\n")
        append("if [ \"\$after\" != \"\$((before + $byteCount))\" ] || [ \"\$h\" != \"$digest\" ]; then\n")
        append("  if truncate -s \"\$before\" \"\$t\" 2>/dev/null; then\n")
        append("    echo \"rolled-back before=\$before after=\$after\"; exit 6\n")
        append("  fi\n")
        append("  echo \"rollback-failed before=\$before after=\$after\"; exit 7\n")
        append("fi\n")
    }

    private fun writeFailureCode(exitCode: Int): String = when (exitCode) {
        1 -> "WRITE_MKDIR_FAILED"
        2 -> "WRITE_STDIN_FAILED"
        3 -> "WRITE_VERIFY_SIZE"
        4 -> "WRITE_VERIFY_HASH"
        5 -> "WRITE_REPLACE_FAILED"
        6 -> "WRITE_VERIFY_FAILED_ROLLED_BACK"
        7 -> "WRITE_VERIFY_FAILED_ROLLBACK_FAILED"
        else -> "WRITE_FAILED"
    }

    private fun writeFailureMessage(path: String, append: Boolean, result: ShellTextResult): String {
        val detail = listOfNotNull(
            result.stderr.trim().takeIf { it.isNotBlank() },
            result.output.trim().takeIf { it.isNotBlank() },
            "exit=${result.exitCode}",
        ).joinToString("；")
        val cause = when (result.exitCode) {
            1 -> "父目录创建失败"
            2 -> "内容没有完整送进目标文件（原文件未改动）"
            3, 4 -> "落盘内容校验不通过，已删除临时文件，原文件未改动"
            5 -> "临时文件就位失败，原文件未改动"
            6 -> "追加后校验不通过，已回退到追加前的长度"
            7 -> "追加后校验不通过，且回退失败，文件尾部可能有残缺字节，请用 read_file 复核"
            else -> if (append) "追加失败" else "写入失败"
        }
        return "写入失败：$path（${if (append) "append" else "overwrite"}）；$cause；$detail"
    }

    private data class ShellTextResult(val exitCode: Int, val output: String, val stderr: String)

    /** 内部调用用的原始执行结果；不做 JSON 包装，由调用方决定怎么用。 */
    data class RawCommandResult(val exitCode: Int, val stdout: String, val stderr: String)
    private data class ShellBytesResult(val exitCode: Int, val output: ByteArray, val stderr: String)
    private data class SessionCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val cwd: String?,
        val timedOut: Boolean
    )

    private class TerminalSession(
        val id: String,
        val identity: String,
        val environment: TerminalEnvironment,
        val rootfsPath: String?,
        var cwd: String,
        val createdAt: Long,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector
    ) {
        val lock = Any()

        @Volatile
        var closed: Boolean = false

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }

    private class AsyncCommand(
        val id: String,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector,
        val command: String,
        val cwd: String,
        val identity: String,
        val environment: TerminalEnvironment,
        val mergeStderr: Boolean,
        val sessionId: String?,
        val startedAt: Long,
        val timeoutMs: Int
    ) {
        @Volatile
        var exitCode: Int? = null

        @Volatile
        var timedOut: Boolean = false

        @Volatile
        var completedAt: Long? = null

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }
}
