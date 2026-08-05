package dev.browserrestrictor.retro.domain

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PeriodCalculatorTest {
    @Test
    fun mondayIsWeeklyStart() {
        val zone = ZoneId.of("Europe/Paris")
        val instant = ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val keys = PeriodCalculator.keysAt(instant, zone)
        assertEquals("2026-07-10", keys.daily)
        assertEquals("2026-07-06", keys.weekly)
        assertEquals("2026-07", keys.monthly)
    }

    @Test
    fun intervalSplitsExactlyAtMidnight() {
        val zone = ZoneId.of("UTC")
        val start = ZonedDateTime.of(2026, 7, 10, 23, 59, 59, 500_000_000, zone).toInstant().toEpochMilli()
        val intervals = PeriodCalculator.splitAtLocalMidnights(start, start + 1_000, zone)
        assertEquals(listOf(500L, 500L), intervals.map { it.durationMs })
    }

    @Test
    fun dstDayUsesLocalMidnightRatherThanTwentyFourHours() {
        val zone = ZoneId.of("Europe/Paris")
        val start = LocalDateTime.of(2026, 3, 29, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val next = PeriodCalculator.nextLocalMidnight(start, zone)
        assertEquals(23 * HOUR_MS, next - start)
    }
}
