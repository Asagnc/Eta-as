package io.github.asagnc.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 失败签名必须区分得开：不同原因不能退化成同一个签名，否则会被误判成"重复失败"。 */
class AgentFailureSignatureTest {

    @Test
    fun successfulResultHasNoFailure() {
        assertNull(AgentFailureSignature.of("terminal", """{"ok":true,"stdout":"done"}"""))
        assertNull(AgentFailureSignature.of("terminal", "not json at all"))
    }

    @Test
    fun terminalFailureFallsBackToExitCodeAndStderr() {
        val failure = AgentFailureSignature.of(
            "terminal",
            """{"ok":false,"exit_code":127,"stdout":"","stderr":"sh: line 1: foo: not found"}""",
        )!!
        assertEquals("terminal:EXIT_127:sh: line 1: foo: not found:127", failure.signature)
        assertEquals("EXIT_127：sh: line 1: foo: not found", failure.detail)
    }

    @Test
    fun codeAndMessageTakePrecedence() {
        val failure = AgentFailureSignature.of(
            "write_file",
            """{"ok":false,"code":"PERMISSION_DENIED","message":"path outside workspace"}""",
        )!!
        assertEquals("write_file:PERMISSION_DENIED:path outside workspace:-2147483648", failure.signature)
        assertEquals("PERMISSION_DENIED：path outside workspace", failure.detail)
    }

    @Test
    fun differentExitCodesProduceDifferentSignatures() {
        val notFound = AgentFailureSignature.of("terminal", """{"ok":false,"exit_code":127,"stderr":"foo: not found"}""")!!
        val noMatch = AgentFailureSignature.of("terminal", """{"ok":false,"exit_code":1,"stderr":"foo: not found"}""")!!
        assertNotEquals(notFound.signature, noMatch.signature)
    }

    @Test
    fun silentFailureWithoutAnyOutputStillHasStableSignature() {
        val first = AgentFailureSignature.of("terminal", """{"ok":false,"exit_code":1,"stdout":"","stderr":""}""")!!
        val second = AgentFailureSignature.of("terminal", """{"ok":false,"exit_code":1,"stdout":"","stderr":""}""")!!
        assertEquals(first.signature, second.signature)
        assertEquals("EXIT_1", first.detail)
    }

    @Test
    fun messageIsTruncatedToKeepTheSignatureBounded() {
        val long = "x".repeat(500)
        val failure = AgentFailureSignature.of("terminal", """{"ok":false,"exit_code":1,"stderr":"$long"}""")!!
        assertTrue(failure.detail.length < 200)
    }
}
