package dev.browserrestrictor.retro.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.browserrestrictor.retro.domain.MAX_SAFE_MINUTES
import dev.browserrestrictor.retro.domain.RestrictorSettings
import dev.browserrestrictor.retro.domain.StateSnapshot
import dev.browserrestrictor.retro.domain.ThemePreference
import dev.browserrestrictor.retro.ui.components.HealthCard
import dev.browserrestrictor.retro.ui.theme.EditorialTheme
import dev.browserrestrictor.retro.ui.theme.PillShape
import dev.browserrestrictor.retro.ui.theme.SerifNote
import dev.browserrestrictor.retro.ui.theme.editorialTitle

@Composable
fun SettingsScreen(
    snapshot: StateSnapshot,
    onBack: () -> Unit,
    onThemeChange: (ThemePreference) -> Unit,
    onPauseProtection: (Long?) -> Unit,
    onResumeProtection: () -> Unit,
    onSave: (RestrictorSettings) -> Unit,
    onResetUsage: () -> Unit,
    onCancelActiveUnlock: () -> Unit,
    onClearReports: () -> Unit,
    onHealthAction: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var dailyEnabled by rememberSaveable { mutableStateOf(snapshot.settings.dailyBudgetEnabled) }
    var dailyMinutes by rememberSaveable { mutableStateOf(snapshot.settings.dailyBudgetMinutes.toString()) }
    var weeklyEnabled by rememberSaveable { mutableStateOf(snapshot.settings.weeklyBudgetEnabled) }
    var weeklyMinutes by rememberSaveable { mutableStateOf(snapshot.settings.weeklyBudgetMinutes.toString()) }
    var monthlyEnabled by rememberSaveable { mutableStateOf(snapshot.settings.monthlyBudgetEnabled) }
    var monthlyMinutes by rememberSaveable { mutableStateOf(snapshot.settings.monthlyBudgetMinutes.toString()) }
    var delayEnabled by rememberSaveable { mutableStateOf(snapshot.settings.delayEnabled) }
    var skipsEnabled by rememberSaveable { mutableStateOf(snapshot.settings.emergencySkipsEnabled) }
    var skipLimit by rememberSaveable { mutableStateOf(snapshot.settings.emergencySkipDailyLimit.toString()) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showClearReportsDialog by rememberSaveable { mutableStateOf(false) }

    val daily = parseWholeNumber(dailyMinutes)
    val weekly = parseWholeNumber(weeklyMinutes)
    val monthly = parseWholeNumber(monthlyMinutes)
    val skips = parseWholeNumber(skipLimit)
    val valid = daily != null && weekly != null && monthly != null && skips != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("‹  Dashboard", color = EditorialTheme.colors.accent)
        }
        Text(
            "SETTINGS",
            style = MaterialTheme.typography.labelSmall,
            color = EditorialTheme.colors.ink3,
        )
        Text(editorialTitle("Browser rules."), style = MaterialTheme.typography.headlineLarge)

        SettingsCard("Appearance") {
            ToggleRow(
                title = "Dark theme",
                supporting = "Dark by default.",
                checked = snapshot.settings.theme == ThemePreference.DARK,
                onCheckedChange = {
                    onThemeChange(if (it) ThemePreference.DARK else ThemePreference.LIGHT)
                },
            )
        }

        SettingsCard("Reset usage") {
            Text(
                "Clears usage, the current session, wait, skips, and today's wait cost. Keeps rules and theme.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GhostButton(onClick = { showResetDialog = true }) {
                Text("Reset usage")
            }
            Text(
                "Keeps your 30-day report.",
                style = SerifNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showClearReportsDialog = true }) {
                Text("Clear report history", color = EditorialTheme.colors.accent)
            }
        }

        SettingsCard("Developer controls") {
            Text(
                "Ends only the current browser session. Everything else stays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GhostButton(
                onClick = onCancelActiveUnlock,
                enabled = snapshot.activeUnlock != null,
            ) {
                Text(if (snapshot.activeUnlock != null) "Cancel active unlock" else "No active unlock")
            }
        }

        SettingsCard("Enforcement") {
            Text(
                if (snapshot.settings.enforcementEnabled) "Browser protection is on." else
                    "Protection is off. Browser use still appears in your report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.settings.enforcementEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1L, 2L, 4L).forEach { hours ->
                        GhostButton(
                            onClick = { onPauseProtection(hours) },
                            modifier = Modifier.weight(1f),
                        ) { Text("${hours}h") }
                    }
                }
                TextButton(onClick = { onPauseProtection(null) }) {
                    Text("Keep off until noon", color = EditorialTheme.colors.accent)
                }
            } else {
                Button(
                    onClick = onResumeProtection,
                    modifier = Modifier.fillMaxWidth(),
                    shape = PillShape,
                ) { Text("Resume protection") }
            }
            Text(
                "Turns on again daily at noon in your current timezone.",
                style = SerifNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Budgets") {
            BudgetInput("Daily", dailyEnabled, { dailyEnabled = it }, dailyMinutes, { dailyMinutes = it })
            HorizontalDivider(color = EditorialTheme.colors.hairlineSoft)
            BudgetInput("Weekly", weeklyEnabled, { weeklyEnabled = it }, weeklyMinutes, { weeklyMinutes = it })
            HorizontalDivider(color = EditorialTheme.colors.hairlineSoft)
            BudgetInput("Monthly", monthlyEnabled, { monthlyEnabled = it }, monthlyMinutes, { monthlyMinutes = it })
            Text(
                "Usage keeps counting while a limit is off.",
                style = SerifNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Browser access") {
            ToggleRow(
                title = "Require wait before browser access",
                supporting = "When off, supported browsers open directly. Limits and tracking stay active.",
                checked = delayEnabled,
                onCheckedChange = { delayEnabled = it },
            )
        }

        SettingsCard("Emergency skips") {
            ToggleRow(
                title = "Enable skips",
                supporting = "Each skip opens a browser for 30 minutes, even after a limit is used.",
                checked = skipsEnabled,
                onCheckedChange = { skipsEnabled = it },
            )
            EditorialField(
                value = skipLimit,
                onValueChange = { skipLimit = it },
                label = { Text("Daily wait skips") },
                isError = skips == null,
                supportingText = if (skips == null) ({ Text("Enter a whole number, zero or greater.") }) else null,
            )
        }

        HealthCard(
            health = snapshot.enforcementHealth,
            onAction = onHealthAction,
        )

        Button(
            onClick = {
                onSave(
                    snapshot.settings.copy(
                        dailyBudgetEnabled = dailyEnabled,
                        dailyBudgetMinutes = daily!!,
                        weeklyBudgetEnabled = weeklyEnabled,
                        weeklyBudgetMinutes = weekly!!,
                        monthlyBudgetEnabled = monthlyEnabled,
                        monthlyBudgetMinutes = monthly!!,
                        delayEnabled = delayEnabled,
                        emergencySkipsEnabled = skipsEnabled,
                        emergencySkipDailyLimit = skips!!,
                    ),
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = PillShape,
        ) { Text("Save changes") }

        snapshot.lastErrorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedButton(
            onClick = onExportDiagnostics,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = PillShape,
            border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) { Text("Export diagnostics") }
        Spacer(Modifier.height(12.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Reset all usage?") },
            text = {
                Text("Also ends the current session and clears any wait. Limits, skip settings, delay, and theme stay.")
            },
            confirmButton = {
                Button(onClick = {
                    showResetDialog = false
                    onResetUsage()
                }) { Text("Reset usage") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = EditorialTheme.colors.accent)
                }
            },
        )
    }

    if (showClearReportsDialog) {
        AlertDialog(
            onDismissRequest = { showClearReportsDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Clear report history?") },
            text = { Text("Deletes protected/off usage history. Limits and current counters stay.") },
            confirmButton = {
                Button(onClick = {
                    showClearReportsDialog = false
                    onClearReports()
                }) { Text("Clear reports") }
            },
            dismissButton = {
                TextButton(onClick = { showClearReportsDialog = false }) {
                    Text("Cancel", color = EditorialTheme.colors.accent)
                }
            },
        )
    }
}

@Composable
private fun GhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        content = content,
    )
}

@Composable
private fun EditorialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    isError: Boolean,
    supportingText: @Composable (() -> Unit)?,
    suffix: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        suffix = suffix,
        singleLine = true,
        isError = isError,
        supportingText = supportingText,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = EditorialTheme.colors.accent,
            unfocusedBorderColor = EditorialTheme.colors.hairline,
            cursorColor = EditorialTheme.colors.accent,
            focusedLabelColor = EditorialTheme.colors.accentInk,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = EditorialTheme.colors.hairline)
        Column(
            Modifier.padding(top = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = EditorialTheme.colors.go,
                checkedBorderColor = EditorialTheme.colors.go,
                uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedTrackColor = EditorialTheme.colors.hairline,
                uncheckedBorderColor = EditorialTheme.colors.hairline,
            ),
        )
    }
}

@Composable
private fun BudgetInput(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val parsed = parseWholeNumber(value)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleRow(
            title = "$title budget",
            supporting = if (enabled) "Blocks supported browsers at this limit." else "Does not block. Usage still counts.",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        EditorialField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Minutes") },
            suffix = { Text("min") },
            isError = parsed == null,
            supportingText = if (parsed == null) ({ Text("Enter a whole number, zero or greater.") }) else null,
        )
    }
}

private fun parseWholeNumber(raw: String): Long? {
    if (raw.isBlank() || raw.any { !it.isDigit() }) return null
    return raw.toLongOrNull()?.takeIf { it in 0..MAX_SAFE_MINUTES }
}
