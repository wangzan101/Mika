package com.wangzan101.mika.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.ThresholdMode
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only fixture models; exercises the actual settings form without account or model calls. */
@RunWith(AndroidJUnit4::class)
class ModelContextScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()

    @Test fun opensFromModelSettingsAndKeepsSavedValuesOnReopen() {
        val provider = Provider("p", "Example", ProviderConfig.Kind.OpenAiResponses, "https://example.test",
            listOf(ListedModel("example-model", contextTokens = 500_000)))
        val config = ProviderConfig("c", provider.kind, provider.baseUrl, "example-model",
            Capabilities(true, true, 500_000, true), provider.name, provider.id)
        var saved: ModelContextSettings? = null
        var onboarding by mutableStateOf(true)
        compose.setContent {
            var state by remember { mutableStateOf(ModelSetupUi(AddForm.of(ProviderPresets.all.first()),
                providers = listOf(provider), configs = listOf(config), roles = RoleChoices(main = "c"))) }
            MikaTheme {
                ModelSetupContent(state, onboarding, ModelSetupActions(setModelContext = { pid, model, value ->
                    saved = value
                    state = state.copy(providers = listOf(provider.copy(contextSettings = mapOf(model to value))))
                    pid == provider.id
                }), {})
            }
        }
        compose.onNodeWithTag("context-main").assertDoesNotExist()
        compose.runOnIdle { onboarding = false }
        compose.onNodeWithTag("context-main").performScrollTo().performClick()
        compose.onNodeWithTag("context-window").performTextReplacement("1000000")
        compose.onNodeWithTag("context-threshold").performTextReplacement("80")
        compose.onNodeWithTag("context-effective").performScrollTo().assertTextContains("800000", substring = true)
        screenshot("percentage")
        compose.onNodeWithTag("context-save").performClick()
        compose.onNodeWithTag("context-main").assertTextContains("1000000", substring = true).performClick()
        compose.onNodeWithTag("context-window").assertTextContains("1000000")
        compose.onNodeWithTag("context-threshold").performScrollTo().assertTextContains("80")
        assertEquals(ModelContextSettings(1_000_000, 80), saved)
    }

    @Test fun absoluteThresholdCannotEqualOrExceedEffectiveWindow() {
        var saved: ModelContextSettings? = null
        compose.setContent { MikaTheme { ModelContextDialog("example-model", 500_000, ModelContextSettings(), {}, { saved = it; true }) } }
        compose.onNodeWithTag("context-mode-tokens").performScrollTo().performClick()
        compose.onNodeWithTag("context-threshold").performTextReplacement("500000")
        compose.onNodeWithTag("context-save").assertIsNotEnabled()
        compose.onNodeWithText("绝对阈值不能超过有效上下文窗口的 90%。").performScrollTo().assertIsDisplayed()
        screenshot("invalid-absolute")
        compose.onNodeWithTag("context-window").performScrollTo().performTextReplacement("1000000")
        compose.onNodeWithTag("context-threshold").performScrollTo().performTextReplacement("600000")
        compose.onNodeWithTag("context-save").assertIsEnabled()
        compose.onNodeWithTag("context-effective").performScrollTo().assertTextContains("600000", substring = true)
        screenshot("absolute")
        compose.onNodeWithTag("context-save").performClick()
        assertEquals(ModelContextSettings(1_000_000, 600_000, ThresholdMode.Tokens), saved)
    }

    @Test fun percentageAndWindowValidationBlockSaveAndResetRestoresDefaults() {
        var saved: ModelContextSettings? = null
        compose.setContent { MikaTheme { ModelContextDialog("example-model", 500_000,
            ModelContextSettings(1_000_000, 80), {}, { saved = it; true }) } }
        compose.onNodeWithTag("context-threshold").performScrollTo().performTextReplacement("100")
        compose.onNodeWithTag("context-save").assertIsNotEnabled()
        compose.onNodeWithText("请输入 1 到 90 之间的整数百分比。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("context-window").performScrollTo().performTextReplacement("2147483648")
        compose.onNodeWithTag("context-save").assertIsNotEnabled()
        assertNull(saved)
        compose.onNodeWithTag("context-reset").performScrollTo().performClick()
        compose.onNodeWithTag("context-save").assertIsEnabled().performClick()
        assertEquals(ModelContextSettings(), saved)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("contextScreenshots") != "true") return
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("screenshot unavailable")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "context-settings-qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
