package io.github.mangi.eta.agent.runtime

import android.content.Context
import java.io.File

/**
 * 子智能体轨迹落盘。
 *
 * 子智能体的过程默认不进主上下文（那正是它省 token 的方式），代价是主 loop 只能看到一段摘要、
 * 无法核验。落盘把「信任」变成「可追溯」：主 loop 需要抽查时读文件，用户也能自己翻。
 *
 * 放在应用私有目录而不是工作区：工作区属于用户的项目，轨迹是 Eta 自己的运行记录，
 * 混进去会污染 git status。
 */
internal object AgentSubAgentTraceStore {

    /** 保留份数。轨迹是审计与抽查用的旁路记录、不是产出本身，无限增长没有意义。 */
    private const val KEEP = 200

    private const val DIR = "subagent-traces"

    /** 写失败直接吞掉：落盘失败不该影响委派结果。 */
    fun write(context: Context, name: String, content: String) {
        runCatching {
            val dir = File(context.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, "${System.currentTimeMillis()}-$name.txt").writeText(content)
            prune(dir)
        }
    }

    /** 按修改时间留最近 [KEEP] 份。 */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}
