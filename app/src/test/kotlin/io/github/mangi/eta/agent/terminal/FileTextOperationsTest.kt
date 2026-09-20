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
}
