package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import io.github.asagnc.sta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    /** 一次装配的开关组合：各目录据此决定放行哪些工具。 */
    internal data class Selection(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val deviceDirectTools: Boolean,
        val deviceSensitiveReadTools: Boolean,
        val deviceSensitiveActionTools: Boolean,
        val skillGitHubDiscovery: Boolean,
        val skillGitHubInstall: Boolean,
        val subAgentTools: Boolean,
        val memoryTools: Boolean,
        val memoryWritable: Boolean,
    )

    /**
     * 全部工具目录的登记表，按装配顺序排列。
     *
     * 存在的意义是让「目录写了但没被装配」变成测试能抓到的失败，而不是某组工具永远静默不可见：
     * 信箱工具就曾漏在装配路径之外——schema 构造器写好了，却没有任何地方调用它。
     * 新增目录时把它登记到这里，装配与 [AgentToolCatalogCoverageTest] 都以此为准。
     */
    internal val catalogs: List<Pair<String, (JSONArray, Selection) -> Unit>> = listOf(
        "AgentContextAppToolCatalog" to { tools, _ -> AgentContextAppToolCatalog.appendTo(tools) },
        "AgentRunStatsToolCatalog" to { tools, _ -> AgentRunStatsToolCatalog.appendTo(tools) },
        "AgentGestureToolCatalog" to { tools, _ -> AgentGestureToolCatalog.appendTo(tools) },
        "AgentTextSystemToolCatalog" to { tools, _ -> AgentTextSystemToolCatalog.appendTo(tools) },
        "AgentDeviceToolCatalog" to { tools, selection ->
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = selection.deviceDirectTools,
                sensitiveReadTools = selection.deviceSensitiveReadTools,
                sensitiveActionTools = selection.deviceSensitiveActionTools,
            )
        },
        "AgentBrowserToolCatalog" to { tools, selection ->
            if (selection.browserTools) AgentBrowserToolCatalog.appendTo(tools)
        },
        "AgentSkillToolCatalog" to { tools, selection ->
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = selection.skillGitHubDiscovery,
                githubInstall = selection.skillGitHubInstall,
            )
        },
        "AgentMemoryToolCatalog" to { tools, selection ->
            if (selection.memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = selection.memoryWritable)
        },
        "AgentSubAgentToolCatalog" to { tools, selection ->
            if (selection.subAgentTools) AgentSubAgentToolCatalog.appendTo(tools)
        },
        "AgentFileVisionToolCatalog" to { tools, selection ->
            if (selection.terminalTools) AgentFileVisionToolCatalog.appendTo(tools)
        },
        "AgentTerminalToolCatalog" to { tools, selection ->
            if (selection.terminalTools) AgentTerminalToolCatalog.appendTo(tools)
        },
    )

    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        subAgentTools: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
    ): JSONArray {
        val selection = Selection(
            terminalTools = terminalTools,
            browserTools = browserTools,
            deviceDirectTools = deviceDirectTools,
            deviceSensitiveReadTools = deviceSensitiveReadTools,
            deviceSensitiveActionTools = deviceSensitiveActionTools,
            skillGitHubDiscovery = skillGitHubDiscovery,
            skillGitHubInstall = skillGitHubInstall,
            subAgentTools = subAgentTools,
            memoryTools = memoryTools,
            memoryWritable = memoryWritable,
        )
        return capabilities.project(JSONArray().also { tools ->
            catalogs.forEach { (_, append) -> append(tools, selection) }
        })
    }
}
