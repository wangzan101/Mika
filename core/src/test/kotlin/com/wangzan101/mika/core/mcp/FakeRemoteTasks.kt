package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.agent.TaskState

/** 测试用的任务出入口:状态由测试直接改,记下回答和取消。 */
class FakeRemoteTasks : RemoteTasks {
    val tasks = LinkedHashMap<String, RemoteTask>()
    val answers = mutableListOf<Pair<String, String>>()
    val cancelled = mutableListOf<String>()
    var refuse: String? = null
    private var next = 0

    override fun start(goal: String, conversation: String?): StartOutcome {
        refuse?.let { return StartOutcome.Refused(it) }
        val t = RemoteTask("t${++next}", conversation ?: McpTaskTools.newConversation(), goal, 1000L * next, null)
        tasks[t.id] = t
        return StartOutcome.Started(t)
    }

    override fun get(id: String) = tasks[id]
    override fun list(limit: Int) = tasks.values.reversed().take(limit)

    override fun answer(id: String, text: String): Boolean {
        answers += id to text
        return true
    }

    override fun cancel(id: String): Boolean {
        cancelled += id
        return true
    }

    fun set(id: String, state: TaskState?) {
        tasks[id] = tasks.getValue(id).copy(state = state)
    }
}
