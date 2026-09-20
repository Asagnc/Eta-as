package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(32_000, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsWholeFileWhenItFitsTheBudget() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n不应再按需读取"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(content, context.injectedContent)
        assertTrue(context.injectedFull)
        assertFalse(context.injectedTruncated)
    }

    @Test
    fun headingIndexCarriesLineRanges() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n细节"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(
            "# 核心记忆  [L1-4]\n## 关系  [L3-4]\n# 详细背景  [L5-6]",
            context.headingIndex,
        )
    }

    @Test
    fun oversizedFileFallsBackToTruncatedHead() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertTrue(context.injectedContent.startsWith("# 核心记忆"))
        assertTrue(context.injectedContent.length <= 8_000)
        assertFalse(context.injectedFull)
        assertTrue(context.injectedTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun oversizedFileWithoutCoreHeadingStillInjectsItsHead() {
        // 用户没用 `# 核心记忆` 当标题时也不能一点记忆都不给：按行边界截断注入开头一段。
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n" + "只应按需读取的细节".repeat(2_000)),
            128_000,
        )

        assertTrue(context.injectedContent.startsWith("# 项目"))
        assertFalse(context.injectedFull)
        assertEquals("# 项目  [L1-2]", context.headingIndex)
    }

    @Test
    fun scopedSectionIsInjectedOnlyWhenTheTaskMatches() {
        val content = "# 核心记忆\n通用偏好\n" +
            "## Eta 改造\n<!-- scope: Eta-src -->\n改造细节\n" +
            "## 大块\n" + "z".repeat(9_000)
        val snapshot = snapshot(content)

        val unrelated = AgentMemoryContextBuilder.build(snapshot, null, hint = "整理相册")
        assertFalse(unrelated.injectedContent.contains("改造细节"))
        assertTrue(unrelated.injectedContent.contains("通用偏好"))

        val related = AgentMemoryContextBuilder.build(snapshot, null, hint = "改 /workspace/Eta-src 的代码")
        assertTrue(related.injectedContent.contains("改造细节"))
    }

    @Test
    fun scopedOnlyFileInjectsNothingWhenNothingMatches() {
        val content = "## Eta 改造\n<!-- scope: Eta-src -->\n改造细节\n" + "z".repeat(9_000)

        val context = AgentMemoryContextBuilder.build(snapshot(content), null, hint = "整理相册")

        assertEquals("", context.injectedContent)
    }

    @Test
    fun taskHintKeepsRecentMessagesAndToolArguments() {
        val history = listOf(
            message("user", "第1条"),
            message("user", "第2条"),
            message("user", "第3条"),
            message("user", "第4条"),
            message("user", "第5条"),
            message("user", "第6条"),
            message("user", "第7条"),
            message("assistant", "", """{"name":"read_file","arguments":{"path":"/workspace/Eta-src"}}"""),
        )

        val hint = AgentMemoryContextBuilder.taskHint(history)

        assertTrue(hint.contains("/workspace/Eta-src"))
        assertFalse(hint.contains("第1条"))
        assertTrue(hint.contains("第7条"))
    }

    @Test
    fun taskHintIsEmptyWithoutHistory() {
        assertEquals("", AgentMemoryContextBuilder.taskHint(emptyList()))
    }

    @Test
    fun oversizedFileSkipsUnfittableSectionButKeepsLaterOnes() {
        // 逐节挑选：放不下的大章节跳过，后面放得下的小章节仍然注入——结果可能不连续，这是刻意的
        // （预算内尽量多给有用的章节，而不是遇到第一个放不下的就整体退回截断开头）。
        val content = "# 核心记忆\n通用偏好\n" +
            "## 大块\n" + "z".repeat(9_000) + "\n" +
            "## 小尾巴\n尾巴内容"
        val context = AgentMemoryContextBuilder.build(snapshot(content), null)

        assertFalse(context.injectedFull)
        assertTrue(context.injectedContent.contains("通用偏好"))
        assertTrue(context.injectedContent.contains("尾巴内容"))
        assertFalse(context.injectedContent.contains("zzz"))
    }

    @Test
    fun scopedSectionTakesItsSubsectionsWithIt() {
        // 作用域不匹配的章节连同子章节一起跳过：子章节脱离父章节单独注入会失去上下文。
        val content = "# 核心记忆\n通用偏好\n" +
            "## Eta 改造\n<!-- scope: Eta-src -->\n改造细节\n" +
            "### 子节\n子节细节\n" +
            "## 其他\n其他内容\n" +
            "## 大块\n" + "z".repeat(9_000)
        val context = AgentMemoryContextBuilder.build(snapshot(content), null, hint = "整理相册")

        assertFalse(context.injectedContent.contains("改造细节"))
        assertFalse(context.injectedContent.contains("子节细节"))
        assertTrue(context.injectedContent.contains("其他内容"))
    }

    private fun message(role: String, content: String, toolCalls: String = "") =
        AgentModelClient.ConversationMessage(role = role, content = content, toolCallsJson = toolCalls)

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
