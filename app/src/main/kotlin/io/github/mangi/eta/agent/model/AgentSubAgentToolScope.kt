package io.github.mangi.eta.agent.model

import org.json.JSONObject

/**
 * 写权限子智能体的隔离工作区（git worktree）描述。
 *
 * [repoPath] 是主工作区（子智能体不许直接改它），[worktreePath] 是子智能体的可写目录。
 * 两个路径都用宿主原生形态（Linux 环境写 `/workspace/...`），因为文件工具与终端
 * 各自会把 `/workspace` 映射到自己的命名空间，这里不需要关心。
 */
internal data class SubAgentWorkspace(
    val repoPath: String,
    val worktreePath: String,
)

/**
 * 把子智能体的工具参数收进它的 worktree。
 *
 * 只靠 system prompt 叮嘱是不够的：模型天然会写相对路径（`app/src/...`），
 * 而相对路径在宿主侧默认落到主工作区——那就等于隔离没做。所以这里在**参数层**强制收口：
 * 文件类工具重写 `path`，命令类工具重写 `cwd` 并拒绝提及主工作区的命令。
 *
 * 纯字符串处理，不碰文件系统，可在 JVM 单测里逐条验证。
 */
internal object AgentSubAgentToolScope {

    /** 这些工具用 `path` 参数指向目标文件或目录。 */
    private val PATH_TOOLS = setOf(
        "read_file",
        "write_file",
        "edit_file",
        "list_directory",
        "search_code",
        "find_files",
    )

    /** 这些工具用 `cwd` 表示工作目录，并且能执行任意命令。 */
    private val COMMAND_TOOLS = setOf("terminal", "run_command")

    /** 写入类工具：解析后的路径必须落在 worktree 内，否则拒绝。 */
    private val WRITE_TOOLS = setOf("write_file", "edit_file")

    const val CODE_ESCAPE = "SUB_AGENT_WORKSPACE_ESCAPE"
    const val CODE_BAD_ARGUMENTS = "SUB_AGENT_BAD_ARGUMENTS"

    sealed interface Scoped {
        /** 重写后的参数。 */
        data class Ok(val argumentsJson: String) : Scoped

        /** 越界或参数不可解析，直接作为工具结果回给子智能体。 */
        data class Rejected(val code: String, val message: String) : Scoped
    }

    fun scope(name: String, argumentsJson: String, workspace: SubAgentWorkspace): Scoped {
        if (name !in PATH_TOOLS && name !in COMMAND_TOOLS) return Scoped.Ok(argumentsJson)
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return Scoped.Rejected(CODE_BAD_ARGUMENTS, "工具参数不是合法 JSON")
        if (name in PATH_TOOLS) {
            val raw = args.optString("path")
            val mapped = mapPath(raw, workspace)
            if (name in WRITE_TOOLS && !isInsideWorktree(mapped, workspace)) {
                return Scoped.Rejected(
                    CODE_ESCAPE,
                    "写入目标必须在你的隔离工作区 ${workspace.worktreePath} 内；" +
                        "主工作区 ${workspace.repoPath} 不接受改动。当前路径：$raw",
                )
            }
            args.put("path", mapped)
            return Scoped.Ok(args.toString())
        }
        val command = args.optString("command")
        if (mentionsRepo(command, workspace)) {
            return Scoped.Rejected(
                CODE_ESCAPE,
                "命令里不能出现主工作区 ${workspace.repoPath}；" +
                    "你的工作区是 ${workspace.worktreePath}，用相对路径或该前缀。",
            )
        }
        val rawCwd = args.optString("cwd").trim()
        if (rawCwd.isNotEmpty() && mentionsRepo(rawCwd, workspace)) {
            return Scoped.Rejected(
                CODE_ESCAPE,
                "cwd 不能指向主工作区 ${workspace.repoPath}；你的工作区是 ${workspace.worktreePath}。",
            )
        }
        args.put("cwd", if (rawCwd.isEmpty()) workspace.worktreePath else mapPath(rawCwd, workspace))
        return Scoped.Ok(args.toString())
    }

    /**
     * 相对路径挂到 worktree 下；主工作区路径映射到 worktree 里的同名位置；
     * 其它绝对路径原样保留（读别处无害，写别处由 [isInsideWorktree] 拦）。
     */
    fun mapPath(raw: String, workspace: SubAgentWorkspace): String {
        val path = raw.trim()
        if (path.isEmpty()) return workspace.worktreePath
        if (!path.startsWith("/")) {
            val base = workspace.worktreePath.trimEnd('/')
            return "$base/${path.removePrefix("./")}"
        }
        val repo = workspace.repoPath.trimEnd('/')
        if (path == repo) return workspace.worktreePath
        if (path.startsWith("$repo/")) return workspace.worktreePath.trimEnd('/') + path.removePrefix(repo)
        return path
    }

    /**
     * 是否落在 worktree 内。同时接受 Linux 形态与 Android 形态：
     * 文件工具会把 `/workspace/x` 归一成 `/data/local/tmp/eta/x`，两种写法都要认，
     * 否则子智能体按提示写的路径会被误判成越界。
     */
    fun isInsideWorktree(path: String, workspace: SubAgentWorkspace): Boolean {
        val target = normalize(path)
        val roots = listOf(workspace.worktreePath, androidForm(workspace.worktreePath))
        return roots.any { root ->
            val base = normalize(root).trimEnd('/')
            target == base || target.startsWith("$base/")
        }
    }

    /** 命令文本是否提及主工作区。worktree 在仓库同级且带独立前缀，不会误命中。 */
    fun mentionsRepo(command: String, workspace: SubAgentWorkspace): Boolean {
        val repo = normalize(workspace.repoPath).trimEnd('/')
        val candidates = listOf(repo, androidForm(repo))
        return candidates.any { candidate ->
            candidate.isNotBlank() && command.contains(candidate)
        }
    }

    /** `/workspace/...` 在 Android 侧是 `/data/local/tmp/eta/...`，两种形态互认。 */
    private fun androidForm(path: String): String {
        val normalized = normalize(path)
        return if (normalized == LINUX_WORKSPACE) ANDROID_WORKSPACE
        else if (normalized.startsWith("$LINUX_WORKSPACE/")) {
            ANDROID_WORKSPACE + normalized.removePrefix(LINUX_WORKSPACE)
        } else {
            normalized
        }
    }

    /** 去掉重复斜杠与结尾斜杠，避免 `//` 造成的假越界。 */
    private fun normalize(path: String): String {
        val collapsed = path.trim().replace(Regex("/{2,}"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }

    private const val LINUX_WORKSPACE = "/workspace"
    private const val ANDROID_WORKSPACE = "/data/local/tmp/eta"
}
