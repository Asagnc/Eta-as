package io.github.asagnc.sta.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRequestHeaderScriptTest {
    private val script = BrowserRequestHeaderScript.build(
        headers = mapOf("Authorization" to "Bearer t0ken", "X-Sta-Probe" to "1"),
        origin = "https://example.com",
    )

    @Test
    fun `headers and origin are embedded as json literals`() {
        assertTrue(script.contains("\"Authorization\": \"Bearer t0ken\""))
        assertTrue(script.contains("\"X-Sta-Probe\": \"1\""))
        assertTrue(script.contains("var staOrigin = \"https://example.com\";"))
        assertFalse(script.contains("\$"))
    }

    @Test
    fun `requests are patched for fetch and xhr with a same origin guard`() {
        assertTrue(script.contains("window.fetch = function(input, init)"))
        assertTrue(script.contains("XMLHttpRequest.prototype.open = function(method, url)"))
        assertTrue(script.contains("XMLHttpRequest.prototype.send = function()"))
        assertTrue(script.contains("if (!staSameOrigin(url)) return staFetch.call(this, input, init);"))
        assertTrue(script.contains("if (this.__staSameOrigin) {"))
        assertTrue(script.contains("new URL(url, document.baseURI).origin === staOrigin"))
    }
}
