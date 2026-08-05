package dev.browserrestrictor.retro.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.browserrestrictor.retro.domain.BudgetPeriod
import dev.browserrestrictor.retro.domain.ForegroundClass
import dev.browserrestrictor.retro.domain.PersistedState
import dev.browserrestrictor.retro.domain.RestrictionEngine
import dev.browserrestrictor.retro.domain.RuntimeSignals
import dev.browserrestrictor.retro.domain.UsagePeriodState
import dev.browserrestrictor.retro.ui.gate.GateActions
import dev.browserrestrictor.retro.ui.gate.GateScreen
import dev.browserrestrictor.retro.ui.settings.SettingsScreen
import dev.browserrestrictor.retro.ui.theme.RestrictorTheme
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class GateScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val engine = RestrictionEngine()
    private val now = 1_783_683_200_000L
    private val runtime = RuntimeSignals(
        foregroundClass = ForegroundClass.BROWSER,
        browserActive = true,
        gateFocused = true,
        serviceConnected = true,
        accessibilityEnabled = true,
        storageReady = true,
        gateSessionId = "gate",
    )
    private val actions = GateActions({ _ -> }, {}, {}, {})

    @Test
    fun pausedGateShowsOnlyLockedSessionChoices() {
        val state = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val snapshot = engine.snapshot(state, runtime, now)
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithText("Pause before browsing.").assertIsDisplayed()
        composeRule.onNodeWithText("10 min").assertIsDisplayed()
        composeRule.onNodeWithText("30 min").assertIsDisplayed()
        composeRule.onNodeWithText("60 min").assertIsDisplayed()
        composeRule.onNodeWithText("Start 10 min").assertIsDisplayed()
        composeRule.onNodeWithText("←  Leave browser").assertIsDisplayed()
        composeRule.onAllNodesWithText("Settings", substring = true).assertCountEquals(0)
    }

    @Test
    fun outOfTimeGateOffersEmergencySkipAndHidesSessionControls() {
        val base = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val exhausted = base.copy(
            dailyUsage = UsagePeriodState(
                BudgetPeriod.DAILY,
                base.dailyUsage.key,
                base.settings.dailyBudgetMinutes * 60_000,
            ),
        )
        val snapshot = engine.snapshot(
            exhausted,
            runtime.copy(outOfTimeLatchToken = "latch", latchedBlockers = setOf(BudgetPeriod.DAILY)),
            now,
        )
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithText("Browser time is used up.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.onAllNodesWithText("minute session", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Emergency skip", substring = true).assertIsDisplayed()
    }

    @Test
    fun settingsShowsRepositorySaveError() {
        val state = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val snapshot = engine.snapshot(state, runtime, now).copy(
            lastErrorMessage = "Settings could not be saved.",
        )
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) {
                SettingsScreen(
                    snapshot = snapshot,
                    onBack = {},
                    onThemeChange = {},
                    onPauseProtection = {},
                    onResumeProtection = {},
                    onSave = {},
                    onResetUsage = {},
                    onCancelActiveUnlock = {},
                    onClearReports = {},
                    onHealthAction = {},
                    onExportDiagnostics = {},
                )
            }
        }

        composeRule.onNodeWithText("Settings could not be saved.").assertIsDisplayed()
    }
}
