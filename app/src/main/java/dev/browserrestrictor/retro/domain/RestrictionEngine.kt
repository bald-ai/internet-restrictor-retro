package dev.browserrestrictor.retro.domain

import java.time.ZoneId
import java.time.LocalDate
import kotlin.math.roundToInt

class RestrictionEngine(
    private val idGenerator: IdGenerator = RandomIdGenerator,
) {
    fun validateSettings(settings: RestrictorSettings): Boolean = listOf(
        settings.dailyBudgetMinutes,
        settings.weeklyBudgetMinutes,
        settings.monthlyBudgetMinutes,
        settings.emergencySkipDailyLimit,
    ).all { it in 0..MAX_SAFE_MINUTES }

    fun normalize(input: PersistedState, nowWallMs: Long, zoneId: ZoneId): PersistedState {
        val keys = PeriodCalculator.keysAt(nowWallMs, zoneId)
        var state = input
        var revisionRelevant = false

        fun normalizedUsage(usage: UsagePeriodState, expectedKey: String): UsagePeriodState {
            if (usage.key == expectedKey) return usage
            revisionRelevant = true
            return usage.copy(key = expectedKey, usedMs = 0)
        }

        state = state.copy(
            dailyUsage = normalizedUsage(state.dailyUsage, keys.daily),
            weeklyUsage = normalizedUsage(state.weeklyUsage, keys.weekly),
            monthlyUsage = normalizedUsage(state.monthlyUsage, keys.monthly),
        )

        if (state.dailyCounter.localDayKey != keys.daily) {
            state = state.copy(dailyCounter = DailyCounter(localDayKey = keys.daily))
            revisionRelevant = true
        }

        state.activeUnlock?.let { unlock ->
            if (unlock.expiresAtWallMs <= nowWallMs) {
                state = state.copy(activeUnlock = null)
                revisionRelevant = true
            }
        }

        state.pendingWait?.let { pending ->
            val expired = nowWallMs < pending.createdAtWallMs ||
                nowWallMs - pending.createdAtWallMs >= PENDING_WAIT_EXPIRY_MS
            if (expired) {
                state = state.copy(pendingWait = null)
                revisionRelevant = true
            }
        }

        val todayKey = keys.daily
        val reportCutoff = LocalDate.parse(todayKey).minusDays(REPORT_HISTORY_DAYS.toLong()).toString()
        val retainedReports = state.usageReports
            .filter { it.localDayKey >= reportCutoff }
            .sortedBy { it.localDayKey }
            .takeLast(MAX_REPORT_DAYS)
        if (retainedReports != state.usageReports) {
            state = state.copy(usageReports = retainedReports)
            revisionRelevant = true
        }

        if (state.settings.enforcementEnabled && state.settings.pauseUntilWallMs != null) {
            state = state.copy(settings = state.settings.copy(pauseUntilWallMs = null))
            revisionRelevant = true
        } else if (!state.settings.enforcementEnabled && state.offUsageReminder.sessionStartedAtWallMs == 0L) {
            state = state.copy(
                offUsageReminder = state.offUsageReminder.copy(sessionStartedAtWallMs = nowWallMs),
            )
            revisionRelevant = true
        }
        if (!state.settings.enforcementEnabled && state.currentVisitGraceActive) {
            state = state.copy(currentVisitGraceActive = false)
            revisionRelevant = true
        }

        return if (revisionRelevant) state.copy(stateRevision = nextRevision(state.stateRevision)) else state
    }

    fun budgetSnapshots(state: PersistedState): List<BudgetSnapshot> = BudgetPeriod.entries.map { period ->
        val usage = state.usage(period)
        val (enabled, minutes) = when (period) {
            BudgetPeriod.DAILY -> state.settings.dailyBudgetEnabled to state.settings.dailyBudgetMinutes
            BudgetPeriod.WEEKLY -> state.settings.weeklyBudgetEnabled to state.settings.weeklyBudgetMinutes
            BudgetPeriod.MONTHLY -> state.settings.monthlyBudgetEnabled to state.settings.monthlyBudgetMinutes
        }
        val limitMs = safeMinutesToMs(minutes)
        val remaining = if (enabled) (limitMs - usage.usedMs).coerceAtLeast(0) else null
        val exhausted = enabled && remaining == 0L
        val percentage = when {
            !enabled -> 0
            limitMs == 0L -> 100
            else -> ((usage.usedMs.coerceAtMost(limitMs).toDouble() / limitMs) * 100).roundToInt()
        }
        BudgetSnapshot(
            period = period,
            enabled = enabled,
            limitMinutes = minutes,
            usedMs = usage.usedMs,
            remainingMs = remaining,
            percentage = percentage,
            exhausted = exhausted,
        )
    }

    fun underlyingMode(state: PersistedState, runtime: RuntimeSignals, nowWallMs: Long): GateMode {
        if (runtime.storageError) return GateMode.STORAGE_ERROR
        if (!runtime.browserActive || !runtime.screenInteractive || !runtime.keyguardUnlocked) {
            return GateMode.BROWSER_BACKGROUND
        }
        if (!state.settings.enforcementEnabled) return GateMode.ENFORCEMENT_DISABLED
        if (!runtime.storageReady || !runtime.accessibilityEnabled || !runtime.serviceConnected) {
            return GateMode.SERVICE_UNAVAILABLE
        }
        if (state.currentVisitGraceActive) return GateMode.ACCESS_GRANTED_CURRENT_VISIT
        state.activeUnlock?.takeIf { it.expiresAtWallMs > nowWallMs }?.let {
            when (it.source) {
                UnlockSource.EMERGENCY -> return GateMode.ACCESS_GRANTED_EMERGENCY
                UnlockSource.NORMAL -> Unit
            }
        }
        if (budgetSnapshots(state).any { it.exhausted }) return GateMode.OUT_OF_TIME
        state.activeUnlock?.takeIf { it.expiresAtWallMs > nowWallMs }?.let {
            return GateMode.ACCESS_GRANTED_NORMAL
        }
        if (!state.settings.delayEnabled) return GateMode.ACCESS_GRANTED_NO_DELAY
        if (state.pendingWait != null) return GateMode.WAITING
        return GateMode.PAUSED
    }

    fun snapshot(
        state: PersistedState,
        runtime: RuntimeSignals,
        nowWallMs: Long,
        isLoaded: Boolean = true,
        lastErrorMessage: String? = null,
    ): StateSnapshot {
        val budgets = budgetSnapshots(state)
        val underlying = underlyingMode(state, runtime, nowWallMs)
        val mode = if (
            underlying != GateMode.OUT_OF_TIME &&
            runtime.outOfTimeLatchToken != null &&
            runtime.browserActive &&
            state.settings.enforcementEnabled &&
            runtime.serviceConnected
        ) {
            GateMode.OUT_OF_TIME_LATCHED
        } else {
            underlying
        }
        val pending = state.pendingWait
        val waitSnapshot = pending?.let {
            val remaining = (it.requiredFocusMs - it.accumulatedFocusMs).coerceAtLeast(0)
            WaitSnapshot(
                pendingWaitId = it.id,
                selectedDurationMs = it.selectedDurationMs,
                requiredFocusMs = it.requiredFocusMs,
                accumulatedFocusMs = it.accumulatedFocusMs,
                remainingFocusMs = remaining,
                progress = if (it.requiredFocusMs == 0L) 1f else
                    (it.accumulatedFocusMs.toDouble() / it.requiredFocusMs).toFloat().coerceIn(0f, 1f),
            )
        }
        val remainingSkips = (
            state.settings.emergencySkipDailyLimit - state.dailyCounter.emergencySkipsUsed
        ).coerceAtLeast(0)
        val grantDuration = EMERGENCY_UNLOCK_MS
        val skipModeEligible = when (mode) {
            GateMode.PAUSED, GateMode.WAITING -> state.settings.delayEnabled
            GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> true
            else -> false
        }
        val skipEligible = state.settings.emergencySkipsEnabled &&
            remainingSkips > 0 &&
            skipModeEligible &&
            (state.activeUnlock == null || (
                mode in setOf(GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED) &&
                    state.activeUnlock.source == UnlockSource.NORMAL
            )) &&
            grantDuration > 0

        return StateSnapshot(
            stateRevision = state.stateRevision,
            canonicalMode = mode,
            gateSessionId = runtime.gateSessionId,
            outOfTimeLatchToken = runtime.outOfTimeLatchToken,
            pendingWaitId = pending?.id,
            settings = state.settings,
            budgetSnapshots = budgets,
            skipSnapshot = SkipSnapshot(
                enabled = state.settings.emergencySkipsEnabled,
                limit = state.settings.emergencySkipDailyLimit,
                used = state.dailyCounter.emergencySkipsUsed,
                remaining = remainingSkips,
                grantDurationMs = grantDuration,
                eligible = skipEligible,
            ),
            waitSnapshot = waitSnapshot,
            enforcementHealth = health(state, runtime),
            accessibilityEnabled = runtime.accessibilityEnabled,
            serviceConnected = runtime.serviceConnected,
            supportedBrowserInstalled = runtime.supportedBrowserInstalled,
            activeUnlock = state.activeUnlock,
            completedNormalUnlocksToday = state.dailyCounter.completedNormalUnlocks,
            usageReports = state.usageReports,
            todayUsageReport = state.usageReports.firstOrNull {
                it.localDayKey == PeriodCalculator.keysAt(nowWallMs, java.time.ZoneId.systemDefault()).daily
            } ?: DailyUsageReport(PeriodCalculator.keysAt(nowWallMs, java.time.ZoneId.systemDefault()).daily),
            latchedBlockers = runtime.latchedBlockers,
            wallNowMs = nowWallMs,
            isLoaded = isLoaded,
            lastErrorMessage = lastErrorMessage,
        )
    }

    fun startWait(
        input: PersistedState,
        runtime: RuntimeSignals,
        expectedRevision: Long,
        requestedMinutes: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        if (state.stateRevision != expectedRevision) return rejected(state, "State changed")
        if (underlyingMode(state, runtime, nowWallMs) != GateMode.PAUSED) {
            return rejected(state, "The browser is no longer paused")
        }
        if (requestedMinutes !in WaitCalculator.sessionMinutes) {
            return rejected(state, "Unsupported session duration")
        }
        val selectedDuration = safeMinutesToMs(requestedMinutes)
        val wait = PendingWait(
            id = idGenerator.nextId(),
            selectedDurationMs = selectedDuration,
            requiredFocusMs = WaitCalculator.requiredFocusMs(
                requestedMinutes,
                state.dailyCounter.completedNormalUnlocks,
            ),
            accumulatedFocusMs = 0,
            createdAtWallMs = nowWallMs,
            updatedAtWallMs = nowWallMs,
        )
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                pendingWait = wait,
                activeUnlock = null,
            ),
        )
    }

    fun advanceWait(
        input: PersistedState,
        runtime: RuntimeSignals,
        elapsedMs: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
        expectedGateSessionId: String?,
    ): CommandResult {
        var state = normalize(input, nowWallMs, zoneId)
        val pending = state.pendingWait ?: return rejected(state, "No pending wait")
        if (elapsedMs <= 0) return CommandResult.Applied(state)
        val canAdvance = underlyingMode(state, runtime, nowWallMs) == GateMode.WAITING &&
            runtime.gateFocused && runtime.screenInteractive && runtime.keyguardUnlocked &&
            runtime.gateSessionId != null && runtime.gateSessionId == expectedGateSessionId
        if (!canAdvance) return rejected(state, "Wait is not focused")

        val accumulated = Math.addExact(pending.accumulatedFocusMs, elapsedMs)
            .coerceAtMost(pending.requiredFocusMs)
        state = state.copy(
            pendingWait = pending.copy(
                accumulatedFocusMs = accumulated,
                updatedAtWallMs = nowWallMs,
            ),
        )
        if (accumulated < pending.requiredFocusMs) return CommandResult.Applied(state)
        return completeNormalWait(state, runtime, nowWallMs, zoneId, pending.id, expectedGateSessionId)
    }

    fun completeNormalWait(
        input: PersistedState,
        runtime: RuntimeSignals,
        nowWallMs: Long,
        zoneId: ZoneId,
        expectedPendingWaitId: String,
        expectedGateSessionId: String?,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        val pending = state.pendingWait ?: return rejected(state, "No pending wait")
        val valid = pending.id == expectedPendingWaitId &&
            pending.accumulatedFocusMs >= pending.requiredFocusMs &&
            runtime.gateSessionId != null && runtime.gateSessionId == expectedGateSessionId &&
            runtime.gateFocused &&
            underlyingMode(state, runtime, nowWallMs) == GateMode.WAITING &&
            state.settings.delayEnabled &&
            state.activeUnlock == null &&
            !budgetSnapshots(state).any { it.exhausted }
        if (!valid) return rejected(state, "Completion preconditions changed")

        val unlock = UnlockSession(
            id = idGenerator.nextId(),
            source = UnlockSource.NORMAL,
            grantedAtWallMs = nowWallMs,
            expiresAtWallMs = Math.addExact(nowWallMs, pending.selectedDurationMs),
        )
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                activeUnlock = unlock,
                pendingWait = null,
                dailyCounter = state.dailyCounter.copy(
                    completedNormalUnlocks = Math.addExact(
                        state.dailyCounter.completedNormalUnlocks,
                        1,
                    ),
                ),
            ),
        )
    }

    fun emergencySkip(
        input: PersistedState,
        runtime: RuntimeSignals,
        expectedRevision: Long,
        expectedPendingWaitId: String?,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        var state = normalize(input, nowWallMs, zoneId)
        if (state.stateRevision != expectedRevision) return rejected(state, "State changed")
        if (state.pendingWait?.id != expectedPendingWaitId) return rejected(state, "Wait changed")

        state.pendingWait?.takeIf { it.accumulatedFocusMs >= it.requiredFocusMs }?.let { pending ->
            return when (
                val completion = completeNormalWait(
                    state,
                    runtime,
                    nowWallMs,
                    zoneId,
                    pending.id,
                    runtime.gateSessionId,
                )
            ) {
                is CommandResult.Applied -> CommandResult.NormalCompletionWon(completion.state)
                is CommandResult.NormalCompletionWon -> completion
                is CommandResult.Rejected -> completion
            }
        }

        val currentSnapshot = snapshot(state, runtime, nowWallMs)
        if (!currentSnapshot.skipSnapshot.eligible) return rejected(state, "Emergency skip unavailable")
        val duration = currentSnapshot.skipSnapshot.grantDurationMs
        val unlock = UnlockSession(
            id = idGenerator.nextId(),
            source = UnlockSource.EMERGENCY,
            grantedAtWallMs = nowWallMs,
            expiresAtWallMs = Math.addExact(nowWallMs, duration),
        )
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                activeUnlock = unlock,
                pendingWait = null,
                dailyCounter = state.dailyCounter.copy(
                    emergencySkipsUsed = Math.addExact(state.dailyCounter.emergencySkipsUsed, 1),
                ),
            ),
        )
    }

    fun consumeUsage(
        input: PersistedState,
        runtime: RuntimeSignals,
        intervalStartWallMs: Long,
        intervalEndWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        if (intervalEndWallMs <= intervalStartWallMs) return input
        var state = normalize(input, intervalStartWallMs, zoneId)
        val mode = underlyingMode(state, runtime, intervalStartWallMs)
        if (mode !in accessGrantedModes) return state

        var cursor = intervalStartWallMs
        val originalExhausted = budgetSnapshots(state).any { it.exhausted }
        for (interval in PeriodCalculator.splitAtLocalMidnights(
            intervalStartWallMs,
            intervalEndWallMs,
            zoneId,
        )) {
            state = normalize(state, interval.startMs, zoneId)
            val budgets = budgetSnapshots(state)
            val overBudgetAccess = state.activeUnlock?.let {
                it.source == UnlockSource.EMERGENCY &&
                    it.expiresAtWallMs > interval.startMs
            } == true
            val maximum = if (overBudgetAccess) {
                interval.durationMs
            } else {
                budgets.filter { it.enabled }
                    .minOfOrNull { it.remainingMs ?: Long.MAX_VALUE }
                    ?: interval.durationMs
            }
            val charged = minOf(interval.durationMs, maximum).coerceAtLeast(0)
            if (charged > 0) {
                state = state.copy(
                    dailyUsage = state.dailyUsage.copy(
                        usedMs = Math.addExact(state.dailyUsage.usedMs, charged),
                    ),
                    weeklyUsage = state.weeklyUsage.copy(
                        usedMs = Math.addExact(state.weeklyUsage.usedMs, charged),
                    ),
                    monthlyUsage = state.monthlyUsage.copy(
                        usedMs = Math.addExact(state.monthlyUsage.usedMs, charged),
                    ),
                    runtimeMetadata = state.runtimeMetadata.copy(
                        lastCommittedWallMs = interval.startMs + charged,
                    ),
                )
                state = addReportInterval(
                    state = state,
                    intervalStartWallMs = interval.startMs,
                    intervalEndWallMs = interval.startMs + charged,
                    zoneId = zoneId,
                    protectionOff = false,
                )
                cursor = interval.startMs + charged
            }
            if (charged < interval.durationMs) break
        }
        val nowExhausted = budgetSnapshots(state).any { it.exhausted }
        if (!originalExhausted && nowExhausted) {
            state = state.copy(stateRevision = nextRevision(state.stateRevision))
        }
        return state.copy(runtimeMetadata = state.runtimeMetadata.copy(lastCommittedWallMs = cursor))
    }

    fun consumeOffUsage(
        input: PersistedState,
        runtime: RuntimeSignals,
        intervalStartWallMs: Long,
        intervalEndWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        if (intervalEndWallMs <= intervalStartWallMs) return input
        var state = normalize(input, intervalStartWallMs, zoneId)
        if (underlyingMode(state, runtime, intervalStartWallMs) != GateMode.ENFORCEMENT_DISABLED) {
            return state
        }
        state = addReportInterval(
            state,
            intervalStartWallMs,
            intervalEndWallMs,
            zoneId,
            protectionOff = true,
        )
        val added = intervalEndWallMs - intervalStartWallMs
        val reminder = state.offUsageReminder
        val qualifying = Math.addExact(reminder.qualifyingUsageMs, added)
        val sendFirst = !reminder.firstNotificationSent && qualifying >= FIRST_OFF_NOTIFICATION_USAGE_MS
        val sendRepeat = reminder.firstNotificationSent &&
            intervalEndWallMs - reminder.lastNotificationWallMs >= OFF_NOTIFICATION_REPEAT_INTERVAL_MS &&
            qualifying - reminder.usageAtLastNotificationMs >= OFF_NOTIFICATION_ADDITIONAL_USAGE_MS
        state = state.copy(
            offUsageReminder = reminder.copy(
                qualifyingUsageMs = qualifying,
                firstNotificationSent = reminder.firstNotificationSent || sendFirst,
                lastNotificationWallMs = if (sendFirst || sendRepeat) intervalEndWallMs else reminder.lastNotificationWallMs,
                usageAtLastNotificationMs = if (sendFirst || sendRepeat) qualifying else reminder.usageAtLastNotificationMs,
            ),
            runtimeMetadata = state.runtimeMetadata.copy(lastCommittedWallMs = intervalEndWallMs),
        )
        return state
    }

    fun consumeCurrentVisitUsage(
        input: PersistedState,
        runtime: RuntimeSignals,
        intervalStartWallMs: Long,
        intervalEndWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        if (intervalEndWallMs <= intervalStartWallMs) return input
        val state = normalize(input, intervalStartWallMs, zoneId)
        if (underlyingMode(state, runtime, intervalStartWallMs) != GateMode.ACCESS_GRANTED_CURRENT_VISIT) {
            return state
        }
        return addReportInterval(
            state,
            intervalStartWallMs,
            intervalEndWallMs,
            zoneId,
            protectionOff = false,
        ).copy(runtimeMetadata = state.runtimeMetadata.copy(lastCommittedWallMs = intervalEndWallMs))
    }

    fun setEnforcement(
        input: PersistedState,
        enabled: Boolean,
        pauseUntilWallMs: Long?,
        nowWallMs: Long,
        zoneId: ZoneId,
        allowCurrentVisit: Boolean = false,
    ): PersistedState {
        val state = normalize(input, nowWallMs, zoneId)
        val nextPause = if (enabled) null else pauseUntilWallMs
        return state.copy(
            stateRevision = nextRevision(state.stateRevision),
            settings = state.settings.copy(
                enforcementEnabled = enabled,
                pauseUntilWallMs = nextPause,
            ),
            offUsageReminder = if (enabled) {
                OffUsageReminderState()
            } else {
                OffUsageReminderState(sessionStartedAtWallMs = nowWallMs)
            },
            currentVisitGraceActive = enabled && allowCurrentVisit,
        )
    }

    fun reconcileProtectionSchedule(
        input: PersistedState,
        nowWallMs: Long,
        zoneId: ZoneId,
        currentVisitOpen: Boolean,
    ): PersistedState {
        if (!ProtectionSchedule.isResumeDue(input, nowWallMs, zoneId)) return input
        return setEnforcement(
            input = input,
            enabled = true,
            pauseUntilWallMs = null,
            nowWallMs = nowWallMs,
            zoneId = zoneId,
            allowCurrentVisit = currentVisitOpen,
        )
    }

    fun retainCurrentVisitGrace(
        currentGrace: Boolean,
        serviceConnected: Boolean,
        browserActive: Boolean,
    ): Boolean = currentGrace && (!serviceConnected || browserActive)

    fun rebaseOffUsageReminderAfterClockChange(
        reminder: OffUsageReminderState,
        protectionEnabled: Boolean,
        nowWallMs: Long,
    ): OffUsageReminderState = if (reminder.firstNotificationSent && !protectionEnabled) {
        reminder.copy(lastNotificationWallMs = nowWallMs)
    } else {
        reminder
    }

    fun clearReportHistory(input: PersistedState): PersistedState = input.copy(
        stateRevision = nextRevision(input.stateRevision),
        usageReports = emptyList(),
    )

    fun cancelActiveUnlock(
        input: PersistedState,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        val state = normalize(input, nowWallMs, zoneId)
        if (state.activeUnlock == null) return state
        return state.copy(
            stateRevision = nextRevision(state.stateRevision),
            activeUnlock = null,
        )
    }

    private fun addReportInterval(
        state: PersistedState,
        intervalStartWallMs: Long,
        intervalEndWallMs: Long,
        zoneId: ZoneId,
        protectionOff: Boolean,
    ): PersistedState {
        if (intervalEndWallMs <= intervalStartWallMs) return state
        val reports = state.usageReports.associateBy { it.localDayKey }.toMutableMap()
        for (interval in PeriodCalculator.splitAtLocalMidnights(
            intervalStartWallMs,
            intervalEndWallMs,
            zoneId,
        )) {
            val key = PeriodCalculator.keysAt(interval.startMs, zoneId).daily
            val current = reports[key] ?: DailyUsageReport(key)
            reports[key] = if (protectionOff) {
                current.copy(offUsageMs = Math.addExact(current.offUsageMs, interval.durationMs))
            } else {
                current.copy(protectedUsageMs = Math.addExact(current.protectedUsageMs, interval.durationMs))
            }
        }
        val cutoff = LocalDate.parse(PeriodCalculator.keysAt(intervalEndWallMs - 1, zoneId).daily)
            .minusDays(REPORT_HISTORY_DAYS.toLong())
            .toString()
        return state.copy(
            usageReports = reports.values
                .filter { it.localDayKey >= cutoff }
                .sortedBy { it.localDayKey }
                .takeLast(MAX_REPORT_DAYS),
        )
    }

    fun saveSettings(
        input: PersistedState,
        newSettings: RestrictorSettings,
        expectedRevision: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        if (state.stateRevision != expectedRevision) return rejected(state, "State changed")
        if (!validateSettings(newSettings)) return rejected(state, "Invalid settings")
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                settings = newSettings,
            ),
        )
    }

    fun resetUsage(input: PersistedState, nowWallMs: Long, zoneId: ZoneId): PersistedState {
        val state = normalize(input, nowWallMs, zoneId)
        val keys = PeriodCalculator.keysAt(nowWallMs, zoneId)
        return state.copy(
            stateRevision = nextRevision(state.stateRevision),
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, keys.daily, 0),
            weeklyUsage = UsagePeriodState(BudgetPeriod.WEEKLY, keys.weekly, 0),
            monthlyUsage = UsagePeriodState(BudgetPeriod.MONTHLY, keys.monthly, 0),
            activeUnlock = null,
            pendingWait = null,
            currentVisitGraceActive = false,
            dailyCounter = DailyCounter(localDayKey = keys.daily),
            runtimeMetadata = state.runtimeMetadata.copy(
                lastCommittedWallMs = nowWallMs,
                recoveryEpochId = idGenerator.nextId(),
            ),
        )
    }

    fun updateImmediateSettings(
        input: PersistedState,
        transform: (RestrictorSettings) -> RestrictorSettings,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        val state = normalize(input, nowWallMs, zoneId)
        val next = transform(state.settings)
        require(validateSettings(next))
        if (next == state.settings) return state
        return state.copy(stateRevision = nextRevision(state.stateRevision), settings = next)
    }

    private fun health(state: PersistedState, runtime: RuntimeSignals): ProtectionHealth = when {
        runtime.storageError || runtime.storageWriteError -> ProtectionHealth.STORAGE_ERROR
        !runtime.supportedBrowserInstalled -> ProtectionHealth.BROWSERS_NOT_FOUND
        runtime.advancedProtectionEnabled && !runtime.accessibilityEnabled ->
            ProtectionHealth.ADVANCED_PROTECTION_BLOCKED
        !runtime.accessibilityEnabled -> ProtectionHealth.ACCESSIBILITY_REQUIRED
        !runtime.serviceConnected -> ProtectionHealth.SERVICE_DISCONNECTED
        !state.settings.enforcementEnabled -> ProtectionHealth.ENFORCEMENT_OFF
        else -> ProtectionHealth.READY
    }

    private fun rejected(state: PersistedState, reason: String) = CommandResult.Rejected(state, reason)

    private fun safeMinutesToMs(minutes: Long): Long {
        require(minutes in 0..MAX_SAFE_MINUTES)
        return Math.multiplyExact(minutes, MINUTE_MS)
    }

    private fun nextRevision(revision: Long): Long = Math.addExact(revision, 1)

    companion object {
        const val REPORT_HISTORY_DAYS = 30
        const val MAX_REPORT_DAYS = REPORT_HISTORY_DAYS + 1
        const val FIRST_OFF_NOTIFICATION_USAGE_MS = 30L * MINUTE_MS
        const val OFF_NOTIFICATION_REPEAT_INTERVAL_MS = HOUR_MS
        const val OFF_NOTIFICATION_ADDITIONAL_USAGE_MS = 15L * MINUTE_MS
        val accessGrantedModes = setOf(
            GateMode.ACCESS_GRANTED_NORMAL,
            GateMode.ACCESS_GRANTED_EMERGENCY,
            GateMode.ACCESS_GRANTED_NO_DELAY,
        )
    }
}
