package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.RunBudget
import com.wangzan101.mika.core.record.RunEvent
import kotlinx.coroutines.delay

/** A monotonic activity clock. Its own warning must not reset the silence interval. */
internal class TaskLiveness(private val now: () -> Long) {
    @Volatile private var last = now()

    fun event(event: RunEvent) {
        if (event is RunEvent.State && event.name.startsWith("task_no_event_")) return
        last = now()
    }

    suspend fun watch(
        budget: RunBudget,
        running: () -> Boolean,
        waitingForUser: () -> Boolean,
        warn: () -> Unit,
        stop: suspend () -> Unit,
        wait: suspend (Long) -> Unit = { delay(it) },
    ) {
        // Queue time is not running time.
        last = now()
        var warned = false
        val poll = minOf(1_000L, budget.noEventWarnMs).coerceAtLeast(1L)
        while (running()) {
            wait(poll)
            if (!running()) return
            if (waitingForUser()) { last = now(); warned = false; continue }
            val silent = (now() - last).coerceAtLeast(0L)
            if (silent >= budget.noEventStopMs) { stop(); return }
            if (silent >= budget.noEventWarnMs && !warned) { warned = true; warn() }
            if (silent < budget.noEventWarnMs) warned = false
        }
    }
}