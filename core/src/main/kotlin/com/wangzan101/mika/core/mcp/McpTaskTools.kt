package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** 电脑交来的一个任务:[state] 为空表示已经交给任务服务、还没登记上(马上就会有)。 */
data class RemoteTask(
    val id: String,
    val conversation: String,
    val goal: String,
    val createdAtMs: Long,
    val state: TaskState?,
)

sealed interface StartOutcome {
    data class Started(val task: RemoteTask) : StartOutcome
    data class Refused(val message: String) : StartOutcome
}

/**
 * App 一侧给电脑连接的任务出入口:任务照旧经任务服务开始、在对话里留记录,状态读任务自己的,这里不另存一份。
 * 只认电脑交来的任务(按 [RemoteTask.id]),手机上发起的任务看不到也动不了。
 */
interface RemoteTasks {
    fun start(goal: String, conversation: String?): StartOutcome
    fun get(id: String): RemoteTask?
    /** 新的在前。 */
    fun list(limit: Int): List<RemoteTask>
    /** 只在任务正等着回答追问时调用;交给任务时返回 true。 */
    fun answer(id: String, text: String): Boolean
    fun cancel(id: String): Boolean
}

/** 任务状态对电脑的说法;只读任务自己的状态,不另记。 */
object RemoteStatus {
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val WAITING_FOR_ANSWER = "waiting_for_answer"
    const val WAITING_ON_PHONE = "waiting_on_phone"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    const val ON_PHONE_NOTE = "This step needs the user on the phone and cannot be answered over MCP. " +
        "Mika has shown its usual notification there; keep waiting with mika_wait_task or cancel the task."

    fun of(state: TaskState?): String = when (state) {
        null, TaskState.Idle -> STARTING
        is TaskState.Running -> RUNNING
        is TaskState.Held -> if (state.reason.needsYou) WAITING_ON_PHONE else PAUSED
        is TaskState.WaitingInput -> WAITING_FOR_ANSWER
        is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> WAITING_ON_PHONE
        is TaskState.Succeeded -> DONE
        is TaskState.Failed -> FAILED
        TaskState.Cancelled -> CANCELLED
    }

    fun finished(status: String) = status == DONE || status == FAILED || status == CANCELLED

    /** 一个任务此刻的样子:状态、最近的进度说明、等什么、结果。 */
    fun describe(t: RemoteTask): JsonObject = buildJsonObject {
        val s = t.state
        val status = of(s)
        put("task_id", t.id)
        put("status", status)
        put("conversation", t.conversation)
        put("goal", t.goal)
        put("started_at_ms", t.createdAtMs)
        when (s) {
            is TaskState.Running -> {
                put("step", s.step)
                (s.narration ?: s.reason ?: s.note).takeIf { it.isNotBlank() }?.let { put("progress", it) }
            }
            is TaskState.Held -> {
                put("waiting_for", s.reason.code)
                s.reason.why.takeIf { it.isNotBlank() }?.let { put("progress", it) }
                if (s.reason.needsYou) put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingInput -> {
                put("question", s.prompt)
                s.options?.takeIf { it.isNotEmpty() }?.let { o -> putJsonArray("options") { o.forEach { add(it) } } }
                put("note", "Mika is asking a question. Reply with mika_answer; the user can also answer on the phone.")
            }
            is TaskState.WaitingConfirmation -> {
                put("waiting_for", "confirmation")
                put("app", s.request.app)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingTakeover -> {
                put("waiting_for", "hand_to_user")
                put("reason", s.reason)
                put("message", s.message)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.Succeeded -> {
                put("answer", s.answer)
                if (s.reviewed) put("verified", s.verified)
                s.note?.let { put("verification_note", it) }
                if (s.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { s.unconfirmed.forEach { add(it) } }
            }
            is TaskState.Failed -> {
                put("reason", s.reason)
                put("failure_kind", s.kind.name.lowercase())
            }
            null, TaskState.Idle, TaskState.Cancelled -> Unit
        }
        put("answerable", s is TaskState.WaitingInput)
    }
}

/** 电脑能用的工具:只给高层的「把事交给 Mika」,具体怎么做由手机上的 Mika 自己决定。 */
class McpTaskTools(
    private val tasks: RemoteTasks,
    private val pollMs: Long = 250,
) {
    class ToolError(message: String) : Exception(message)

    data class Tool(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean)

    val tools: List<Tool> = listOf(
        Tool(RUN, "Hand Mika a task to do on the Android phone. Mika runs its own agent on the phone (apps, web, files) " +
            "and records the task in a conversation the user can see in the app. Returns a task_id right away; " +
            "follow it with mika_wait_task. Payments and confirmations are never approved over MCP.",
            schema(required = listOf("goal")) {
                putJsonObject("goal") { put("type", "string"); put("description", "What to get done, in plain words, as you would tell a person.") }
                putJsonObject("conversation") {
                    put("type", "string")
                    put("description", "Optional: continue a conversation returned by an earlier task so Mika keeps its context.")
                }
            }, readOnly = false),
        Tool(STATUS, "Current state of a task: status, latest progress note, the question it is waiting on, or the final answer.",
            schema(required = listOf("task_id")) { taskIdProp() }, readOnly = true),
        Tool(WAIT, "Wait until the task's status changes or it finishes, up to timeout_s seconds " +
            "(default $DEFAULT_WAIT_S, at most $MAX_WAIT_S), then return its state. Call again to keep waiting.",
            schema(required = listOf("task_id")) {
                taskIdProp()
                putJsonObject("timeout_s") {
                    put("type", "integer"); put("minimum", 1); put("maximum", MAX_WAIT_S)
                    put("description", "Longest wait in seconds.")
                }
            }, readOnly = true),
        Tool(ANSWER, "Answer the question Mika asked in this task (status waiting_for_answer). " +
            "Cannot approve payments, confirmations or steps handed to the user; those stay on the phone.",
            schema(required = listOf("task_id", "text")) {
                taskIdProp()
                putJsonObject("text") { put("type", "string"); put("description", "The answer.") }
            }, readOnly = false),
        Tool(CANCEL, "Stop a task that was started over MCP.",
            schema(required = listOf("task_id")) { taskIdProp() }, readOnly = false),
        Tool(LIST, "Recent tasks started over MCP, newest first.",
            schema(required = emptyList()) {
                putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", MAX_LIST); put("description", "How many (default $DEFAULT_LIST).") }
            }, readOnly = true),
    )

    /** MCP 的工具定义(tools/list 的一项)。 */
    fun definitions(): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.schema)
                putJsonObject("annotations") {
                    put("readOnlyHint", t.readOnly)
                    if (!t.readOnly) put("destructiveHint", t.name != ANSWER)
                    put("openWorldHint", t.name == RUN)
                }
            })
        }
    }

    fun has(name: String) = tools.any { it.name == name }

    /** 执行一个工具;参数不对抛 [ToolError](协议层把它当成工具执行出错,交回给模型看)。 */
    suspend fun call(name: String, args: JsonObject): JsonObject = when (name) {
        RUN -> {
            val goal = string(args, "goal")?.trim().orEmpty()
            if (goal.isEmpty()) throw ToolError("goal is required and must be a non-empty string")
            if (goal.length > MAX_GOAL) throw ToolError("goal is too long (at most $MAX_GOAL characters)")
            val conversation = optString(args, "conversation")?.trim()?.takeIf { it.isNotEmpty() }
            if (conversation != null && !isConversation(conversation))
                throw ToolError("conversation must be one returned by an earlier MCP task (looks like mcp-<uuid>)")
            when (val r = tasks.start(goal, conversation)) {
                is StartOutcome.Started -> RemoteStatus.describe(r.task)
                is StartOutcome.Refused -> throw ToolError(r.message)
            }
        }
        STATUS -> RemoteStatus.describe(task(args))
        WAIT -> {
            val first = task(args)
            val timeout = optInt(args, "timeout_s") ?: DEFAULT_WAIT_S
            if (timeout !in 1..MAX_WAIT_S) throw ToolError("timeout_s must be between 1 and $MAX_WAIT_S")
            val from = RemoteStatus.of(first.state)
            var now = first
            var waited = 0L
            while (!RemoteStatus.finished(RemoteStatus.of(now.state)) && RemoteStatus.of(now.state) == from && waited < timeout * 1000L) {
                delay(pollMs)
                waited += pollMs
                now = tasks.get(first.id) ?: break
            }
            buildJsonObject {
                RemoteStatus.describe(now).forEach { (k, v) -> put(k, v) }
                put("changed", RemoteStatus.of(now.state) != from)
            }
        }
        ANSWER -> {
            val t = task(args)
            val text = string(args, "text")?.trim().orEmpty()
            if (text.isEmpty()) throw ToolError("text is required and must be a non-empty string")
            if (text.length > MAX_ANSWER) throw ToolError("text is too long (at most $MAX_ANSWER characters)")
            // 只回答追问;确认、付款、交给用户的那一步不经这里,永远只在手机上处理
            when (RemoteStatus.of(t.state)) {
                RemoteStatus.WAITING_FOR_ANSWER -> Unit
                RemoteStatus.WAITING_ON_PHONE -> throw ToolError(RemoteStatus.ON_PHONE_NOTE)
                else -> throw ToolError("Task ${t.id} is not waiting for an answer (status ${RemoteStatus.of(t.state)}).")
            }
            if (!tasks.answer(t.id, text)) throw ToolError("The question is no longer open; check mika_task_status.")
            buildJsonObject { put("task_id", t.id); put("accepted", true) }
        }
        CANCEL -> {
            val t = task(args)
            val status = RemoteStatus.of(t.state)
            if (RemoteStatus.finished(status)) buildJsonObject { put("task_id", t.id); put("cancelled", false); put("status", status) }
            else buildJsonObject { put("task_id", t.id); put("cancelled", tasks.cancel(t.id)) }
        }
        LIST -> {
            val limit = optInt(args, "limit") ?: DEFAULT_LIST
            if (limit !in 1..MAX_LIST) throw ToolError("limit must be between 1 and $MAX_LIST")
            buildJsonObject {
                putJsonArray("tasks") {
                    tasks.list(limit).forEach { t ->
                        add(buildJsonObject {
                            put("task_id", t.id); put("status", RemoteStatus.of(t.state)); put("goal", t.goal)
                            put("conversation", t.conversation); put("started_at_ms", t.createdAtMs)
                        })
                    }
                }
            }
        }
        else -> throw ToolError("Unknown tool: $name")
    }

    private fun task(args: JsonObject): RemoteTask {
        val id = string(args, "task_id")?.trim().orEmpty()
        if (id.isEmpty()) throw ToolError("task_id is required and must be a string")
        return tasks.get(id) ?: throw ToolError("No MCP task with id $id")
    }

    private fun string(args: JsonObject, key: String): String? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else throw ToolError("$key must be a string")
        else -> throw ToolError("$key must be a string")
    }

    private fun optString(args: JsonObject, key: String) = string(args, key)

    private fun optInt(args: JsonObject, key: String): Int? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) throw ToolError("$key must be an integer")
            else v.intOrNull ?: v.contentOrNull?.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it in -1e9..1e9 }?.toInt()
                ?: throw ToolError("$key must be an integer")
        else -> throw ToolError("$key must be an integer")
    }

    companion object {
        const val RUN = "mika_run_task"
        const val STATUS = "mika_task_status"
        const val WAIT = "mika_wait_task"
        const val ANSWER = "mika_answer"
        const val CANCEL = "mika_cancel_task"
        const val LIST = "mika_list_tasks"
        const val DEFAULT_WAIT_S = 30
        /** 电脑上的客户端一个请求常见的时限是一分钟,等待留出余量。 */
        const val MAX_WAIT_S = 50
        const val DEFAULT_LIST = 10
        const val MAX_LIST = 50
        const val MAX_GOAL = 4_000
        const val MAX_ANSWER = 2_000
        const val CONVERSATION_PREFIX = "mcp-"

        /** 电脑交来的任务各自的对话:编号带固定前缀,电脑只能接着自己开的对话,碰不到手机上的对话。 */
        fun newConversation(): String = CONVERSATION_PREFIX + UUID.randomUUID()

        fun isConversation(id: String): Boolean = id.startsWith(CONVERSATION_PREFIX) &&
            id.removePrefix(CONVERSATION_PREFIX).let { raw -> runCatching { UUID.fromString(raw).toString() == raw }.getOrDefault(false) }

        private fun schema(required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", props)
            putJsonArray("required") { required.forEach { add(it) } }
            put("additionalProperties", false)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.taskIdProp() =
            putJsonObject("task_id") { put("type", "string"); put("description", "The task_id returned by mika_run_task.") }
    }
}
