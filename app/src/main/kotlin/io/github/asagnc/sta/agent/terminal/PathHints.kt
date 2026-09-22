package io.github.asagnc.sta.agent.terminal

import java.io.File

/**
 * 路径不存在时的可操作提示：给出最近的可用父目录及其子项，让调用方一轮就能改对路径，
 * 而不是拿到上游的原始 IO 报错（rg 的 "IO error for operation on ..."、find 的
 * "No such file or directory"）反复换路径试。
 *
 * 两条通道共用同一份文案与解析：[message] 是纯函数，root 通道用 [probeScript] 在 shell 侧
 * 探测（App 进程看不到的 /data/data 私有目录也能给出准确提示），无 root 通道用
 * [missingPathMessage] 直接查 File。
 */
internal object PathHints {
    const val MAX_ENTRIES = 12

    /** 路径存在时返回 null。无 root 通道用：只能看见 App 进程有权访问的目录。 */
    fun missingPathMessage(path: File): String? {
        if (path.exists()) return null
        var parent = path.parentFile
        while (parent != null && !parent.exists()) parent = parent.parentFile
        val entries = parent
            ?.listFiles()
            ?.sortedBy { it.name }
            ?.take(MAX_ENTRIES)
            ?.map { it.name + if (it.isDirectory) "/" else "" }
            .orEmpty()
        return message(path.path, parent?.path, entries)
    }

    /** 统一文案。nearest 为空表示连根目录都没探到。 */
    fun message(path: String, nearest: String?, entries: List<String>): String {
        val message = StringBuilder("路径不存在：").append(path)
        if (nearest.isNullOrBlank()) return message.toString()
        message.append("。最近可用的是 ").append(nearest)
        if (entries.isNotEmpty()) {
            message.append("，其下：")
            message.append(entries.take(MAX_ENTRIES).joinToString("、"))
        }
        return message.toString()
    }

    /**
     * shell 侧探测脚本：逐级向上找到第一个存在的父目录，输出一行 `NEAREST=<路径>`，
     * 随后是该目录下的条目（`ls -F` 给目录补 `/`、给可执行文件补 `*`）。
     * 用 `[ -e ]` 而不是 `-d`：文件路径写错时也要能停在文件所在的目录上。
     */
    fun probeScript(path: String): String = buildString {
        append("p=").append(shellQuoted(path)).append('\n')
        append("while [ ! -e \"\$p\" ] && [ \"\$p\" != \"/\" ] && [ \"\$p\" != \".\" ]; do p=$(dirname \"\$p\"); done\n")
        append("echo \"NEAREST=\$p\"\n")
        append("ls -AF \"\$p\" 2>/dev/null | head -n ").append(MAX_ENTRIES).append('\n')
    }

    data class Probe(val nearest: String?, val entries: List<String>)

    /**
     * 解析 [probeScript] 的输出。首行固定是 `NEAREST=`，其余是目录条目；
     * 万一脚本没跑起来（首行不是 NEAREST），把全部输出当作条目，至少别把信息丢掉。
     */
    fun parseProbe(output: String): Probe {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val head = lines.firstOrNull()
        val hasNearest = head != null && head.startsWith("NEAREST=")
        val nearest = if (hasNearest) head?.removePrefix("NEAREST=")?.takeIf { it.isNotBlank() } else null
        return Probe(nearest = nearest, entries = if (hasNearest) lines.drop(1) else lines)
    }

    private fun shellQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
