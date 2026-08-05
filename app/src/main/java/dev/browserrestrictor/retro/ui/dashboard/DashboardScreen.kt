package dev.browserrestrictor.retro.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.browserrestrictor.retro.domain.BudgetPeriod
import dev.browserrestrictor.retro.domain.BudgetSnapshot
import dev.browserrestrictor.retro.domain.MINUTE_MS
import dev.browserrestrictor.retro.domain.ProtectionHealth
import dev.browserrestrictor.retro.domain.StateSnapshot
import dev.browserrestrictor.retro.ui.components.BudgetBar
import dev.browserrestrictor.retro.ui.components.HealthCard
import dev.browserrestrictor.retro.ui.components.formatCompactTime
import dev.browserrestrictor.retro.ui.components.formatReportTime
import dev.browserrestrictor.retro.ui.components.formatLongTime
import dev.browserrestrictor.retro.ui.components.friendlyLabel
import dev.browserrestrictor.retro.ui.components.healthCopy
import dev.browserrestrictor.retro.ui.theme.EditorialTheme
import dev.browserrestrictor.retro.ui.theme.PillShape
import dev.browserrestrictor.retro.ui.theme.SerifNote
import dev.browserrestrictor.retro.ui.theme.editorialTitle

@Composable
fun DashboardScreen(
    snapshot: StateSnapshot,
    onOpenSettings: () -> Unit,
    onTestBrowser: () -> Unit,
    onHealthAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Text(
            "INTERNET RESTRICTOR",
            style = MaterialTheme.typography.labelSmall,
            color = EditorialTheme.colors.ink3,
        )
        Spacer(Modifier.height(10.dp))
        Text(editorialTitle("Today's boundary."), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))

        StatusLine(snapshot)

        val copy = healthCopy(snapshot.enforcementHealth)
        if (!copy.positive || copy.action != null) {
            Spacer(Modifier.height(18.dp))
            HealthCard(
                health = snapshot.enforcementHealth,
                onAction = onHealthAction,
            )
        }

        Spacer(Modifier.height(18.dp))
        HeroBudgetCard(snapshot)

        Spacer(Modifier.height(18.dp))
        UsageReportCard(snapshot)

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) {
            Text("Open settings")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onTestBrowser,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
            border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
            enabled = snapshot.supportedBrowserInstalled,
        ) {
            Text("Test browser gate", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun UsageReportCard(snapshot: StateSnapshot) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, EditorialTheme.colors.hairlineSoft),
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "USAGE REPORT",
                style = MaterialTheme.typography.labelSmall,
                color = EditorialTheme.colors.ink3,
            )
            Text("Today", style = MaterialTheme.typography.titleMedium)
            ReportRow(
                "Protection on",
                snapshot.todayUsageReport.protectedUsageMs,
                EditorialTheme.colors.go,
            )
            ReportRow(
                "Protection off",
                snapshot.todayUsageReport.offUsageMs,
                EditorialTheme.colors.low,
            )
            val history = snapshot.usageReports.asReversed().dropWhile {
                it.localDayKey == snapshot.todayUsageReport.localDayKey
            }
            if (history.isNotEmpty()) {
                HorizontalDivider(color = EditorialTheme.colors.hairlineSoft)
                Text("Last 30 days", style = MaterialTheme.typography.titleSmall)
                history.forEach { report ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            report.localDayKey,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "On ${formatReportTime(report.protectedUsageMs)} · Off ${formatReportTime(report.offUsageMs)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportRow(label: String, durationMs: Long, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(formatReportTime(durationMs), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StatusLine(snapshot: StateSnapshot) {
    val hairline = EditorialTheme.colors.hairline
    val (statusText, statusColor) = when {
        snapshot.enforcementHealth == ProtectionHealth.ENFORCEMENT_OFF ->
            "Enforcement off" to EditorialTheme.colors.ink3
        snapshot.enforcementHealth in setOf(
            ProtectionHealth.READY,
        ) -> "Browsers protected" to EditorialTheme.colors.go
        else -> "Needs attention" to EditorialTheme.colors.low
    }
    val detail = buildString {
        append(if (snapshot.settings.delayEnabled) "Wait on" else "No wait")
        if (snapshot.settings.emergencySkipsEnabled) {
            append(" · ${snapshot.skipSnapshot.remaining} skips left")
        }
    }
    Column {
        HorizontalDivider(color = hairline)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Text(statusText, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        snapshot.activeUnlock?.let {
            Text(
                "Session: ${formatLongTime(it.expiresAtWallMs - snapshot.wallNowMs)} left",
                modifier = Modifier.padding(bottom = 13.dp),
                style = SerifNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = hairline)
    }
}

@Composable
private fun HeroBudgetCard(snapshot: StateSnapshot) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, EditorialTheme.colors.hairlineSoft),
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) {
            val enabled = snapshot.budgetSnapshots.filter { it.enabled }
            if (enabled.isEmpty()) {
                Text(
                    "No budget currently blocks browsers. Usage is still counted for every period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val hero = enabled.first()
            val remaining = hero.remainingMs ?: 0
            Text(
                heroEyebrow(hero.period),
                style = MaterialTheme.typography.labelSmall,
                color = EditorialTheme.colors.ink3,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hero.exhausted) "0m" else formatCompactTime(remaining),
                style = MaterialTheme.typography.displayLarge,
                color = if (hero.exhausted) EditorialTheme.colors.low else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "of ${formatCompactTime(hero.limitMinutes * MINUTE_MS)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            HeroTrack(hero)
            enabled.drop(1).forEach { budget ->
                Spacer(Modifier.height(18.dp))
                BudgetBar(budget, label = budget.period.friendlyLabel())
            }
        }
    }
}

@Composable
private fun HeroTrack(budget: BudgetSnapshot) {
    val color = if (budget.exhausted) EditorialTheme.colors.low else EditorialTheme.colors.go
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(EditorialTheme.colors.hairlineSoft, RoundedCornerShape(100)),
    ) {
        Box(
            Modifier
                .fillMaxWidth((budget.percentage / 100f).coerceIn(0f, 1f))
                .height(4.dp)
                .background(color, RoundedCornerShape(100)),
        )
    }
}

private fun heroEyebrow(period: BudgetPeriod): String = when (period) {
    BudgetPeriod.DAILY -> "TIME REMAINING TODAY"
    BudgetPeriod.WEEKLY -> "TIME REMAINING THIS WEEK"
    BudgetPeriod.MONTHLY -> "TIME REMAINING THIS MONTH"
}
