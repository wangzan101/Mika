package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.core.usage.metered
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCallerTimeoutTest {
    private val history = listOf(Message(Role.User, listOf(Part.Text("查一下")), Author.User))

    private class IgnoresCancellation(private val blockedMs: Long) : ChatModel {
        override val name = "blocked"
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)

        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val end = System.nanoTime() + blockedMs * 1_000_000
            while (System.nanoTime() < end) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // Deliberately ignore cancellation: the caller must still stop waiting.
                }
            }
            return answerReply("too late")
        }
    }

    private class StallsAfterOneChunk(private val blockedMs: Long) : StreamingChatModel {
        override val name = "stalled-stream"
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
        override val managedTransport = true

        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            answerReply("fallback")

        override suspend fun completeStreaming(
            system: String,
            messages: List<Message>,
            tools: List<ToolSpec>,
            onText: (String) -> Unit,
        ): ModelReply {
            onText("partial")
            val end = System.nanoTime() + blockedMs * 1_000_000
            while (System.nanoTime() < end) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // Simulate a provider stream that stops producing chunks but ignores cancellation.
                }
            }
            return answerReply("too late")
        }
    }

    @Test fun blockingModelTimesOutAtTheRunBudgetAndEmitsHeartbeats() = runBlocking {
        val events = mutableListOf<RunEvent>()
        val managed = IgnoresCancellation(blockedMs = 400).metered(
            UsageSource("p", "Blocked", "blocked", UsagePurpose.Main),
            sink = { _, _ -> },
        )
        val caller = ModelCaller(
            managed,
            toolDispatcher(FakeScreen()),
            null,
            SpeedFlags.OFF,
            RetryPolicy.NONE,
            events::add,
            System::currentTimeMillis,
        )
        val budget = RunBudget(
            modelCallTimeoutMs = 80,
            noEventWarnMs = 20,
            noEventStopMs = 40,
        )
        caller.reset("", budget)

        val began = System.nanoTime()
        val failure = try {
            caller.ask(history, emptyList(), step = 1)
            null
        } catch (e: ModelError.Network) {
            e
        }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        assertNotNull("a blocking model must become a retryable network timeout", failure)
        assertTrue("caller waited ${elapsedMs}ms", elapsedMs < 350)
        val started = events.filterIsInstance<RunEvent.ModelRequestStarted>().single()
        assertTrue(started.model == "blocked" && started.timeoutMs == 80L && started.retry == 0)
        assertTrue(events.filterIsInstance<RunEvent.ModelHeartbeat>().any {
            it.model == "blocked" && it.retry == 0 && it.phase == "waiting_for_model"
        })
    }

    @Test fun stalledStreamStopsAtTheNoEventThresholdBeforeTheOverallModelTimeout() = runBlocking {
        val events = mutableListOf<RunEvent>()
        val caller = ModelCaller(
            StallsAfterOneChunk(blockedMs = 600),
            toolDispatcher(FakeScreen()),
            null,
            SpeedFlags.OFF,
            RetryPolicy.NONE,
            events::add,
            System::currentTimeMillis,
        )
        caller.reset("", RunBudget(
            modelCallTimeoutMs = 500,
            noEventWarnMs = 40,
            noEventStopMs = 120,
        ))

        val began = System.nanoTime()
        val failure = try {
            caller.ask(history, emptyList(), step = 1, onText = {})
            null
        } catch (e: ModelError.Network) {
            e
        }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000

        assertNotNull(failure)
        assertTrue("caller waited ${elapsedMs}ms", elapsedMs < 400)
        assertTrue(events.filterIsInstance<RunEvent.ModelHeartbeat>().any {
            it.model == "stalled-stream" && it.phase == "no_event_timeout"
        })
    }
    @Test fun modelDeadlineDoesNotDependOnAFrozenRecordClock() = runBlocking {
        val managed = object : ChatModel by IgnoresCancellation(500) { override val managedTransport = true }
        val caller = ModelCaller(managed, toolDispatcher(FakeScreen()), null,
            SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0L })
        caller.reset("", RunBudget(modelCallTimeoutMs = 50))
        val began = System.nanoTime()
        var timedOut = false
        try { caller.ask(history, emptyList(), step = 1) } catch (_: ModelError.Network) { timedOut = true }
        assertTrue(timedOut)
        assertTrue((System.nanoTime() - began) / 1_000_000 < 400)
    }

}