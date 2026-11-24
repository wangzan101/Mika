package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.capability.LocalToolExecutionScope
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PhoneBoundaryTest {
    private fun blocked(release: CountDownLatch) {
        while (true) try { if (release.await(5, TimeUnit.MILLISECONDS)) return } catch (_: InterruptedException) { }
    }

    @Test fun preparationAndCaptureHaveTheSameHardDeadlineAsTheAction() = runBlocking {
        for (phase in listOf("prepare", "capture", "composite", "observe")) {
            val release = CountDownLatch(1)
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun peek(): ScreenSnapshot { if (phase == "prepare") blocked(release); return FakeScreen().peek() }
                override suspend fun screenshotPng(): ByteArray? { if (phase == "capture") blocked(release); return null }
                override suspend fun snapshot(): ScreenSnapshot { if (phase in listOf("composite", "observe")) blocked(release); return FakeScreen().snapshot() }
            }
            val tools = toolDispatcher(screen, recordFrames = phase == "capture").apply { localToolTimeoutMs = 50 }
            val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {},
                recorder = ActionRecorder(object : RunSink { override fun event(e: RunEvent) {} }), phoneToolTimeoutMs = 50)
            val call = when (phase) {
                "composite" -> Part.ToolCall("c", "phone_find", """{"text":"missing","direction":"down"}""")
                "observe" -> Part.ToolCall("c", "phone_observe", "{}")
                else -> Part.ToolCall("c", "phone_back", "{}")
            }
            val start = System.nanoTime()
            try {
                val result = executor.run(call, Origin.Model(1)).execution()
                assertEquals("$phase $result", "phone_execution_stuck", result.content["error"]?.toString()?.trim('"'))
                assertTrue("$phase exceeded hard boundary", (System.nanoTime() - start) / 1_000_000 < 1000)
                assertTrue(tools.localToolsBusy)
            } finally { release.countDown(); tools.awaitLocalToolIdle(); tools.closeLocalTools() }
        }
    }

    @Test fun waitTimeoutBlocksOtherPhoneOperationsAndCleanupWaitsForExit() = runBlocking {
        val release = CountDownLatch(1)
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean { blocked(release); return true }
        }
        val tools = toolDispatcher(screen).apply { localToolTimeoutMs = 1000 }
        var quarantined = false
        tools.onPhoneExecutionStuck = { quarantined = true }
        try {
            tools.executeRead(Part.ToolCall("w", "phone_wait", """{"condition":"appear","value":"x","timeoutMs":10}"""))
            assertTrue(quarantined)
            val result = tools.executeRead(Part.ToolCall("o", "phone_observe", "{}"))
            assertEquals("phone_execution_stuck", result.content["error"]?.toString()?.trim('"'))
            val released = AtomicBoolean(false)
            val cleanup = launch { tools.closeLocalTools(); tools.awaitLocalToolIdle(); released.set(true) }
            delay(20)
            assertFalse(released.get())
            release.countDown()
            withTimeout(2000) { cleanup.join() }
            assertTrue(released.get())
        } finally { release.countDown(); tools.awaitLocalToolIdle(); tools.closeLocalTools() }
    }

    @Test fun workerPreservesContextAndRevokesNestedNonCancellableActions() = runBlocking {
        val release = CountDownLatch(1)
        val attempted = AtomicBoolean(false)
        val tools = toolDispatcher(FakeScreen()).apply { localToolTimeoutMs = 30 }
        try {
            try {
                withContext(CoroutineName("task-token-context")) {
                    tools.localOperation("phone_observe") {
                        assertEquals("task-token-context", currentCoroutineContext()[CoroutineName]?.name)
                        assertNotNull(currentCoroutineContext()[LocalToolExecutionScope])
                        blocked(release)
                        withContext(NonCancellable) { tools.localOperation("phone_back") { attempted.set(true) } }
                    }
                }
                fail("expected timeout")
            } catch (_: com.wangzan101.mika.core.tool.LocalToolTimeout) { }
            release.countDown()
            tools.awaitLocalToolIdle()
            assertFalse(attempted.get())
        } finally { release.countDown(); tools.awaitLocalToolIdle(); tools.closeLocalTools() }
    }

    @Test fun batchConditionsAndStableWaitsUseTheProductionBoundary() = runBlocking {
        for (condition in listOf("until", "stable", "text")) {
            val release = CountDownLatch(1)
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun peek(): ScreenSnapshot { blocked(release); return FakeScreen().snapshot() }
                override suspend fun waitStable(timeoutMs: Long): Boolean { blocked(release); return true }
            }
            val tools = toolDispatcher(screen).apply { localToolTimeoutMs = 30 }
            val start = System.nanoTime()
            try {
                when (condition) {
                    "until" -> assertEquals("phone_execution_stuck", tools.waitUntil(
                        com.wangzan101.mika.core.tool.Until(appears = "missing", timeoutMs = 50), System::currentTimeMillis, { false }).stopped)
                    "stable" -> tools.settle(50)
                    else -> assertTrue(tools.visibleText().isEmpty())
                }
                assertTrue((System.nanoTime() - start) / 1_000_000 < 500)
                assertTrue(tools.localToolsBusy)
            } finally { release.countDown(); tools.awaitLocalToolIdle(); tools.closeLocalTools() }
        }
    }

    @Test fun cancellingBeforeAWorkerStartsNeverLeavesCleanupWaitingForever() = runBlocking {
        repeat(30) {
            val runner = PhoneExecutionRunner()
            val caller = launch(Dispatchers.Default) { runner.execute(1000) { yield(); "done" } }
            caller.cancelAndJoin()
            runner.close()
            withTimeout(2000) { runner.awaitIdle() }
            assertFalse(runner.isBusy)
        }
    }
}