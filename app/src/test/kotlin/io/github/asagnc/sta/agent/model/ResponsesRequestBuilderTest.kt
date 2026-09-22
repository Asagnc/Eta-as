package io.github.asagnc.sta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResponsesRequestBuilderTest {
    @Test
    fun writesMaxOutputTokensOnlyWhenConfigured() {
        val defaultRequest =
            ResponsesRequestBuilder.build(config(), JSONArray(), JSONArray(), ProviderRequestPurpose.CHAT)
        assertFalse(defaultRequest.has("max_output_tokens"))

        val boosted = ResponsesRequestBuilder.build(
            config().copy(maxOutputTokens = 32_768),
            JSONArray(),
            JSONArray(),
            ProviderRequestPurpose.CHAT,
        )
        assertEquals(32_768, boosted.getInt("max_output_tokens"))
    }

    @Test
    fun ignoresNonPositiveMaxOutputTokens() {
        val request = ResponsesRequestBuilder.build(
            config().copy(maxOutputTokens = 0),
            JSONArray(),
            JSONArray(),
            ProviderRequestPurpose.CHAT,
        )
        assertFalse(request.has("max_output_tokens"))
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "",
    )
}
