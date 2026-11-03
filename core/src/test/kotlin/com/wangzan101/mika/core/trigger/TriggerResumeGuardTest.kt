package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerResumeGuardTest {
    private fun oneShot(nextAtMs: Long = 1_000) = TriggerDefinition("once", "t", "do", nextAtMs)

    @Test
    fun blocksFinishedOneShotWithAnyWindow() {
        assertTrue(resumeFinishedOneShotBlocked(oneShot(), hasAnyWindow = true))
    }

    @Test
    fun allowsNeverRunOneShot() {
        assertFalse(resumeFinishedOneShotBlocked(oneShot(), hasAnyWindow = false))
    }

    @Test
    fun allowsRecurringPlansEvenWithWindows() {
        assertFalse(resumeFinishedOneShotBlocked(oneShot().copy(intervalMs = 60_000), hasAnyWindow = true))
        assertFalse(resumeFinishedOneShotBlocked(
            oneShot().copy(calendarTime = "09:00", calendarZone = "Asia/Shanghai"), hasAnyWindow = true))
    }
}
