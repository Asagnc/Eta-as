package io.github.asagnc.sta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.asagnc.sta", "io.github.asagnc.sta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.asagnc.sta:voice", "io.github.asagnc.sta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.asagnc.sta:voice_session", "io.github.asagnc.sta"))
    }
}
