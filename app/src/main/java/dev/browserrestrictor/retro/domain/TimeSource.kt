package dev.browserrestrictor.retro.domain

import android.os.SystemClock
import java.time.ZoneId

interface TimeSource {
    fun wallTimeMs(): Long
    fun elapsedRealtimeMs(): Long
    fun zoneId(): ZoneId
}

class AndroidTimeSource : TimeSource {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}
