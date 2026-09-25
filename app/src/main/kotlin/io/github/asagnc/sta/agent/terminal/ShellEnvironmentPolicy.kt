package io.github.asagnc.sta.agent.terminal

/**
 * 子进程环境变量的清洗策略。
 *
 * 为什么要有：模型驱动的 shell 原样继承本进程环境，`env`、`printenv` 或带 `set -x` 的脚本都能把
 * 变量原样打进工具输出，再进模型上下文、进而进远端服务。用户在自己的 Linux 环境里 export 过 token
 * 是常见情况，继承一次就等于长期泄漏。
 *
 * 做法：默认剔除「名字像凭据」的变量，其余（PATH、HOME、LANG、ANDROID_ 与 PROOT_ 前缀的变量…）
 * 原样保留。用黑名单
 * 而不是白名单，是因为白名单会静默砍掉用户脚本依赖的未知变量，那个代价比漏掉一个名字更大。
 *
 * 需要放行时由用户在自己的环境里显式声明——模型看不到也改不了这个变量，因此无法用它绕过清洗：
 * - `STA_KEEP_ENV=NAME1,NAME2` 放行指定变量
 * - `STA_KEEP_ENV=all` 完全放行
 */
internal object ShellEnvironmentPolicy {
    internal const val KEEP_ENV_VARIABLE = "STA_KEEP_ENV"

    private val sensitiveNamePatterns = listOf(
        Regex("(?i)token"),
        Regex("(?i)secret"),
        Regex("(?i)password"),
        Regex("(?i)passwd"),
        Regex("(?i)credential"),
        Regex("(?i)api[_-]?key"),
        Regex("(?i)private[_-]?key"),
        Regex("(?i)(^|[_-])key$"),
        Regex("(?i)auth"),
    )

    /** 名字里带 auth/key 但不是凭据本身：误删会直接弄坏用户环境里的正常功能。 */
    private val safeNames = setOf(
        "SSH_AUTH_SOCK",
        "SSH_AGENT_PID",
        "GPG_AGENT_INFO",
        "XDG_SESSION_TYPE",
    )

    /**
     * 就地清洗 [environment]，返回被剔除的变量名（已排序，便于留痕与测试断言）。
     */
    internal fun sanitize(environment: MutableMap<String, String>): List<String> {
        val keepAll = environment[KEEP_ENV_VARIABLE]?.trim()?.equals("all", ignoreCase = true) == true
        val explicit = environment[KEEP_ENV_VARIABLE]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        val removed = environment.keys
            .filter { name ->
                name != KEEP_ENV_VARIABLE &&
                    name !in safeNames &&
                    name !in explicit &&
                    !keepAll &&
                    sensitiveNamePatterns.any { pattern -> pattern.containsMatchIn(name) }
            }
            .sorted()
        removed.forEach { environment.remove(it) }
        // 控制变量本身不进子进程：它是给本层看的，传给用户命令只会多一个莫名其妙的环境变量。
        // 不计入返回的 removed，因为它不是凭据。
        environment.remove(KEEP_ENV_VARIABLE)
        return removed
    }
}
