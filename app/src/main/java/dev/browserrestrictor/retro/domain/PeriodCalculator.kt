package dev.browserrestrictor.retro.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class PeriodKeys(
    val daily: String,
    val weekly: String,
    val monthly: String,
)

data class WallInterval(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs)
    }

    val durationMs: Long get() = endMs - startMs
}

object PeriodCalculator {
    fun keysAt(wallMs: Long, zoneId: ZoneId): PeriodKeys {
        val date = Instant.ofEpochMilli(wallMs).atZone(zoneId).toLocalDate()
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return PeriodKeys(
            daily = date.toString(),
            weekly = monday.toString(),
            monthly = YearMonth.from(date).toString(),
        )
    }

    fun nextLocalMidnight(wallMs: Long, zoneId: ZoneId): Long {
        val current = Instant.ofEpochMilli(wallMs).atZone(zoneId)
        return current.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    fun splitAtLocalMidnights(startMs: Long, endMs: Long, zoneId: ZoneId): List<WallInterval> {
        if (endMs <= startMs) return emptyList()
        val result = mutableListOf<WallInterval>()
        var cursor = startMs
        while (cursor < endMs) {
            val boundary = nextLocalMidnight(cursor, zoneId)
            val segmentEnd = minOf(endMs, boundary)
            result += WallInterval(cursor, segmentEnd)
            cursor = segmentEnd
        }
        return result
    }
}
