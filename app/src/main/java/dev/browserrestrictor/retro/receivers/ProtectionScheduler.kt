package dev.browserrestrictor.retro.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.browserrestrictor.retro.appContainer
import dev.browserrestrictor.retro.domain.ProtectionSchedule
import java.time.ZoneId
import kotlinx.coroutines.launch

class ProtectionScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(pauseUntilWallMs: Long?) {
        val now = System.currentTimeMillis()
        val nextNoon = ProtectionSchedule.nextLocalNoon(now, ZoneId.systemDefault())
        val nextWake = listOfNotNull(
            nextNoon,
            pauseUntilWallMs?.takeIf { it > now },
        ).min()
        val operation = pendingIntent()
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            if (alarmDeliveryMode(exactAllowed) == AlarmDeliveryMode.EXACT) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWake, operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWake, operation)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWake, operation)
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, ProtectionAlarmReceiver::class.java).setAction(ACTION_AUTO_ENABLE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_AUTO_ENABLE = "dev.browserrestrictor.retro.AUTO_ENABLE"
        private const val REQUEST_CODE = 4101
    }
}

internal enum class AlarmDeliveryMode { EXACT, INEXACT }

internal fun alarmDeliveryMode(exactAllowed: Boolean): AlarmDeliveryMode =
    if (exactAllowed) AlarmDeliveryMode.EXACT else AlarmDeliveryMode.INEXACT

class ProtectionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProtectionScheduler.ACTION_AUTO_ENABLE) return
        val pending = goAsync()
        val container = context.appContainer
        container.applicationScope.launch {
            try {
                container.reconcileProtectionSchedule()
            } finally {
                pending.finish()
            }
        }
    }
}
