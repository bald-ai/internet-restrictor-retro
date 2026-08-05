package dev.browserrestrictor.retro.monitoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveRearmGuardTest {
    @Test
    fun sameBrowserWindowCannotImmediatelyRelockAfterLeave() {
        val guard = LeaveRearmGuard()
        guard.beginSuppression()

        assertFalse(guard.observe(browserActive = true))
        assertTrue(guard.suppressingOverlays)
    }

    @Test
    fun browserRearmsOnlyAfterConfirmedNonBrowserObservation() {
        val guard = LeaveRearmGuard()
        guard.beginSuppression()

        assertFalse(guard.observe(browserActive = false))
        assertTrue(guard.observe(browserActive = true))
        assertFalse(guard.suppressingOverlays)
    }

    @Test
    fun repeatedNonBrowserObservationsKeepSuppressionActive() {
        val guard = LeaveRearmGuard()
        guard.beginSuppression()

        assertFalse(guard.observe(browserActive = false))
        assertFalse(guard.observe(browserActive = false))
        assertTrue(guard.suppressingOverlays)
    }
}
