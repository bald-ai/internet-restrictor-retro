package dev.browserrestrictor.retro.domain

import kotlin.math.ceil

object WaitCalculator {
    val sessionMinutes = listOf(10L, 30L, 60L)

    fun costPerTenSeconds(completedNormalUnlocks: Long): Long {
        require(completedNormalUnlocks >= 0)
        return Math.multiplyExact(Math.addExact(completedNormalUnlocks, 1), 5)
    }

    fun requiredFocusMs(requestedMinutes: Long, completedNormalUnlocks: Long): Long {
        require(requestedMinutes in sessionMinutes)
        val tenMinuteBlocks = requestedMinutes / 10
        return Math.multiplyExact(
            Math.multiplyExact(tenMinuteBlocks, costPerTenSeconds(completedNormalUnlocks)),
            1_000,
        )
    }

    fun displaySeconds(remainingMs: Long): Long =
        ceil(remainingMs.coerceAtLeast(0) / 1_000.0).toLong()

    fun formatWait(ms: Long): String = if (ms < MINUTE_MS) {
        "${displaySeconds(ms)} sec"
    } else {
        val minutes = ms / MINUTE_MS.toDouble()
        "%.1f min".format(minutes)
    }
}
