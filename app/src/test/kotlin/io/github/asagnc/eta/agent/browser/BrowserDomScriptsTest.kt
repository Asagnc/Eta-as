package io.github.asagnc.eta.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomScriptsTest {
    @Test
    fun `readable extraction is bounded and preserves absolute urls`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.readable(offset = 0, maxChars = 8_000))

        assertTrue(script.contains("!visible(node)"))
        assertTrue(script.contains("remainingNodes: 8000"))
        assertTrue(script.contains("deadline: Date.now() + 750"))
        assertTrue(script.contains("return boundedString(parsed.href"))
        assertFalse(script.contains("parsed.protocol !== 'https:'"))
        assertFalse(script.contains("innerText"))
        assertFalse(script.contains("textContent"))
    }

    @Test
    fun `script evaluation keeps the result on the page until it is read`() {
        val script = BrowserDomScripts.evaluateScript(
            expression = "await fetch('/token').then(function(response) { return response.text(); })",
            resultKey = "etaScriptProbe",
            maxChars = 2_000,
        )

        assertTrue(script.trimStart().startsWith("var key = \"etaScriptProbe\";"))
        assertTrue(script.contains("var limit = 2000;"))
        assertTrue(script.contains("return (await fetch('/token')"))
        assertTrue(script.contains("window[key] = JSON.stringify(payload);"))
        assertTrue(script.contains("var truncated = described.text.length > limit;"))
        assertTrue(script.contains("Promise.resolve(pending).then"))
        assertTrue(script.contains("return null;"))
    }

    @Test
    fun `script outcome reads the stored result once`() {
        val script = BrowserDomScripts.scriptOutcome("etaScriptProbe")

        assertTrue(script.trimStart().startsWith("var key = \"etaScriptProbe\";"))
        assertTrue(script.contains("if (window[key] === undefined) return { done: false };"))
        assertTrue(script.contains("delete window[key];"))
        assertTrue(script.contains("return { done: true, payload: String(payload) };"))
    }

    @Test
    fun `target resolution does not apply visibility or hit target guards`() {
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.click(selector = "#submit", x = null, y = null)
        )

        assertTrue(script.contains("document.querySelector(selector);"))
        assertFalse(script.contains("requireHitTarget"))
        assertFalse(script.contains("TARGET_OCCLUDED"))
    }
}
