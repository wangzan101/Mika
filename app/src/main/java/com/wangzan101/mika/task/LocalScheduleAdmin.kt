package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.resumeFinishedOneShotBlocked

class LocalScheduleAdmin internal constructor(
    private val plans: () -> List<TriggerDefinition>,
    private val changeEnabled: (String, Boolean) -> Unit,
    private val delete: (String) -> Unit,
    private val changeTime: (String, Long) -> Unit,
    private val executeNow: (String) -> Unit,
    private val hasWindows: (String) -> Boolean = { false },
) : ScheduleAdmin {
    constructor(context: Context) : this(
        { TriggerRuntime.list(context) },
        { id, enabled -> TriggerRuntime.setEnabled(context, id, enabled) },
        { id -> TriggerRuntime.remove(context, id) },
        { id, atMs -> TriggerRuntime.reschedule(context, id, atMs) },
        { id -> TriggerRuntime.runNow(context, id) },
        { id -> TriggerRuntime.hasWindows(context, id) },
    )

    override fun list(threadId: String): List<TriggerDefinition> = plans().filter { it.threadId == threadId }

    private fun owned(threadId: String, id: String): Boolean = list(threadId).any { it.id == id }

    override fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean {
        if (!owned(threadId, id)) return false
        val plan = plans().firstOrNull { it.id == id }
        // #259: 已终结的一次性计划 resume 只会进闹钟热循环；与 UI 的 canToggle 一致地拒绝。
        if (enabled && plan != null && resumeFinishedOneShotBlocked(plan, hasWindows(id))) return false
        changeEnabled(id, enabled)
        return true
    }

    override fun remove(threadId: String, id: String): Boolean {
        if (!owned(threadId, id)) return false
        delete(id)
        return true
    }

    override fun reschedule(threadId: String, id: String, atMs: Long): Boolean {
        if (!owned(threadId, id)) return false
        changeTime(id, atMs)
        return true
    }

    override fun runNow(threadId: String, id: String): Boolean {
        if (!owned(threadId, id)) return false
        executeNow(id)
        return true
    }
}
