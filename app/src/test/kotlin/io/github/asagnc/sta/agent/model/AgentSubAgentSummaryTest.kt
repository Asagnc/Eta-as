package io.github.asagnc.sta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSubAgentSummaryTest {

    @Test
    fun parsesTheThreeSections() {
        val summary = AgentSubAgentSummary.parse(
            """
            结论：失败学习的读写已经从文件堆迁到观测库。
            证据：app/src/main/kotlin/io/github/asagnc/eta/data/world/WorldKnowledgeStore.kt:42
            证据：./gradlew :app:testDebugUnitTest → BUILD SUCCESSFUL
            不确定：未在真机验证数据库文件的实际增长。
            """.trimIndent(),
        )

        assertEquals("失败学习的读写已经从文件堆迁到观测库。", summary.conclusion)
        assertEquals(2, summary.evidence.size)
        assertEquals(
            listOf("app/src/main/kotlin/io/github/asagnc/eta/data/world/WorldKnowledgeStore.kt"),
            summary.filePaths,
        )
        assertEquals(listOf("未在真机验证数据库文件的实际增长。"), summary.uncertainty)
    }

    @Test
    fun separatesFileAndCommandEvidence() {
        val summary = AgentSubAgentSummary.parse(
            """
            结论：结论
            证据：a/b.kt:10
            证据：grep -rn foo → 3 处命中
            """.trimIndent(),
        )

        val file = summary.evidence.first { it.kind == AgentSubAgentSummary.Evidence.Kind.FILE }
        val command = summary.evidence.first { it.kind == AgentSubAgentSummary.Evidence.Kind.COMMAND }
        assertEquals("a/b.kt", file.target)
        assertEquals(10, file.line)
        assertEquals("grep -rn foo", command.target)
    }

    @Test
    fun acceptsInlineLabelAndBoldMarkers() {
        // 模型不总写得工整：标签后直接跟内容、或加粗包裹，都要认。
        val summary = AgentSubAgentSummary.parse(
            """
            **结论**：一句话结论
            **证据**：pkg/Foo.kt:7
            """.trimIndent(),
        )

        assertEquals("一句话结论", summary.conclusion)
        assertEquals("pkg/Foo.kt", summary.evidence.single().target)
    }

    @Test
    fun acceptsLabelWithParentheticalNote() {
        // 实测踩到：模型写成 `**证据**（均为 Sta 自身，非问题所指外部产品）：`，
        // 标签与冒号之间夹了括注。只认 `证据：` 会让这一行识别不出来——后面的证据、
        // 不确定与结尾备注全部并进结论段，结论从一句话膨胀成整份报告。
        val summary = AgentSubAgentSummary.parse(
            """
            结论：一句话结论
            **证据**（均为 Sta 自身，非问题所指外部产品）：
            - pkg/Foo.kt:7
            **不确定**（无来源，不给推测）：
            - 没有联网工具，无法核实。
            """.trimIndent(),
        )

        assertEquals("一句话结论", summary.conclusion)
        assertEquals("pkg/Foo.kt", summary.evidence.single().target)
        assertEquals(listOf("没有联网工具，无法核实。"), summary.uncertainty)
    }

    @Test
    fun dropsTrailingNotesAfterHorizontalRule() {
        // `---` 是摘要与结尾备注（模型自己加的「给主智能体的提示」之类）的分界：
        // 它后面的内容不属于三段，不能被当成不确定项收进去。
        val summary = AgentSubAgentSummary.parse(
            """
            结论：一句话结论
            不确定：
            - 没有验证并发写入
            ---
            给主智能体的提示：需要联网才能查。
            """.trimIndent(),
        )

        assertEquals("一句话结论", summary.conclusion)
        assertEquals(listOf("没有验证并发写入"), summary.uncertainty)
    }

    @Test
    fun keepsEvidenceWithoutLineNumbersAsFiles() {
        // 只给路径不给行号也应当作文件依赖，它是新鲜度校验的输入。
        val summary = AgentSubAgentSummary.parse(
            """
            结论：结论
            证据：app/src/main/kotlin/Foo.kt
            """.trimIndent(),
        )

        // 没有冒号时无法判定为文件引用，落进原文而非误判。
        assertTrue(summary.evidence.isEmpty())
        assertEquals("app/src/main/kotlin/Foo.kt", summary.raw.lines()[1].substringAfter("："))
    }

    @Test
    fun ignoresNumericOnlyPseudoPaths() {
        // `123:456` 是编号或时间，不是文件引用，不能拿去做依赖校验。
        val summary = AgentSubAgentSummary.parse(
            """
            结论：结论
            证据：123:456
            """.trimIndent(),
        )

        assertTrue(summary.evidence.isEmpty())
    }

    @Test
    fun fallsBackToRawTextWhenFormatIsMissing() {
        // 格式不合规时不能丢掉产出：全文作为结论交回，原文另存一份。
        val summary = AgentSubAgentSummary.parse("这是一段没有分段的自由回答。")

        assertEquals("这是一段没有分段的自由回答。", summary.conclusion)
        assertTrue(summary.evidence.isEmpty())
        assertTrue(summary.uncertainty.isEmpty())
        assertEquals("这是一段没有分段的自由回答。", summary.raw)
    }

    @Test
    fun handlesBlankInput() {
        val summary = AgentSubAgentSummary.parse("   ")

        assertEquals("", summary.conclusion)
        assertTrue(summary.evidence.isEmpty())
        assertTrue(summary.raw.isEmpty())
    }

    @Test
    fun stripsBulletMarkersFromUncertainty() {
        val summary = AgentSubAgentSummary.parse(
            """
            结论：结论
            不确定：
            - 没有验证并发写入
            - 没有验证磁盘增长
            """.trimIndent(),
        )

        assertEquals(listOf("没有验证并发写入", "没有验证磁盘增长"), summary.uncertainty)
    }

    @Test
    fun keepsCommandEvidenceContainingArrowsInOutput() {
        // 输出里再出现箭头时，只按第一个箭头切分，其余留在输出侧。
        val summary = AgentSubAgentSummary.parse(
            """
            结论：结论
            证据：cat a.txt → foo → bar
            """.trimIndent(),
        )

        assertEquals("cat a.txt", summary.evidence.single().target)
    }

    @Test
    fun filePathsAreDeduplicatedInOrder() {
        val summary = AgentSubAgentSummary.parse(
            """
            结论：结论
            证据：a.kt:1
            证据：b.kt:2
            证据：a.kt:9
            """.trimIndent(),
        )

        assertEquals(listOf("a.kt", "b.kt"), summary.filePaths)
    }
}
