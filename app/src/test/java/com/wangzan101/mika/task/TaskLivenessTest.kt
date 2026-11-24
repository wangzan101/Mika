package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.RunBudget
import com.wangzan101.mika.core.record.RunEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TaskLivenessTest {
    @Test fun warningDoesNotResetSilenceAndUnlimitedTasksStillHaveLivenessProtection() = runTest {
        var now = 0L
        var warning = 0
        var stopped = 0L
        val monitor = TaskLiveness { now }
        monitor.watch(RunBudget(noEventWarnMs = 60, noEventStopMs = 120), { stopped == 0L }, { false },
            warn = { warning++; monitor.event(RunEvent.State(now, "task_no_event_warning", "")) },
            stop = { stopped = now }, wait = { now += it })
        assertEquals(1, warning)
        assertEquals(120L, stopped)
    }

    @Test fun modelHeartbeatsAndWaitingForUserPreventFalseTimeouts() = runTest {
        var now = 0L
        var warnings = 0
        var stopped = false
        val monitor = TaskLiveness { now }
        monitor.watch(RunBudget(noEventWarnMs = 60, noEventStopMs = 120), { now < 600 }, { now < 300 },
            warn = { warnings++ }, stop = { stopped = true }, wait = {
                now += it
                if (now >= 300) monitor.event(RunEvent.ModelHeartbeat(now, 1, "m", now, 0, "waiting_for_model"))
            })
        assertFalse(stopped)
        assertEquals(0, warnings)
    }
}