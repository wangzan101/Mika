package com.wangzan101.mika.settings

import org.junit.Assert.*
import org.junit.Test

class ModelContextFormTest {
    @Test fun automaticAndExplicitWindowAndBothThresholdModes() {
        assertEquals(ModelContextSettings(), ModelContextForm().settings(500_000))
        assertEquals(ModelContextSettings(1_000_000, 80),
            ModelContextForm("1000000", ThresholdMode.Percent, "80").settings(500_000))
        assertEquals(ModelContextSettings(null, 300_000, ThresholdMode.Tokens),
            ModelContextForm("", ThresholdMode.Tokens, "300000").settings(500_000))
        assertEquals(ModelContextSettings(1_000_000, 600_000, ThresholdMode.Tokens),
            ModelContextForm("1000000", ThresholdMode.Tokens, "600000").settings(500_000))
    }

    @Test fun windowRejectsZeroNegativesFractionsSignsSeparatorsAndOverflow() {
        for (invalid in listOf("0", "-1", "+12", "1.5", "NaN", "Infinity", "1e6", "1_000", "1,000", "1 000", "2147483648", "9".repeat(100))) {
            val form = ModelContextForm(window = invalid)
            assertEquals(invalid, ContextInputError.PositiveInteger, form.windowError())
            assertNull(invalid, form.settings(500_000))
        }
        assertEquals(Int.MAX_VALUE, ModelContextForm(window = Int.MAX_VALUE.toString()).settings(500_000)!!.windowTokens)
        assertNull(ModelContextForm(window = "   ").settings(500_000)!!.windowTokens)
    }

    @Test fun percentageHasInclusiveOneToNinetyBounds() {
        for (invalid in listOf("", " ", "0", "91", "100", "-5", "+50", "60.5", "90%", "2147483648")) {
            val form = ModelContextForm(threshold = invalid)
            assertEquals(invalid, ContextInputError.PercentRange, form.thresholdError(500_000))
            assertNull(invalid, form.settings(500_000))
        }
        for (percent in listOf(1, 60, 90)) assertEquals(percent,
            ModelContextForm(threshold = percent.toString()).settings(500_000)!!.threshold)
    }

    @Test fun tokenThresholdUsesTheEffectiveWindowAndRevalidatesWhenWindowChanges() {
        val automatic = ModelContextForm(mode = ThresholdMode.Tokens, threshold = "90001")
        assertEquals(ContextInputError.BelowWindow, automatic.thresholdError(100_000))
        assertNull(automatic.settings(100_000))
        assertNotNull(automatic.copy(threshold = "90000").settings(100_000))
        assertEquals(ContextInputError.BelowWindow, automatic.copy(window = "50000").thresholdError(200_000))
        assertNotNull(automatic.copy(window = "100002").settings(50_000))
        for (invalid in listOf("", "0", "-1", "1.5", "1e3", "2147483648")) {
            assertEquals(invalid, ContextInputError.PositiveInteger, automatic.copy(threshold = invalid).thresholdError(500_000))
        }
        val max = ModelContextForm(Int.MAX_VALUE.toString(), ThresholdMode.Tokens, (Int.MAX_VALUE.toLong() * 90 / 100).toString())
        assertNotNull(max.settings(500_000))
        assertNull(max.copy(threshold = Int.MAX_VALUE.toString()).settings(500_000))
    }
}
