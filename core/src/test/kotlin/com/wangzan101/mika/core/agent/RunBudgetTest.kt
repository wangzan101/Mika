package com.wangzan101.mika.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunBudgetTest {
    @Test fun defaultsAreUnlimitedTasksWithFiniteRuntimeSafetyBudgets() {
        val budget = RunBudget.DEFAULT
        assertNull(budget.maxSteps)
        assertNull(budget.timeBudgetMs)
        assertEquals(180_000L, budget.modelCallTimeoutMs)
        assertEquals(60_000L, budget.noEventWarnMs)
        assertEquals(120_000L, budget.noEventStopMs)
        assertNull(budget.phoneToolTimeoutMs)
    }

    @Test fun calculatesRemainingTimeAndSoftLimit() {
        val budget = RunBudget(timeBudgetMs = 600_000)
        assertEquals(600_000L, budget.timeRemainingMs(1_000, 1_000))
        assertEquals(479_000L, budget.timeRemainingMs(1_000, 122_000))
        assertFalse(budget.softTimeLimitReached(1_000, 480_999))
        assertTrue(budget.softTimeLimitReached(1_000, 481_000))
        assertEquals(0L, budget.timeRemainingMs(1_000, 700_000))
    }

    @Test fun unlimitedTimeBudgetNeverReportsSoftLimit() {
        val budget = RunBudget(timeBudgetMs = null)
        assertNull(budget.timeRemainingMs(0, Long.MAX_VALUE))
        assertFalse(budget.softTimeLimitReached(0, Long.MAX_VALUE))
    }

    @Test fun rejectsInvalidLimits() {
        for (make in listOf<() -> Unit>(
            { RunBudget(maxSteps = 0) },
            { RunBudget(timeBudgetMs = 0) },
            { RunBudget(modelCallTimeoutMs = 0) },
            { RunBudget(phoneToolTimeoutMs = 0) },
            { RunBudget(noEventWarnMs = 2, noEventStopMs = 2) },
        )) {
            try {
                make()
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}