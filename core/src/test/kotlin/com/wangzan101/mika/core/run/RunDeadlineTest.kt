package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.*
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.usage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RunDeadlineTest {
    @Test fun expiredContextStopsToolsEvenInsideNonCancellable() = runBlocking {
        var left = 100L
        var expired = 0
        val deadline = RunDeadline({ left }, { expired++ })
        val screen = FakeScreen()
        val tools = toolDispatcher(screen)
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE))
        withContext(deadline) {
            left = 0
            withContext(NonCancellable) {
                try { executor.run(Part.ToolCall("c", "phone_back", "{}"), Origin.Model(1)); fail("expired action ran") }
                catch (_: RunDeadline.Exceeded) { }
                try { tools.executeRead(Part.ToolCall("r", "phone_observe", "{}")); fail("expired read ran") }
                catch (_: RunDeadline.Exceeded) { }
            }
        }
        assertEquals(1, expired)
        assertEquals(0, screen.snapshots)
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun expiredContextBlocksMeteredMainSideAndChildModelCalls() = runBlocking {
        var invoked = false
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                invoked = true
                return answerReply("late")
            }
        }.metered(UsageSource("p", "provider", "m", UsagePurpose.Main), sink = { _, _ -> })
        val deadline = RunDeadline({ 0L }, {})
        withContext(deadline) {
            try { model.complete("", emptyList(), emptyList()); fail("expired request sent") }
            catch (_: RunDeadline.Exceeded) { }
            val child = async {
                try { model.complete("", emptyList(), emptyList()); false } catch (_: RunDeadline.Exceeded) { true }
            }
            assertTrue(child.await())
        }
        assertFalse(invoked)
    }

    @Test fun duplicateAlarmAndAdmissionChecksClaimOneExpiry() {
        var calls = 0
        val deadline = RunDeadline({ 0L }, { calls++ })
        deadline.expire(); deadline.expire()
        repeat(2) { try { deadline.check() } catch (_: RunDeadline.Exceeded) { } }
        assertEquals(1, calls)
    }

    @Test fun failedExpiryPublicationCanRetryButNeverAdmitWork() {
        var calls = 0
        val deadline = RunDeadline({ 0L }, { if (++calls == 1) throw IllegalStateException("storage busy") })
        try { deadline.check(); fail("first admission must fail") } catch (_: IllegalStateException) { }
        try { deadline.check(); fail("second admission must still fail") } catch (_: RunDeadline.Exceeded) { }
        assertEquals(2, calls)
    }
}