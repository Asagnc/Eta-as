package io.github.asagnc.sta.agent.model

import io.github.asagnc.sta.agent.tool.AgentLocalTools

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools

    private val sensitiveTools = AgentLocalTools.DEVICE_SENSITIVE_READ_TOOL_NAMES + setOf(
        "read_image",
        "set_setting",
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",
    )
}
