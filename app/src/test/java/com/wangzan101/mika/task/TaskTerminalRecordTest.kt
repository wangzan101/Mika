package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import org.junit.Assert.*
import org.junit.Test

class TaskTerminalRecordTest {
    @Test fun watchdogPublishesTimeLimitBeforeWorkerExitAndLateCancellationIsIgnored() {
        val events = mutableListOf<RunEvent>()
        val state = TaskState.Failed("task time limit", FailKind.TimeLimit)
        val record = TaskTerminalRecord(object : RunSink { override fun event(e: RunEvent) { events += e } }) { state }
        record.finish(state, 600_000)
        assertEquals(1, events.size)
        record.event(RunEvent.TaskEnded(700_000, "cancelled", "cancelled"))
        record.finish(state, 700_001)
        val end = events.single() as RunEvent.TaskEnded
        assertEquals(600_000L, end.atMs)
        assertEquals("failed", end.outcome)
        assertEquals("timelimit", end.kind)
        assertEquals("task time limit", end.answerOrReason)
    }

    @Test fun loopCancellationUsesAlreadyClaimedHostState() {
        val events = mutableListOf<RunEvent>()
        val record = TaskTerminalRecord(object : RunSink { override fun event(e: RunEvent) { events += e } }) {
            TaskState.Failed("no events", FailKind.TimeLimit)
        }
        record.event(RunEvent.TaskEnded(120_000, "cancelled", "cancelled"))
        assertEquals("timelimit", (events.single() as RunEvent.TaskEnded).kind)
    }

    @Test fun normalCompletionAndUsageArePreserved() {
        val events = mutableListOf<RunEvent>()
        val record = TaskTerminalRecord(object : RunSink { override fun event(e: RunEvent) { events += e } }) { null }
        val done = RunEvent.TaskEnded(3, "succeeded", "done")
        record.event(done)
        record.finish(TaskState.Succeeded("done"), 4)
        val state = RunEvent.State(5, "usage_saved", "")
        record.event(state)
        assertEquals(listOf(done, state), events)
    }
}