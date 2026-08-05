package dev.browserrestrictor.retro.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionScheduleTest {
    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun missedPreviousDayNoonIsDueBeforeTodaysNoon() {
        val started = at(2026, 7, 20, 10, 0)
        val nextMorning = at(2026, 7, 21, 9, 0)
        val state = PersistedState(
            settings = RestrictorSettings(enforcementEnabled = false),
            offUsageReminder = OffUsageReminderState(sessionStartedAtWallMs = started),
        )
        assertTrue(ProtectionSchedule.isResumeDue(state, nextMorning, zone))
    }

    @Test
    fun pauseStartedAfterNoonWaitsForNextNoon() {
        val started = at(2026, 7, 20, 13, 0)
        val sameEvening = at(2026, 7, 20, 20, 0)
        val state = PersistedState(
            settings = RestrictorSettings(enforcementEnabled = false),
            offUsageReminder = OffUsageReminderState(sessionStartedAtWallMs = started),
        )
        assertFalse(ProtectionSchedule.isResumeDue(state, sameEvening, zone))
        assertTrue(ProtectionSchedule.isResumeDue(state, at(2026, 7, 21, 12, 0), zone))
    }

    @Test
    fun expiredDurationIsDueEvenBeforeNoon() {
        val started = at(2026, 7, 20, 7, 0)
        val pauseEnd = at(2026, 7, 20, 9, 0)
        val state = PersistedState(
            settings = RestrictorSettings(
                enforcementEnabled = false,
                pauseUntilWallMs = pauseEnd,
            ),
            offUsageReminder = OffUsageReminderState(sessionStartedAtWallMs = started),
        )
        assertTrue(ProtectionSchedule.isResumeDue(state, pauseEnd, zone))
    }

    @Test
    fun backwardDateChangeRebasesDailyPauseToTheNewCurrentDay() {
        val previousStart = at(2026, 7, 20, 10, 0)
        val correctedNow = at(2026, 7, 19, 11, 0)
        val state = PersistedState(
            settings = RestrictorSettings(enforcementEnabled = false),
            offUsageReminder = OffUsageReminderState(sessionStartedAtWallMs = previousStart),
        )

        val rebased = ProtectionSchedule.rebaseAfterBackwardClockChange(state, correctedNow, zone)

        assertEquals(correctedNow, rebased.offUsageReminder.sessionStartedAtWallMs)
        assertTrue(ProtectionSchedule.isResumeDue(rebased, at(2026, 7, 19, 12, 0), zone))
    }

    @Test
    fun backwardDateChangeAfterNoonMakesRecoveryImmediatelyDue() {
        val state = PersistedState(
            settings = RestrictorSettings(enforcementEnabled = false),
            offUsageReminder = OffUsageReminderState(
                sessionStartedAtWallMs = at(2026, 7, 20, 14, 0),
            ),
        )
        val correctedNow = at(2026, 7, 19, 13, 0)

        val rebased = ProtectionSchedule.rebaseAfterBackwardClockChange(state, correctedNow, zone)

        assertTrue(ProtectionSchedule.isResumeDue(rebased, correctedNow, zone))
    }

    @Test
    fun backwardClockChangeKeepsDurationPauseBoundedToItsOriginalLength() {
        val previousStart = at(2026, 7, 20, 8, 0)
        val state = PersistedState(
            settings = RestrictorSettings(
                enforcementEnabled = false,
                pauseUntilWallMs = previousStart + 2 * HOUR_MS,
            ),
            offUsageReminder = OffUsageReminderState(sessionStartedAtWallMs = previousStart),
        )
        val correctedNow = at(2026, 7, 19, 7, 0)

        val rebased = ProtectionSchedule.rebaseAfterBackwardClockChange(state, correctedNow, zone)

        assertEquals(correctedNow + 2 * HOUR_MS, rebased.settings.pauseUntilWallMs)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
