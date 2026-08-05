package dev.browserrestrictor.retro.ui.onboarding

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.browserrestrictor.retro.domain.StateSnapshot
import dev.browserrestrictor.retro.ui.components.PauseEmblem
import dev.browserrestrictor.retro.ui.theme.EditorialTheme
import dev.browserrestrictor.retro.ui.theme.PillShape
import dev.browserrestrictor.retro.ui.theme.editorialTitle

@Composable
fun OnboardingScreen(
    snapshot: StateSnapshot,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onTestBrowser: () -> Unit,
    onComplete: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text(
            "SETUP  ${page + 1} / 4",
            style = MaterialTheme.typography.labelSmall,
            color = EditorialTheme.colors.ink3,
        )
        when (page) {
            0 -> WelcomeStep()
            1 -> DefaultsStep()
            2 -> AccessibilityStep(
                enabled = snapshot.accessibilityEnabled,
                onOpenAccessibility = onOpenAccessibility,
                onOpenAppInfo = onOpenAppInfo,
            )
            else -> ReadyStep(snapshot, onTestBrowser)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page > 0) {
                OutlinedButton(
                    onClick = { page-- },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = PillShape,
                    border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text("Back") }
            }
            Button(
                onClick = { if (page < 3) page++ else onComplete() },
                enabled = page != 2 || snapshot.accessibilityEnabled,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = PillShape,
            ) {
                Text(if (page == 3) "Finish setup" else "Continue")
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PauseEmblem()
        Text(editorialTitle("Pause before browsing."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Choose a session, wait briefly, and keep browser time within your limits.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            "Private by default",
            "Your data stays on this device. No account, ads, or analytics.",
        )
        InfoCard(
            "You stay in control",
            "Turn protection off or disable Accessibility anytime.",
        )
    }
}

@Composable
private fun DefaultsStep() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Start with simple limits."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Change them anytime.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard("Daily budget", "60 minutes")
        InfoCard("Weekly budget", "10 hours")
        InfoCard("Monthly budget", "40 hours")
        InfoCard("Sessions", "Choose 10, 30, or 60 minutes. First wait: 5 seconds per 10 minutes.")
        InfoCard("Emergency skips", "Three per day. Each opens a browser for 30 minutes, even after a limit is used.")
    }
}

@Composable
private fun AccessibilityStep(
    enabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Enable the browser gate."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Accessibility detects supported browsers, shows the gate, and counts active use.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DisclosureCard()
        Button(
            onClick = onOpenAccessibility,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) { Text(if (enabled) "Accessibility enabled ✓" else "Open Accessibility settings") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InfoCard(
                "Switch blocked?",
                "In App info, choose Allow restricted settings, then return here.",
                actionLabel = "Open app info",
                onAction = onOpenAppInfo,
            )
        }
        if (!enabled) {
            Text(
                "Continue after Android confirms Accessibility is enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DisclosureCard() {
    val accent = EditorialTheme.colors.accent
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Why it is needed", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Detects when Chrome, Firefox, Edge, or Opera is in front.",
                "Shows the pause screen.",
                "Counts active browser time.",
                "Never reads pages, URLs, typing, or screenshots.",
                "Keeps data on this device.",
            ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ReadyStep(snapshot: StateSnapshot, onTestBrowser: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Test the gate."), style = MaterialTheme.typography.headlineLarge)
        Text(
            if (snapshot.serviceConnected) {
                "Open any supported browser. The pause screen should appear over it."
            } else {
                "The service is enabled but not connected yet. Open a supported browser to check again."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onTestBrowser,
            enabled = snapshot.supportedBrowserInstalled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) { Text("Test browser gate") }
        InfoCard(
            "What to expect",
            "The browser may appear briefly before the gate covers it. Your tab stays unchanged.",
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, EditorialTheme.colors.hairlineSoft),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actionLabel?.let {
                OutlinedButton(
                    onClick = onAction,
                    shape = PillShape,
                    border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(it) }
            }
        }
    }
}
