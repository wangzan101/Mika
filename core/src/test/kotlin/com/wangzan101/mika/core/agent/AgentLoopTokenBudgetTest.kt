package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.usage.RunUsageTally
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.core.usage.metered
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每任务 token 预算:累计用量(含旁路与重试,这里用计量的假模型模拟)一到预算就照步数用完收尾。 */
class AgentLoopTokenBudgetTest {
    private val source = UsageSource("p1", "服务商甲", "m1", com.wangzan101.mika.core.usage.UsagePurpose.Main)
    private val sink = UsageSink { _, _ -> }

    private fun call(name: String, args: String = "{}") = Part.ToolCall("budget${++seq}", name, args)
    private var seq = 0

    private fun metered(model: FakeModel) = model.metered(source, sink)

    /** 回复带 [usage](预算只看服务商报的用量)。 */
    private fun used(usage: Usage?, vararg parts: Part) = ModelReply(parts.toList(), StopReason.ToolUse, usage)

    private fun spec(budget: Long?) = RunSpec(RunId("r1"), "查天气", "thread-1", tokenBudget = budget)

    /** 照产品路径把 [RunUsageTally] 放进运行的协程上下文。 */
    private suspend fun runWithTally(loop: AgentLoop, budget: Long?, inbox: RunInbox = RunInbox()) =
        withContext(RunUsageTally()) { loop.run(spec(budget), inbox) }

    @Test fun stopsWhenReportedUsageReachesBudget() = runTest {
        // 每次回复 input 600 + cached 400 = 1000 token;预算 1000,第一次调用后就到限
        val fake = FakeModel(used(Usage(600, 0, 400), call("wait", """{"seconds":0.2}""")), repeatLast = true)
        val events = mutableListOf<RunEvent>()
        val r = AgentLoop(metered(fake), toolDispatcher(FakeScreen()), "BASE", sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).let { loop -> runWithTally(loop, 1000) }
        assertTrue("$r", r is TaskState.Failed && r.kind == FailKind.TokenLimit && "1000" in r.reason)
        // 一次干活调用 + 一次收尾,收尾只给 end_not_done
        assertEquals(2, fake.requests.size)
        assertEquals(listOf("end_not_done"), fake.offeredTools.last())
        val notice = (fake.requests.last().withoutRuntime().last().parts.single() as Part.Text).text
        assertTrue(notice, "token budget" in notice)
        assertEquals(1000L, events.filterIsInstance<RunEvent.TaskStarted>().single().tokenBudget)
    }

    @Test fun budgetLeftAloneDoesNotStopTheTask() = runTest {
        val m = metered(FakeModel(
            used(Usage(100, 20, 0), call("wait", """{"seconds":0.2}""")),
            com.wangzan101.mika.core.testing.answerReply("查到了"),
        ))
        val r = runWithTally(AgentLoop(m, toolDispatcher(FakeScreen()), "BASE"), 10_000)
        com.wangzan101.mika.core.testing.assertAnswered("查到了", r)
    }

    @Test fun unreportedUsageNeverTriggersBudget() = runTest {
        // 服务商不报用量:预算再小也不会触发(不估 token)
        val m = metered(FakeModel(
            used(null, call("wait", """{"seconds":0.2}""")),
            com.wangzan101.mika.core.testing.answerReply("查到了"),
        ))
        val r = runWithTally(AgentLoop(m, toolDispatcher(FakeScreen()), "BASE"), 1)
        com.wangzan101.mika.core.testing.assertAnswered("查到了", r)
    }

    @Test fun noBudgetRecordsNullAndDoesNotStop() = runTest {
        val m = metered(FakeModel(
            used(Usage(600, 0, 400), call("wait", """{"seconds":0.2}""")),
            com.wangzan101.mika.core.testing.answerReply("查到了"),
        ))
        val events = mutableListOf<RunEvent>()
        val r = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).let { loop -> runWithTally(loop, null) }
        com.wangzan101.mika.core.testing.assertAnswered("查到了", r)
        assertNull(events.filterIsInstance<RunEvent.TaskStarted>().single().tokenBudget)
    }
}
