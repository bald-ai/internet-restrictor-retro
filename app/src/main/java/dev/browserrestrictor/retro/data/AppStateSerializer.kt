package dev.browserrestrictor.retro.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.browserrestrictor.retro.domain.BudgetPeriod
import dev.browserrestrictor.retro.domain.DailyCounter
import dev.browserrestrictor.retro.domain.DailyUsageReport
import dev.browserrestrictor.retro.domain.ForegroundClass
import dev.browserrestrictor.retro.domain.MAX_SAFE_MINUTES
import dev.browserrestrictor.retro.domain.PendingWait
import dev.browserrestrictor.retro.domain.OffUsageReminderState
import dev.browserrestrictor.retro.domain.PersistedState
import dev.browserrestrictor.retro.domain.RESTRICTOR_SCHEMA_VERSION
import dev.browserrestrictor.retro.domain.RestrictionEngine
import dev.browserrestrictor.retro.domain.RestrictorSettings
import dev.browserrestrictor.retro.domain.RuntimeMetadata
import dev.browserrestrictor.retro.domain.ThemePreference
import dev.browserrestrictor.retro.domain.UnlockSession
import dev.browserrestrictor.retro.domain.UnlockSource
import dev.browserrestrictor.retro.domain.UsagePeriodState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object AppStateSerializer : Serializer<PersistedState> {
    private const val MAGIC = 0x49525354 // IRST

    override val defaultValue: PersistedState = PersistedState()

    override suspend fun readFrom(input: InputStream): PersistedState {
        try {
            val source = DataInputStream(input)
            if (source.readInt() != MAGIC) throw CorruptionException("Unrecognized state file")
            val schema = source.readInt()
            if (schema !in 1..RESTRICTOR_SCHEMA_VERSION) {
                throw CorruptionException("Unsupported state schema: $schema")
            }
            val revision = source.readLong()
            val settings = RestrictorSettings(
                enforcementEnabled = source.readBoolean(),
                pauseUntilWallMs = if (schema >= 2 && source.readBoolean()) source.readLong() else null,
                delayEnabled = source.readBoolean(),
                dailyBudgetEnabled = source.readBoolean(),
                dailyBudgetMinutes = source.readLong(),
                weeklyBudgetEnabled = source.readBoolean(),
                weeklyBudgetMinutes = source.readLong(),
                monthlyBudgetEnabled = source.readBoolean(),
                monthlyBudgetMinutes = source.readLong(),
                emergencySkipsEnabled = source.readBoolean(),
                emergencySkipDailyLimit = source.readLong(),
                theme = source.readEnum<ThemePreference>(),
                onboardingCompleted = source.readBoolean(),
            )
            val daily = source.readUsage(BudgetPeriod.DAILY)
            val weekly = source.readUsage(BudgetPeriod.WEEKLY)
            val monthly = source.readUsage(BudgetPeriod.MONTHLY)
            val unlock = if (source.readBoolean()) {
                UnlockSession(
                    id = source.readUTF(),
                    source = source.readEnum<UnlockSource>(),
                    grantedAtWallMs = source.readLong(),
                    expiresAtWallMs = source.readLong(),
                )
            } else null
            val pending = if (source.readBoolean()) {
                PendingWait(
                    id = source.readUTF(),
                    selectedDurationMs = source.readLong(),
                    requiredFocusMs = source.readLong(),
                    accumulatedFocusMs = source.readLong(),
                    createdAtWallMs = source.readLong(),
                    updatedAtWallMs = source.readLong(),
                )
            } else null
            val counter = DailyCounter(
                localDayKey = source.readUTF(),
                completedNormalUnlocks = source.readLong(),
                emergencySkipsUsed = source.readLong(),
            )
            val metadata = RuntimeMetadata(
                lastCommittedWallMs = source.readLong(),
                recoveryEpochId = source.readUTF(),
                serviceLastConnectedWallMs = source.readLong(),
                lastKnownForegroundClass = source.readEnum<ForegroundClass>(),
            )
            val reports = if (schema >= 2) {
                List(source.readInt()) {
                    DailyUsageReport(
                        localDayKey = source.readUTF(),
                        protectedUsageMs = source.readLong(),
                        offUsageMs = source.readLong(),
                    )
                }
            } else emptyList()
            val reminder = if (schema >= 2) {
                OffUsageReminderState(
                    sessionStartedAtWallMs = source.readLong(),
                    qualifyingUsageMs = source.readLong(),
                    firstNotificationSent = source.readBoolean(),
                    lastNotificationWallMs = source.readLong(),
                    usageAtLastNotificationMs = source.readLong(),
                )
            } else OffUsageReminderState()
            val currentVisitGraceActive = if (schema >= 2) {
                try {
                    source.readBoolean()
                } catch (_: EOFException) {
                    // Early schema-v2 development builds ended after the reminder state.
                    false
                }
            } else false
            val state = PersistedState(
                schemaVersion = RESTRICTOR_SCHEMA_VERSION,
                stateRevision = revision,
                settings = settings,
                dailyUsage = daily,
                weeklyUsage = weekly,
                monthlyUsage = monthly,
                activeUnlock = unlock,
                pendingWait = pending,
                dailyCounter = counter,
                runtimeMetadata = metadata,
                usageReports = reports,
                offUsageReminder = reminder,
                currentVisitGraceActive = currentVisitGraceActive,
            )
            validate(state)
            return state
        } catch (error: CorruptionException) {
            throw error
        } catch (error: Exception) {
            throw CorruptionException("State could not be read", error)
        }
    }

    override suspend fun writeTo(t: PersistedState, output: OutputStream) {
        validate(t)
        val target = DataOutputStream(output)
        target.writeInt(MAGIC)
        target.writeInt(RESTRICTOR_SCHEMA_VERSION)
        target.writeLong(t.stateRevision)
        with(t.settings) {
            target.writeBoolean(enforcementEnabled)
            target.writeBoolean(pauseUntilWallMs != null)
            pauseUntilWallMs?.let(target::writeLong)
            target.writeBoolean(delayEnabled)
            target.writeBoolean(dailyBudgetEnabled)
            target.writeLong(dailyBudgetMinutes)
            target.writeBoolean(weeklyBudgetEnabled)
            target.writeLong(weeklyBudgetMinutes)
            target.writeBoolean(monthlyBudgetEnabled)
            target.writeLong(monthlyBudgetMinutes)
            target.writeBoolean(emergencySkipsEnabled)
            target.writeLong(emergencySkipDailyLimit)
            target.writeInt(theme.ordinal)
            target.writeBoolean(onboardingCompleted)
        }
        target.writeUsage(t.dailyUsage)
        target.writeUsage(t.weeklyUsage)
        target.writeUsage(t.monthlyUsage)
        target.writeBoolean(t.activeUnlock != null)
        t.activeUnlock?.let {
            target.writeUTF(it.id)
            target.writeInt(it.source.ordinal)
            target.writeLong(it.grantedAtWallMs)
            target.writeLong(it.expiresAtWallMs)
        }
        target.writeBoolean(t.pendingWait != null)
        t.pendingWait?.let {
            target.writeUTF(it.id)
            target.writeLong(it.selectedDurationMs)
            target.writeLong(it.requiredFocusMs)
            target.writeLong(it.accumulatedFocusMs)
            target.writeLong(it.createdAtWallMs)
            target.writeLong(it.updatedAtWallMs)
        }
        with(t.dailyCounter) {
            target.writeUTF(localDayKey)
            target.writeLong(completedNormalUnlocks)
            target.writeLong(emergencySkipsUsed)
        }
        with(t.runtimeMetadata) {
            target.writeLong(lastCommittedWallMs)
            target.writeUTF(recoveryEpochId)
            target.writeLong(serviceLastConnectedWallMs)
            target.writeInt(lastKnownForegroundClass.ordinal)
        }
        target.writeInt(t.usageReports.size)
        t.usageReports.forEach {
            target.writeUTF(it.localDayKey)
            target.writeLong(it.protectedUsageMs)
            target.writeLong(it.offUsageMs)
        }
        with(t.offUsageReminder) {
            target.writeLong(sessionStartedAtWallMs)
            target.writeLong(qualifyingUsageMs)
            target.writeBoolean(firstNotificationSent)
            target.writeLong(lastNotificationWallMs)
            target.writeLong(usageAtLastNotificationMs)
        }
        target.writeBoolean(t.currentVisitGraceActive)
        target.flush()
    }

    private fun validate(state: PersistedState) {
        fun invalid(message: String): Nothing = throw CorruptionException(message)
        if (state.schemaVersion != RESTRICTOR_SCHEMA_VERSION) invalid("Wrong schema")
        if (state.stateRevision < 0) invalid("Negative revision")
        if ((state.settings.pauseUntilWallMs ?: 0) < 0) invalid("Invalid pause end")
        val numericSettings = listOf(
            state.settings.dailyBudgetMinutes,
            state.settings.weeklyBudgetMinutes,
            state.settings.monthlyBudgetMinutes,
            state.settings.emergencySkipDailyLimit,
        )
        if (numericSettings.any { it !in 0..MAX_SAFE_MINUTES }) invalid("Invalid setting value")
        listOf(state.dailyUsage, state.weeklyUsage, state.monthlyUsage).forEach {
            if (it.usedMs < 0) invalid("Negative usage")
        }
        state.activeUnlock?.let {
            if (it.id.isBlank() || it.grantedAtWallMs < 0 || it.expiresAtWallMs < it.grantedAtWallMs) {
                invalid("Invalid unlock")
            }
        }
        state.pendingWait?.let {
            if (
                it.id.isBlank() || it.selectedDurationMs <= 0 || it.requiredFocusMs < 0 ||
                it.accumulatedFocusMs !in 0..it.requiredFocusMs || it.createdAtWallMs < 0 ||
                it.updatedAtWallMs < it.createdAtWallMs
            ) invalid("Invalid pending wait")
        }
        if (state.activeUnlock != null && state.pendingWait != null) invalid("Unlock and wait overlap")
        if (state.dailyCounter.completedNormalUnlocks < 0 || state.dailyCounter.emergencySkipsUsed < 0) {
            invalid("Invalid daily counter")
        }
        if (state.usageReports.size > RestrictionEngine.MAX_REPORT_DAYS) invalid("Too many report days")
        state.usageReports.forEach {
            if (it.localDayKey.isBlank() || it.protectedUsageMs < 0 || it.offUsageMs < 0) {
                invalid("Invalid usage report")
            }
        }
        with(state.offUsageReminder) {
            if (
                sessionStartedAtWallMs < 0 || qualifyingUsageMs < 0 || lastNotificationWallMs < 0 ||
                usageAtLastNotificationMs < 0 || usageAtLastNotificationMs > qualifyingUsageMs
            ) invalid("Invalid reminder state")
        }
    }

    private fun DataOutputStream.writeUsage(usage: UsagePeriodState) {
        writeUTF(usage.key)
        writeLong(usage.usedMs)
    }

    private fun DataInputStream.readUsage(period: BudgetPeriod) = UsagePeriodState(
        period = period,
        key = readUTF(),
        usedMs = readLong(),
    )

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val values = enumValues<T>()
        val ordinal = readInt()
        if (ordinal !in values.indices) throw CorruptionException("Invalid enum value")
        return values[ordinal]
    }
}
