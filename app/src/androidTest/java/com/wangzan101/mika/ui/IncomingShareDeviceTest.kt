package com.wangzan101.mika.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IncomingShareDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as MikaApp

    /** 测试直接进入已看过引导的聊天页,不填模型配置,以便检查接收本身不会执行任务。 */
    @Before fun skipOnboarding() {
        OnboardingState(context).markModelSetupDone()
        PhoneServices.of(context).setupPrompt.markGuideSeen()
    }

    /** 分享和本地图片打开都由主界面承接,不注册多图或通用网页打开。 */
    @Test fun androidResolvesSharesToTheMainChat() {
        fun resolves(action: String, type: String, data: Uri? = null): Boolean {
            val intent = Intent(action).setDataAndType(data, type).setPackage(context.packageName)
            return context.packageManager.queryIntentActivities(intent, 0).any {
                it.activityInfo.name == MainActivity::class.java.name
            }
        }
        assertTrue(resolves(Intent.ACTION_SEND, "text/plain"))
        assertTrue(resolves(Intent.ACTION_SEND, "image/png"))
        assertTrue(resolves(Intent.ACTION_VIEW, "image/jpeg", Uri.parse("content://photos/shared/1")))
        assertFalse(resolves(Intent.ACTION_SEND_MULTIPLE, "image/png"))
        assertFalse(resolves(Intent.ACTION_VIEW, "text/html", Uri.parse("https://example.com")))
    }

    /** 冷启动文字分享直接填聊天输入框;伪造的状态无效,重建后也不会把用户改好的草稿覆盖回来。 */
    @Test fun textShareOpensEditableChatWithoutSending() {
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/shared")
            .putExtra("pending", "forged-conversation")
            .putExtra("source", "file:///private/forged.png")
            .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, "forged-conversation")
        val original = app.chat.ui.value.conversationId
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.waitUntil(5_000) { app.chat.ui.value.sharedText == null && app.chat.ui.value.conversationId != original }
            compose.onNodeWithTag("composer-field").assertTextEquals("https://example.com/shared")
            compose.onNodeWithText("分享到 Mika").assertDoesNotExist()
            val id = app.chat.ui.value.conversationId
            assertNotEquals("forged-conversation", id)
            compose.onNodeWithTag("composer-field").performTextReplacement("我改过的要求")
            scenario.recreate()
            compose.onNodeWithTag("composer-field").assertTextEquals("我改过的要求")
            assertEquals(id, app.chat.ui.value.conversationId)
            assertTrue(app.chat.ui.value.exchanges.isEmpty())
        }
    }

    /** 已打开 App 时接收真实 MediaStore 图片:显示原缩略图,可移除,旋转和切回原聊天都保留各自草稿。 */
    @Test fun imageShareUsesTheExistingComposerAndKeepsTheOldDraft() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "mika-share-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff4488ff.toInt()) }
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val shareIntent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri)
            ActivityScenario.launch<MainActivity>(shareIntent).use { scenario ->
                compose.waitUntil(5_000) { app.chat.ui.value.attachments.isNotEmpty() }
                compose.onNodeWithTag("composer-field").performTextReplacement("旋转前的图片要求")
                scenario.recreate()
                compose.onNodeWithTag("tray-photo").assertIsDisplayed()
                compose.onNodeWithTag("composer-field").assertTextEquals("旋转前的图片要求")
                scenario.onActivity { app.chat.removeAttachment(app.chat.ui.value.attachments.single().uri) }
                val oldId = UUID.randomUUID().toString()
                scenario.onActivity { app.chat.openConversation(oldId) }
                compose.onNodeWithTag("composer-field").performTextReplacement("原来没发的草稿")
                // 实际再次启动 singleTask Activity,覆盖 onNewIntent 热启动路径;VIEW 不显式提供 MIME。
                scenario.onActivity {
                    // ActivityScenario 给启动 Intent 加了 CLEAR_TASK;再次分享时移除测试框架的清栈标记。
                    it.startActivity(Intent(shareIntent).setFlags(0))
                }
                compose.waitUntil(5_000) { app.chat.ui.value.attachments.isNotEmpty() }
                compose.onNodeWithTag("tray-photo").assertIsDisplayed()
                assertNotEquals(oldId, app.chat.ui.value.conversationId)
                assertTrue(app.chat.ui.value.exchanges.isEmpty())
                val sharedId = app.chat.ui.value.conversationId
                val cached = File(context.cacheDir, "attach/incoming/$sharedId/image.png")
                assertTrue(cached.isFile)
                compose.onNodeWithTag("composer-field").performTextReplacement("看看这张图片")
                compose.onNodeWithTag("composer-field").assertTextEquals("看看这张图片")
                compose.onNodeWithContentDescription(context.getString(R.string.attach_remove, "image.png")).performClick()
                compose.onNodeWithTag("tray-photo").assertDoesNotExist()
                compose.waitUntil(5_000) { !cached.exists() }
                // 私有缓存清理不会删掉来源相册中的图片。
                context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
                scenario.onActivity { app.chat.openConversation(oldId) }
                compose.onNodeWithTag("composer-field").assertTextEquals("原来没发的草稿")
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    /** 图片读不到时报告失败并保留原对话,不把附言作为成功接收的图片草稿。 */
    @Test fun unreadableImageDoesNotReplaceTheCurrentChat() {
        lateinit var receiver: IncomingShareReceiver
        val store = ViewModelStore()
        val original = app.chat.ui.value.conversationId
        instrumentation.runOnMainSync {
            receiver = IncomingShareReceiver(app, SavedStateHandle())
            store.put("receiver", receiver)
            receiver.receive(IncomingShare("帮我看图", "content://missing.share.provider/image", "image/png"))
        }
        try {
            val notice = runBlocking { withTimeout(5_000) { receiver.notices.receive() } }
            assertEquals(R.string.share_read_failed, notice)
            assertEquals(original, app.chat.ui.value.conversationId)
            assertTrue(receiver.opened.tryReceive().isFailure)
            val forged = Intent(Intent.ACTION_VIEW).setDataAndType(
                Uri.parse("content://${context.packageName}.attachfiles/incoming/forged/image.png"), "image/png")
            assertNull(readIncomingShare(context, forged))
            forged.data = Uri.parse("content://0@${context.packageName}.attachfiles/incoming/forged/image.png")
            assertNull(readIncomingShare(context, forged))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
