package com.wangzan101.mika.task

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.mcp.McpHttpServer
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.ui.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** HTTP → TaskService → 脚本模型;手机操作题还会走真实的内置助手和后台屏。 */
@RunWith(AndroidJUnit4::class)
class McpTaskDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as MikaApp
    private val models = List(4) { ScriptedModel() }
    private val created = AtomicInteger()
    private val client = OkHttpClient()

    @Before fun setUp() {
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        app.tasks.modelFactory = { _, _ -> models[created.getAndIncrement().coerceAtMost(models.lastIndex)] }
        app.tasks.lightModelFactory = { _, _ -> null }
        app.tasks.visionModelFactory = { _, _ -> null }
        instrumentation.runOnMainSync { app.mcp.enable() }
        check(pollUntil(10_000) { app.mcp.state.value.token } != null) { "MCP did not start: ${app.mcp.state.value.error}" }
    }

    @After fun tearDown() {
        app.taskState.runs.value.forEach { TaskService.cancel(context, it.key) }
        pollUntil(15_000) { true.takeIf { !app.taskState.busy.value } }
        instrumentation.runOnMainSync { app.mcp.disable() }
        pollUntil(5_000) { true.takeIf { !app.mcp.state.value.enabled } }
        app.tasks.modelFactory = TaskEnvironment.defaultModelFactory
        app.tasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        app.tasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    @Test fun questionCanBeAnsweredOverMcpAndResultStaysAvailable() {
        val id = start("帮我整理群聊，先问我是哪一个群")
        models[0].awaitCall()
        models[0].feed("ask_user", """{"prompt":"哪个群？","options":["工作群","读书群"]}""")
        val waiting = awaitStatus(id, "needs_input")
        assertEquals("哪个群？", waiting["prompt"]!!.jsonPrimitive.content)
        assertTrue(call("mika_reply", buildJsonObject { put("task_id", id); put("message", "读书群") })["accepted"]!!.jsonPrimitive.boolean)
        val next = models[0].awaitCall()
        assertTrue(next.messages.toString().contains("读书群"))
        models[0].answer("已收到，整理读书群。")
        // 没有再操作就交回答时,现有主循环会提醒一次继续做事。
        models[0].awaitCall()
        models[0].answer("已收到，整理读书群。")
        assertEquals("已收到，整理读书群。", awaitStatus(id, "succeeded")["answer"]!!.jsonPrimitive.content)
        assertEquals("succeeded", status(id)["status"]!!.jsonPrimitive.content)
    }

    @Test fun steerInterruptsTheModelAndQueuedCancellationNeverStartsIt() {
        val first = start("整理今天的讨论")
        models[0].awaitCall()
        assertTrue(call("mika_reply", buildJsonObject { put("task_id", first); put("message", "只整理上午的") })["accepted"]!!.jsonPrimitive.boolean)
        assertTrue(models[0].awaitCall().messages.toString().contains("只整理上午的"))
        val queued = start("这个排队任务不应运行")
        awaitStatus(queued, "queued")
        assertTrue(call("mika_cancel", buildJsonObject { put("task_id", queued) })["cancel_requested"]!!.jsonPrimitive.boolean)
        awaitStatus(queued, "cancelled")
        models[0].answer("已整理上午的讨论。")
        awaitStatus(first, "succeeded")
        assertEquals(1, created.get())
    }

    @Test fun mcpTaskOpensSettingsOnTheRealBackgroundDisplay() {
        sh("sh /sdcard/Android/data/${context.packageName}/files/usb-start.sh")
        DeviceSupport.shell()
        DeviceSupport.service()
        val id = start("打开系统设置")
        models[0].awaitCall()
        models[0].feed("phone_launch_app", """{"package":"com.android.settings"}""")
        models[0].awaitCall()
        val display = app.tasks.agentDisplayId.value
        assertTrue("background display was not created", display > 0)
        assertTrue(DeviceSupport.shell().topTaskId(display) >= 0)
        models[0].answer("已打开系统设置。")
        awaitStatus(id, "succeeded")
    }

    @Test fun disablingClosesTheEndpointAndReenablingRotatesTheToken() {
        val before = app.mcp.state.value.token
        instrumentation.runOnMainSync { app.mcp.disable() }
        check(pollUntil(5_000) { true.takeIf { !app.mcp.state.value.enabled } } != null)
        try {
            client.newCall(Request.Builder().url(URL).build()).execute().use { fail("MCP is still listening") }
        } catch (_: java.io.IOException) { }
        instrumentation.runOnMainSync { app.mcp.enable() }
        val after = pollUntil(5_000) { app.mcp.state.value.token }
        assertNotNull(after)
        assertNotEquals(before, after)
    }

    @Test fun runningTaskCanBeCancelledWhileWaitingForTheModel() {
        val id = start("等待测试取消")
        models[0].awaitCall()
        assertTrue(call("mika_cancel", buildJsonObject { put("task_id", id) })["cancel_requested"]!!.jsonPrimitive.boolean)
        awaitStatus(id, "cancelled")
    }

    /** 可选的桌面标准客户端探针:令牌只写 App 私有缓存,由 adb run-as 读取;不进测试输出。 */
    @Test fun externalClientProbe() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mcpExternalProbe") == "true")
        app.tasks.modelFactory = { _, _ -> object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("Mika MCP probe complete")), StopReason.EndTurn, null)
        } }
        val token = File(context.cacheDir, "mcp-probe.token")
        val done = File(context.cacheDir, "mcp-probe.done")
        done.delete()
        try {
            token.writeText(app.mcp.state.value.token!!)
            check(pollUntil(60_000) { true.takeIf { done.exists() } } != null) { "Desktop MCP client did not finish" }
        } finally { token.delete(); done.delete() }
    }

    private fun start(goal: String): String = call("mika_task", buildJsonObject { put("goal", goal) })["task_id"]!!.jsonPrimitive.content
    private fun status(id: String) = call("mika_status", buildJsonObject { put("task_id", id) })
    private fun awaitStatus(id: String, expected: String) = pollUntil(15_000) { status(id).takeIf { it["status"] == JsonPrimitive(expected) } }
        ?: error("Expected $expected; got ${status(id)}")

    private fun call(name: String, args: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            put("params", buildJsonObject { put("name", name); put("arguments", args) })
        }
        val request = Request.Builder().url(URL).header("Authorization", "Bearer ${app.mcp.state.value.token}")
            .header("Accept", "application/json, text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use {
            assertEquals(200, it.code)
            val response = Json.parseToJsonElement(it.body!!.string()).jsonObject
            check("error" !in response) { response.toString() }
            val result = response["result"]!!.jsonObject
            check(result["isError"] != JsonPrimitive(true)) { result.toString() }
            result["structuredContent"]!!.jsonObject
        }
    }

    private companion object { const val URL = "http://127.0.0.1:${McpHttpServer.DEFAULT_PORT}/mcp" }
}
