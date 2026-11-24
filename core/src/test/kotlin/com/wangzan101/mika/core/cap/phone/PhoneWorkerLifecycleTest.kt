package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.capability.LocalToolResult
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Args
import kotlinx.serialization.json.Json
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorkerLifecycleTest {
    @Test
    fun blockedWaitQuarantinesEveryPhoneWorkerUntilTheWaitReallyFinishes() = runBlocking {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean {
                val end = System.nanoTime() + 300_000_000L
                while (System.nanoTime() < end) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        // A blocked accessibility/Binder call may ignore interruption.
                    }
                }
                return true
            }
        }
        var stuck = 0
        var recovered = 0
        val phone = PhoneCapability(screen, deepLinks = false).apply {
            onExecutionCancelled = { stuck++ }
            onExecutionRecovered = { recovered++ }
        }

        val outcome = phone.waitRunner.wait(screen, "appear", "完成", 50)

        assertEquals(PhoneWaitRunner.Result.TimedOut, outcome)
        assertEquals(1, stuck)
        assertTrue(phone.localToolStuck)
        assertTrue(phone.localToolBusy)
        assertEquals(LocalToolResult.Stuck, phone.runLocalTool(50) { "must not run" })

        phone.awaitLocalToolIdle()

        assertEquals(1, recovered)
        assertFalse(phone.localToolStuck)
        assertFalse(phone.localToolBusy)
    }

    @Test
    fun blockedOrdinaryPhoneToolAlsoRejectsPhoneWaitForTheSameTask() = runBlocking {
        val dispatcher = toolDispatcher(FakeScreen())
        val phone = dispatcher.ctx.phone
        val outcome = phone.executionRunner.execute(50) {
            val end = System.nanoTime() + 300_000_000L
            while (System.nanoTime() < end) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // A blocked accessibility/Binder call may ignore interruption.
                }
            }
        }
        assertEquals(PhoneExecutionRunner.Result.TimedOut, outcome)

        val waited = PhoneWait.execute(
            Args(PhoneWait.def, Json.parseToJsonElement("""{"condition":"appear","value":"完成","timeoutMs":500}""").jsonObject),
            dispatcher.ctx,
        )

        assertEquals("screen_read_stuck", waited.content["error"]!!.jsonPrimitive.content)
        assertEquals(false, waited.content["met"]!!.jsonPrimitive.content.toBoolean())
        phone.awaitLocalToolIdle()
    }
}