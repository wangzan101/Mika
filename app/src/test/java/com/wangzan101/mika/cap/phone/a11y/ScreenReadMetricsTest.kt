package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReadMetricsTest {
    private fun metrics(total: Long) = ScreenReadMetrics(
        windowsMs = 0,
        rootsMs = 0,
        propertiesMs = 0,
        childrenMs = 0,
        totalMs = total,
        nodes = 0,
        maxDepth = 0,
    )

    @Test fun logsEveryReadInDetailedModeButOnlySlowReadsOtherwise() {
        assertFalse(metrics(99).shouldLog(detailedTraces = false))
        assertTrue(metrics(100).shouldLog(detailedTraces = false))
        assertTrue(metrics(1).shouldLog(detailedTraces = true))
    }
}