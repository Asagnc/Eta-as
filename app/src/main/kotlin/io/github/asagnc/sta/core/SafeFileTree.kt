package io.github.asagnc.sta.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 递归删除，但永不跟随符号链接。
 *
 * 为什么不用 File 自带的递归删除：它靠 listFiles() 展开目录，而 listFiles() 会解析符号链接——
 * 删除对象里只要有一个指向外部的链接目录，删除就会越界到链接目标。Sta 删的是由 apt 与第三方压缩包
 * 解出来的 rootfs、staging 目录，内容不可信，必须先把这条路切断。
 *
 * 符号链接按链接处理：只删链接本身，不动它指向的东西。根目录自身是链接时同理。
 */
internal fun deleteTreeSafely(root: File): Boolean {
    val path = root.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
    var failed = false
    try {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!Files.deleteIfExists(file)) failed = true
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    failed = true
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) {
                        failed = true
                        return FileVisitResult.CONTINUE
                    }
                    if (!Files.deleteIfExists(dir)) failed = true
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (_: IOException) {
        failed = true
    }
    return !failed
}
