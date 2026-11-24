package com.wangzan101.mika.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneQuarantineTest {
    @Test
    fun quarantineStaysUntilEveryStuckRunIsCleared() {
        val quarantine = PhoneQuarantine()
        quarantine.add("run-a")
        quarantine.add("run-b")

        assertTrue(quarantine.blocked())
        quarantine.remove("run-a")
        assertTrue(quarantine.blocked())
        quarantine.remove("run-b")
        assertFalse(quarantine.blocked())
    }

    @Test
    fun clearingAnUnknownRunCannotReleaseAnotherRun() {
        val quarantine = PhoneQuarantine()
        quarantine.add("run-a")

        quarantine.remove("run-b")

        assertTrue(quarantine.blocked())
    }
}