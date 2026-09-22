package io.github.asagnc.eta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.asagnc.eta", "io.github.asagnc.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.asagnc.eta:voice", "io.github.asagnc.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.asagnc.eta:voice_session", "io.github.asagnc.eta"))
    }
}
