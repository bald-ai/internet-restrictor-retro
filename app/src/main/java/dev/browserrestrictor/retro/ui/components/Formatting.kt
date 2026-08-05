package dev.browserrestrictor.retro.ui.components

import dev.browserrestrictor.retro.domain.BudgetPeriod
import dev.browserrestrictor.retro.domain.HOUR_MS
import dev.browserrestrictor.retro.domain.MINUTE_MS
import kotlin.math.ceil

fun BudgetPeriod.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)

fun BudgetPeriod.friendlyLabel(): String = when (this) {
    BudgetPeriod.DAILY -> "Today"
    BudgetPeriod.WEEKLY -> "This week"
    BudgetPeriod.MONTHLY -> "This month"
}

fun formatCompactTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    if (safe < MINUTE_MS) return "<1m"
    if (safe < HOUR_MS) return "${ceil(safe / MINUTE_MS.toDouble()).toLong()}m"
    val hours = safe / HOUR_MS
    val minutes = (safe % HOUR_MS) / MINUTE_MS
    return if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
}

fun formatReportTime(ms: Long): String = if (ms <= 0) "0m" else formatCompactTime(ms)

fun formatLongTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    if (safe < MINUTE_MS) return "less than a minute"
    if (safe < HOUR_MS) return "${ceil(safe / MINUTE_MS.toDouble()).toLong()} min"
    val hours = safe / HOUR_MS
    val minutes = (safe % HOUR_MS) / MINUTE_MS
    return if (minutes == 0L) "$hours hr" else "$hours hr $minutes min"
}
