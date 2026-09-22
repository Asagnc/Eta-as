package io.github.asagnc.sta.agent.device

import java.io.File

/**
 * su 二进制的定位策略。这是全工程唯一的 su 路径来源，避免各处自行拼 PATH 造成行为分叉。
 *
 * 为什么不能只写 `ProcessBuilder("su", ...)`：
 * 基于 KernelPatch 的 root 方案（APatch / FolkPatch）只对授权名单内的进程暴露 su，
 * 名单外的进程连 `stat` 都拿不到结果。当 `Runtime.exec` 走 PATH 查找（execvp 语义）时，
 * 内核对候选路径逐个判定，命中隐藏即返回 ENOENT —— 表现为 "su: inaccessible or not found"，
 * 即使该应用其实已在 /data/adb/ap/package_config 里被授权。
 * 直接以绝对路径 execve 走的是另一条判定路径（由 root 方案的 execve hook 放行），可以正常提权。
 *
 * 因此候选顺序是：先绝对路径（root 方案标准安装位置），最后才回退裸名交给 PATH。
 */
internal object SuBinary {
    /** root 方案的标准 su 安装位置，按可信度排序。 */
    val absoluteCandidates: List<String> = listOf(
        "/system/bin/su", // Magisk / APatch / KernelSU 的常规注入点
        "/system/xbin/su", // 旧式布局
        "/debug_ramdisk/su", // Magisk 4.x 起的 arm64 场景
        "/data/adb/ksu/bin/su", // KernelSU
        "/data/adb/ap/bin/su", // APatch / FolkPatch
    )

    /** 探测时额外遍历的目录；这些目录不一定出现在 App 进程自己的 PATH 里。 */
    val extraSearchDirs: List<String> = listOf(
        "/system/bin",
        "/system/xbin",
        "/sbin",
        "/debug_ramdisk",
        "/data/adb/ksu/bin",
        "/data/adb/ap/bin",
    )

    /** PATH 查找的最终回退值；只有在所有绝对路径都不存在时才使用。 */
    const val FALLBACK_NAME: String = "su"

    /**
     * 解析可执行的 su 路径。优先返回存在的绝对路径，否则回退 [FALLBACK_NAME] 交给 PATH。
     * [exists] 可注入，便于单测；默认只判断 isFile —— 不使用 canExecute()，
     * 因为部分 root 方案给 su 设了自定义 SELinux 类型，access(X_OK) 会失败。
     */
    fun resolve(exists: (String) -> Boolean = { File(it).isFile }): String =
        absoluteCandidates.firstOrNull(exists) ?: FALLBACK_NAME

    /**
     * 判断系统上是否存在 su。同时考虑 App 进程的 PATH 与 root 方案的标准目录；
     * 只看 getenv("PATH") 会漏掉 su 只装在 /data/adb/{ap,ksu}/bin 的配置。
     */
    fun exists(
        pathEnv: String? = System.getenv("PATH"),
        fileExists: (String) -> Boolean = { File(it).isFile },
    ): Boolean {
        if (absoluteCandidates.any(fileExists)) return true
        val fromPath = pathEnv.orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
        return (fromPath + extraSearchDirs).any { dir -> fileExists(File(dir, FALLBACK_NAME).path) }
    }
}