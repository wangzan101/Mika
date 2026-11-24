package com.wangzan101.mika.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** On-device ART/coroutine smoke test; uses an injected uninterruptible port, no real app actions. */
@RunWith(AndroidJUnit4::class)
class PhoneDeadlineDeviceTest {
    private class BlockingScreen(private val release: CountDownLatch) : ScreenPort {
        override suspend fun snapshot(): ScreenSnapshot {
            while (true) try { if (release.await(5, TimeUnit.MILLISECONDS)) break } catch (_: InterruptedException) { }
            return ScreenSnapshot("device-probe", "test.local", emptyList(), 100, 100)
        }
        override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean { snapshot(); return true }
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok()
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
        override suspend fun back() = ActionResult.Ok()
        override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = emptyList<Pair<String, String>>()
    }

    @Test fun uninterruptibleReadReturnsAtDeadlineAndResourcesWaitForActualExit() = runBlocking {
        val release = CountDownLatch(1)
        val tools = toolDispatcher(BlockingScreen(release)).apply { localToolTimeoutMs = 100 }
        val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {},
            recorder = ActionRecorder(object : RunSink { override fun event(e: RunEvent) {} }), phoneToolTimeoutMs = 100)
        val start = System.nanoTime()
        try {
            val result = executor.run(Part.ToolCall("read", "phone_observe", "{}"), Origin.Model(1)).execution()
            assertEquals("phone_execution_stuck", result.content["error"]?.toString()?.trim('"'))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
            assertTrue(tools.localToolsBusy)
            val freed = AtomicBoolean(false)
            val cleanup = launch { tools.closeLocalTools(); tools.awaitLocalToolIdle(); freed.set(true) }
            delay(30)
            assertFalse(freed.get())
            release.countDown()
            withTimeout(3000) { cleanup.join() }
            assertTrue(freed.get())
        } finally { release.countDown(); tools.closeLocalTools(); tools.awaitLocalToolIdle() }
    }
}