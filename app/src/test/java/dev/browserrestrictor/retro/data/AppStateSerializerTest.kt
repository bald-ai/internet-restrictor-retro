package dev.browserrestrictor.retro.data

import androidx.datastore.core.CorruptionException
import dev.browserrestrictor.retro.domain.BudgetPeriod
import dev.browserrestrictor.retro.domain.DailyCounter
import dev.browserrestrictor.retro.domain.DailyUsageReport
import dev.browserrestrictor.retro.domain.OffUsageReminderState
import dev.browserrestrictor.retro.domain.PendingWait
import dev.browserrestrictor.retro.domain.PersistedState
import dev.browserrestrictor.retro.domain.RestrictorSettings
import dev.browserrestrictor.retro.domain.ThemePreference
import dev.browserrestrictor.retro.domain.UnlockSession
import dev.browserrestrictor.retro.domain.UnlockSource
import dev.browserrestrictor.retro.domain.UsagePeriodState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateSerializerTest {
    @Test
    fun stateRoundTripsWithoutDroppingRuntimeRules() = runTest {
        val state = PersistedState(
            stateRevision = 42,
            settings = RestrictorSettings(
                theme = ThemePreference.LIGHT,
                delayEnabled = false,
                enforcementEnabled = false,
                pauseUntilWallMs = 999_999,
            ),
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, "2026-07-10", 12_345),
            weeklyUsage = UsagePeriodState(BudgetPeriod.WEEKLY, "2026-07-06", 23_456),
            monthlyUsage = UsagePeriodState(BudgetPeriod.MONTHLY, "2026-07", 34_567),
            activeUnlock = UnlockSession("unlock", UnlockSource.EMERGENCY, 100, 200),
            dailyCounter = DailyCounter("2026-07-10", 2, 1),
            usageReports = listOf(DailyUsageReport("2026-07-10", 5_000, 6_000)),
            offUsageReminder = OffUsageReminderState(
                sessionStartedAtWallMs = 100,
                qualifyingUsageMs = 6_000,
            ),
        )
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(state, restored)
    }

    @Test
    fun schemaV1BytesMigrateWithoutLosingPersistedPhoneState() = runTest {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { target ->
                target.writeInt(0x49525354)
                target.writeInt(1)
                target.writeLong(77)
                target.writeBoolean(false)
                target.writeBoolean(false)
                target.writeBoolean(true)
                target.writeLong(17)
                target.writeBoolean(true)
                target.writeLong(170)
                target.writeBoolean(true)
                target.writeLong(1_700)
                target.writeBoolean(true)
                target.writeLong(2)
                target.writeInt(ThemePreference.LIGHT.ordinal)
                target.writeBoolean(true)
                target.writeUTF("2026-07-19")
                target.writeLong(12_345)
                target.writeUTF("2026-07-13")
                target.writeLong(23_456)
                target.writeUTF("2026-07")
                target.writeLong(34_567)
                target.writeBoolean(false)
                target.writeBoolean(false)
                target.writeUTF("2026-07-19")
                target.writeLong(4)
                target.writeLong(1)
                target.writeLong(99_000)
                target.writeUTF("legacy-epoch")
                target.writeLong(98_000)
                target.writeInt(0)
            }
        }.toByteArray()

        val migrated = AppStateSerializer.readFrom(ByteArrayInputStream(bytes))

        assertEquals(2, migrated.schemaVersion)
        assertEquals(77, migrated.stateRevision)
        assertEquals(false, migrated.settings.enforcementEnabled)
        assertEquals(false, migrated.settings.delayEnabled)
        assertEquals(17, migrated.settings.dailyBudgetMinutes)
        assertEquals(12_345, migrated.dailyUsage.usedMs)
        assertEquals(23_456, migrated.weeklyUsage.usedMs)
        assertEquals(34_567, migrated.monthlyUsage.usedMs)
        assertEquals(4, migrated.dailyCounter.completedNormalUnlocks)
        assertEquals("legacy-epoch", migrated.runtimeMetadata.recoveryEpochId)
        assertEquals(emptyList<DailyUsageReport>(), migrated.usageReports)
        assertEquals(false, migrated.currentVisitGraceActive)
    }

    @Test
    fun currentVisitGraceSurvivesV2PersistenceRoundTrip() = runTest {
        val state = PersistedState(currentVisitGraceActive = true)
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(true, restored.currentVisitGraceActive)
    }

    @Test
    fun earlySchemaV2BytesWithoutGraceFieldKeepMeasuredUsage() = runTest {
        val state = PersistedState(
            stateRevision = 12,
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, "2026-07-20", 1_494_393),
            weeklyUsage = UsagePeriodState(BudgetPeriod.WEEKLY, "2026-07-20", 1_494_393),
            monthlyUsage = UsagePeriodState(BudgetPeriod.MONTHLY, "2026-07", 3_663_000),
            usageReports = listOf(
                DailyUsageReport("2026-07-19", offUsageMs = 22_935),
                DailyUsageReport("2026-07-20", protectedUsageMs = 1_494_393),
            ),
            currentVisitGraceActive = true,
        )
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val earlyV2Bytes = output.toByteArray().copyOf(output.size() - 1)

        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(earlyV2Bytes))

        assertEquals(state.copy(currentVisitGraceActive = false), restored)
    }

    @Test(expected = CorruptionException::class)
    fun invalidMagicIsReportedAsCorruption() = runTest {
        AppStateSerializer.readFrom(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
    }

    @Test(expected = CorruptionException::class)
    fun overlappingUnlockAndWaitCannotBeWritten() = runTest {
        val state = PersistedState(
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, 100, 200),
            pendingWait = PendingWait("wait", 1_000, 100, 0, 100, 100),
        )
        AppStateSerializer.writeTo(state, ByteArrayOutputStream())
    }
}
