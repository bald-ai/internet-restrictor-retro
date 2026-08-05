package dev.browserrestrictor.retro.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object ProtectionSchedule {
    fun nextLocalNoon(nowWallMs: Long, zoneId: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowWallMs).atZone(zoneId)
        var noon = now.toLocalDate().atTime(12, 0).atZone(zoneId)
        if (!noon.toInstant().isAfter(now.toInstant())) noon = noon.plusDays(1)
        return noon.toInstant().toEpochMilli()
    }

    fun firstLocalNoonAfter(startWallMs: Long, zoneId: ZoneId): Long {
        val start = Instant.ofEpochMilli(startWallMs).atZone(zoneId)
        var noon = start.toLocalDate().atTime(12, 0).atZone(zoneId)
        if (!noon.toInstant().isAfter(start.toInstant())) noon = noon.plusDays(1)
        return noon.toInstant().toEpochMilli()
    }

    fun isResumeDue(state: PersistedState, nowWallMs: Long, zoneId: ZoneId): Boolean {
        if (state.settings.enforcementEnabled) return false
        if (state.settings.pauseUntilWallMs?.let { it <= nowWallMs } == true) return true
        val offStarted = state.offUsageReminder.sessionStartedAtWallMs
        return offStarted > 0 && firstLocalNoonAfter(offStarted, zoneId) <= nowWallMs
    }

    fun rebaseAfterBackwardClockChange(
        state: PersistedState,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        if (state.settings.enforcementEnabled) return state
        val previousStart = state.offUsageReminder.sessionStartedAtWallMs
        if (previousStart <= 0 || previousStart <= nowWallMs) return state

        val now = Instant.ofEpochMilli(nowWallMs).atZone(zoneId)
        val todayNoon = now.toLocalDate().atTime(LocalTime.NOON).atZone(zoneId)
        val rebasedStart = if (now.toInstant().isBefore(todayNoon.toInstant())) {
            nowWallMs
        } else {
            now.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
        val pauseDuration = state.settings.pauseUntilWallMs
            ?.takeIf { it > previousStart }
            ?.let { it - previousStart }
        val rebasedPause = pauseDuration?.let { duration ->
            if (duration > Long.MAX_VALUE - nowWallMs) Long.MAX_VALUE else nowWallMs + duration
        }
        return state.copy(
            settings = state.settings.copy(pauseUntilWallMs = rebasedPause),
            offUsageReminder = state.offUsageReminder.copy(sessionStartedAtWallMs = rebasedStart),
        )
    }
}
