package io.github.asagnc.eta.agent.tool

import org.json.JSONObject

/**
 * `pkill -f <模式>` / `killall` 会把命令自己一起带走：shell 的 cmdline 里就含着那段模式，
 * 于是命令刚执行就收到 SIGTERM，结果只剩「退出码 143 · Terminated」，看不出是谁杀的。
 *
 * 这里按「命令里带 pkill/killall + 退出码是信号终止」补一句**可能性**提示（不是结论）。
 * 放在工具层而不是控制器：一次性执行与 session 复用是控制器里两个不同的结果构造点，
 * 在工具层补一次就都覆盖到了。
 */
internal object SelfKillHint {
    /** 会按 `-f` 匹配整条命令行的工具。 */
    private val TOOLS = Regex("\\b(pkill|killall)\\b")

    /** 命中时给结果补 `hint` 字段；任何解析失败都原样返回。 */
    fun append(command: String, result: String): String {
        val hint = hintFor(command, result) ?: return result
        return runCatching { JSONObject(result).put("hint", hint).toString() }.getOrDefault(result)
    }

    /** 需要提示时返回提示文本，否则 null（供单测直接断言）。 */
    fun hintFor(command: String, result: String): String? {
        if (command.isBlank() || !TOOLS.containsMatchIn(command)) return null
        val json = runCatching { JSONObject(result) }.getOrNull() ?: return null
        val exitCode = json.optInt("exit_code", 0)
        if (exitCode != 143 && exitCode != 137) return null
        if (json.has("hint")) return null
        return "命令被信号终止（exit=$exitCode）。pkill/killall -f 的模式会匹配到命令自身的命令行" +
            "（shell 的 cmdline 里就含这段模式），最常见的原因就是它把自己杀掉了；" +
            "改成不自匹配的写法：先 `pgrep -f 'pat[t]ern'` 取到 pid 再 kill。"
    }
}
