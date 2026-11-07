package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureFlagsTokenBudgetTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun defaultsToNoBudget() {
        assertNull(flags().taskTokenBudget())
    }

    @Test fun keepsAChosenBudget() {
        val f = flags()
        f.setTaskTokenBudget(1_000_000)
        assertEquals(1_000_000L, f.taskTokenBudget())
        f.setTaskTokenBudget(null)
        assertNull(f.taskTokenBudget())
    }

    @Test fun clampsIntoRange() {
        val f = flags()
        f.setTaskTokenBudget(0)
        assertNull(f.taskTokenBudget())
        f.setTaskTokenBudget(-5)
        assertNull(f.taskTokenBudget())
        f.setTaskTokenBudget(FeatureFlags.MAX_TASK_TOKEN_BUDGET + 100)
        assertEquals(FeatureFlags.MAX_TASK_TOKEN_BUDGET, f.taskTokenBudget())
    }
}
