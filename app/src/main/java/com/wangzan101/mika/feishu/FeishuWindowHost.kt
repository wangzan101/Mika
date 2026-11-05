package com.wangzan101.mika.feishu

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.feishu.FeishuWindow
import com.wangzan101.mika.core.feishu.FeishuWindowHost
import com.wangzan101.mika.core.remote.RemoteStatus
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Uses the same conversation titles, task registry and queue as the phone UI and WeChat. */
class AndroidFeishuWindows(private val app: MikaApp, private val owner: () -> String?) : FeishuWindowHost {
    override fun list(): List<FeishuWindow> {
        val live = app.taskState.runs.value.map { it.conversationId }.distinct()
        val stored = app.conversations.list().associateBy { it.id }
        return (live + stored.keys).distinct().map { id ->
            val run = current(id)
            val title = app.conversations.title(id) ?: stored[id]?.title ?: run?.goal?.take(100) ?: "Mika"
            FeishuWindow(id, title, run?.let { RemoteStatus.of(it.state.value) } ?: "idle", runKey = run?.key,
                controllable = run?.let(::owned) == true, active = run?.state?.value?.let { it !is TaskState.Terminal } == true)
        }
    }
    fun title(id: String): String = app.conversations.title(id) ?: current(id)?.goal?.take(100)
        ?: app.conversations.list().firstOrNull { it.id == id }?.title ?: "Mika 任务"

    override fun get(id: String): FeishuWindow? {
        val run = current(id)
        val history = app.conversations.list().firstOrNull { it.id == id }
        if (run == null && history == null) return null
        val title = app.conversations.title(id) ?: history?.title ?: run?.goal?.take(100) ?: "Mika"
        if (run != null) {
            val state = run.state.value
            val status = RemoteStatus.of(state)
            val report = RemoteStatus.describe(RemoteTask(run.key, id, run.goal, 0L, state))
            val detail = (listOf("要求：${run.goal.take(500)}") + listOf("step", "progress", "question", "message", "reason", "answer", "waiting_for")
                .mapNotNull { key -> report[key]?.jsonPrimitive?.contentOrNull?.let { "$key：$it" } }).joinToString("\n")
            return FeishuWindow(id, title, status, detail, run.key,
                run.activeAsk?.takeIf { it.kind == Delivery.Ask.Kind.Input }?.askId,
                owned(run), state !is TaskState.Terminal)
        }
        val last = app.conversations.turns(id).lastOrNull()
        return FeishuWindow(id, title, last?.status ?: "idle",
            last?.let { "要求：${it.userText.take(500)}\n${it.outcome}" }.orEmpty())
    }

    override fun newId(): String = app.conversations.newConversation()
    override fun submit(window: String, messageId: String, text: String) {
        val owner = checkNotNull(owner()) { "Feishu owner not paired" }
        TaskService.startChannel(app, ChannelPeer(FeishuChannel.ID, owner, window), messageId, text)
    }
    override suspend fun answer(window: String, runKey: String, askId: String, text: String): Boolean {
        val run = controlled(window, runKey) ?: return false
        if (run.activeAsk?.let { it.askId == askId && it.kind == Delivery.Ask.Kind.Input } != true) return false
        return run.answerFromChannel(text, askId)
    }
    override fun steer(window: String, runKey: String, messageId: String, text: String): Boolean =
        controlled(window, runKey)?.steerFromChannel(text, messageId) == true
    override fun cancel(window: String, runKey: String): Boolean {
        val run = controlled(window, runKey) ?: return false
        if (run.state.value is TaskState.Terminal) return false
        TaskService.cancel(app, run.key)
        return true
    }
    private fun owned(run: TaskRun): Boolean = run.channelPeer?.let { it.channelId == FeishuChannel.ID && it.peerId == owner() } == true
    private fun current(id: String): TaskRun? = app.taskState.runs.value.filter { it.conversationId == id }
        .let { runs -> runs.firstOrNull { run -> (run.state.value as? TaskState.Held)?.reason?.queued != true } ?: runs.firstOrNull() }
    private fun controlled(id: String, key: String): TaskRun? = current(id)?.takeIf { it.key == key && owned(it) }
}
