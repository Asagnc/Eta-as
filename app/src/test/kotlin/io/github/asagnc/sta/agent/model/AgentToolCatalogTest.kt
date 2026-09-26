package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogTest {
    @Test
    fun featureFlagsProduceExactUniqueToolUnions() {
        val base = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).toolNames()
        val baseTools = base.toSet()
        val variants = listOf(
            ToolVariant(terminalTools = false, browserTools = false, addedTools = emptySet()),
            ToolVariant(terminalTools = false, browserTools = true, addedTools = BROWSER_TOOLS),
            ToolVariant(terminalTools = true, browserTools = false, addedTools = TERMINAL_TOOLS),
            ToolVariant(
                terminalTools = true,
                browserTools = true,
                addedTools = BROWSER_TOOLS + TERMINAL_TOOLS,
            ),
            ToolVariant(
                terminalTools = true,
                browserTools = false,
                // 关掉代码执行：只影响 run_code 自己。
                addedTools = TERMINAL_TOOLS - "run_code",
                codeExecution = false,
            ),
        )

        assertEquals(base.size, baseTools.size)
        assertTrue(
            base.containsAll(
                setOf("observe_screen", "skills_list", "skills_read", "skills_read_resource"),
            ),
        )
        assertFalse("browser_use" in base)
        assertFalse("terminal" in base)

        variants.forEach { variant ->
            val names = AgentToolCatalog.build(
                terminalTools = variant.terminalTools,
                browserTools = variant.browserTools,
                codeExecution = variant.codeExecution,
            ).toolNames()
            val label = "terminal=${variant.terminalTools}, browser=${variant.browserTools}, " +
                "codeExecution=${variant.codeExecution}"

            assertEquals("$label must not contain duplicate tools", names.size, names.toSet().size)
            assertEquals("$label must be an exact union", baseTools + variant.addedTools, names.toSet())
        }
    }

    /**
     * 只读文件工具与 run_code 同时可见：隐藏只读工具会让「一次读多个文件」失去动作形状，
     * 而 run_code 的参数是一段代码，没有「读哪些文件」这个维度。分工改由提示词规则约束。
     * 写入类工具不随代码执行变化——沙箱写不到应用私有目录与设备共享存储。
     */
    @Test
    fun readOnlyFileToolsStayVisibleWhileWritesStayToo() {
        val withCode = AgentToolCatalog.build(terminalTools = true, browserTools = false).toolNames()
        assertTrue("run_code" in withCode)
        assertTrue(
            "只读文件工具应与 run_code 同时可见，实际缺失：${FILE_READ_TOOLS.filterNot { it in withCode }}",
            FILE_READ_TOOLS.all { it in withCode },
        )
        assertTrue(
            "写入类工具不随代码执行收起",
            setOf("write_file", "edit_file", "edit_files").all { it in withCode },
        )

        val withoutCode = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
            codeExecution = false,
        ).toolNames()
        assertTrue("run_code" !in withoutCode)
        assertTrue("关掉代码执行时只读文件工具仍可见", FILE_READ_TOOLS.all { it in withoutCode })
    }

    @Test
    fun browserToolAllowsArbitraryUrlsAndFormSubmission() {
        val function = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = true,
        ).function("browser_use")
        val properties = function
            .getJSONObject("parameters")
            .getJSONObject("properties")

        assertEquals("string", properties.getJSONObject("url").getString("type"))
        assertEquals("boolean", properties.getJSONObject("submit").getString("type"))
        assertFalse(properties.getJSONObject("url").getString("description").contains("HTTPS"))
        assertFalse(function.getString("description").contains("拦截"))
    }

    @Test
    fun elementToolsRequireObservationIdFromTheSameObservation() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        listOf("tap_element", "long_press_element", "scroll_element").forEach { name ->
            val function = tools.function(name)
            val parameters = function.getJSONObject("parameters")
            val properties = parameters.getJSONObject("properties")

            assertEquals("string", properties.getJSONObject("observation_id").getString("type"))
            assertTrue("observation_id must be required for $name", "observation_id" in parameters.requiredNames())
            assertTrue(function.getString("description").contains("同一次"))
            assertTrue(function.getString("description").contains("observe_screen"))
            assertTrue(function.getString("description").contains("重新观察"))
        }
    }

    @Test
    fun screenObservationDefaultsToTreeAndDescribesVisualEscalation() {
        val function = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).function("observe_screen")
        val properties = function.getJSONObject("parameters").getJSONObject("properties")

        assertFalse(properties.getJSONObject("include_screenshot").getBoolean("default"))
        assertTrue(properties.getJSONObject("include_ui_tree").getBoolean("default"))
        assertEquals(60, properties.getJSONObject("max_nodes").getInt("default"))
        assertEquals(1, properties.getJSONObject("max_nodes").getInt("minimum"))
        assertEquals(120, properties.getJSONObject("max_nodes").getInt("maximum"))
        assertTrue(function.getString("description").contains("默认只返回"))
        assertTrue(function.getString("description").contains("include_screenshot=true"))
        assertTrue(function.getString("description").contains("保持 include_ui_tree=true"))
        assertTrue(function.getString("description").contains("禁止把新截图与旧节点混用"))
    }

    @Test
    fun scrollDirectionsUseContentBrowsingSemantics() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val expectedDirections = listOf("up", "down", "left", "right")

        listOf("scroll", "scroll_element").forEach { name ->
            val function = tools.function(name)
            val directions = function
                .getJSONObject("parameters")
                .getJSONObject("properties")
                .getJSONObject("direction")
                .getJSONArray("enum")
                .stringValues()

            assertEquals(expectedDirections, directions)
            assertTrue(function.getString("description").contains("down 显示下方内容"))
            assertTrue(function.getString("description").contains("up 显示上方内容"))
        }
    }

    @Test
    fun indexedTextToolsDescribeObservationPairingWithoutRequiringItForFocusedInput() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        listOf("replace_text", "clear_text").forEach { name ->
            val function = tools.function(name)
            val parameters = function.getJSONObject("parameters")
            val properties = parameters.getJSONObject("properties")

            assertEquals("string", properties.getJSONObject("observation_id").getString("type"))
            assertFalse("observation_id remains optional when $name targets focus", "observation_id" in parameters.requiredNames())
            assertTrue(function.getString("description").contains("index 与 observation_id"))
            assertTrue(properties.getJSONObject("index").getString("description").contains("同时传入"))
        }

        val inputText = tools.function("input_text")
        val inputProperties = inputText
            .getJSONObject("parameters")
            .getJSONObject("properties")
        assertEquals("integer", inputProperties.getJSONObject("index").getString("type"))
        assertEquals(
            "string",
            inputProperties.getJSONObject("observation_id").getString("type"),
        )
        assertTrue(
            inputProperties.getJSONObject("index").getString("description")
                .contains("observation_id"),
        )
    }

    @Test
    fun textToolsDeclareTheSameLimitsAsRuntime() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        assertEquals(1_000, tools.maxTextLength("input_text"))
        assertEquals(4_000, tools.maxTextLength("replace_text"))
        assertEquals(20_000, tools.maxTextLength("set_clipboard"))
        assertEquals(20_000, tools.maxTextLength("paste_text"))
    }

    @Test
    fun terminalSeparatesAndroidAndLinuxEnvironments() {
        val terminal = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
        ).function("terminal")
        val environment = terminal
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("environment")

        assertEquals(listOf("android", "linux", "debian"), environment.getJSONArray("enum").stringValues())
        assertTrue(terminal.getString("description").contains("environment=android"))
        assertTrue(terminal.getString("description").contains("environment=linux"))
    }

    @Test
    fun memoryToolsAreExposedOnlyWhenEnabledAndDeclareBoundedOperations() {
        val disabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = false,
        ).toolNames()
        assertFalse("memory_get" in disabled)
        assertFalse("memory_write" in disabled)

        val enabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = true,
        )
        assertTrue("memory_get" in enabled.toolNames())
        val write = enabled.function("memory_write")
        val parameters = write.getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")
        assertEquals(AgentMemoryStore.MAX_WRITE_CONTENT_CHARS, properties.getJSONObject("content").getInt("maxLength"))
        assertEquals(
            listOf("upsert_section", "replace_range", "append", "clear"),
            properties.getJSONObject("mode").getJSONArray("enum").stringValues(),
        )
        // mode / revision 允许省略：漏字段由工具自己按字段推断，不该被参数校验直接拒掉。
        assertFalse(parameters.has("required"))
        assertTrue(properties.has("heading"))
    }

    @Test
    fun everyCatalogToolHasAUserFacingLabel() {
        // 工具页与运行轨迹都按名字查中文标签，漏一个就显示成兜底文案「准备执行」，
        // 而且只在那一个工具被调用时才看得出来（edit_file 就这么漏了很久）。
        // 这里把整个目录一次过一遍。
        val names = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            subAgentTools = true,
            memoryTools = true,
        ).toolNames()
        val formatter = AgentTraceFormatter()
        val unlabeled = names
            .filterNot { it.startsWith("mcp_") }
            .filter { formatter.summarizeArguments(AgentModelClient.ToolCall("id", it, "{}")) == "准备执行" }
            .sorted()
        assertEquals("这些工具没有中文标签：" + unlabeled.joinToString(), emptyList<String>(), unlabeled)
    }

    private fun JSONArray.toolNames(): List<String> =
        (0 until length()).map { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .asSequence()
            .map { index -> getJSONObject(index).getJSONObject("function") }
            .first { function -> function.getString("name") == name }

    private fun JSONObject.requiredNames(): Set<String> =
        optJSONArray("required")?.stringValues()?.toSet().orEmpty()

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map(::getString)

    private fun JSONArray.maxTextLength(name: String): Int =
        function(name)
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("text")
            .getInt("maxLength")

    private data class ToolVariant(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val addedTools: Set<String>,
        /** 缺省跟随 terminalTools；置 false 用来单独验证只读文件工具的回退路径。 */
        val codeExecution: Boolean = true,
    )

    private companion object {
        val BROWSER_TOOLS = setOf("browser_use")

        /** 终端开关打开时可见的工具；只读文件工具也在内——它们不再随 run_code 变化。 */
        val TERMINAL_TOOLS = setOf(
            "read_image",
            "terminal",
            "run_command",
            "run_code",
            "edit_file",
            "edit_files",
            "write_file",
            "read_file",
            "read_files",
            "find_files",
            "search_code",
            "list_directory",
        )

        /** 只读文件工具：与 run_code 同时可见，由提示词规则分工（多文件走 read_files）。 */
        val FILE_READ_TOOLS = setOf(
            "read_file",
            "read_files",
            "find_files",
            "search_code",
            "list_directory",
        )
    }
}
