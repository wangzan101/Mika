package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActionBoundaryTest {
    private fun executor(screen: ScreenPort) = ActionExecutor(
        toolDispatcher(screen),
        OperationGuards(),
        null,
        gate = {},
        recorder = ActionRecorder(RunSink.NONE),
        autonomous = true,
        phoneToolTimeoutMs = 50,
    )

    @Test
    fun describedTargetReadIsInsideTheWholeActionDeadline() = runBlocking {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                val end = System.nanoTime() + 300_000_000L
                while (System.nanoTime() < end) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        // A blocking accessibility/Binder read may ignore interruption.
                    }
                }
                return FakeScreen().snapshot()
            }
        }
        val started = System.nanoTime()
        val outcome = executor(screen).run(
            Part.ToolCall("described", "phone_tap", """{"element":"搜索"}"""),
            Origin.Model(1),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("described target read blocked for ${elapsedMs}ms", elapsedMs < 250)
        assertTrue("$outcome", outcome is ActionOutcome.Ran)
        assertEquals("phone_execution_stuck", outcome.execution().content["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun postActionAccessCheckIsInsideTheWholeActionDeadline() = runBlocking {
        var checks = 0
        val began = System.nanoTime()
        val checkTimes = mutableListOf<Long>()
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun checkAppAccess() {
                checks++
                checkTimes += (System.nanoTime() - began) / 1_000_000
                if (checks < 2) return
                val end = System.nanoTime() + 300_000_000L
                while (System.nanoTime() < end) {
                    try {
                        Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        // A blocking window probe may ignore interruption.
                    }
                }
            }
        }
        val started = System.nanoTime()
        val outcome = executor(screen).run(
            Part.ToolCall("post-check", "phone_tap", """{"x":500,"y":500}"""),
            Origin.Model(1),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val postCheckElapsedMs = elapsedMs - checkTimes[1]

        assertTrue("post-action access check blocked for ${postCheckElapsedMs}ms checks=$checkTimes", postCheckElapsedMs < 150)
        assertTrue("$outcome", outcome is ActionOutcome.Ran)
        assertEquals("phone_execution_stuck", outcome.execution().content["error"]!!.jsonPrimitive.content)
    }
}