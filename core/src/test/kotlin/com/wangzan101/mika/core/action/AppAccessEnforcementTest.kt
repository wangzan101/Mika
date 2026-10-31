package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.access.*
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppAccessEnforcementTest {
    @Test fun blockedScreenIsRefusedBeforeReadButShellStillRuns() = runTest {
        var reads = 0
        var commands = 0
        val screen = object : ScreenPort by FakeScreen() {
            override val appAccess = AppAccessPolicy { AppAccessState(blocked = setOf("bank")) }
            override suspend fun checkAppAccess() { throw AppAccessDenied("app_blocked", "bank") }
            override suspend fun snapshot(): ScreenSnapshot { reads++; error("must not read") }
            override suspend fun systemCommand(command: String): SystemCommandResult { commands++; return SystemCommandResult(0, "shell output") }
        }
        val executor = ActionExecutor(toolDispatcher(screen), OperationGuards(), null, gate = {}, recorder = ActionRecorder(com.wangzan101.mika.core.record.RunSink.NONE), autonomous = true)
        val blocked = executor.run(Part.ToolCall("read", "phone_observe", "{}"), Origin.Model(1))
        assertTrue(blocked is ActionOutcome.Refused)
        assertEquals("app_blocked", (blocked as ActionOutcome.Refused).code)
        assertEquals(0, reads)
        val shell = executor.run(Part.ToolCall("shell", "phone_system_command", """{"command":"id"}"""), Origin.Model(2))
        assertTrue("$shell", shell is ActionOutcome.Ran)
        assertEquals(1, commands)
        assertEquals(0, reads)
    }

    /** 单步被应用权限挡住时,结果要交给模型继续判断,不能把整次任务当成异常结束。 */
    @Test fun blockedToolResultReachesModelInsteadOfFailingTask() = runTest {
        var reads = 0
        val screen = object : ScreenPort by FakeScreen() {
            override val appAccess = AppAccessPolicy { AppAccessState(blocked = setOf("bank")) }
            override suspend fun checkAppAccess() { throw AppAccessDenied("app_blocked", "bank") }
            override suspend fun snapshot(): ScreenSnapshot { reads++; error("must not read") }
        }
        var calls = 0
        var toolResult: String? = null
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                calls++
                if (calls == 1) {
                    return ModelReply(listOf(Part.ToolCall("c1", "phone_observe", "{}")), StopReason.ToolUse, null)
                }
                toolResult = messages.asReversed().flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                    .firstOrNull { it.callId == "c1" }?.content
                return answerReply("这个应用已被你禁用，无法自动读取。")
            }
        }
        val end = AgentLoop(model, toolDispatcher(screen), "BASE", 10).run("看看银行 App")
        assertTrue("$end", end is TaskState.Succeeded)
        assertEquals(0, reads)
        assertEquals(2, calls)
        assertEquals(true, toolResult?.contains("\"error\":\"app_blocked\""))
        assertEquals(true, toolResult?.contains("disabled by the user"))
    }
}
