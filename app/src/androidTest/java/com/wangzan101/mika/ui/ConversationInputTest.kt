package com.wangzan101.mika.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationInputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 分享后的新对话使用空输入,原稿在切回和界面恢复后仍然保留。 */
    @Test fun draftsBelongToTheirConversations() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var conversation by rememberSaveable { mutableStateOf("old") }
            var input by rememberConversationInput(conversation)
            MikaTheme {
                Column {
                    OutlinedTextField(input, { input = it }, Modifier.testTag("draft"))
                    Button({ conversation = "shared-new" }) { Text("分享的新对话") }
                    Button({ conversation = "old" }) { Text("原来的对话") }
                }
            }
        }
        compose.onNodeWithTag("draft").performTextInput("原来没发的文字")
        compose.onNodeWithText("分享的新对话").performClick()
        compose.onNodeWithTag("draft").assertTextEquals("")
        compose.onNodeWithTag("draft").performTextInput("新对话草稿")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("draft").assertTextEquals("新对话草稿")
        compose.onNodeWithText("原来的对话").performClick()
        compose.onNodeWithTag("draft").assertTextEquals("原来没发的文字")
    }
}
