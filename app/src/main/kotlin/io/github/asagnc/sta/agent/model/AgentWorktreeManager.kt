package io.github.asagnc.sta.agent.model

import java.io.File

/**
 * 写权限子智能体的隔离工作区（git worktree）。
 *
 * 为什么必须隔离：子智能体与主智能体各自基于同一份快照读、再各自写回，后写的会静默覆盖前者
 * （TOCTOU）。worktree 让子智能体在自己的检出目录里改文件，主智能体先看 diff 再决定是否合并，
 * 把"并发写"降级成"先隔离、后合并"。
 *
 * 这里只做命令拼装与路径校验，不直接执行——执行交给宿主已有的 terminal 能力
 * （`RootShellTerminalController.runCommand(cwd = ..., environment = "linux")`），
 * 这样 Android 侧不需要内置 git 二进制，每一行命令也能在单测里验证。
 */
internal object AgentWorktreeManager {

    /** worktree 放在源仓库同级，名字带固定前缀，便于一眼看出归属、能安全批量清理。 */
    const val DIRECTORY_PREFIX = "sta-worktree-"

    /** 一次 run 内最多同时存在的 worktree 数；超了先回收最旧的，避免悄悄吃掉用户存储。 */
    const val MAX_LIVE_WORKTREES = 3

    /**
     * 子智能体在 worktree 里要用的相对路径。`local.properties` 被 gitignore，
     * 新 worktree 不会有它，缺了它 Gradle 找不到 SDK、子智能体就没法自验证。
     */
    const val LOCAL_PROPERTIES = "local.properties"

    /**
     * 把 worktree 名限制成安全字符：既防路径穿越，也防拼进 shell 时被当成参数。
     * 保留中文会被 shell 引号处理，索性统一降级成 `-`。
     */
    fun sanitizeToken(raw: String): String {
        val cleaned = raw.trim().lowercase().map { char ->
            when {
                char in 'a'..'z' || char in '0'..'9' -> char
                char == '-' || char == '_' -> char
                else -> '-'
            }
        }.joinToString("")
        val collapsed = cleaned.replace(Regex("-{2,}"), "-").trim('-')
        return collapsed.take(MAX_TOKEN_CHARS).ifBlank { DEFAULT_TOKEN }
    }

    /** worktree 绝对路径：`<仓库父目录>/sta-worktree-<token>`。 */
    fun worktreePath(repoRoot: String, token: String): String {
        val parent = File(repoRoot).absoluteFile.parentFile?.absolutePath
            ?: error("无法解析仓库父目录：$repoRoot")
        return "$parent/$DIRECTORY_PREFIX${sanitizeToken(token)}"
    }

    /**
     * 创建 worktree。用 `--detach` 而不是分支：子智能体的产出通过 diff 交回，
     * 不需要在仓库里留下分支引用，也就不会污染用户的分支列表。
     *
     * 分两步（`--no-checkout` 再 `checkout`）而不是一条 `worktree add`：
     * 大仓库首次检出很慢，拆开后超时可以落在"检出"这一步，错误信息能指明是哪一步。
     */
    fun createCommands(repoRoot: String, worktreePath: String, baseRef: String): List<String> = listOf(
        "git -C ${shellQuote(repoRoot)} worktree add --detach --no-checkout " +
            "${shellQuote(worktreePath)} ${shellQuote(baseRef)}",
        "git -C ${shellQuote(worktreePath)} checkout --detach ${shellQuote(baseRef)}",
    )

    /**
     * 清理所有托管 worktree。
     *
     * 放在 run 结束时统一调用（而不是子智能体一返回就删）：保留期间主智能体可以随时
     * 回去看改动细节，否则刚回传的 `--stat` 指向的目录已经不存在，等于给了一个死链接。
     *
     * 用 `worktree list --porcelain` 拿准确路径再逐个 remove，而不是 `rm -rf` 通配：
     * 万一前缀撞上用户自己的目录，删的是别人的东西。
     */
    fun listManagedWorktreesCommand(repoRoot: String): String =
        "git -C ${shellQuote(repoRoot)} worktree list --porcelain"

    /** 回收命令：逐个移除，最后一个 `prune` 收尾清掉注册信息。 */
    fun cleanupCommands(repoRoot: String, worktreePaths: List<String>): List<String> =
        worktreePaths.map { path ->
            "git -C ${shellQuote(repoRoot)} worktree remove --force ${shellQuote(path)}"
        } + "git -C ${shellQuote(repoRoot)} worktree prune"

    /** 从 `worktree list --porcelain` 的输出里挑出托管的 worktree 路径。 */
    fun parseManagedWorktrees(repoRoot: String, porcelainOutput: String): List<String> =
        porcelainOutput.lineSequence()
            .filter { line -> line.startsWith("worktree ") }
            .map { line -> line.removePrefix("worktree ").trim() }
            .filter { path -> isManagedWorktree(repoRoot, path) }
            .toList()

    /**
     * 本次该回收哪些 worktree。
     *
     * 调用方马上就要新建一个，所以这里保留最新的 [limit] - 1 个，其余交给调用方回收。
     * `git worktree list --porcelain` 按登记顺序输出，也就是创建顺序，因此 dropLast 留下的正是
     * 最近几次。抽成纯函数是为了能用单测把「谁先被回收」这条规则固定下来。
     */
    fun worktreesToRecycle(
        repoRoot: String,
        porcelainOutput: String,
        limit: Int = MAX_LIVE_WORKTREES,
    ): List<String> {
        val managed = parseManagedWorktrees(repoRoot, porcelainOutput)
        return managed.dropLast((limit - 1).coerceAtLeast(0))
    }

    /**
     * 把被 gitignore 的本地配置复制进 worktree。`cp -n` 不覆盖已存在文件，
     * 重复调用幂等；源文件不存在时 `|| true` 让流程继续（没有它也能跑，只是构建会报缺 SDK）。
     */
    fun bootstrapCommands(repoRoot: String, worktreePath: String): List<String> = listOf(
        "cp -n ${shellQuote("$repoRoot/$LOCAL_PROPERTIES")} " +
            "${shellQuote("$worktreePath/$LOCAL_PROPERTIES")} || true",
    )

    /**
     * 改动摘要：只统计"改了哪些文件、增删多少行"。
     * 不把整份 diff 塞进主上下文——那会把子智能体省下的 token 又还回去。
     */
    fun diffStatCommand(worktreePath: String): String =
        "git -C ${shellQuote(worktreePath)} --no-pager diff --stat HEAD"

    /** 回收：先删 worktree 注册信息，再删目录（`--force` 处理"有未提交改动"的情况）。 */
    fun removeCommands(repoRoot: String, worktreePath: String): List<String> = listOf(
        "git -C ${shellQuote(repoRoot)} worktree remove --force ${shellQuote(worktreePath)}",
        "git -C ${shellQuote(repoRoot)} worktree prune",
    )

    /**
     * 把 worktree 里的改动应用到主工作区。
     *
     * 用 `git apply --3way` 而不是 `git merge`：worktree 是 detached、没有分支可合；
     * 补丁应用失败时主工作区保持原样（不会产生半合并状态），失败信息也能原样交给主智能体。
     */
    fun applyCommand(repoRoot: String, worktreePath: String): String =
        "git -C ${shellQuote(worktreePath)} --no-pager diff HEAD | " +
            "git -C ${shellQuote(repoRoot)} apply --3way"

    /** 路径校验：只接受 DIRECTORY_PREFIX 开头、且确实位于仓库父目录下的目录。 */
    fun isManagedWorktree(repoRoot: String, candidate: String): Boolean {
        val parent = File(repoRoot).absoluteFile.parentFile?.absolutePath ?: return false
        val file = File(candidate).absoluteFile
        return file.parent == parent && file.name.startsWith(DIRECTORY_PREFIX)
    }

    /**
     * 单引号包裹，内部单引号用 `'\''` 转义——POSIX shell 通用写法，
     * 避免路径里的空格、`$`、反引号被解释。
     */
    internal fun shellQuote(raw: String): String =
        "'" + raw.replace("'", "'\\''") + "'"

    private const val DEFAULT_TOKEN = "run"
    private const val MAX_TOKEN_CHARS = 40
}
