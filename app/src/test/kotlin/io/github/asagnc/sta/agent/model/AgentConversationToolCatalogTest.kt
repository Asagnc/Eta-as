package io.github.asagnc.sta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationToolCatalogTest {
    @Test
    fun `instructions are read from the tool arguments`() {
        val arguments = """{"instructions":"保留测试结论"}"""

        assertEquals("保留测试结论", AgentConversationToolCatalog.instructionsOf(arguments))
    }

    @Test
    fun `missing or malformed arguments yield no instructions`() {
        assertEquals("", AgentConversationToolCatalog.instructionsOf("{}"))
        assertEquals("", AgentConversationToolCatalog.instructionsOf("not json"))
        assertEquals("", AgentConversationToolCatalog.instructionsOf("""{"instructions":"   "}"""))
    }

    @Test
    fun `instructions are clipped to the declared limit`() {
        val long = "x".repeat(2_000)

        val instructions = AgentConversationToolCatalog.instructionsOf("""{"instructions":"$long"}""")

        assertEquals(AgentConversationToolCatalog.MAX_INSTRUCTIONS_CHARS, instructions.length)
    }

    @Test
    fun `compact schema declares the instructions argument`() {
        val schema = AgentConversationToolCatalog.compactSchema().getJSONObject("function")

        assertEquals(AgentConversationToolCatalog.COMPACT_CONTEXT, schema.getString("name"))
        assertTrue(schema.getJSONObject("parameters").getJSONObject("properties").has("instructions"))
    }
}
