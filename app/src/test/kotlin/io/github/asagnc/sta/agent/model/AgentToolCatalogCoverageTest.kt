package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具「链路可达性」测试：目录里声明的工具必须真的能到模型手里。
 *
 * 起因是一次真实的静默失效：信箱工具 mailbox_post 的 schema 构造器写在目录里，却没有任何
 * 装配路径调用它，于是模型永远看不到这个工具——信箱只被读、从来没有被写过，而所有单测都是绿的。
 * 它们测的是单元，不是链路。
 */
class AgentToolCatalogCoverageTest {
    /** 被 run_code 替代的只读文件工具；这里写死，让实现改动必须显式更新期望。 */
    private val readOnlyFileTools = setOf(
        "read_file",
        "read_files",
        "find_files",
        "search_code",
        "list_directory",
    )

    private fun selection() = AgentToolCatalog.Selection(
        terminalTools = true,
        codeExecution = true,
        browserTools = true,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true,
        skillGitHubInstall = true,
        subAgentTools = true,
        memoryTools = true,
        memoryWritable = true,
    )

    private fun allOn(): JSONArray = AgentToolCatalog.build(
        terminalTools = true,
        codeExecution = true,
        browserTools = true,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true,
        skillGitHubInstall = true,
        subAgentTools = true,
        memoryTools = true,
        capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
    )

    private fun names(tools: JSONArray): List<String> =
        (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }

    @Test
    fun everyRegisteredCatalogReachesTheModel() {
        val built = names(allOn()).toSet()
        val unreachable = AgentToolCatalog.catalogs.mapNotNull { (label, append) ->
            val produced = JSONArray().also { append(it, selection()) }
            val absent = names(produced).filterNot { it in built }
            if (absent.isEmpty()) null else "$label → ${absent.joinToString("、")}"
        }

        assertTrue(
            "有目录没被装配进 AgentToolCatalog.build()，它声明的工具模型看不到：\n" +
                unreachable.joinToString("\n"),
            unreachable.isEmpty(),
        )
    }

    @Test
    fun subAgentToolTableKeepsAuthorizedToolsAndAddsMailboxOnlyWhenEnabled() {
        val parent = allOn()
        val allowed = READ_ONLY_TOOL_NAMES + WRITE_TOOL_NAMES + MAILBOX_TOOL_NAMES + BROWSER_TOOL_NAME
        val withoutMailbox = names(subAgentTools(parent, allowed, mailboxEnabled = false))
        val withMailbox = names(subAgentTools(parent, allowed, mailboxEnabled = true))

        // 父表里存在的授权工具一个都不能丢：丢掉就是子智能体看不见自己本来能用的能力。
        assertEquals(allowed intersect names(parent).toSet(), withoutMailbox.toSet())
        // 信箱工具只对子智能体有意义，因此不在主循环的工具表里，只能由 subAgentTools 补上。
        assertFalse("mailbox_post 不该出现在主循环工具表里", "mailbox_post" in names(parent))
        assertFalse("信箱关闭时不该出现", "mailbox_post" in withoutMailbox)
        assertEquals(withoutMailbox.toSet() + MAILBOX_TOOL_NAMES, withMailbox.toSet())
        // 重名 schema 会让部分模型直接报错，工具表里每个名字只能出现一次。
        assertEquals(withMailbox.toSet().size, withMailbox.size)
    }

    @Test
    fun mailboxSchemaIsUsableByTheModel() {
        val tools = subAgentTools(allOn(), READ_ONLY_TOOL_NAMES + MAILBOX_TOOL_NAMES, mailboxEnabled = true)
        val mailbox = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .first { it.getJSONObject("function").getString("name") == "mailbox_post" }

        val function = mailbox.getJSONObject("function")
        assertTrue("工具要有说明，否则模型不知道什么时候该用", function.getString("description").isNotBlank())
        assertTrue(
            "工具要有参数 schema",
            function.getJSONObject("parameters").getJSONObject("properties").length() > 0,
        )
    }

    @Test
    fun codeExecutionReplacesReadOnlyFileToolsForTheMainLoop() {
        val all = names(allOn()).toSet()

        assertTrue("run_code" in all)
        assertTrue(
            "只读文件工具应与 run_code 互斥，实际仍可见：${readOnlyFileTools.filter { it in all }}",
            readOnlyFileTools.none { it in all },
        )
        assertTrue(
            "写入类工具不随代码执行收起",
            setOf("write_file", "edit_file", "edit_files").all { it in all },
        )

        val withoutCode = names(
            AgentToolCatalog.build(
                terminalTools = true,
                browserTools = false,
                codeExecution = false,
                capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            ),
        ).toSet()
        assertTrue("关掉代码执行时只读文件工具要回来", readOnlyFileTools.all { it in withoutCode })
        assertTrue("run_code" !in withoutCode)
    }
}
