package dev.browserrestrictor.retro.ui.gate

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.browserrestrictor.retro.domain.GateMode
import dev.browserrestrictor.retro.domain.MINUTE_MS
import dev.browserrestrictor.retro.domain.StateSnapshot
import dev.browserrestrictor.retro.domain.WaitCalculator
import dev.browserrestrictor.retro.ui.components.BudgetBar
import dev.browserrestrictor.retro.ui.components.PauseEmblem
import dev.browserrestrictor.retro.ui.components.friendlyLabel
import dev.browserrestrictor.retro.ui.theme.EditorialTheme
import dev.browserrestrictor.retro.ui.theme.PillShape
import dev.browserrestrictor.retro.ui.theme.SerifNote
import dev.browserrestrictor.retro.ui.theme.editorialTitle

data class GateActions(
    val onStartSession: (Long) -> Unit,
    val onEmergencySkip: () -> Unit,
    val onTryAgain: () -> Unit,
    val onLeaveBrowser: () -> Unit,
)

@Composable
fun GateScreen(snapshot: StateSnapshot, actions: GateActions) {
    var selectedMinutes by rememberSaveable(snapshot.gateSessionId) { mutableLongStateOf(10) }
    var actionInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.stateRevision, snapshot.canonicalMode) { actionInFlight = false }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = actions.onLeaveBrowser) {
                    Text(
                        "←  Leave browser",
                        style = MaterialTheme.typography.labelLarge,
                        color = EditorialTheme.colors.accent,
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PauseEmblem()
                Spacer(Modifier.height(4.dp))
                Text(
                    text = eyebrow(snapshot.canonicalMode),
                    style = MaterialTheme.typography.labelSmall,
                    color = EditorialTheme.colors.ink3,
                )
                Text(
                    text = editorialTitle(headline(snapshot.canonicalMode)),
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = supportingCopy(snapshot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (snapshot.canonicalMode) {
                    GateMode.PAUSED -> PausedControls(
                        snapshot = snapshot,
                        selectedMinutes = selectedMinutes,
                        onSelect = { selectedMinutes = it },
                        actionInFlight = actionInFlight,
                        onStart = {
                            actionInFlight = true
                            actions.onStartSession(selectedMinutes)
                        },
                        onSkip = {
                            actionInFlight = true
                            actions.onEmergencySkip()
                        },
                    )
                    GateMode.WAITING -> WaitingControls(
                        snapshot = snapshot,
                        actionInFlight = actionInFlight,
                        onSkip = {
                            actionInFlight = true
                            actions.onEmergencySkip()
                        },
                    )
                    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> OutOfTimeControls(
                        snapshot = snapshot,
                        actionInFlight = actionInFlight,
                        onSkip = {
                            actionInFlight = true
                            actions.onEmergencySkip()
                        },
                        onTryAgain = {
                            actionInFlight = true
                            actions.onTryAgain()
                        },
                    )
                    else -> Unit
                }
                snapshot.lastErrorMessage?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(vertical = 8.dp),
                        style = SerifNote,
                        textAlign = TextAlign.Center,
                        color = EditorialTheme.colors.accentInk,
                    )
                }
            }
        }
    }
}

@Composable
private fun PausedControls(
    snapshot: StateSnapshot,
    selectedMinutes: Long,
    onSelect: (Long) -> Unit,
    actionInFlight: Boolean,
    onStart: () -> Unit,
    onSkip: () -> Unit,
) {
    val waitMs = WaitCalculator.requiredFocusMs(selectedMinutes, snapshot.completedNormalUnlocksToday)
    val cost = WaitCalculator.costPerTenSeconds(snapshot.completedNormalUnlocksToday)
    Column(
        Modifier.padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WaitCalculator.sessionMinutes.forEach { minutes ->
                DurationCard(
                    minutes = minutes,
                    selected = minutes == selectedMinutes,
                    onClick = { onSelect(minutes) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            "Today: $cost sec per 10 min. This session waits ${WaitCalculator.formatWait(waitMs)}.",
            style = MaterialTheme.typography.bodySmall,
            color = EditorialTheme.colors.ink3,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onStart,
            enabled = !actionInFlight,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) {
            Text("Start $selectedMinutes min", style = MaterialTheme.typography.labelLarge)
        }
        SkipButton(snapshot, actionInFlight, onSkip)
        Budgets(snapshot)
    }
}

@Composable
private fun WaitingControls(
    snapshot: StateSnapshot,
    actionInFlight: Boolean,
    onSkip: () -> Unit,
) {
    val wait = snapshot.waitSnapshot ?: return
    val seconds = WaitCalculator.displaySeconds(wait.remainingFocusMs)
    val animatedProgress by animateFloatAsState(
        targetValue = wait.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "focused wait progress",
    )
    Column(
        Modifier.padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SectionLabel("Wait remaining")
        Column(
            Modifier.fillMaxWidth().semantics {
                contentDescription = "$seconds seconds remaining"
                liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                seconds.toString(),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 92.sp,
                    letterSpacing = (-3).sp,
                ),
            )
            Text("SECONDS", style = MaterialTheme.typography.labelSmall, color = EditorialTheme.colors.ink3)
        }
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .background(EditorialTheme.colors.hairlineSoft, RoundedCornerShape(100)),
        ) {
            Box(
                Modifier.fillMaxWidth(animatedProgress).height(4.dp)
                    .background(EditorialTheme.colors.accent, RoundedCornerShape(100)),
            )
        }
        Text(
            "Stay on this screen to keep counting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        SkipButton(snapshot, actionInFlight, onSkip)
        Budgets(snapshot)
    }
}

@Composable
private fun OutOfTimeControls(
    snapshot: StateSnapshot,
    actionInFlight: Boolean,
    onSkip: () -> Unit,
    onTryAgain: () -> Unit,
) {
    Column(
        Modifier.padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Budgets(snapshot)
        SkipButton(snapshot, actionInFlight, onSkip)
        Button(
            onClick = onTryAgain,
            enabled = !actionInFlight,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) {
            Text("Try again", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SkipButton(snapshot: StateSnapshot, actionInFlight: Boolean, onClick: () -> Unit) {
    if (!snapshot.skipSnapshot.eligible) return
    val actual = snapshot.skipSnapshot.grantDurationMs
    val label = if (actual < MINUTE_MS) "<1 min" else "${actual / MINUTE_MS} min"
    TextButton(
        onClick = onClick,
        enabled = !actionInFlight,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Emergency skip · ${snapshot.skipSnapshot.remaining} left today",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = EditorialTheme.colors.accent,
            )
            Text(
                "Open a browser for $label",
                style = MaterialTheme.typography.bodySmall,
                color = EditorialTheme.colors.ink3,
            )
        }
    }
}

@Composable
private fun Budgets(snapshot: StateSnapshot) {
    val enabled = snapshot.budgetSnapshots.filter { it.enabled }
    if (enabled.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        HorizontalDivider(color = EditorialTheme.colors.hairline)
        enabled.forEach {
            BudgetBar(
                budget = it,
                label = it.period.friendlyLabel(),
                forceExhausted = snapshot.canonicalMode == GateMode.OUT_OF_TIME_LATCHED &&
                    it.period in snapshot.latchedBlockers,
            )
        }
    }
}

@Composable
private fun DurationCard(
    minutes: Long,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val numberColor = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    val unitColor = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f)
    else EditorialTheme.colors.ink3
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else EditorialTheme.colors.hairline,
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "$minutes minute session"
            }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)) {
                    append("$minutes")
                }
                withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = unitColor)) {
                    append(" min")
                }
            },
            style = MaterialTheme.typography.labelLarge,
            color = numberColor,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = EditorialTheme.colors.ink3,
    )
}

private fun eyebrow(mode: GateMode): String = when (mode) {
    GateMode.PAUSED -> "TAKE A MOMENT"
    GateMode.WAITING -> "ALMOST THERE"
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "BUDGET USED"
    else -> "BROWSER"
}

private fun headline(mode: GateMode): String = when (mode) {
    GateMode.PAUSED -> "Pause before browsing."
    GateMode.WAITING -> "Wait here."
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "Browser time is used up."
    else -> "Browser"
}

private fun supportingCopy(snapshot: StateSnapshot): String = when (snapshot.canonicalMode) {
    GateMode.PAUSED -> "Choose a session."
    GateMode.WAITING -> {
        val minutes = (snapshot.waitSnapshot?.selectedDurationMs ?: 0) / MINUTE_MS
        "Your $minutes min session starts when the timer ends."
    }
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "Try again after the limit resets."
    else -> ""
}
