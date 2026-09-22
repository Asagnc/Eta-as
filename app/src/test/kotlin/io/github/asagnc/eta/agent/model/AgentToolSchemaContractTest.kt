package io.github.asagnc.eta.agent.model

import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 工具 schema 的结构契约。
 *
 * 上游对工具定义做严格校验，结构错误会让整个请求被拒（表现为“不支持工具调用”一类与 schema 无关的报错），
 * 所以这里把所有已发布的工具扫一遍：属性必须带 type、数组必须带 items、required 必须与 properties 同级并指向真实属性。
 */
class AgentToolSchemaContractTest {
    @Test
    fun `every published tool has an internally consistent schema`() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
        )
        val problems = mutableListOf<String>()
        for (index in 0 until tools.length()) {
            val function = tools.getJSONObject(index).getJSONObject("function")
            val name = function.getString("name")
            if (function.optString("description").isBlank()) problems += "$name: 缺少 description"
            val parameters = function.optJSONObject("parameters")
            if (parameters == null) {
                problems += "$name: 缺少 parameters"
                continue
            }
            if (parameters.optString("type") != "object") problems += "$name: parameters.type 必须是 object"
            validateObjectSchema(name, "$name.parameters", parameters, problems)
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun validateObjectSchema(
        tool: String,
        path: String,
        schema: JSONObject,
        problems: MutableList<String>,
    ) {
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val names = properties.keys().asSequence().toList()
        names.forEach { key ->
            val property = properties.getJSONObject(key)
            if (!property.has("type")) problems += "$tool: $path.properties.$key 缺少 type"
            if (property.optString("type") == "array") {
                val items = property.optJSONObject("items")
                if (items == null) {
                    problems += "$tool: $path.properties.$key 是数组但缺少 items"
                } else if (items.optString("type") == "object") {
                    validateObjectSchema(tool, "$path.properties.$key.items", items, problems)
                }
            }
        }
        val required = schema.optJSONArray("required") ?: return
        for (index in 0 until required.length()) {
            val requiredName = required.optString(index)
            if (requiredName !in names) {
                problems += "$tool: $path.required 里的 $requiredName 不在同一级 properties 中"
            }
        }
    }
}
