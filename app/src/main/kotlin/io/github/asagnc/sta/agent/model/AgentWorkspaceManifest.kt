package io.github.asagnc.sta.agent.model

import java.io.File

/**
 * 工作区清单的纯文本渲染，开局放进系统提示的稳定前缀（见 [AgentPromptBuilder]）。
 *
 * 解决的是「模型起步时对工作区一无所知，只能摸一个读一个」：没有清单时，读哪些文件是每轮
 * 重新决定的，于是步数随文件数线性增长，而步数越多最终用掉多少步越不可预测。
 *
 * 为什么给**全部文件**而不是目录级或按相关性排序的片段：
 * 本仓库 760 个文件、139 个目录，完整清单实测约 40 KB。放进系统提示意味着它在整个 run 内
 * 逐字节不变，因而命中前缀缓存——**一次性成本**，不是每轮成本（注入到最后一条消息才是每轮
 * 全价，那是给每轮都变的内容留的位置）。而"能给全"就绕开了排序问题：静态相关性排序无法把
 * 任务相关文件稳定抬到头部（实测 `AgentPromptBuilder.kt` 在 369 个文件里排第 203），
 * 预置片段既贵又不准。能看全，就不需要猜哪些重要。
 *
 * 目录行给**完整相对路径**、文件名只给基名：最深路径有 11 层，靠缩进重建路径容易出错；
 * 直接给路径则文件名紧跟在所属目录下，拼接即得，没有需要推断的东西。
 */
internal object AgentWorkspaceManifest {
    /** 表头行前缀。 */
    const val HEADER_PREFIX = "工作区文件清单"

    /** 沙箱内的工作区挂载点；宿主上的工作区根挂到这里，模型的命令都跑在这个命名空间。 */
    const val SANDBOX_ROOT = "/workspace"

    /**
     * 渲染的文件数上限。
     *
     * 760 个文件约 40 KB，上限留出量级余量；超过时截断并如实报数——静默截断比漏报更糟，
     * 模型会以为清单是完整的，从而漏掉真实存在的文件。
     */
    const val MAX_FILES = 4000

    /**
     * 扫描时跳过的目录名。
     *
     * `.git` 与构建产物不属于「能读懂的项目结构」，条目量级却比源文件大得多——不跳过的话
     * 清单会被它们挤满，真正要看的源文件反而被截掉。
     */
    val SKIPPED_DIRS = setOf(".git", "build", ".gradle", ".kotlin", ".idea", "node_modules")

    /**
     * 一个目录：[path] 是**相对工作区根**的路径（`/` 分隔，根为空串），
     * [files] 是它的**直接**子文件名。
     *
     * 只要直接子文件，不做递归累计：文件紧跟在所属目录下，路径拼接关系才不会产生歧义。
     */
    data class Dir(val path: String, val files: List<String>)

    /**
     * 扫出目录与文件清单。都以 `listFiles()` 的实际结果为准，不做猜测。
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
            found += Dir(
                path = path,
                files = children.filter { it.isFile }.map { it.name }.sorted(),
            )
            children.asSequence()
                .filter { it.isDirectory && it.name !in SKIPPED_DIRS }
                .sortedBy { it.name }
                .forEach { queue += it to if (path.isEmpty()) it.name else "$path/${it.name}" }
        }
        return found
    }

    /**
     * 渲染成清单文本；没有任何目录时返回 null。
     *
     * 按路径字典序排序即得到先序深度优先的结果（子路径以父路径为前缀，排序必然跟在其后）。
     * 同一份目录集合每次渲染都逐字节相同——这份文本要放进系统提示做缓存前缀，漂移一次就会
     * 让整个前缀失去命中。
     */
    fun render(dirs: List<Dir>, root: String = SANDBOX_ROOT): String? {
        if (dirs.isEmpty()) return null
        val sorted = dirs.sortedBy { it.path }
        var budget = MAX_FILES
        val lines = mutableListOf<String>()
        var omitted = 0
        for (dir in sorted) {
            val shown = dir.files.take(budget)
            omitted += dir.files.size - shown.size
            budget -= shown.size
            lines += "$ITEM_PREFIX${dir.path.ifEmpty { "." }}/"
            shown.forEach { lines += "$ITEM_PREFIX  $it" }
        }
        val header = "$HEADER_PREFIX（根为 $root，${sorted.size} 个目录、" +
            "${sorted.sumOf { it.files.size }} 个文件，缩进两格的文件属于上一行的目录）："
        val tail = if (omitted > 0) listOf("$ITEM_PREFIX…其余 $omitted 个文件略，需要时自行列目录。") else emptyList()
        return (listOf(header) + lines + tail).joinToString("\n")
    }

    /**
     * 条目行前缀：唯一到不可能出现在正常内容里。
     *
     * 清单只在系统提示里出现一次、不参与逐行去重（去重是尾部注入才需要的，见
     * [AgentRequestContext]）；前缀保留是为了让清单块在系统提示里可识别、也便于测试断言。
     */
    const val ITEM_PREFIX = "[file] "
}
