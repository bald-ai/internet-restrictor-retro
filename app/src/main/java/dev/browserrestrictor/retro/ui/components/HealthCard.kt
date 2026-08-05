package dev.browserrestrictor.retro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.browserrestrictor.retro.domain.ProtectionHealth
import dev.browserrestrictor.retro.ui.theme.EditorialTheme
import dev.browserrestrictor.retro.ui.theme.PillShape

data class HealthCopy(
    val title: String,
    val body: String,
    val action: String?,
    val positive: Boolean,
)

fun healthCopy(health: ProtectionHealth): HealthCopy = when (health) {
    ProtectionHealth.READY -> HealthCopy(
        "Protection ready",
        "Chrome, Firefox, Edge, and Opera are protected.",
        null,
        true,
    )
    ProtectionHealth.ENFORCEMENT_OFF -> HealthCopy(
        "Protection is off",
        "Browsers open normally. Active use still appears in your report.",
        "Turn on",
        false,
    )
    ProtectionHealth.ACCESSIBILITY_REQUIRED -> HealthCopy(
        "Accessibility required",
        "Enable the service to protect supported browsers.",
        "Open Accessibility",
        false,
    )
    ProtectionHealth.SERVICE_DISCONNECTED -> HealthCopy(
        "Service disconnected",
        "Reopen Accessibility settings to reconnect it.",
        "Repair service",
        false,
    )
    ProtectionHealth.BROWSERS_NOT_FOUND -> HealthCopy(
        "No supported browser found",
        "Install or enable Chrome, Firefox, Edge, or Opera.",
        "Get a browser",
        false,
    )
    ProtectionHealth.STORAGE_ERROR -> HealthCopy(
        "Storage problem",
        "Protection is off until local state is repaired.",
        "Open app info",
        false,
    )
    ProtectionHealth.ADVANCED_PROTECTION_BLOCKED -> HealthCopy(
        "Advanced Protection blocked setup",
        "This device mode does not allow the required service.",
        "Open Android help",
        false,
    )
}

@Composable
fun HealthCard(
    health: ProtectionHealth,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = healthCopy(health)
    val accent = if (copy.positive) EditorialTheme.colors.go else EditorialTheme.colors.low
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(if (copy.positive) "●" else "!", color = accent)
                Text(copy.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                copy.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            copy.action?.let { label ->
                Button(onClick = onAction, shape = PillShape) { Text(label) }
            }
        }
    }
}
