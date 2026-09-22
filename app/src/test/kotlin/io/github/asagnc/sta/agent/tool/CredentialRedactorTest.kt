package io.github.asagnc.sta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactorTest {
    @Test
    fun providerKeyShapesAreRedacted() {
        assertEquals(
            "key=${CredentialRedactor.PLACEHOLDER}",
            CredentialRedactor.redact("key=sk-abcdef0123456789ghijklmn"),
        )
        assertEquals(
            CredentialRedactor.PLACEHOLDER,
            CredentialRedactor.redact("sk-ant-api03-abcdefghijklmnopqrstuvwxyz"),
        )
        assertEquals(
            CredentialRedactor.PLACEHOLDER,
            CredentialRedactor.redact("AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"),
        )
    }

    @Test
    fun headersKeepTheirNameAndLoseTheSecret() {
        val redacted = CredentialRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
        assertTrue(redacted.startsWith("Authorization: "))
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue(redacted.contains(CredentialRedactor.PLACEHOLDER))

        val bearerOnly = CredentialRedactor.redact("curl -H \"Bearer abcdefghijklmnopqrstuvwxyz012345\"")
        assertFalse(bearerOnly.contains("abcdefghijklmnopqrstuvwxyz012345"))
    }

    @Test
    fun ordinaryOutputIsUntouched() {
        val text = "commitSha=9c42a78 sid=conv-9e19a97e md5=a298853bd51feaaeaf566fe5747a9d8c"
        assertEquals(text, CredentialRedactor.redact(text))
        assertEquals("", CredentialRedactor.redact(""))
    }
}
