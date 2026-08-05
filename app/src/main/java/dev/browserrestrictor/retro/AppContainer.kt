package dev.browserrestrictor.retro

import android.content.Context
import dev.browserrestrictor.retro.data.RestrictorRepository
import dev.browserrestrictor.retro.diagnostics.LocalEventLog
import dev.browserrestrictor.retro.domain.AndroidTimeSource
import dev.browserrestrictor.retro.monitoring.DeviceAccessChecker
import dev.browserrestrictor.retro.notifications.ProtectionNotificationHelper
import dev.browserrestrictor.retro.receivers.ProtectionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val timeSource = AndroidTimeSource()
    val eventLog = LocalEventLog(context)
    val notificationHelper = ProtectionNotificationHelper(context)
    val protectionScheduler = ProtectionScheduler(context)
    val repository = RestrictorRepository(
        context = context,
        timeSource = timeSource,
        applicationScope = applicationScope,
        diagnosticSink = eventLog::record,
        offUsageNotificationSink = notificationHelper::showOffUsageReminder,
    )
    val accessChecker = DeviceAccessChecker(context)

    init {
        applicationScope.launch {
            repository.awaitLoaded()
            reconcileProtectionSchedule()
        }
    }

    suspend fun pauseProtection(durationMs: Long?) {
        if (repository.pauseProtection(durationMs)) {
            protectionScheduler.schedule(repository.snapshot.value.settings.pauseUntilWallMs)
        }
    }

    suspend fun resumeProtection() {
        repository.setEnforcementEnabled(true, allowCurrentVisit = true)
        protectionScheduler.schedule(null)
    }

    fun syncProtectionSchedule() {
        protectionScheduler.schedule(repository.snapshot.value.settings.pauseUntilWallMs)
    }

    suspend fun reconcileProtectionSchedule() {
        repository.reconcileProtectionSchedule()
        syncProtectionSchedule()
    }
}
