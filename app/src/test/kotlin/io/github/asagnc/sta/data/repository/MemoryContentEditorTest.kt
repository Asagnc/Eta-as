package io.github.asagnc.sta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑测试：不依赖 Robolectric，本地（aarch64）与 CI 都会跑。 */
class MemoryContentEditorTest {
    @Test
    fun upsertReplacesTheMatchingSectionInPlace() {
        val edit = MemoryContentEditor.upsertSection(
            content = "# 核心记忆\n旧偏好\n## 项目\n旧项目\n## 其他\n保留",
            heading = "项目",
            body = "新项目\n第二行",
        )

        assertEquals("# 核心记忆\n旧偏好\n## 项目\n新项目\n第二行\n## 其他\n保留", edit.content)
        assertEquals("## 项目", edit.section?.heading)
        assertTrue(edit.section?.replaced == true)
        assertEquals(3, edit.section?.startLine)
        assertEquals(5, edit.section?.endLine)
    }

    @Test
    fun upsertMatchesTitleIgnoringLevelAndKeepsOriginalHeading() {
        val edit = MemoryContentEditor.upsertSection("# 核心记忆\n旧偏好", "## 核心记忆", "新偏好")

        assertEquals("# 核心记忆\n新偏好", edit.content)
        assertEquals("# 核心记忆", edit.section?.heading)
        assertEquals(1, edit.section?.startLine)
        assertEquals(2, edit.section?.endLine)
    }

    @Test
    fun upsertCreatesMissingSectionAtTheEnd() {
        val edit = MemoryContentEditor.upsertSection("# 核心记忆\n新偏好", "新主题", "内容")

        assertEquals("# 核心记忆\n新偏好\n\n## 新主题\n内容", edit.content)
        assertEquals("## 新主题", edit.section?.heading)
        assertTrue(edit.section?.replaced == false)
        assertEquals(4, edit.section?.startLine)
        assertEquals(5, edit.section?.endLine)
    }

    @Test
    fun upsertRefusesToSilentlyDropNestedSections() {
        val failure = assertThrows(AgentMemoryException::class.java) {
            MemoryContentEditor.upsertSection("# 核心记忆\n旧偏好\n## 项目\n旧项目", "核心记忆", "只剩一句话")
        }

        assertEquals("MEMORY_SECTION_NESTED", failure.code)
    }

    @Test
    fun upsertReplacesAContainerWhenItsSubsectionsAreIncluded() {
        val edit = MemoryContentEditor.upsertSection("# 核心记忆\n旧\n## A\n1", "核心记忆", "新\n## A\n2")

        assertEquals("# 核心记忆\n新\n## A\n2", edit.content)
    }

    @Test
    fun upsertWithEmptyBodyClearsSectionTextButKeepsHeading() {
        val edit = MemoryContentEditor.upsertSection("# 核心记忆\n旧偏好", "核心记忆", "")

        assertEquals("# 核心记忆", edit.content)
        assertEquals(1, edit.section?.endLine)
    }

    @Test
    fun upsertRejectsEmptyHeading() {
        val failure = assertThrows(AgentMemoryException::class.java) {
            MemoryContentEditor.upsertSection("# 核心记忆\n旧偏好", "###", "内容")
        }

        assertEquals("MEMORY_HEADING_INVALID", failure.code)
    }

    @Test
    fun replaceRangeEditsInclusiveLinesAndDeletesOnEmptyReplacement() {
        assertEquals("a\nB\nd", MemoryContentEditor.replaceRange("a\nb\nc\nd", 2, 3, "B").content)
        assertEquals("c\nd", MemoryContentEditor.replaceRange("a\nb\nc\nd", 1, 2, "").content)
        assertEquals("a\nb\nc\nB\nd", MemoryContentEditor.replaceRange("a\nb\nc\nd", 4, 4, "B\nd").content)
    }

    @Test
    fun replaceRangeRejectsOutOfBoundsRange() {
        val failure = assertThrows(AgentMemoryException::class.java) {
            MemoryContentEditor.replaceRange("a\nb", 2, 5, "x")
        }

        assertEquals("MEMORY_RANGE_INVALID", failure.code)
    }

    @Test
    fun appendAddsBlockAtTheEndWithoutTrailingBlankLines() {
        assertEquals("a\nb", MemoryContentEditor.append("a\n", "b").content)
        assertEquals("b", MemoryContentEditor.append("", "b").content)
        assertEquals("a", MemoryContentEditor.append("a", "").content)
    }
}
