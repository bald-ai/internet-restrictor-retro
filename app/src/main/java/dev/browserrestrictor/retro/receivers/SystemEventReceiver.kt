package dev.browserrestrictor.retro.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.browserrestrictor.retro.appContainer
import kotlinx.coroutines.launch

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.appContainer
        container.applicationScope.launch {
            try {
                container.eventLog.record("System boundary: ${intent.action.orEmpty().substringAfterLast('.')}")
                container.repository.handleClockOrTimezoneChanged()
                val access = container.accessChecker.read()
                container.repository.updateDeviceAccess(
                    access.accessibilityEnabled,
                    access.supportedBrowserInstalled,
                    access.advancedProtectionEnabled,
                )
                container.reconcileProtectionSchedule()
            } finally {
                pending.finish()
            }
        }
    }
}
