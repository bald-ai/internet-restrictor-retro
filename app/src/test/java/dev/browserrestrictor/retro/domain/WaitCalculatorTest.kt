package dev.browserrestrictor.retro.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitCalculatorTest {
    @Test
    fun firstUnlockCostsMatchSpec() {
        assertEquals(5_000, WaitCalculator.requiredFocusMs(10, 0))
        assertEquals(15_000, WaitCalculator.requiredFocusMs(30, 0))
        assertEquals(30_000, WaitCalculator.requiredFocusMs(60, 0))
    }

    @Test
    fun costEscalatesOnlyFromCompletedCount() {
        assertEquals(10, WaitCalculator.costPerTenSeconds(1))
        assertEquals(15, WaitCalculator.costPerTenSeconds(2))
        assertEquals(60_000, WaitCalculator.requiredFocusMs(30, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSessionIsRejected() {
        WaitCalculator.requiredFocusMs(20, 0)
    }

    @Test
    fun countdownRoundsUp() {
        assertEquals(2, WaitCalculator.displaySeconds(1_001))
        assertEquals(1, WaitCalculator.displaySeconds(1))
        assertEquals(0, WaitCalculator.displaySeconds(0))
    }
}
