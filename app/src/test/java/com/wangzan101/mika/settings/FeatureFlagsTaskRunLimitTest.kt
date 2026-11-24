package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsTaskRunLimitTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun defaultsToTenMinutes() {
        assertEquals(FeatureFlags.DEFAULT_TASK_RUN_MINUTES, flags().taskRunMinutes())
    }

    @Test fun storesSupportedValuesAndExplicitUnlimited() {
        val f = flags()
        f.setTaskRunMinutes(30)
        assertEquals(30, f.taskRunMinutes())
        f.setTaskRunMinutes(null)
        assertNull(f.taskRunMinutes())
    }

    @Test fun unsupportedStoredValueFallsBackToUnlimited() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit().putInt("task_run_limit_minutes", 7).commit()
        assertNull(FeatureFlags(prefs).taskRunMinutes())
    }

    @Test fun migratingStepLimitAlsoSeedsTheRunLimitOnce() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val f = FeatureFlags(prefs)

        assertEquals(FeatureFlags.DEFAULT_TASK_STEP_LIMIT, f.taskStepLimit())
        assertTrue(prefs.getBoolean("task_budget_defaults_v1", false))
        assertEquals(FeatureFlags.DEFAULT_TASK_RUN_MINUTES, prefs.getInt("task_run_limit_minutes", -1))
    }

    @Test fun budgetSnapshotDoesNotChangeWhenSettingsChangeLater() {
        val f = flags()
        f.setTaskStepLimit(30)
        f.setTaskRunMinutes(15)
        val snapshot = f.taskRunBudget()

        f.setTaskStepLimit(120)
        f.setTaskRunMinutes(null)

        assertEquals(30, snapshot.maxSteps)
        assertEquals(15 * 60_000L, snapshot.timeBudgetMs)
        assertEquals(30_000L, snapshot.phoneToolTimeoutMs)
    }
}