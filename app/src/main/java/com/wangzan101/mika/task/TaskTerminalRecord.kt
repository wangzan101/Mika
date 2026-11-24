package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink

/** Record the host's claimed terminal immediately; the cancelled loop cannot overwrite it. */
internal class TaskTerminalRecord(
    private val sink: RunSink,
    private val claimed: () -> TaskState.Terminal?,
) : RunSink {
    private val lock = Any()
    private var ended = false

    override fun event(e: RunEvent) {
        if (e !is RunEvent.TaskEnded) { sink.event(e); return }
        synchronized(lock) {
            if (ended) return
            ended = true
            sink.event(claimed()?.let { terminal(it, e.atMs) } ?: e)
        }
    }

    fun finish(state: TaskState, atMs: Long) {
        if (state !is TaskState.Terminal) return
        event(terminal(state, atMs))
    }

    override fun media(mediaId: Int, png: ByteArray) = sink.media(mediaId, png)

    private fun terminal(state: TaskState.Terminal, atMs: Long): RunEvent.TaskEnded = when (state) {
        is TaskState.Failed -> RunEvent.TaskEnded(atMs, "failed", Redactor.text(state.reason), state.kind.name.lowercase())
        is TaskState.Succeeded -> RunEvent.TaskEnded(atMs, if (state.doubtful) "unverified" else "succeeded", Redactor.text(state.answer))
        TaskState.Cancelled -> RunEvent.TaskEnded(atMs, "cancelled", "已取消")
    }
}