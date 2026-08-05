package dev.browserrestrictor.retro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import dev.browserrestrictor.retro.domain.BudgetPeriod
import dev.browserrestrictor.retro.domain.CommandResult
import dev.browserrestrictor.retro.domain.ForegroundClass
import dev.browserrestrictor.retro.domain.GateMode
import dev.browserrestrictor.retro.domain.IdGenerator
import dev.browserrestrictor.retro.domain.PersistedState
import dev.browserrestrictor.retro.domain.ProtectionSchedule
import dev.browserrestrictor.retro.domain.RandomIdGenerator
import dev.browserrestrictor.retro.domain.RestrictionEngine
import dev.browserrestrictor.retro.domain.RestrictorSettings
import dev.browserrestrictor.retro.domain.RuntimeSignals
import dev.browserrestrictor.retro.domain.StateSnapshot
import dev.browserrestrictor.retro.domain.ThemePreference
import dev.browserrestrictor.retro.domain.TimeSource
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RestrictorRepository(
    context: Context,
    private val timeSource: TimeSource,
    private val applicationScope: CoroutineScope,
    private val engine: RestrictionEngine = RestrictionEngine(),
    private val idGenerator: IdGenerator = RandomIdGenerator,
    private val diagnosticSink: (String) -> Unit = {},
    private val offUsageNotificationSink: () -> Unit = {},
    dataStoreOverride: DataStore<PersistedState>? = null,
) {
    private val dataStore = dataStoreOverride ?: DataStoreFactory.create(
        serializer = AppStateSerializer,
        scope = applicationScope,
        produceFile = { File(context.filesDir, "datastore/restrictor_state.bin") },
    )
    private val mutationMutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private var persistedState = PersistedState()
    private var runtime = RuntimeSignals()
    private var isLoaded = false
    private var lastError: String? = null
    private var lastTickElapsedMs: Long? = null
    private var lastTickWallMs: Long? = null
    private var bufferedUsageStartWallMs: Long? = null
    private var bufferedUsageEndWallMs: Long? = null
    private var bufferedUsageIsOff = false
    private var bufferedUsageIsCurrentVisit = false

    private val _snapshot = MutableStateFlow(
        engine.snapshot(persistedState, runtime, timeSource.wallTimeMs(), isLoaded = false),
    )
    val snapshot: StateFlow<StateSnapshot> = _snapshot.asStateFlow()

    init {
        applicationScope.launch {
            try {
                val stored = dataStore.data.first()
                mutationMutex.withLock {
                    val normalized = engine.normalize(stored, timeSource.wallTimeMs(), timeSource.zoneId())
                    if (normalized != stored) dataStore.updateData { normalized }
                    persistedState = normalized
                    isLoaded = true
                    runtime = runtime.copy(storageReady = true, storageError = false)
                    manageGateTokensLocked()
                    publishLocked()
                    diagnosticSink("State store loaded")
                }
            } catch (error: Exception) {
                mutationMutex.withLock {
                    isLoaded = true
                    lastError = "Stored state could not be trusted. Open app info to clear data, or reinstall."
                    runtime = runtime.copy(storageReady = false, storageError = true)
                    publishLocked()
                    diagnosticSink("State store read failed")
                }
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    suspend fun updateDeviceAccess(
        accessibilityEnabled: Boolean,
        supportedBrowserInstalled: Boolean,
        advancedProtectionEnabled: Boolean,
    ) {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(
                timeSource.elapsedRealtimeMs(),
                timeSource.wallTimeMs(),
                forceUsageFlush = true,
            )
            val old = runtime
            runtime = runtime.copy(
                accessibilityEnabled = accessibilityEnabled,
                supportedBrowserInstalled = supportedBrowserInstalled,
                advancedProtectionEnabled = advancedProtectionEnabled,
            )
            manageGateTokensLocked()
            if (old.healthFingerprint() != runtime.healthFingerprint()) bumpRevisionLocked()
            publishLocked()
        }
    }

    suspend fun updateServiceRuntime(
        foregroundClass: ForegroundClass = runtime.foregroundClass,
        browserActive: Boolean = runtime.browserActive,
        gateFocused: Boolean = runtime.gateFocused,
        screenInteractive: Boolean = runtime.screenInteractive,
        keyguardUnlocked: Boolean = runtime.keyguardUnlocked,
        serviceConnected: Boolean = runtime.serviceConnected,
    ) {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(
                timeSource.elapsedRealtimeMs(),
                timeSource.wallTimeMs(),
                forceUsageFlush = true,
            )
            val old = runtime
            val retainedGrace = engine.retainCurrentVisitGrace(
                currentGrace = persistedState.currentVisitGraceActive,
                serviceConnected = serviceConnected,
                browserActive = browserActive,
            )
            val graceChanged = retainedGrace != persistedState.currentVisitGraceActive
            val waitWasPending = persistedState.pendingWait != null
            val updatedState = abortPendingWaitAfterServiceUpdate(
                state = persistedState.copy(currentVisitGraceActive = retainedGrace),
                browserActive = browserActive,
            )
            val waitAborted = waitWasPending && updatedState.pendingWait == null
            if (graceChanged || waitAborted) {
                persistedState = updatedState
            }
            runtime = runtime.copy(
                foregroundClass = foregroundClass,
                browserActive = browserActive,
                gateFocused = gateFocused,
                screenInteractive = screenInteractive,
                keyguardUnlocked = keyguardUnlocked,
                serviceConnected = serviceConnected,
            )
            if (serviceConnected && !old.serviceConnected) {
                persistedState = persistedState.copy(
                    runtimeMetadata = persistedState.runtimeMetadata.copy(
                        serviceLastConnectedWallMs = timeSource.wallTimeMs(),
                    ),
                )
            }
            persistedState = persistedState.copy(
                runtimeMetadata = persistedState.runtimeMetadata.copy(
                    lastKnownForegroundClass = persistedForegroundAfterServiceUpdate(
                        previous = persistedState.runtimeMetadata.lastKnownForegroundClass,
                        observed = foregroundClass,
                        serviceConnected = serviceConnected,
                    ),
                ),
            )
            manageGateTokensLocked()
            if (old.policyFingerprint() != runtime.policyFingerprint() || graceChanged || waitAborted) {
                bumpRevisionLocked()
            }
            if (waitAborted) diagnosticSink("Focused wait aborted after leaving the browser")
            if (old.foregroundClass != runtime.foregroundClass || old.browserActive != runtime.browserActive) {
                diagnosticSink("Foreground transition: ${runtime.foregroundClass.name}")
            }
            publishLocked()
        }
    }

    suspend fun tick(elapsedRealtimeMs: Long, wallTimeMs: Long) {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(elapsedRealtimeMs, wallTimeMs, forceUsageFlush = false)
            publishLocked(wallTimeMs)
        }
    }

    suspend fun flushAccounting() {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            publishLocked()
        }
    }

    suspend fun startWait(expectedRevision: Long, requestedMinutes: Long): Boolean = command {
        engine.startWait(
            input = persistedState,
            runtime = runtime,
            expectedRevision = expectedRevision,
            requestedMinutes = requestedMinutes,
            nowWallMs = timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
        )
    }

    suspend fun emergencySkip(expectedRevision: Long, expectedPendingWaitId: String?): Boolean = command(
        clearOutOfTimeLatch = true,
    ) {
        engine.emergencySkip(
            input = persistedState,
            runtime = runtime,
            expectedRevision = expectedRevision,
            expectedPendingWaitId = expectedPendingWaitId,
            nowWallMs = timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
        )
    }

    suspend fun tryAgain(
        expectedRevision: Long,
        expectedGateSessionId: String?,
        expectedLatchToken: String?,
    ): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val current = engine.snapshot(persistedState, runtime, timeSource.wallTimeMs(), isLoaded)
            val valid = current.stateRevision == expectedRevision &&
                current.canonicalMode in setOf(GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED) &&
                runtime.gateSessionId != null && runtime.gateSessionId == expectedGateSessionId &&
                runtime.outOfTimeLatchToken != null &&
                runtime.outOfTimeLatchToken == expectedLatchToken
            if (!valid) {
                lastError = "Browser state changed. The latest gate is still active."
                publishLocked()
                return@withLock false
            }
            val normalized = engine.normalize(persistedState, timeSource.wallTimeMs(), timeSource.zoneId())
            val next = normalized.copy(stateRevision = Math.addExact(normalized.stateRevision, 1))
            if (!persistLocked(next)) return@withLock false
            persistedState = next
            runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
            diagnosticSink("Out-of-time retry evaluated")
            manageGateTokensLocked()
            lastError = null
            publishLocked()
            true
        }
    }

    suspend fun saveSettings(expectedRevision: Long, settings: RestrictorSettings): Boolean = command(
        clearOutOfTimeLatch = true,
    ) {
        engine.saveSettings(
            input = persistedState,
            newSettings = settings,
            expectedRevision = expectedRevision,
            nowWallMs = timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
        )
    }

    suspend fun setEnforcementEnabled(
        enabled: Boolean,
        allowCurrentVisit: Boolean = false,
    ): Boolean = immediateMutation(clearOutOfTimeLatch = true) {
        val currentVisitWasOpen = runtime.browserActive ||
            (!runtime.serviceConnected &&
                it.runtimeMetadata.lastKnownForegroundClass == ForegroundClass.BROWSER)
        engine.setEnforcement(
            it,
            enabled = enabled,
            pauseUntilWallMs = null,
            nowWallMs = timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
            allowCurrentVisit = enabled && allowCurrentVisit &&
                (it.currentVisitGraceActive ||
                    (!it.settings.enforcementEnabled && currentVisitWasOpen)),
        )
    }

    suspend fun pauseProtection(durationMs: Long?): Boolean = immediateMutation(clearOutOfTimeLatch = true) {
        val now = timeSource.wallTimeMs()
        engine.setEnforcement(
            it,
            enabled = false,
            pauseUntilWallMs = durationMs?.let { duration -> Math.addExact(now, duration) },
            nowWallMs = now,
            zoneId = timeSource.zoneId(),
            allowCurrentVisit = false,
        )
    }

    suspend fun reconcileProtectionSchedule(): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            publishLocked()
            persistedState.settings.enforcementEnabled
        }
    }

    suspend fun setTheme(theme: ThemePreference): Boolean = immediateMutation {
        engine.updateImmediateSettings(it, { settings -> settings.copy(theme = theme) }, timeSource.wallTimeMs(), timeSource.zoneId())
    }

    suspend fun completeOnboarding(): Boolean = immediateMutation {
        engine.updateImmediateSettings(it, { settings -> settings.copy(onboardingCompleted = true) }, timeSource.wallTimeMs(), timeSource.zoneId())
    }

    suspend fun resetUsage(): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val next = engine.resetUsage(persistedState, timeSource.wallTimeMs(), timeSource.zoneId())
            if (!persistLocked(next)) return@withLock false
            persistedState = next
            runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
            diagnosticSink("Usage and session state reset")
            manageGateTokensLocked()
            lastError = null
            publishLocked()
            true
        }
    }

    suspend fun clearReportHistory(): Boolean = immediateMutation {
        engine.clearReportHistory(it)
    }

    suspend fun cancelActiveUnlock(): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val hadActiveUnlock = persistedState.activeUnlock != null
            val next = engine.cancelActiveUnlock(
                persistedState,
                timeSource.wallTimeMs(),
                timeSource.zoneId(),
            )
            if (!persistLocked(next)) return@withLock false
            persistedState = next
            runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
            if (hadActiveUnlock && next.activeUnlock == null) {
                diagnosticSink("Active unlock cancelled from developer controls")
            }
            manageGateTokensLocked()
            lastError = null
            publishLocked()
            true
        }
    }

    suspend fun handleClockOrTimezoneChanged() {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val nowWallMs = timeSource.wallTimeMs()
            val normalized = engine.normalize(persistedState, nowWallMs, timeSource.zoneId())
            val rebased = ProtectionSchedule.rebaseAfterBackwardClockChange(
                state = normalized,
                nowWallMs = nowWallMs,
                zoneId = timeSource.zoneId(),
            )
            val next = rebased.copy(
                runtimeMetadata = rebased.runtimeMetadata.copy(
                    recoveryEpochId = idGenerator.nextId(),
                    lastCommittedWallMs = nowWallMs,
                ),
                offUsageReminder = engine.rebaseOffUsageReminderAfterClockChange(
                    reminder = rebased.offUsageReminder,
                    protectionEnabled = rebased.settings.enforcementEnabled,
                    nowWallMs = nowWallMs,
                ),
            )
            if (persistLocked(next)) persistedState = next
            reconcileProtectionDueLocked(nowWallMs)
            diagnosticSink("Clock or timezone boundary normalized")
            resetTickAnchorsLocked()
            manageGateTokensLocked()
            publishLocked()
        }
    }

    private suspend fun command(
        clearOutOfTimeLatch: Boolean = false,
        transition: () -> CommandResult,
    ): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            when (val result = transition()) {
                is CommandResult.Applied -> {
                    val before = persistedState
                    if (!persistLocked(result.state)) return@withLock false
                    persistedState = result.state
                    if (clearOutOfTimeLatch) {
                        runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
                    }
                    manageGateTokensLocked()
                    recordTransition(before, result.state)
                    lastError = null
                    publishLocked()
                    true
                }
                is CommandResult.NormalCompletionWon -> {
                    val before = persistedState
                    if (!persistLocked(result.state)) return@withLock false
                    persistedState = result.state
                    manageGateTokensLocked()
                    recordTransition(before, result.state)
                    lastError = null
                    publishLocked()
                    true
                }
                is CommandResult.Rejected -> {
                    if (result.state != persistedState) {
                        if (!persistLocked(result.state)) return@withLock false
                        persistedState = result.state
                    }
                    lastError = result.reason
                    diagnosticSink("Action rejected: ${result.reason}")
                    manageGateTokensLocked()
                    publishLocked()
                    false
                }
            }
        }
    }

    private suspend fun immediateMutation(
        clearOutOfTimeLatch: Boolean = false,
        transform: (PersistedState) -> PersistedState,
    ): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val next = transform(persistedState)
            if (!persistLocked(next)) return@withLock false
            persistedState = next
            if (clearOutOfTimeLatch) {
                runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
            }
            manageGateTokensLocked()
            lastError = null
            publishLocked()
            true
        }
    }

    private suspend fun advanceLocked(
        elapsedRealtimeMs: Long,
        wallTimeMs: Long,
        forceUsageFlush: Boolean,
    ) {
        val previousElapsed = lastTickElapsedMs
        val previousWall = lastTickWallMs
        lastTickElapsedMs = elapsedRealtimeMs
        lastTickWallMs = wallTimeMs
        if (previousElapsed == null || previousWall == null) {
            val normalized = engine.normalize(persistedState, wallTimeMs, timeSource.zoneId())
            if (normalized != persistedState && persistLocked(normalized)) persistedState = normalized
            reconcileProtectionDueLocked(wallTimeMs)
            manageGateTokensLocked()
            return
        }

        val rawDelta = elapsedRealtimeMs - previousElapsed
        if (rawDelta <= 0) {
            if (forceUsageFlush) flushUsageBufferLocked()
            val normalized = engine.normalize(persistedState, wallTimeMs, timeSource.zoneId())
            if (normalized != persistedState && persistLocked(normalized)) persistedState = normalized
            reconcileProtectionDueLocked(wallTimeMs)
            manageGateTokensLocked()
            return
        }
        val elapsedDelta = rawDelta.coerceAtMost(MAX_UNRECONCILED_INTERVAL_MS)
        if (rawDelta > MAX_UNRECONCILED_INTERVAL_MS) {
            diagnosticSink("Long accounting gap left uncounted: ${rawDelta}ms")
        }
        val wallDelta = wallTimeMs - previousWall
        val intervalStart = if (abs(wallDelta - rawDelta) <= CLOCK_DRIFT_TOLERANCE_MS) {
            previousWall
        } else {
            previousWall
        }
        val intervalEnd = Math.addExact(intervalStart, elapsedDelta)
        val mode = engine.underlyingMode(persistedState, runtime, intervalStart)

        when (mode) {
            GateMode.WAITING -> {
                flushUsageBufferLocked()
                val result = engine.advanceWait(
                    input = persistedState,
                    runtime = runtime,
                    elapsedMs = elapsedDelta,
                    nowWallMs = intervalEnd,
                    zoneId = timeSource.zoneId(),
                    expectedGateSessionId = runtime.gateSessionId,
                )
                val next = when (result) {
                    is CommandResult.Applied -> result.state
                    is CommandResult.NormalCompletionWon -> result.state
                    is CommandResult.Rejected -> result.state
                }
                if (next != persistedState && persistLocked(next)) persistedState = next
            }
            GateMode.ACCESS_GRANTED_NORMAL,
            GateMode.ACCESS_GRANTED_EMERGENCY,
            GateMode.ACCESS_GRANTED_NO_DELAY,
            -> {
                if (bufferedUsageStartWallMs != null && (bufferedUsageIsOff || bufferedUsageIsCurrentVisit)) {
                    flushUsageBufferLocked()
                }
                bufferedUsageIsOff = false
                bufferedUsageIsCurrentVisit = false
                val cappedEnd = persistedState.activeUnlock?.expiresAtWallMs
                    ?.coerceAtMost(intervalEnd)
                    ?.coerceAtLeast(intervalStart)
                    ?: intervalEnd
                if (cappedEnd > intervalStart) {
                    if (bufferedUsageStartWallMs == null) bufferedUsageStartWallMs = intervalStart
                    bufferedUsageEndWallMs = cappedEnd
                }
                val bufferedDuration = (bufferedUsageEndWallMs ?: 0) - (bufferedUsageStartWallMs ?: 0)
                val tightRemaining = engine.budgetSnapshots(persistedState)
                    .filter { it.enabled }
                    .minOfOrNull { it.remainingMs ?: Long.MAX_VALUE }
                val boundaryReached = tightRemaining != null && bufferedDuration >= tightRemaining
                val unlockReached = persistedState.activeUnlock?.expiresAtWallMs?.let { it <= wallTimeMs } == true
                if (forceUsageFlush || bufferedDuration >= USAGE_HEARTBEAT_MS || boundaryReached || unlockReached) {
                    flushUsageBufferLocked()
                }
            }
            GateMode.ENFORCEMENT_DISABLED -> {
                if (bufferedUsageStartWallMs != null && (!bufferedUsageIsOff || bufferedUsageIsCurrentVisit)) {
                    flushUsageBufferLocked()
                }
                bufferedUsageIsOff = true
                bufferedUsageIsCurrentVisit = false
                if (bufferedUsageStartWallMs == null) bufferedUsageStartWallMs = intervalStart
                bufferedUsageEndWallMs = intervalEnd
                val bufferedDuration = (bufferedUsageEndWallMs ?: 0) - (bufferedUsageStartWallMs ?: 0)
                if (forceUsageFlush || bufferedDuration >= USAGE_HEARTBEAT_MS) {
                    flushUsageBufferLocked()
                }
            }
            GateMode.ACCESS_GRANTED_CURRENT_VISIT -> {
                if (bufferedUsageStartWallMs != null && !bufferedUsageIsCurrentVisit) {
                    flushUsageBufferLocked()
                }
                bufferedUsageIsOff = false
                bufferedUsageIsCurrentVisit = true
                if (bufferedUsageStartWallMs == null) bufferedUsageStartWallMs = intervalStart
                bufferedUsageEndWallMs = intervalEnd
                val bufferedDuration = (bufferedUsageEndWallMs ?: 0) - (bufferedUsageStartWallMs ?: 0)
                if (forceUsageFlush || bufferedDuration >= USAGE_HEARTBEAT_MS) flushUsageBufferLocked()
            }
            else -> flushUsageBufferLocked()
        }

        val normalized = engine.normalize(persistedState, wallTimeMs, timeSource.zoneId())
        if (normalized != persistedState && persistLocked(normalized)) persistedState = normalized
        reconcileProtectionDueLocked(wallTimeMs)
        manageGateTokensLocked()
    }

    private suspend fun reconcileProtectionDueLocked(wallTimeMs: Long) {
        val currentVisitWasOpen = runtime.browserActive ||
            (!runtime.serviceConnected &&
                persistedState.runtimeMetadata.lastKnownForegroundClass == ForegroundClass.BROWSER)
        val next = engine.reconcileProtectionSchedule(
            input = persistedState,
            nowWallMs = wallTimeMs,
            zoneId = timeSource.zoneId(),
            currentVisitOpen = currentVisitWasOpen,
        )
        if (next == persistedState) return
        if (persistLocked(next)) {
            persistedState = next
            runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
            diagnosticSink("Protection automatically resumed")
        }
    }

    private suspend fun flushUsageBufferLocked() {
        val start = bufferedUsageStartWallMs
        val end = bufferedUsageEndWallMs
        val wasOff = bufferedUsageIsOff
        val wasCurrentVisit = bufferedUsageIsCurrentVisit
        bufferedUsageStartWallMs = null
        bufferedUsageEndWallMs = null
        bufferedUsageIsOff = false
        bufferedUsageIsCurrentVisit = false
        if (start == null || end == null || end <= start) return
        val next = if (wasOff) {
            engine.consumeOffUsage(persistedState, runtime, start, end, timeSource.zoneId())
        } else if (wasCurrentVisit) {
            engine.consumeCurrentVisitUsage(persistedState, runtime, start, end, timeSource.zoneId())
        } else {
            engine.consumeUsage(persistedState, runtime, start, end, timeSource.zoneId())
        }
        val reminderFired = wasOff &&
            next.offUsageReminder.lastNotificationWallMs !=
            persistedState.offUsageReminder.lastNotificationWallMs
        if (next != persistedState && persistLocked(next)) {
            val becameExhausted = !engine.budgetSnapshots(persistedState).any { it.exhausted } &&
                engine.budgetSnapshots(next).any { it.exhausted }
            persistedState = next
            diagnosticSink("${if (wasOff) "Off-mode report" else "Usage"} interval committed: ${end - start}ms")
            if (becameExhausted) diagnosticSink("Browser budget exhausted")
            if (reminderFired) offUsageNotificationSink()
        }
    }

    private suspend fun bumpRevisionLocked() {
        if (!runtime.storageReady || runtime.storageError) return
        val next = persistedState.copy(stateRevision = Math.addExact(persistedState.stateRevision, 1))
        if (persistLocked(next)) persistedState = next
    }

    private suspend fun persistLocked(next: PersistedState): Boolean = try {
        val written = dataStore.updateData { next }
        persistedState = written
        runtime = runtime.copy(storageReady = true, storageError = false, storageWriteError = false)
        true
    } catch (error: Exception) {
        runtime = runtime.copy(storageWriteError = true)
        lastError = "A state change could not be saved. The browser has not been unlocked."
        diagnosticSink("State store write failed")
        publishLocked()
        false
    }

    private fun manageGateTokensLocked() {
        val underlying = engine.underlyingMode(persistedState, runtime, timeSource.wallTimeMs())
        val exhaustedPeriods = engine.budgetSnapshots(persistedState)
            .filter { it.exhausted }
            .mapTo(mutableSetOf()) { it.period }
        runtime = clearResolvedOutOfTimeLatch(runtime, exhaustedPeriods)
        if (!runtime.browserActive) {
            runtime = runtime.copy(
                gateSessionId = null,
                outOfTimeLatchToken = null,
                latchedBlockers = emptySet(),
                gateFocused = false,
            )
            return
        }
        if (runtime.outOfTimeLatchToken != null) {
            if (runtime.gateSessionId == null) runtime = runtime.copy(gateSessionId = idGenerator.nextId())
            return
        }
        if (underlying in nonGateModes) {
            runtime = runtime.copy(gateSessionId = null, gateFocused = false)
            return
        }
        if (runtime.gateSessionId == null) runtime = runtime.copy(gateSessionId = idGenerator.nextId())
        if (underlying == GateMode.OUT_OF_TIME && runtime.outOfTimeLatchToken == null) {
            runtime = runtime.copy(
                outOfTimeLatchToken = idGenerator.nextId(),
                latchedBlockers = exhaustedPeriods,
            )
        }
    }

    private fun publishLocked(wallTimeMs: Long = timeSource.wallTimeMs()) {
        _snapshot.value = engine.snapshot(
            state = persistedState,
            runtime = runtime,
            nowWallMs = wallTimeMs,
            isLoaded = isLoaded,
            lastErrorMessage = lastError,
        )
    }

    private fun resetTickAnchorsLocked() {
        lastTickElapsedMs = null
        lastTickWallMs = null
        bufferedUsageStartWallMs = null
        bufferedUsageEndWallMs = null
        bufferedUsageIsOff = false
        bufferedUsageIsCurrentVisit = false
    }

    private fun recordTransition(before: PersistedState, after: PersistedState) {
        when {
            before.pendingWait == null && after.pendingWait != null -> diagnosticSink("Focused wait started")
            before.activeUnlock == null && after.activeUnlock?.source?.name == "NORMAL" ->
                diagnosticSink("Normal unlock granted")
            before.activeUnlock == null && after.activeUnlock?.source?.name == "EMERGENCY" ->
                diagnosticSink("Emergency skip granted")
            before.settings != after.settings -> diagnosticSink("Settings changed")
        }
    }

    private fun RuntimeSignals.policyFingerprint() = listOf(
        foregroundClass,
        browserActive,
        gateFocused,
        screenInteractive,
        keyguardUnlocked,
        serviceConnected,
        accessibilityEnabled,
        storageReady,
        storageError,
        storageWriteError,
        gateSessionId,
        outOfTimeLatchToken,
    )

    private fun RuntimeSignals.healthFingerprint() = listOf(
        accessibilityEnabled,
        supportedBrowserInstalled,
        advancedProtectionEnabled,
        serviceConnected,
        storageReady,
        storageError,
        storageWriteError,
    )

    private companion object {
        const val USAGE_HEARTBEAT_MS = 1_000L
        const val MAX_UNRECONCILED_INTERVAL_MS = 5_000L
        const val CLOCK_DRIFT_TOLERANCE_MS = 2_000L
        val nonGateModes = setOf(
            GateMode.ACCESS_GRANTED_NORMAL,
            GateMode.ACCESS_GRANTED_EMERGENCY,
            GateMode.ACCESS_GRANTED_NO_DELAY,
            GateMode.ACCESS_GRANTED_CURRENT_VISIT,
            GateMode.BROWSER_BACKGROUND,
            GateMode.ENFORCEMENT_DISABLED,
            GateMode.SERVICE_UNAVAILABLE,
            GateMode.STORAGE_ERROR,
        )
    }
}

internal fun persistedForegroundAfterServiceUpdate(
    previous: ForegroundClass,
    observed: ForegroundClass,
    serviceConnected: Boolean,
): ForegroundClass = if (serviceConnected) observed else previous

internal fun abortPendingWaitAfterServiceUpdate(
    state: PersistedState,
    browserActive: Boolean,
): PersistedState = if (!browserActive && state.pendingWait != null) {
    state.copy(pendingWait = null)
} else {
    state
}

internal fun clearResolvedOutOfTimeLatch(
    runtime: RuntimeSignals,
    exhaustedPeriods: Set<BudgetPeriod>,
): RuntimeSignals = if (
    runtime.outOfTimeLatchToken != null &&
    runtime.latchedBlockers.none { it in exhaustedPeriods }
) {
    runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
} else {
    runtime
}
