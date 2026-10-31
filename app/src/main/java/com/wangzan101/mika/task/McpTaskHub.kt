package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.mcp.McpTaskHost
import com.wangzan101.mika.core.mcp.McpTaskLink
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement

/** MCP 只跟踪自己提交的任务;句柄跟进程走,不暴露其他对话。 */
class McpTaskHub(
    private val start: (RunSpec) -> Unit,
    private val stop: (String) -> Unit,
) : McpTaskHost {
    constructor(context: Context) : this(
        { spec -> TaskService.startMcp(context, spec.goal, spec.threadId, spec.id.value, spec.provenance.attendance) },
        { id -> TaskService.cancelMcp(context, id) },
    )

    private class Entry {
        val events = RunEventBus()
        var state: StateFlow<TaskState>? = null
        var input: (suspend (String) -> Boolean)? = null
        var queued = true
        var result: TaskState? = null
        var cancelled = false
    }
    private val entries = linkedMapOf<RunId, Entry>()

    @Synchronized override fun submit(spec: RunSpec): McpTaskLink {
        require(spec.provenance.origin == com.wangzan101.mika.core.run.RunOrigin.Mcp)
        check(spec.id !in entries)
        val entry = Entry().also { entries[spec.id] = it }
        try { start(spec) }
        catch (e: Exception) { entries.remove(spec.id); throw e }
        return McpTaskLink(spec.id, entry.events) { cancel(spec.id) }
    }

    @Synchronized fun attach(id: String, source: RunEventBus): AutoCloseable? =
        entries[RunId(id)]?.let { source.subscribe(it.events) }

    @Synchronized fun bind(id: String, state: StateFlow<TaskState>, queued: Boolean, input: suspend (String) -> Boolean) {
        entries[RunId(id)]?.let { it.state = state; it.queued = queued; it.input = input }
    }

    @Synchronized fun running(id: String) { entries[RunId(id)]?.queued = false }
    @Synchronized fun cancelled(id: String) = entries[RunId(id)]?.cancelled == true

    @Synchronized override fun status(id: RunId): JsonObject? = entries[id]?.let { entry ->
        snapshot(id.value, entry.result ?: entry.state?.value, entry.queued && entry.result == null)
    }

    override suspend fun reply(id: RunId, text: String): Boolean {
        val input = synchronized(this) { entries[id]?.takeIf { it.result == null && !it.cancelled }?.input }
        return input?.invoke(text) ?: false
    }

    override fun cancel(id: RunId): Boolean {
        synchronized(this) {
            val entry = entries[id]?.takeIf { it.result == null } ?: return false
            entry.cancelled = true
        }
        stop(id.value)
        return true
    }

    @Synchronized fun ended(id: String, result: TaskState) {
        entries[RunId(id)]?.let {
            it.result = result
            it.state = null
            it.input = null
            it.events.event(RunEvent.State(System.currentTimeMillis(), "mcp_task_ended", result::class.simpleName.orEmpty()))
        }
        // 只淘汰已结束的句柄,在跑和排队的任务始终可查、可取消。
        entries.filterValues { it.result != null }.keys.toList().dropLast(100).forEach(entries::remove)
    }

    companion object {
        internal fun snapshot(id: String, state: TaskState?, queued: Boolean) = buildJsonObject {
            put("task_id", id)
            if (queued) { put("status", "queued"); return@buildJsonObject }
            when (state) {
                is TaskState.Running -> {
                    put("status", if (state.step == 0) "preparing" else "running")
                    put("step", state.step)
                    put("progress", state.narration ?: state.reason ?: state.note)
                }
                is TaskState.WaitingInput -> {
                    put("status", "needs_input"); put("prompt", state.prompt)
                    state.options?.let { put("options", JsonArray(it.map(::JsonPrimitive))) }
                }
                is TaskState.WaitingTakeover -> { put("status", "needs_phone"); put("message", state.message) }
                is TaskState.WaitingConfirmation -> {
                    put("status", "needs_phone"); put("message", "Approve or decline this action in Mika on the phone.")
                }
                is TaskState.Held -> { put("status", "held"); put("message", state.reason.why) }
                is TaskState.Succeeded -> {
                    put("status", "succeeded"); put("answer", state.answer)
                    put("verified", state.verified); put("reviewed", state.reviewed)
                    state.note?.let { put("note", it) }
                    put("sources", Json.encodeToJsonElement(state.sources))
                }
                is TaskState.Failed -> { put("status", "failed"); put("reason", state.reason) }
                TaskState.Cancelled -> put("status", "cancelled")
                else -> put("status", "preparing")
            }
        }
    }
}
