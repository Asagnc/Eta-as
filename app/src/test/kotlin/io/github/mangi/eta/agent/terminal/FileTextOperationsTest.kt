package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTextOperationsTest {
    @Test
    fun `trailing newline does not add a line`() {
        assertEquals(listOf("a", "b"), FileTextOperations.linesOf("a\nb\n"))
        assertEquals(listOf("a", "b"), FileTextOperations.linesOf("a\nb"))
        assertEquals(emptyList<String>(), FileTextOperations.linesOf(""))
        assertEquals(listOf("a", ""), FileTextOperations.linesOf("a\n\n"))
    }

    @Test
    fun `slice renders real line numbers`() {
        val slice = FileTextOperations.sliceLines("one\ntwo\nthree\n", startLine = 2, endLine = 3)

        assertEquals("2\ttwo\n3\tthree", slice.text)
        assertEquals(3, slice.totalLines)
        assertEquals(2, slice.firstLine)
        assertEquals(3, slice.lastLine)
        assertFalse(slice.truncated)
    }

    @Test
    fun `slice without end line reads to the end`() {
        val slice = FileTextOperations.sliceLines("one\ntwo\nthree", startLine = 2, endLine = null)

        assertEquals("2\ttwo\n3\tthree", slice.text)
        assertEquals(3, slice.lastLine)
    }

    @Test
    fun `slice beyond the last line returns empty text`() {
        val slice = FileTextOperations.sliceLines("one\ntwo", startLine = 5, endLine = 8)

        assertEquals("", slice.text)
        assertEquals(2, slice.totalLines)
    }

    @Test
    fun `slice reports truncation when the char budget runs out`() {
        val slice = FileTextOperations.sliceLines("aaaa\nbbbb\ncccc", startLine = 1, endLine = null, maxChars = 12)

        assertEquals("1\taaaa", slice.text)
        assertTrue(slice.truncated)
    }

    @Test
    fun `replace applies a unique match`() {
        val outcome = FileTextOperations.replace("alpha\nbeta\n", "beta", "BETA", replaceAll = false)

        assertTrue(outcome is FileTextOperations.ReplaceOutcome.Applied)
        val applied = outcome as FileTextOperations.ReplaceOutcome.Applied
        assertEquals("alpha\nBETA\n", applied.content)
        assertEquals(1, applied.occurrences)
        assertEquals(2, applied.firstLine)
    }

    @Test
    fun `replace reports a missing match with the line count`() {
        val outcome = FileTextOperations.replace("alpha\nbeta\n", "gamma", "delta", replaceAll = false)

        assertTrue(outcome is FileTextOperations.ReplaceOutcome.NotFound)
        assertEquals(2, (outcome as FileTextOperations.ReplaceOutcome.NotFound).totalLines)
    }

    @Test
    fun `replace refuses ambiguous matches`() {
        val outcome = FileTextOperations.replace("same\nother\nsame\n", "same", "x", replaceAll = false)

        assertTrue(outcome is FileTextOperations.ReplaceOutcome.Ambiguous)
        assertEquals(listOf(1, 3), (outcome as FileTextOperations.ReplaceOutcome.Ambiguous).lines)
    }

    @Test
    fun `replace all rewrites every occurrence`() {
        val outcome = FileTextOperations.replace("same\nother\nsame\n", "same", "x", replaceAll = true)

        assertTrue(outcome is FileTextOperations.ReplaceOutcome.Applied)
        val applied = outcome as FileTextOperations.ReplaceOutcome.Applied
        assertEquals("x\nother\nx\n", applied.content)
        assertEquals(2, applied.occurrences)
    }

    @Test
    fun `replace keeps the file byte for byte when nothing matches`() {
        val original = "line one\nline two\n"
        val outcome = FileTextOperations.replace(original, "missing", "x", replaceAll = false)

        assertTrue(outcome is FileTextOperations.ReplaceOutcome.NotFound)
        assertEquals(original, "line one\nline two\n")
    }

    @Test
    fun `diff preview marks removed and added lines`() {
        val diff = FileTextOperations.diffPreview("a\nb\nc\n", "a\nB2\nc\n")

        assertTrue(diff.contains("- 2\tb"))
        assertTrue(diff.contains("+ 2\tB2"))
        assertTrue(diff.contains("  1\ta"))
    }

    @Test
    fun `diff preview survives line count changes`() {
        val diff = FileTextOperations.diffPreview("a\nb\nc\n", "a\nb\nc\nd\n")

        assertTrue(diff.contains("+ 4\td"))
    }

    @Test
    fun `first difference points at the line that differs`() {
        val description = FileTextOperations.describeFirstDifference("alpha\nbeta\ngamma\n", "alpha\nBETA\ngamma")

        assertTrue(description.contains("第 2 行"))
    }

    @Test
    fun `first difference reports indentation on the first line`() {
        val description = FileTextOperations.describeFirstDifference("    fun main() {\n    }\n", "fun main() {\n}")

        assertTrue(description.contains("第 1 行"))
    }

    @Test
    fun `ambiguity snippet keeps the matched line for every hit`() {
        val content = (1..40).joinToString("\n") { "line $it" }

        val snippet = FileTextOperations.ambiguitySnippet(content, listOf(5, 20, 35))

        assertTrue(snippet.contains("> L5:"))
        assertTrue(snippet.contains("> L20:"))
        assertTrue(snippet.contains("> L35:"))
        assertFalse(snippet.contains("未展示"))
    }

    @Test
    fun `ambiguity snippet clips long lines instead of dropping later hits`() {
        val content = "x".repeat(5_000) + "\nshort\n" + "x".repeat(5_000)

        val snippet = FileTextOperations.ambiguitySnippet(content, listOf(1, 2), radius = 1, maxChars = 1_200)

        assertTrue(snippet.contains("> L1:"))
        assertTrue(snippet.contains("> L2:"))
    }

    @Test
    fun `ambiguity snippet admits hits dropped by the budget`() {
        val content = (1..200).joinToString("\n") { "line $it" }

        val snippet = FileTextOperations.ambiguitySnippet(content, (1..200).toList(), radius = 1, maxChars = 400)

        assertTrue(snippet.contains("未展示"))
    }

    @Test
    fun `clip chars never splits a surrogate pair`() {
        val text = "ab\uD83D\uDE00cd"

        val (clipped, truncated) = FileTextOperations.clipChars(text, 3)

        assertEquals("ab", clipped)
        assertTrue(truncated)
    }

    @Test
    fun `clip chars returns the whole text when it fits`() {
        val (text, truncated) = FileTextOperations.clipChars("abc", 3)

        assertEquals("abc", text)
        assertFalse(truncated)
    }

    @Test
    fun `clip chars reports truncation for an empty budget`() {
        assertEquals("" to true, FileTextOperations.clipChars("abc", 0))
        assertEquals("" to false, FileTextOperations.clipChars("", 0))
    }

    @Test
    fun `directory listing drops the total line and keeps every entry`() {
        val raw = "total 8\ndrwxr-xr-x 2 root root 4096 a\n-rw-r--r-- 1 root root 0 b\n"

        val listing = FileTextOperations.directoryListing(raw, entryCount = 2, maxEntries = 80)

        assertEquals("drwxr-xr-x 2 root root 4096 a\n-rw-r--r-- 1 root root 0 b", listing.text)
        assertEquals(2, listing.entryCount)
        assertFalse(listing.truncated)
    }

    @Test
    fun `directory listing reports truncation from the directory entry count`() {
        val raw = "-rw-r--r-- 1 root root 0 b\n"

        val listing = FileTextOperations.directoryListing(raw, entryCount = 5, maxEntries = 1)

        assertEquals("-rw-r--r-- 1 root root 0 b", listing.text)
        assertEquals(5, listing.entryCount)
        assertTrue(listing.truncated)
    }

    @Test
    fun `directory listing keeps an entry whose name starts with total`() {
        val raw = "total 8\n-rw-r--r-- 1 root root 0 total 5\n"

        val listing = FileTextOperations.directoryListing(raw, entryCount = 1, maxEntries = 80)

        assertEquals("-rw-r--r-- 1 root root 0 total 5", listing.text)
    }

    @Test
    fun `search page skips the offset and reports further pages`() {
        val lines = (1..10).map { "line $it" }

        val first = FileTextOperations.searchPage(lines, offset = 0, limit = 4)
        val last = FileTextOperations.searchPage(lines, offset = 8, limit = 4)

        assertEquals(4, first.lines.size)
        assertTrue(first.hasMore)
        assertEquals(listOf("line 9", "line 10"), last.lines)
        assertFalse(last.hasMore)
    }

    @Test
    fun `search page past the end reports no further page`() {
        val page = FileTextOperations.searchPage(listOf("a", "b"), offset = 5, limit = 2)

        assertTrue(page.lines.isEmpty())
        assertFalse(page.hasMore)
    }

    // ---- planEdits：批量替换的「全成功才写」契约 ----

    @Test
    fun `plan edits accepts a batch where every entry matches`() {
        val plan = FileTextOperations.planEdits(
            requests = listOf(
                edit("a.kt", "foo()", "bar()"),
                edit("b.kt", "foo()", "bar()"),
            ),
            contents = mapOf("a.kt" to "val x = foo()\n", "b.kt" to "val y = foo()\n"),
            loadFailure = { null },
        )

        assertTrue(plan.isComplete)
        assertEquals(2, plan.plan.size)
        assertEquals("val x = bar()\n", plan.plan.first { it.request.path == "a.kt" }.content)
    }

    @Test
    fun `plan edits fails the whole batch when one entry does not match`() {
        val plan = FileTextOperations.planEdits(
            requests = listOf(
                edit("a.kt", "foo()", "bar()"),
                edit("b.kt", "missing()", "bar()"),
            ),
            contents = mapOf("a.kt" to "val x = foo()\n", "b.kt" to "val y = foo()\n"),
            loadFailure = { null },
        )

        assertFalse(plan.isComplete)
        assertEquals(listOf("b.kt"), plan.failures.map { it.path })
        assertEquals("EDIT_NOT_FOUND", plan.failures.single().code)
        // 失败即整批放弃：即便 a.kt 本身校验通过，也不能留下待写条目。
        assertTrue(plan.plan.isEmpty())
    }

    @Test
    fun `plan edits reports ambiguous hits with the replace all hint`() {
        val plan = FileTextOperations.planEdits(
            requests = listOf(edit("a.kt", "foo()", "bar()")),
            contents = mapOf("a.kt" to "foo()\nfoo()\n"),
            loadFailure = { null },
        )

        val failure = plan.failures.single()
        assertEquals("EDIT_NOT_UNIQUE", failure.code)
        assertTrue(failure.message.contains("replace_all=true"))
        assertTrue(failure.message.contains("2 处"))
    }

    @Test
    fun `plan edits stacks repeated edits on the same path`() {
        val plan = FileTextOperations.planEdits(
            requests = listOf(
                edit("a.kt", "one", "two"),
                edit("a.kt", "two", "three"),
            ),
            contents = mapOf("a.kt" to "one\n"),
            loadFailure = { null },
        )

        assertTrue(plan.isComplete)
        assertEquals(1, plan.plan.size)
        assertEquals("three\n", plan.plan.single().content)
    }

    @Test
    fun `plan edits surfaces a load failure as a batch failure`() {
        val plan = FileTextOperations.planEdits(
            requests = listOf(edit("missing.kt", "a", "b")),
            contents = emptyMap(),
            loadFailure = { FileTextOperations.EditFailure(it.path, "PATH_NOT_FOUND", "读取不到文件") },
        )

        assertFalse(plan.isComplete)
        assertEquals("PATH_NOT_FOUND", plan.failures.single().code)
    }

    @Test
    fun `plan edits rejects an empty old text`() {
        val plan = FileTextOperations.planEdits(
            requests = listOf(edit("a.kt", "", "b")),
            contents = mapOf("a.kt" to "content\n"),
            loadFailure = { null },
        )

        assertFalse(plan.isComplete)
        assertEquals("INVALID_ARGUMENT", plan.failures.single().code)
    }

    // ---- joinSections：批量读取的分节与预算共享 ----

    @Test
    fun `join sections labels every file with its path and line count`() {
        val (text, sections) = FileTextOperations.joinSections(
            sections = listOf(
                "a.kt" to slice("1\n2\n", totalLines = 2),
                "b.kt" to slice("3\n", totalLines = 1),
            ),
            budget = 1_000,
        )

        assertTrue(text.contains("=== a.kt（共 2 行）==="))
        assertTrue(text.contains("=== b.kt（共 1 行）==="))
        assertEquals(2, sections.size)
        assertFalse(sections.any { it.truncated })
    }

    @Test
    fun `join sections shares one budget across files`() {
        // 预算只够第一个文件：第二个文件必须被标记截断并给出续读位置，
        // 而不是各自拿到一份完整预算（那等于总量随文件数线性膨胀）。
        val (text, sections) = FileTextOperations.joinSections(
            sections = listOf(
                "a.kt" to slice("first\nsecond\n", totalLines = 2),
                "b.kt" to slice("third\n", totalLines = 1),
            ),
            // A 段头 18 字符 + 首行 6 字符 = 24；再加第二行 7 字符就到 31，会把 B 段头挤掉。
            budget = 30,
        )

        assertTrue(text.contains("=== a.kt"))
        assertTrue(sections[0].truncated)
        assertEquals(2, sections[0].nextStartLine)
        assertTrue(sections[1].truncated)
        assertEquals(1, sections[1].nextStartLine)
    }

    @Test
    fun `join sections marks an unreadable file without dropping the rest`() {
        val (text, sections) = FileTextOperations.joinSections(
            sections = listOf(
                "ok.kt" to slice("body\n", totalLines = 1),
                "huge.kt" to slice("", totalLines = 900_000, truncated = true),
            ),
            budget = 1_000,
        )

        assertTrue(text.contains("=== ok.kt"))
        val huge = sections.single { it.path == "huge.kt" }
        assertTrue(huge.truncated)
        assertEquals(900_000, huge.totalLines)
    }

    private fun edit(path: String, oldText: String, newText: String) =
        FileTextOperations.EditRequest(path = path, oldText = oldText, newText = newText, replaceAll = false)

    private fun slice(text: String, totalLines: Int, truncated: Boolean = false): FileTextOperations.LineSlice {
        val lines = if (text.isEmpty()) emptyList() else text.trimEnd('\n').split('\n')
        return FileTextOperations.LineSlice(
            text = text,
            totalLines = totalLines,
            firstLine = 1,
            lastLine = lines.size,
            truncated = truncated,
        )
    }
}
