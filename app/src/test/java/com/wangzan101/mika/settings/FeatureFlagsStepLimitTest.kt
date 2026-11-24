package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsStepLimitTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun defaultsToSixtySteps() {
        assertEquals(FeatureFlags.DEFAULT_TASK_STEP_LIMIT, flags().taskStepLimit())
    }

    @Test fun keepsAChosenLimit() {
        val f = flags()
        f.setTaskStepLimit(60)
        assertEquals(60, f.taskStepLimit())
        f.setTaskStepLimit(null)
        assertNull(f.taskStepLimit())
    }

    @Test fun clampsIntoRange() {
        val f = flags()
        f.setTaskStepLimit(0)
        assertNull(f.taskStepLimit())
        f.setTaskStepLimit(-5)
        assertNull(f.taskStepLimit())
        f.setTaskStepLimit(FeatureFlags.MAX_TASK_STEP_LIMIT + 100)
        assertEquals(FeatureFlags.MAX_TASK_STEP_LIMIT, f.taskStepLimit())
    }

    @Test fun explicitUnlimitedIsPreserved() {
        val f = flags()
        f.setTaskStepLimit(null)
        assertNull(f.taskStepLimit())
    }

    @Test fun firstReadMigratesDefaultsOnceWithoutOverwritingExplicitUnlimited() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit().putInt("task_step_limit", 0).commit()
        val f = FeatureFlags(prefs)

        assertFalse(prefs.contains("task_budget_defaults_v1"))
        assertNull(f.taskStepLimit())
        assertTrue(prefs.getBoolean("task_budget_defaults_v1", false))
        assertEquals(0, prefs.getInt("task_step_limit", -1))
    }
}