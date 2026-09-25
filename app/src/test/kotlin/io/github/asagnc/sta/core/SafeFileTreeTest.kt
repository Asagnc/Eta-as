package io.github.asagnc.sta.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeFileTreeTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun symlinkIsUnlinkedInsteadOfFollowed() {
        val outside = temp.newFolder("outside")
        val survivor = File(outside, "keep.txt").apply { writeText("链接目标里的文件，不该被删") }
        val tree = temp.newFolder("tree")
        File(tree, "inner.txt").writeText("树里的文件，应该被删")
        Files.createSymbolicLink(File(tree, "link").toPath(), outside.toPath())

        assertTrue(deleteTreeSafely(tree))
        assertFalse("树本身要被删掉", tree.exists())
        assertTrue("链向的目录必须完好，否则就是删穿了", survivor.exists())
        assertTrue(survivor.readText().isNotEmpty())
    }

    @Test
    fun removesNestedDirectories() {
        val tree = temp.newFolder("nested")
        File(tree, "a/b/c").mkdirs()
        File(tree, "a/b/c/deep.txt").writeText("x")

        assertTrue(deleteTreeSafely(tree))
        assertFalse(tree.exists())
    }

    @Test
    fun missingPathCountsAsSuccess() {
        val missing = File(temp.root, "does-not-exist")

        assertTrue(deleteTreeSafely(missing))
    }
}
