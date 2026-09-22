package io.github.asagnc.eta.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移链的结构断言。
 *
 * 这个测试**故意不依赖 Robolectric**：`EtaMigrations` 只用到 `androidx.room.migration.Migration`，
 * 所以能在本地 aarch64 上跑。而真正的迁移行为测试（`EtaDatabaseMigrationTest`）需要 Robolectric，
 * 本地跑不了、只能等 CI——"版本号涨了但迁移没接上"这类错误以前就只能在 CI 里暴露。
 *
 * 把结构约束挪到本地可跑的这一层，是这次 CI 失败换来的教训。
 */
class EtaMigrationsChainTest {

    private val migrations = EtaMigrations.ALL

    @Test
    fun `迁移链连续覆盖从最老支持版本到当前版本`() {
        val sorted = migrations.sortedBy { it.startVersion }
        assertTrue("迁移链不能为空", sorted.isNotEmpty())
        assertEquals(
            "链的起点必须是 MIN_SUPPORTED_VERSION",
            EtaMigrations.MIN_SUPPORTED_VERSION,
            sorted.first().startVersion,
        )
        var expected = EtaMigrations.MIN_SUPPORTED_VERSION
        sorted.forEach { migration ->
            assertEquals(
                "迁移链出现断点：期望 $expected -> ${expected + 1}，实际 " +
                    "${migration.startVersion} -> ${migration.endVersion}",
                expected,
                migration.startVersion,
            )
            assertEquals(
                "一步迁移不能跨多个版本：${migration.startVersion} -> ${migration.endVersion}",
                expected + 1,
                migration.endVersion,
            )
            expected = migration.endVersion
        }
        assertEquals(
            "迁移链末端必须到达 CURRENT_VERSION（涨版本号时别忘了接上迁移）",
            EtaMigrations.CURRENT_VERSION,
            expected,
        )
    }

    @Test
    fun `没有重复的迁移段`() {
        val pairs = migrations.map { it.startVersion to it.endVersion }
        assertEquals("存在重复的迁移段：$pairs", pairs.size, pairs.toSet().size)
    }

    @Test
    fun `当前版本与最老支持版本的常量互相自洽`() {
        assertTrue(
            "MIN_SUPPORTED_VERSION 应小于 CURRENT_VERSION",
            EtaMigrations.MIN_SUPPORTED_VERSION < EtaMigrations.CURRENT_VERSION,
        )
    }
}
