package io.github.asagnc.eta.data.world

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorldHealthTest {

    @Before
    fun setUp() {
        // 纯 JVM 单测里 android.util.Log 是未实现的 stub，一调就抛「not mocked」。
        // 换掉日志通道后，这里测的是状态记录本身，真机上仍然是正常日志。
        WorldHealth.logSink = {}
    }

    @After
    fun tearDown() {
        WorldHealth.resetForTests()
    }

    @Test
    fun startsClean() {
        assertEquals(0, WorldHealth.degradedCount())
        assertEquals(0, WorldHealth.unexpectedCount())
        assertNull(WorldHealth.lastIncident())
        // 没有降级时不该给警告：那只是白白占上下文。
        assertNull(WorldHealth.warningLine())
    }

    @Test
    fun recordsDegradationWithContext() {
        WorldHealth.recordDegradation("knowledge.write(failure)", RuntimeException("disk gone"))

        val incident = WorldHealth.lastIncident()!!
        assertEquals("knowledge.write(failure)", incident.operation)
        assertEquals("RuntimeException", incident.errorType)
        assertEquals("disk gone", incident.message)
        assertEquals(1, WorldHealth.degradedCount())
    }

    @Test
    fun expectedFailuresDoNotRaiseWarning() {
        // 锁竞争是设计内的情况：写入方分布在多条路径上，偶发冲突不影响结果完整性。
        // 把它也报成警告会让警告变成噪声，而噪声会让真正的异常被忽略。
        WorldHealth.recordDegradation("knowledge.write(failure)", sqliteError("SQLiteDatabaseLockedException"))

        assertEquals(1, WorldHealth.degradedCount())
        assertEquals(0, WorldHealth.unexpectedCount())
        assertNull(WorldHealth.warningLine())
    }

    @Test
    fun diskFullIsTreatedAsExpected() {
        // 本层是可丢弃的观测数据，空间不足时静默跳过是正确取舍。
        WorldHealth.recordDegradation("trace.record", sqliteError("SQLiteFullException"))

        assertNull(WorldHealth.warningLine())
    }

    @Test
    fun unexpectedFailuresRaiseWarning() {
        // schema 损坏这类不是设计内的：它会让观测层持续失效，必须让人看见。
        WorldHealth.recordDegradation("knowledge.search", sqliteError("SQLiteException"))

        assertEquals(1, WorldHealth.degradedCount())
        assertEquals(1, WorldHealth.unexpectedCount())
        val warning = WorldHealth.warningLine()!!
        assertTrue(warning.contains("可能不完整"))
        assertTrue(warning.contains("knowledge.search"))
        assertTrue(warning.contains("SQLiteException"))
    }

    @Test
    fun warningCountsOnlyUnexpectedDegradations() {
        WorldHealth.recordDegradation("a", sqliteError("SQLiteDatabaseLockedException"))
        WorldHealth.recordDegradation("b", sqliteError("SQLiteException"))
        WorldHealth.recordDegradation("c", RuntimeException("boom"))

        // 三次降级里只有两次属于异常信号，警告里报的应该是后者。
        assertEquals(3, WorldHealth.degradedCount())
        assertEquals(2, WorldHealth.unexpectedCount())
        assertTrue(WorldHealth.warningLine()!!.contains("2 次异常降级"))
    }

    @Test
    fun warningCarriesAgeAndOperation() {
        WorldHealth.recordDegradation("knowledge.recall(failure)", RuntimeException("boom"))

        val warning = WorldHealth.warningLine()!!

        // 时间戳是相关性代理：让人判断这是刚发生的还是很久以前的。
        assertTrue(warning.contains("前"))
        assertTrue(warning.contains("knowledge.recall(failure)"))
    }

    @Test
    fun lastIncidentTracksTheMostRecentOne() {
        WorldHealth.recordDegradation("first", RuntimeException("one"))
        WorldHealth.recordDegradation("second", RuntimeException("two"))

        assertEquals("second", WorldHealth.lastIncident()!!.operation)
        assertEquals(2, WorldHealth.degradedCount())
    }

    @Test
    fun marksWhetherIncidentWasExpected() {
        WorldHealth.recordDegradation("a", sqliteError("SQLiteDatabaseLockedException"))
        assertTrue(WorldHealth.lastIncident()!!.expected)

        WorldHealth.recordDegradation("b", RuntimeException("boom"))
        assertFalse(WorldHealth.lastIncident()!!.expected)
    }

    @Test
    fun truncatesLongMessages() {
        WorldHealth.recordDegradation("op", RuntimeException("x".repeat(1000)))

        // 警告行会被注入上下文，不能让一条超长错误信息撑爆它。
        assertTrue(WorldHealth.lastIncident()!!.message.length <= 200)
    }

    @Test
    fun handlesNullMessage() {
        WorldHealth.recordDegradation("op", RuntimeException())

        assertNotNull(WorldHealth.lastIncident())
        assertEquals("", WorldHealth.lastIncident()!!.message)
    }

    /**
     * 构造一个类型名匹配的 SQLite 异常。
     *
     * 用匿名子类而不是直接 new：`recordDegradation` 按 `simpleName` 判断预期性，
     * 而真实的 `android.database.sqlite.*` 异常在纯 JVM 单测里没有实现。
     */
    private fun sqliteError(typeName: String): Throwable = when (typeName) {
        "SQLiteDatabaseLockedException" -> NamedError.SQLiteDatabaseLockedException()
        "SQLiteFullException" -> NamedError.SQLiteFullException()
        else -> NamedError.SQLiteException()
    }

    @Suppress("ClassName")
    private object NamedError {
        class SQLiteDatabaseLockedException : RuntimeException("locked")
        class SQLiteFullException : RuntimeException("full")
        class SQLiteException : RuntimeException("broken")
    }
}
