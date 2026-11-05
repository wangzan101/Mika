package com.wangzan101.mika.core.feishu

import com.wangzan101.mika.core.run.SendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

@Serializable data class FeishuMessage(
    val id: String, val sender: String, val chat: String, val text: String, val created: Long,
    val action: String? = null, val window: String? = null, val menu: String? = null,
    val runKey: String? = null, val page: Int = 0,
)
@Serializable data class FeishuState(
    val pairingCode: String = newPairingCode(), val owner: String? = null, val chat: String? = null,
    val inbox: List<FeishuMessage> = emptyList(), val seen: Map<String, Long> = emptyMap(),
    // Keep v0.1.35 fields so upgrading does not invalidate the encrypted owner binding.
    val taskId: String? = null, val conversation: String? = null,
    val pendingGoal: String? = null, val pendingConversation: String? = null,
    val attention: String? = null, val noticeSequence: Long = 0,
    val retryAfter: Map<String, Long> = emptyMap(), val attempts: Set<String> = emptySet(),
    val localResults: Map<String, String> = emptyMap(),
    val selectedWindow: String? = conversation, val selectionVersion: Long = 0,
    val drafts: Set<String> = emptySet(), val menus: Map<String, FeishuMenu> = emptyMap(),
    val submissions: Map<String, FeishuSubmission> = emptyMap(),
)
private fun newPairingCode(): String = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

class FeishuJournal(read: () -> String?, private val write: (String) -> Unit) {
    @Volatile var state: FeishuState = read()?.let { Json.decodeFromString<FeishuState>(it) } ?: FeishuState()
        private set
    @Synchronized fun update(change: (FeishuState) -> FeishuState) {
        val next = change(state)
        if (next == state) return
        write(Json.encodeToString(next)); state = next
    }
    fun acknowledge(messageId: String) = update { current ->
        val window = current.submissions[messageId]?.window
        current.copy(submissions = current.submissions - messageId,
            drafts = if (window != null) current.drafts - window else current.drafts)
    }
}

/** A selected window is an input destination; selecting it never mutates another run. */
class FeishuBot(
    private val appId: String, private val journal: FeishuJournal, private val windows: FeishuWindowHost,
    private val send: suspend (String, String, String) -> SendOutcome,
    private val sendCard: suspend (String, JsonObject, String) -> SendOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val processing = Mutex()

    fun enqueue(payload: JsonObject) {
        val header = payload["header"]?.jsonObject ?: return
        if (header.string("app_id") != appId) return
        val event = payload["event"]?.jsonObject ?: return
        val input = when (header.string("event_type")) {
            "im.message.receive_v1" -> {
                val sender = event["sender"]?.jsonObject ?: return
                val m = event["message"]?.jsonObject ?: return
                if (sender.string("sender_type") != "user" || m.string("chat_type") != "p2p" || m.string("message_type") != "text") return
                FeishuMessage(m.string("message_id"), sender["sender_id"]?.jsonObject?.string("open_id").orEmpty(),
                    m.string("chat_id"), Json.parseToJsonElement(m.string("content")).jsonObject.string("text").trim(),
                    eventTime(m.string("create_time")) ?: return)
            }
            "card.action.trigger" -> {
                val action = event["action"]?.jsonObject ?: return
                val value = action["value"]?.jsonObject ?: return
                val context = event["context"]?.jsonObject ?: return
                val operator = event["operator"]?.jsonObject ?: return
                val op = value.string("op")
                if (op !in setOf("select", "new", "list", "view", "cancel")) return
                FeishuMessage("card:" + header.string("event_id"), operator.string("open_id"), context.string("open_chat_id"), "",
                    eventTime(header.string("create_time")) ?: return, op,
                    if (op == "select") action.string("option") else value.string("window").ifBlank { null },
                    value.string("menu"), value.string("run").ifBlank { null }, value["page"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            else -> return
        }
        if (input.id.isBlank() || input.id == "card:" || input.sender.isBlank() || input.chat.isBlank() ||
            input.text.length > 8000 || !fresh(input.created) || (input.action == null && input.text.isBlank())) return
        journal.update { state ->
            if (input.id in state.seen || state.inbox.any { it.id == input.id }) return@update state
            if (state.owner != null && (state.owner != input.sender || state.chat != input.chat)) return@update state
            if (state.owner == null && (input.action != null || !binds(input.text, state.pairingCode))) return@update state
            check(state.inbox.size < 100) { "Feishu inbox full" }
            state.copy(inbox = state.inbox + input)
        }
    }
    fun acknowledge(messageId: String) = journal.acknowledge(messageId)
    fun mirrorResult(id: String, text: String) = journal.update { state ->
        if (state.owner == null || state.chat == null || id in state.localResults || "local:$id" in state.attempts) state
        else {
            check(state.localResults.size < 100) { "Feishu result queue full" }
            state.copy(localResults = state.localResults + (id to text.take(12_000)))
        }
    }

    suspend fun tick() = processing.withLock {
        journal.state.inbox.firstOrNull()?.let { input ->
            if (input.id in journal.state.seen) notice(input.id + ":interrupted", "上次处理被中断，请发送 /任务 核对各窗口；未自动重发。")
            else {
                journal.update { state -> state.copy(seen = (state.seen + (input.id to clock()))
                    .filterValues { clock() - it < 30 * 86400_000L }.entries.toList().takeLast(1000).associate { it.toPair() }) }
                try {
                    if (!fresh(input.created)) notice(input.id + ":expired", "排队消息已过期，未执行。")
                    else handle(input)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice(input.id + ":error", "未确认操作结果。请发送 /任务 核对原窗口，勿重复下发。") }
            }
            journal.update { it.copy(inbox = it.inbox.filterNot { pending -> pending.id == input.id }) }
        }
        journal.state.localResults.entries.firstOrNull()?.let { (id, text) ->
            notice("local:$id", text)
            if ("local:$id" in journal.state.attempts) journal.update { it.copy(localResults = it.localResults - id) }
        }
    }

    private suspend fun handle(input: FeishuMessage) {
        val state = journal.state
        if (state.owner == null) {
            if (!binds(input.text, state.pairingCode)) return
            journal.update { it.copy(owner = input.sender, chat = input.chat, pairingCode = "") }
            notice(input.id, "已绑定 Mika。" + HELP); return
        }
        if (state.owner != input.sender || state.chat != input.chat) return
        if (input.action != null) { callback(input); return }
        when (val text = input.text) {
            "帮助", "/help" -> { notice(input.id, HELP); return }
            "/任务", "/tasks", "任务列表" -> { menu(input.id); return }
            "新对话", "/新建", "/new", "新建任务" -> { newWindow(input.id); return }
            "状态", "进度", "/status", "/查看" -> { view(input.id); return }
            "重试", "/retry" -> {
                val pending = state.submissions.values.lastOrNull { it.window == state.selectedWindow }
                if (pending == null) notice(input.id, "当前窗口没有待核对提交。")
                else { windows.submit(pending.window, pending.messageId, pending.text); notice(input.id, "已使用原窗口和原消息编号核对或补交。") }
                return
            }
        }
        val selected = selected()
        if (input.text in setOf("取消", "/cancel")) { cancel(input.id, selected, selected?.runKey); return }
        val reply = Regex("^(回复|追加)[：:]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).matchEntire(input.text)
        if (reply != null) {
            if (reply.groupValues[2].length > 4000) { notice(input.id, "回答或追加内容最多 4000 字符。"); return }
            if (selected?.controllable != true || selected.runKey == null) {
                notice(input.id, "当前窗口没有本机器人可控制的运行。可查看其他窗口或新建任务。"); return
            }
            val accepted = if (reply.groupValues[1] == "回复")
                selected.askId?.let { windows.answer(selected.id, selected.runKey, it, reply.groupValues[2]) } ?: false
            else windows.steer(selected.id, selected.runKey, input.id, reply.groupValues[2])
            notice(input.id, if (accepted) "已交给【${selected.title}】。" else "该运行已变化或不接受此操作，请刷新当前窗口。")
            return
        }
        if (selected?.active == true || state.submissions.values.any { it.window == state.selectedWindow }) {
            notice(input.id, "【${selected?.title ?: "当前窗口"}】已有运行或待核对提交。普通文字不会追加。请用 /任务 新建独立任务，或明确发送“追加：内容”。")
            return
        }
        if (state.selectedWindow != null && selected == null) {
            notice(input.id, "所选窗口已删除，请发送 /任务 重新选择或新建。"); return
        }
        val id = state.selectedWindow ?: allocate()
        journal.update { it.copy(submissions = it.submissions + (input.id to FeishuSubmission(id, input.id, input.text))) }
        windows.submit(id, input.id, input.text)
        // TaskService sends one acceptance after durable native registry registration.
    }

    private fun all(): List<FeishuWindow> {
        val native = windows.list()
        return (native + journal.state.drafts.filter { id -> native.none { it.id == id } }
            .map { FeishuWindow(it, "新任务窗口", "draft") }).distinctBy { it.id }
    }
    private fun selected(): FeishuWindow? = journal.state.selectedWindow?.let { id ->
        windows.get(id) ?: if (id in journal.state.drafts) FeishuWindow(id, "新任务窗口", "draft") else null
    }
    private fun allocate(): String {
        val id = windows.newId()
        journal.update { it.copy(selectedWindow = id, selectionVersion = it.selectionVersion + 1,
            drafts = (it.drafts + id).toList().takeLast(32).toSet()) }
        return id
    }
    private suspend fun newWindow(id: String) { allocate(); view(id) }
    private fun token(allowed: Set<String>, page: Int): String {
        val nonce = UUID.randomUUID().toString()
        journal.update { state -> state.copy(menus = (state.menus.filterValues { clock() - it.created < 3600_000 } +
            (nonce to FeishuMenu(allowed, state.selectionVersion, clock(), page))).entries.toList().takeLast(8).associate { it.toPair() }) }
        return nonce
    }
    private suspend fun menu(id: String, page: Int = 0) {
        val list = all()
        val current = page.coerceIn(0, (list.size - 1).coerceAtLeast(0) / FeishuCards.PAGE_SIZE)
        val allowed = list.drop(current * FeishuCards.PAGE_SIZE).take(FeishuCards.PAGE_SIZE).map { it.id }.toSet()
        card(id, FeishuCards.menu(list, journal.state.selectedWindow, token(allowed, current), current))
    }
    private suspend fun view(id: String) {
        val window = selected()
        if (window == null) { notice(id, "窗口不存在或已删除，请发送 /任务 重新选择。"); return }
        card(id, FeishuCards.detail(window, token(setOf(window.id), 0)))
    }
    private suspend fun callback(input: FeishuMessage) {
        val session = input.menu?.let { journal.state.menus[it] }
        if (session == null || clock() - session.created > 3600_000 || session.revision != journal.state.selectionVersion) {
            notice(input.id, "此卡片已过期或当前窗口已变化，请发送 /任务 打开新菜单。"); return
        }
        when (input.action) {
            "new" -> newWindow(input.id)
            "list" -> menu(input.id, input.page)
            "select" -> {
                val id = input.window
                if (id == null || id !in session.windows || all().none { it.id == id }) {
                    notice(input.id, "窗口已删除或不在此菜单中，请刷新任务列表。"); return
                }
                journal.update { it.copy(selectedWindow = id, selectionVersion = it.selectionVersion + 1) }
                view(input.id)
            }
            "view", "cancel" -> {
                if (input.window != journal.state.selectedWindow || input.window !in session.windows) {
                    notice(input.id, "卡片目标已变化，未操作任何任务。"); return
                }
                if (input.action == "view") view(input.id) else cancel(input.id, selected(), input.runKey)
            }
        }
    }
    private suspend fun cancel(id: String, window: FeishuWindow?, expected: String?) {
        val accepted = window?.controllable == true && expected != null && window.runKey == expected && windows.cancel(window.id, expected)
        notice(id, if (accepted) "已请求取消【${window!!.title}】的此运行，最终状态以刷新结果为准。" else "该运行已结束、已变化或为只读窗口，未取消其他任务。")
    }
    private suspend fun notice(id: String, text: String) = deliver(id) { chat, uuid -> send(chat, text, uuid) }
    private suspend fun card(id: String, card: JsonObject) = deliver(id) { chat, uuid -> sendCard(chat, card, uuid) }
    private suspend fun deliver(id: String, action: suspend (String, String) -> SendOutcome) {
        val chat = journal.state.chat ?: return
        if (id in journal.state.attempts || clock() < (journal.state.retryAfter[id] ?: 0)) return
        journal.update { it.copy(attempts = (it.attempts + id).toList().takeLast(2000).toSet()) }
        val result = action(chat, UUID.nameUUIDFromBytes(id.toByteArray()).toString())
        if (result == SendOutcome.Failed) journal.update { it.copy(attempts = it.attempts - id,
            retryAfter = (it.retryAfter + (id to clock() + 60_000)).entries.toList().takeLast(2000).associate { entry -> entry.toPair() }) }
        else if (id in journal.state.retryAfter) journal.update { it.copy(retryAfter = it.retryAfter - id) }
    }
    private fun fresh(created: Long) = created in (clock() - 10 * 60_000)..(clock() + 60_000)
    companion object {
        const val HELP = "\n/任务：按标题选择所有 Mika 窗口；/新建：进入新窗口；/status：查看当前窗口。\n普通文字只在选中的空闲窗口下发新任务，运行中不会自动追加。回复：内容、追加：内容、/cancel 只作用于当前窗口的本机器人运行。付款审批和接管仍在手机完成。"
        private fun binds(text: String, code: String): Boolean {
            val input = Regex("^(?:绑定|/pair)\\s*([0-9a-fA-F]{16})$").matchEntire(text)?.groupValues?.get(1)?.lowercase() ?: return false
            return code.isNotBlank() && MessageDigest.isEqual(input.toByteArray(), code.toByteArray())
        }
        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        internal fun eventTime(value: String): Long? = runCatching {
            var time = value.toBigDecimal()
            time = when {
                time >= "100000000000000000".toBigDecimal() -> time.divide("1000000".toBigDecimal())
                time >= "100000000000000".toBigDecimal() -> time.divide("1000".toBigDecimal())
                time < "100000000000".toBigDecimal() -> time.multiply("1000".toBigDecimal())
                else -> time
            }
            time.toLong()
        }.getOrNull()
    }
}
