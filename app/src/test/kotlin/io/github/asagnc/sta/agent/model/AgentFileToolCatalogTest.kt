package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件工具目录的分组边界。
 *
 * 两组由装配侧分别控制：只读组会被 run_code 替代，写入组始终装配。分组本身出错不会让任何
 * 单测变红——模型只是少看到或少用到一个入口——所以这里把边界固定下来。
 */
class AgentFileToolCatalogTest {
    @Test
    fun readOnlyGroupCarriesOnlySearchTools() {
        assertEquals(
            setOf("read_file", "read_files", "find_files", "search_code", "list_directory"),
            names { AgentFileToolCatalog.appendReadOnlyTo(it) },
        )
    }

    @Test
    fun writeGroupCarriesEveryWriteTool() {
        assertEquals(
            setOf("write_file", "edit_file", "edit_files"),
            names { AgentFileToolCatalog.appendWriteTo(it) },
        )
    }

    @Test
    fun groupsDoNotOverlap() {
        val readOnly = names { AgentFileToolCatalog.appendReadOnlyTo(it) }
        val write = names { AgentFileToolCatalog.appendWriteTo(it) }
        assertEquals("同名 schema 会让部分模型直接报错", emptySet<String>(), readOnly intersect write)
    }

    @Test
    fun everyFileToolDeclaresUsableSchema() {
        val tools = JSONArray()
        AgentFileToolCatalog.appendReadOnlyTo(tools)
        AgentFileToolCatalog.appendWriteTo(tools)

        for (index in 0 until tools.length()) {
            val function = tools.getJSONObject(index).getJSONObject("function")
            val name = function.getString("name")
            assertTrue("$name 要有说明", function.getString("description").isNotBlank())
            assertTrue(
                "$name 要有参数 schema",
                function.getJSONObject("parameters").getJSONObject("properties").length() > 0,
            )
        }
    }

    private fun names(append: (JSONArray) -> Unit): Set<String> =
        JSONArray().also(append).let { tools ->
            (0 until tools.length()).mapTo(linkedSetOf()) { index ->
                tools.getJSONObject(index).getJSONObject("function").getString("name")
            }
        }
}
