package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.core.usage.metered
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ModelContextCallerTest {
    @Test fun configuredBudgetsReachTheRealCallerThroughMeteringWithoutChangingWireModel() = runTest {
        val received = mutableListOf<List<Message>>()
        fun underlying(caps: Capabilities) = object : ChatModel {
            override val name = "provider-model"
            override val capabilities = caps
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                received += messages
                return ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, null)
            }
        }
        val history = listOf(Message(Role.User, listOf(Part.Text("Keep the current goal")), Author.User)) + (1..40).flatMap { n -> listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("c$n", "phone_tap", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c$n", """{"ok":true,"pad":"${"x".repeat(1000)}"}""", false)), Author.Mika),
        ) }
        suspend fun ask(window: Int = 1_000_000, compactAt: Double = 0.6) {
            val model = underlying(Capabilities(true, false, window, false, compactAt = compactAt))
                .metered(UsageSource("p", "provider", "model", UsagePurpose.Main), UsageSink { _, _ -> })
            assertEquals("provider-model", model.name)
            val caller = ModelCaller(model, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
            caller.reset("system")
            caller.ask(history, emptyList())
        }
        ask()
        ask(compactAt = 0.005)
        ask(window = 8000)
        assertEquals(history, received[0])
        for (sent in received.drop(1)) {
            assertTrue(sent.any { it.plainText().startsWith(ContextCompressor.HEADER) })
            assertTrue(sent.size < history.size)
            assertEquals(history.first(), sent.first())
            val calls = sent.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().map { it.id }
            val results = sent.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.callId }
            assertEquals(calls, results)
        }
    }
}
