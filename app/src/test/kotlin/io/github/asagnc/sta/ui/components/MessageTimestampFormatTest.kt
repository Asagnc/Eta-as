package io.github.asagnc.sta.ui.components

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTimestampFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `same day shows clock only`() {
        val now = millisOf(2026, 9, 18, 12, 0)
        assertEquals("04:15:00", formatMessageTimestamp(millisOf(2026, 9, 18, 4, 15), now, zone))
    }

    @Test
    fun `yesterday is labelled`() {
        val now = millisOf(2026, 9, 18, 12, 0)
        assertEquals("昨天 23:40:30", formatMessageTimestamp(millisOf(2026, 9, 17, 23, 40, 30), now, zone))
    }

    @Test
    fun `older messages show date and clock`() {
        val now = millisOf(2026, 9, 18, 12, 0)
        assertEquals("09-15 08:05:00", formatMessageTimestamp(millisOf(2026, 9, 15, 8, 5), now, zone))
    }

    @Test
    fun `missing timestamp renders nothing`() {
        assertEquals("", formatMessageTimestamp(0L, millisOf(2026, 9, 18, 12, 0), zone))
    }
}
