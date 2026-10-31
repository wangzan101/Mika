package com.wangzan101.mika.core.access

import org.junit.Assert.*
import org.junit.Test

class AppClassifierTest {
    @Test fun ignoresInventedPackagesAndUnknownCategories() {
        val parsed = AppClassifier.parse("""[{"package":"bank","category":"Finance","reason":"银行"},{"package":"invented","category":"Game"},{"package":"other","category":"invalid"}]""", setOf("bank", "other"))
        assertEquals(setOf("bank", "other"), parsed.keys)
        assertEquals(AppCategory.Finance, parsed["bank"]?.category)
        assertEquals(AppCategory.Unknown, parsed["other"]?.category)
    }

    @Test fun malformedReplyDoesNotProducePermissions() {
        assertTrue(AppClassifier.parse("not json", setOf("bank")).isEmpty())
        assertNull(AppAccessState(classifications = mapOf("bank" to AppClassification(AppCategory.Finance))).denial("bank"))
    }
}
