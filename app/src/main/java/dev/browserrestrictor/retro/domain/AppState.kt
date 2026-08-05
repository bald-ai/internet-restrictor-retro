package dev.browserrestrictor.retro.domain

import java.util.UUID

data class SupportedBrowser(
    val name: String,
    val packageName: String,
)

val SUPPORTED_BROWSERS = listOf(
    SupportedBrowser("Chrome", "com.android.chrome"),
    SupportedBrowser("Firefox", "org.mozilla.firefox"),
    SupportedBrowser("Edge", "com.microsoft.emmx"),
    SupportedBrowser("Opera", "com.opera.browser"),
)
val SUPPORTED_BROWSER_PACKAGES = SUPPORTED_BROWSERS.mapTo(linkedSetOf()) { it.packageName }

fun isSupportedBrowserPackage(packageName: String?): Boolean =
    packageName != null && packageName in SUPPORTED_BROWSER_PACKAGES
const val RESTRICTOR_SCHEMA_VERSION = 2
const val MINUTE_MS = 60_000L
const val HOUR_MS = 60L * MINUTE_MS
const val DAY_MS = 24L * HOUR_MS
const val PENDING_WAIT_EXPIRY_MS = DAY_MS
const val EMERGENCY_UNLOCK_MS = 30L * MINUTE_MS
const val MAX_SAFE_MINUTES = Long.MAX_VALUE / MINUTE_MS

enum class ThemePreference { DARK, LIGHT }

enum class BudgetPeriod { DAILY, WEEKLY, MONTHLY }

enum class UnlockSource { NORMAL, EMERGENCY }

enum class ForegroundClass { BROWSER, RESTRICTOR, OTHER }

enum class GateMode {
    SERVICE_UNAVAILABLE,
    ENFORCEMENT_DISABLED,
    BROWSER_BACKGROUND,
    PAUSED,
    WAITING,
    ACCESS_GRANTED_NORMAL,
    ACCESS_GRANTED_EMERGENCY,
    ACCESS_GRANTED_NO_DELAY,
    ACCESS_GRANTED_CURRENT_VISIT,
    OUT_OF_TIME,
    OUT_OF_TIME_LATCHED,
    STORAGE_ERROR,
}

enum class ProtectionHealth {
    READY,
    ENFORCEMENT_OFF,
    ACCESSIBILITY_REQUIRED,
    SERVICE_DISCONNECTED,
    BROWSERS_NOT_FOUND,
    STORAGE_ERROR,
    ADVANCED_PROTECTION_BLOCKED,
}

data class RestrictorSettings(
    val enforcementEnabled: Boolean = true,
    val pauseUntilWallMs: Long? = null,
    val delayEnabled: Boolean = true,
    val dailyBudgetEnabled: Boolean = true,
    val dailyBudgetMinutes: Long = 60,
    val weeklyBudgetEnabled: Boolean = true,
    val weeklyBudgetMinutes: Long = 600,
    val monthlyBudgetEnabled: Boolean = true,
    val monthlyBudgetMinutes: Long = 2_400,
    val emergencySkipsEnabled: Boolean = true,
    val emergencySkipDailyLimit: Long = 3,
    val theme: ThemePreference = ThemePreference.DARK,
    val onboardingCompleted: Boolean = false,
)

data class DailyUsageReport(
    val localDayKey: String,
    val protectedUsageMs: Long = 0,
    val offUsageMs: Long = 0,
)

data class OffUsageReminderState(
    val sessionStartedAtWallMs: Long = 0,
    val qualifyingUsageMs: Long = 0,
    val firstNotificationSent: Boolean = false,
    val lastNotificationWallMs: Long = 0,
    val usageAtLastNotificationMs: Long = 0,
)

data class UsagePeriodState(
    val period: BudgetPeriod,
    val key: String = "",
    val usedMs: Long = 0,
)

data class UnlockSession(
    val id: String,
    val source: UnlockSource,
    val grantedAtWallMs: Long,
    val expiresAtWallMs: Long,
)

data class PendingWait(
    val id: String,
    val selectedDurationMs: Long,
    val requiredFocusMs: Long,
    val accumulatedFocusMs: Long,
    val createdAtWallMs: Long,
    val updatedAtWallMs: Long,
)

data class DailyCounter(
    val localDayKey: String = "",
    val completedNormalUnlocks: Long = 0,
    val emergencySkipsUsed: Long = 0,
)

data class RuntimeMetadata(
    val lastCommittedWallMs: Long = 0,
    val recoveryEpochId: String = "",
    val serviceLastConnectedWallMs: Long = 0,
    val lastKnownForegroundClass: ForegroundClass = ForegroundClass.OTHER,
)

data class PersistedState(
    val schemaVersion: Int = RESTRICTOR_SCHEMA_VERSION,
    val stateRevision: Long = 0,
    val settings: RestrictorSettings = RestrictorSettings(),
    val dailyUsage: UsagePeriodState = UsagePeriodState(BudgetPeriod.DAILY),
    val weeklyUsage: UsagePeriodState = UsagePeriodState(BudgetPeriod.WEEKLY),
    val monthlyUsage: UsagePeriodState = UsagePeriodState(BudgetPeriod.MONTHLY),
    val activeUnlock: UnlockSession? = null,
    val pendingWait: PendingWait? = null,
    val dailyCounter: DailyCounter = DailyCounter(),
    val runtimeMetadata: RuntimeMetadata = RuntimeMetadata(),
    val usageReports: List<DailyUsageReport> = emptyList(),
    val offUsageReminder: OffUsageReminderState = OffUsageReminderState(),
    val currentVisitGraceActive: Boolean = false,
) {
    fun usage(period: BudgetPeriod): UsagePeriodState = when (period) {
        BudgetPeriod.DAILY -> dailyUsage
        BudgetPeriod.WEEKLY -> weeklyUsage
        BudgetPeriod.MONTHLY -> monthlyUsage
    }

    fun withUsage(value: UsagePeriodState): PersistedState = when (value.period) {
        BudgetPeriod.DAILY -> copy(dailyUsage = value)
        BudgetPeriod.WEEKLY -> copy(weeklyUsage = value)
        BudgetPeriod.MONTHLY -> copy(monthlyUsage = value)
    }
}

data class RuntimeSignals(
    val foregroundClass: ForegroundClass = ForegroundClass.OTHER,
    val browserActive: Boolean = false,
    val gateFocused: Boolean = false,
    val screenInteractive: Boolean = true,
    val keyguardUnlocked: Boolean = true,
    val serviceConnected: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val supportedBrowserInstalled: Boolean = true,
    val advancedProtectionEnabled: Boolean = false,
    val storageReady: Boolean = false,
    val storageError: Boolean = false,
    val storageWriteError: Boolean = false,
    val gateSessionId: String? = null,
    val outOfTimeLatchToken: String? = null,
    val latchedBlockers: Set<BudgetPeriod> = emptySet(),
)

data class BudgetSnapshot(
    val period: BudgetPeriod,
    val enabled: Boolean,
    val limitMinutes: Long,
    val usedMs: Long,
    val remainingMs: Long?,
    val percentage: Int,
    val exhausted: Boolean,
)

data class WaitSnapshot(
    val pendingWaitId: String,
    val selectedDurationMs: Long,
    val requiredFocusMs: Long,
    val accumulatedFocusMs: Long,
    val remainingFocusMs: Long,
    val progress: Float,
)

data class SkipSnapshot(
    val enabled: Boolean,
    val limit: Long,
    val used: Long,
    val remaining: Long,
    val grantDurationMs: Long,
    val eligible: Boolean,
)

data class StateSnapshot(
    val stateRevision: Long,
    val canonicalMode: GateMode,
    val gateSessionId: String?,
    val outOfTimeLatchToken: String?,
    val pendingWaitId: String?,
    val settings: RestrictorSettings,
    val budgetSnapshots: List<BudgetSnapshot>,
    val skipSnapshot: SkipSnapshot,
    val waitSnapshot: WaitSnapshot?,
    val enforcementHealth: ProtectionHealth,
    val accessibilityEnabled: Boolean,
    val serviceConnected: Boolean,
    val supportedBrowserInstalled: Boolean,
    val activeUnlock: UnlockSession?,
    val completedNormalUnlocksToday: Long,
    val usageReports: List<DailyUsageReport>,
    val todayUsageReport: DailyUsageReport,
    val latchedBlockers: Set<BudgetPeriod>,
    val wallNowMs: Long,
    val isLoaded: Boolean,
    val lastErrorMessage: String? = null,
) {
    val usesFullGate: Boolean
        get() = canonicalMode in setOf(
            GateMode.PAUSED,
            GateMode.WAITING,
            GateMode.OUT_OF_TIME,
            GateMode.OUT_OF_TIME_LATCHED,
        )

    val usesBadge: Boolean
        get() = canonicalMode in setOf(
            GateMode.ACCESS_GRANTED_NORMAL,
            GateMode.ACCESS_GRANTED_EMERGENCY,
            GateMode.ACCESS_GRANTED_NO_DELAY,
            GateMode.ACCESS_GRANTED_CURRENT_VISIT,
        )

    val needsActiveTicker: Boolean
        get() = usesFullGate || usesBadge || canonicalMode == GateMode.ENFORCEMENT_DISABLED
}

sealed interface CommandResult {
    data class Applied(val state: PersistedState) : CommandResult
    data class Rejected(val state: PersistedState, val reason: String) : CommandResult
    data class NormalCompletionWon(val state: PersistedState) : CommandResult
}

fun interface IdGenerator {
    fun nextId(): String
}

object RandomIdGenerator : IdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}
