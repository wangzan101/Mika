package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Test

class RunUsageTallyTest {
    @Test fun totalAddsInputOutputAndCached() {
        val t = RunUsageTally()
        assertEquals(0L, t.total())
        t.add(Usage(100, 20, 60), 1)
        t.add(Usage(1, 2, 3), 2)
        assertEquals(186L, t.total())
    }

    @Test fun unreportedCallsAddNoTokens() {
        val t = RunUsageTally()
        t.add(null, 3)
        t.add(Usage(10, 5, 0), 1)
        assertEquals(15L, t.total())
    }
}
