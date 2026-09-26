package io.github.asagnc.sta.agent.model

import java.io.File

/**
 * 工作区目录拓扑的纯文本渲染，用于开局随请求注入（见 [AgentRequestContext]）。
 *
 * 解决的是「模型起步时对工作区一无所知，只能摸一个读一个」：没有拓扑时，读哪些文件是每轮
 * 重新决定的，于是步数随文件数线性增长，而步数越多最终用掉多少步越不可预测。拓扑把「有哪些
 * 目录」变成开局已知，模型据此定位后再一次性取用。
 *
 * 为什么只到目录级、不给文件清单：本仓库有 633 个 Kotlin 文件、16k+ 声明，全量文件清单约
 * 62 KB。而目录只有 139 个、约 4 KB，且目录数增长远慢于文件数。文件级细节按需现取——静态
 * 排序无法把任务相关文件稳定抬到头部（实测 `AgentPromptBuilder.kt` 在 369 个文件里排第 203），
 * 预置它既贵又不准。
 *
 * 注入在**最后一条消息**上且每轮重写，所以每一行都要能按前缀识别（否则重复注入会叠加）：
 * 表头与条目各一个前缀，见 [HEADER_PREFIX] 与 [ITEM_PREFIX]。
 */
internal object AgentWorkspaceManifest {
    /** 表头行前缀：重复注入时按它整行剥掉。 */
    const val HEADER_PREFIX = "工作区目录拓扑"

    /** 条目行前缀：唯一到不可能出现在正常内容里，去重时按它逐行剥掉。 */
    const val ITEM_PREFIX = "[tree] "

    /** 沙箱内的工作区挂载点；宿主上的工作区根挂到这里，模型的命令都跑在这个命名空间。 */
    const val SANDBOX_ROOT = "/workspace"

    /**
     * 渲染的目录数上限。
     *
     * 139 个目录约 4 KB，上限留出余量；超过时截断并如实报数——静默截断比漏报更糟，
     * 模型会以为拓扑是完整的，从而漏掉真实存在的目录。
     */
    const val MAX_DIRS = 240

    /**
     * 扫描时跳过的目录名。
     *
     * `.git` 与构建产物不属于「能读懂的项目结构」，条目量级却比源文件大得多——不跳过的话
     * 拓扑会被它们挤满，真正要看的包反而被截掉。
     */
    val SKIPPED_DIRS = setOf(".git", "build", ".gradle", ".kotlin", ".idea", "node_modules")

    /**
     * 一个目录：[path] 是**相对工作区根**的路径（`/` 分隔，根为空串），
     * [fileCount] 是它**直接**子文件数。
     *
     * 只要直接子文件数、不做递归累计：累计数会掩盖「文件集中在这一层」，而那正是决定
     * 「该对哪个目录取符号清单」的依据。
     */
    data class Dir(val path: String, val fileCount: Int)

    /**
     * 扫出目录拓扑。目录与文件都以 `listFiles()` 的实际结果为准，不做猜测。
     *
     * [host] 是宿主上的工作区根（设备上为 `/data/local/tmp/sta`）。产出的路径相对该根，
     * 因为模型在沙箱里发的命令用相对路径——绝对路径是 `/workspace/...`，两者是同一份内容
     * 的不同命名空间，写死在条目里会让模型拿到用不了的路径。
     */
    fun scan(host: File): List<Dir> {
        if (!host.isDirectory) return emptyList()
        val found = mutableListOf<Dir>()
        val queue = ArrayDeque<Pair<File, String>>()
        queue += host to ""
        while (queue.isNotEmpty()) {
            val (dir, path) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            found += Dir(path = path, fileCount = children.count { it.isFile })
            children.asSequence()
                .filter { it.isDirectory && it.name !in SKIPPED_DIRS }
                .sortedBy { it.name }
                .forEach { queue += it to if (path.isEmpty()) it.name else "$path/${it.name}" }
        }
        return found
    }

    /**
     * 渲染成注入文本；没有任何目录时返回 null。
     *
     * 按路径字典序排序即得到先序深度优先的结果（子路径以父路径为前缀，排序必然跟在其后），
     * 因此同一份目录集合每次渲染都逐字节相同。注入内容在同一 run 内必须稳定，否则会毁掉
     * 请求前缀缓存——那正是这个机制唯一依赖的前提。
     */
    fun render(dirs: List<Dir>, root: String = SANDBOX_ROOT): String? {
        if (dirs.isEmpty()) return null
        val sorted = dirs.sortedBy { it.path }
        val shown = sorted.take(MAX_DIRS)
        val omitted = sorted.size - shown.size
        val lines = buildList {
            add("$HEADER_PREFIX（根为 $root，共 ${dirs.size} 个目录，括号内为该目录直接子文件数）：")
            shown.forEach { dir ->
                val depth = if (dir.path.isEmpty()) 0 else dir.path.count { it == '/' } + 1
                // 根目录没有目录名，用一个点表示；给它拼 `/` 会渲染成 `./`，既难看也不是路径。
                val name = dir.path.substringAfterLast('/')
                val label = if (name.isEmpty()) "." else "$name/"
                add("$ITEM_PREFIX${"  ".repeat(depth)}$label (${dir.fileCount})")
            }
            if (omitted > 0) {
                add("$ITEM_PREFIX…其余 $omitted 个目录略，需要时自行列目录。")
            }
        }
        return lines.joinToString("\n")
    }
}
