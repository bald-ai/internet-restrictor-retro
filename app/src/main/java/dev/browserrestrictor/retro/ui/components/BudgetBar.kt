package dev.browserrestrictor.retro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.browserrestrictor.retro.domain.BudgetSnapshot
import dev.browserrestrictor.retro.ui.theme.EditorialTheme

@Composable
fun BudgetBar(
    budget: BudgetSnapshot,
    modifier: Modifier = Modifier,
    forceExhausted: Boolean = false,
    label: String? = null,
) {
    if (!budget.enabled) return
    val exhausted = budget.exhausted || forceExhausted
    val percent = if (forceExhausted) 100 else budget.percentage
    val color = when {
        exhausted -> EditorialTheme.colors.low
        percent >= 80 -> EditorialTheme.colors.warn
        else -> EditorialTheme.colors.go
    }
    val remaining = if (forceExhausted) 0L else budget.remainingMs ?: 0
    Column(
        modifier = modifier.semantics {
            contentDescription = "${budget.period.label()} budget, $percent percent used, ${formatLongTime(remaining)} left"
        },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label ?: budget.period.label(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (exhausted) "Used" else "${formatCompactTime(remaining)} left",
                style = MaterialTheme.typography.bodySmall,
                color = if (exhausted) EditorialTheme.colors.low else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(EditorialTheme.colors.hairlineSoft, RoundedCornerShape(100)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((percent / 100f).coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(color, RoundedCornerShape(100)),
            )
        }
    }
}
