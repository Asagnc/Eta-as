package io.github.asagnc.eta.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRequestHeaderScriptTest {
    private val script = BrowserRequestHeaderScript.build(
        headers = mapOf("Authorization" to "Bearer t0ken", "X-Eta-Probe" to "1"),
        origin = "https://example.com",
    )

    @Test
    fun `headers and origin are embedded as json literals`() {
        assertTrue(script.contains("\"Authorization\": \"Bearer t0ken\""))
        assertTrue(script.contains("\"X-Eta-Probe\": \"1\""))
        assertTrue(script.contains("var etaOrigin = \"https://example.com\";"))
        assertFalse(script.contains("\$"))
    }

    @Test
    fun `requests are patched for fetch and xhr with a same origin guard`() {
        assertTrue(script.contains("window.fetch = function(input, init)"))
        assertTrue(script.contains("XMLHttpRequest.prototype.open = function(method, url)"))
        assertTrue(script.contains("XMLHttpRequest.prototype.send = function()"))
        assertTrue(script.contains("if (!etaSameOrigin(url)) return etaFetch.call(this, input, init);"))
        assertTrue(script.contains("if (this.__etaSameOrigin) {"))
        assertTrue(script.contains("new URL(url, document.baseURI).origin === etaOrigin"))
    }
}
