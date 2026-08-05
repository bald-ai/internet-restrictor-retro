package dev.browserrestrictor.retro.receivers

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionSchedulerTest {
    @Test
    fun exactPermissionSelectsExactDelivery() {
        assertEquals(AlarmDeliveryMode.EXACT, alarmDeliveryMode(exactAllowed = true))
    }

    @Test
    fun missingExactPermissionSelectsSafeInexactFallback() {
        assertEquals(AlarmDeliveryMode.INEXACT, alarmDeliveryMode(exactAllowed = false))
    }
}
