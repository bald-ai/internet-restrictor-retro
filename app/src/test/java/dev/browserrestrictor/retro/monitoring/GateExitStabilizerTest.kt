package dev.browserrestrictor.retro.monitoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateExitStabilizerTest {
    @Test
    fun singleNoisyObservationNeverConfirmsExit() {
        val stabilizer = GateExitStabilizer(stableForMs = 300)

        assertFalse(stabilizer.observe("com.example.popup", 1_000))
        assertFalse(stabilizer.observe(null, 1_500))
    }

    @Test
    fun agreeingObservationsMustSpanStableWindow() {
        val stabilizer = GateExitStabilizer(stableForMs = 300)

        assertFalse(stabilizer.observe("com.example.launcher", 1_000))
        assertFalse(stabilizer.observe("com.example.launcher", 1_299))
        assertTrue(stabilizer.observe("com.example.launcher", 1_300))
    }

    @Test
    fun candidateChangeRestartsStableWindow() {
        val stabilizer = GateExitStabilizer(stableForMs = 300)

        assertFalse(stabilizer.observe("com.example.popup", 1_000))
        assertFalse(stabilizer.observe("com.example.launcher", 1_300))
        assertFalse(stabilizer.observe("com.example.launcher", 1_599))
        assertTrue(stabilizer.observe("com.example.launcher", 1_600))
    }

    @Test
    fun delayedForegroundCheckCanConfirmLegitimateExit() {
        val stabilizer = GateExitStabilizer(stableForMs = 300)

        assertFalse(stabilizer.observe("com.example.launcher", 1_000))
        assertTrue(stabilizer.confirmAfterDelay(1_300, candidateStillForeground = true))
    }

    @Test
    fun delayedCheckCancelsCandidateWhenBrowserIsStillForeground() {
        val stabilizer = GateExitStabilizer(stableForMs = 300)

        assertFalse(stabilizer.observe("com.example.popup", 1_000))
        assertFalse(stabilizer.confirmAfterDelay(1_300, candidateStillForeground = false))
        assertFalse(stabilizer.hasPendingCandidate)
    }
}
