package io.github.asagnc.sta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellEnvironmentPolicyTest {
    @Test
    fun credentialLookingNamesAreRemovedAndOrdinaryOnesSurvive() {
        val environment = mutableMapOf(
            "PATH" to "/usr/bin",
            "LANG" to "zh_CN.UTF-8",
            "SSH_AUTH_SOCK" to "/tmp/agent.sock",
            "OPENAI_API_KEY" to "sk-xxx",
            "GH_TOKEN" to "ghp_xxx",
            "DB_PASSWORD" to "hunter2",
            "MY_PRIVATE_KEY" to "-----BEGIN",
        )

        val removed = ShellEnvironmentPolicy.sanitize(environment)

        assertEquals(listOf("DB_PASSWORD", "GH_TOKEN", "MY_PRIVATE_KEY", "OPENAI_API_KEY"), removed)
        // SSH_AUTH_SOCK 名字里带 AUTH，但它不是凭据本身，误删会弄坏用户环境里的 ssh-agent。
        assertEquals(setOf("PATH", "LANG", "SSH_AUTH_SOCK"), environment.keys)
    }

    @Test
    fun keepEnvAllIsAnEscapeHatch() {
        val environment = mutableMapOf("GH_TOKEN" to "ghp_x", "STA_KEEP_ENV" to "all")

        assertEquals(emptyList<String>(), ShellEnvironmentPolicy.sanitize(environment))
        assertEquals(setOf("GH_TOKEN"), environment.keys)
    }

    @Test
    fun keepEnvListReleasesOnlyNamedVariables() {
        val environment = mutableMapOf(
            "GH_TOKEN" to "ghp_x",
            "AWS_SECRET_ACCESS_KEY" to "y",
            "STA_KEEP_ENV" to "GH_TOKEN",
        )

        assertEquals(listOf("AWS_SECRET_ACCESS_KEY"), ShellEnvironmentPolicy.sanitize(environment))
        assertEquals(setOf("GH_TOKEN"), environment.keys)
    }
}
