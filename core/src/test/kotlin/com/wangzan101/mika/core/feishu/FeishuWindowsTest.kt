package com.wangzan101.mika.core.feishu

import com.wangzan101.mika.core.run.SendOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FeishuWindowsTest {
    private var now = 1_800_000_000_000L
    private var saved: String? = null
    private val journal = FeishuJournal({ saved }, { saved = it })
    private val texts = mutableListOf<String>()
    private val cards = mutableListOf<JsonObject>()
    private val host = Host()
    private var outcome = SendOutcome.Delivered
    private val bot = FeishuBot("cli_test", journal, host,
        { _, text, _ -> texts += text; outcome }, { _, card, _ -> cards += card; outcome }, { now })
    private var nextMessage = 0

    private inner class Host : FeishuWindowHost {
        val entries = linkedMapOf<String, FeishuWindow>()
        val submitted = mutableListOf<FeishuSubmission>()
        val cancelled = mutableListOf<Pair<String, String>>()
        val steered = mutableListOf<Pair<String, String>>()
        val answered = mutableListOf<Triple<String, String, String>>()
        private val registered = mutableSetOf<String>()
        private var next = 0
        override fun list() = entries.values.toList()
        override fun get(id: String) = entries[id]
        override fun newId() = "window-${++next}"
        override fun submit(window: String, messageId: String, text: String) {
            if (registered.add(messageId)) {
                submitted += FeishuSubmission(window, messageId, text)
                entries[window] = FeishuWindow(window, text, "running", "working", "run-$messageId", controllable = true, active = true)
            }
            bot.acknowledge(messageId)
        }
        override suspend fun answer(window: String, runKey: String, askId: String, text: String): Boolean {
            if (entries[window]?.let { it.controllable && it.runKey == runKey && it.askId == askId } != true) return false
            answered += Triple(window, askId, text); return true
        }
        override fun steer(window: String, runKey: String, messageId: String, text: String): Boolean {
            if (entries[window]?.let { it.controllable && it.runKey == runKey && it.status == "running" } != true) return false
            steered += window to text; return true
        }
        override fun cancel(window: String, runKey: String): Boolean {
            if (entries[window]?.let { it.controllable && it.runKey == runKey && it.active } != true) return false
            cancelled += window to runKey; return true
        }
    }

    private fun event(text: String, id: String = "m-${++nextMessage}", user: String = "owner", chat: String = "chat", type: String = "p2p", created: Long = now): JsonObject = buildJsonObject {
        putJsonObject("header") { put("app_id", "cli_test"); put("event_type", "im.message.receive_v1") }
        putJsonObject("event") {
            putJsonObject("sender") { put("sender_type", "user"); putJsonObject("sender_id") { put("open_id", user) } }
            putJsonObject("message") {
                put("message_id", id); put("chat_id", chat); put("chat_type", type); put("message_type", "text")
                put("create_time", created.toString()); put("content", buildJsonObject { put("text", text) }.toString())
            }
        }
    }
    private fun callback(op: String, token: String, target: String? = null, run: String? = null, id: String = "c-${++nextMessage}", user: String = "owner", chat: String = "chat", page: Int = 0): JsonObject = buildJsonObject {
        putJsonObject("header") { put("app_id", "cli_test"); put("event_type", "card.action.trigger"); put("event_id", id); put("create_time", now.toString()) }
        putJsonObject("event") {
            putJsonObject("operator") { put("open_id", user) }
            putJsonObject("context") { put("open_chat_id", chat); put("open_message_id", "card") }
            putJsonObject("action") {
                put("tag", if (op == "select") "select_static" else "button")
                if (op == "select") put("option", target)
                putJsonObject("value") { put("op", op); put("menu", token); put("page", page); target?.let { put("window", it) }; run?.let { put("run", it) } }
            }
        }
    }
    private suspend fun say(text: String) { bot.enqueue(event(text)); bot.tick() }
    private suspend fun pair() { say("/pair ${journal.state.pairingCode}"); texts.clear(); cards.clear() }
    private fun token() = journal.state.menus.keys.last()
    private suspend fun select(window: String) { say("/任务"); bot.enqueue(callback("select", token(), window)); bot.tick() }

    @Test fun oneOwnerPrivateChatAndDurableInbox() = runTest {
        bot.enqueue(event("work")); bot.tick(); assertTrue(host.submitted.isEmpty())
        bot.enqueue(event("/pair ${journal.state.pairingCode}", type = "group")); bot.tick(); assertNull(journal.state.owner)
        pair(); bot.enqueue(event("work", user = "stranger")); bot.tick(); assertTrue(host.submitted.isEmpty())
        val message = event("first", id = "same")
        bot.enqueue(message); bot.enqueue(message)
        assertEquals(1, FeishuJournal({ saved }, {}).state.inbox.size)
        bot.tick(); bot.enqueue(message); bot.tick()
        assertEquals(1, host.submitted.size)
    }

    @Test fun menuContainsPhoneTitlesAndNativeDropdown() = runTest {
        pair()
        host.entries["local"] = FeishuWindow("local", "手机上的天气任务", "running", active = true)
        host.entries["old"] = FeishuWindow("old", "读书总结", "done")
        say("/任务")
        val card = cards.last().toString()
        assertTrue(card.contains("select_static")); assertTrue(card.contains("手机上的天气任务"))
        assertTrue(card.contains("读书总结")); assertTrue(card.contains("新建任务"))
        assertTrue(host.submitted.isEmpty()); assertTrue(host.cancelled.isEmpty())
    }

    @Test fun newWindowDoesNotChangeRunningTaskAndAllowsSecondRun() = runTest {
        pair(); say("first task")
        val first = journal.state.selectedWindow!!
        val run = host.entries[first]
        say("/新建")
        val second = journal.state.selectedWindow!!
        assertNotEquals(first, second); assertEquals(run, host.entries[first])
        say("second task")
        assertEquals(listOf(first, second), host.submitted.map { it.window })
        assertTrue(host.cancelled.isEmpty()); assertTrue(host.steered.isEmpty())
    }

    @Test fun plainTextInBusyWindowDoesNotSteerOrCreateAnotherRun() = runTest {
        pair(); say("first task"); say("another requirement")
        assertEquals(1, host.submitted.size); assertTrue(host.steered.isEmpty())
        assertTrue(texts.last().contains("不会追加"))
    }

    @Test fun selectingExistingWindowIsReadOnlyAndUpdatesOnlyInputDestination() = runTest {
        pair(); say("first"); val first = journal.state.selectedWindow!!
        say("/new"); say("second"); val second = journal.state.selectedWindow!!
        select(first)
        assertEquals(first, journal.state.selectedWindow); assertEquals(2, host.submitted.size)
        say("追加：only first")
        assertEquals(listOf(first to "only first"), host.steered)
        assertEquals("second", host.entries[second]?.title)
    }

    @Test fun callbackIsDurableDeduplicatedAndCannotBeUsedByStranger() = runTest {
        pair(); say("/tasks"); val nonce = token()
        bot.enqueue(callback("new", nonce, user = "stranger")); bot.tick(); assertNull(journal.state.selectedWindow)
        val click = callback("new", nonce, id = "one-click")
        bot.enqueue(click); bot.enqueue(click); bot.tick()
        val selected = journal.state.selectedWindow
        bot.enqueue(click); bot.tick()
        assertEquals(selected, journal.state.selectedWindow); assertEquals(1, journal.state.drafts.size)
    }

    @Test fun staleCancelDoesNotCancelANewerRunInSameWindow() = runTest {
        pair(); say("first"); val window = journal.state.selectedWindow!!
        say("/status"); val nonce = token(); val original = host.entries.getValue(window)
        host.entries[window] = original.copy(runKey = "newer-run")
        bot.enqueue(callback("cancel", nonce, window, original.runKey)); bot.tick()
        assertTrue(host.cancelled.isEmpty())
    }

    @Test fun staleCardAfterWindowSwitchDoesNotAffectOtherWindow() = runTest {
        pair(); say("first"); val first = journal.state.selectedWindow!!; say("/status"); val old = token()
        say("/new"); say("second")
        bot.enqueue(callback("cancel", old, first, host.entries[first]?.runKey)); bot.tick()
        assertTrue(host.cancelled.isEmpty()); assertTrue(texts.last().contains("过期"))
    }

    @Test fun forgedWindowAndDeletedWindowAreNotSelected() = runTest {
        pair(); host.entries["real"] = FeishuWindow("real", "real", "done"); say("/tasks"); val nonce = token()
        bot.enqueue(callback("select", nonce, "../../private")); bot.tick(); assertNull(journal.state.selectedWindow)
        host.entries.clear(); bot.enqueue(callback("select", nonce, "real")); bot.tick(); assertNull(journal.state.selectedWindow)
    }

    @Test fun localRunningWindowCanBeViewedButNotRemotelyMutated() = runTest {
        pair(); host.entries["local"] = FeishuWindow("local", "手机本地运行", "running", "local detail", "local-run", active = true)
        select("local"); assertTrue(cards.last().toString().contains("local detail"))
        say("追加：change local"); say("取消"); say("ordinary text")
        assertTrue(host.submitted.isEmpty()); assertTrue(host.steered.isEmpty()); assertTrue(host.cancelled.isEmpty())
    }

    @Test fun completedWindowCanAcceptANewTurnInSameConversation() = runTest {
        pair(); say("first"); val id = journal.state.selectedWindow!!
        host.entries[id] = host.entries.getValue(id).copy(status = "done", active = false)
        say("follow-up")
        assertEquals(listOf(id, id), host.submitted.map { it.window })
    }

    @Test fun registeredWindowIsNotResurrectedAsDraftAfterPhoneDeletion() = runTest {
        pair(); say("first"); val id = journal.state.selectedWindow!!
        assertFalse(id in journal.state.drafts)
        host.entries.remove(id)
        say("/tasks")
        assertTrue(journal.state.menus.getValue(token()).windows.isEmpty())
        say("do not recreate deleted conversation")
        assertEquals(1, host.submitted.size)
    }

    @Test fun selectedWindowAndAllBindingsSurviveRestart() = runTest {
        pair(); say("first"); say("/new"); say("second")
        val restored = FeishuJournal({ saved }, {})
        assertEquals(journal.state.selectedWindow, restored.state.selectedWindow)
        assertEquals("owner", restored.state.owner); assertEquals("chat", restored.state.chat)
    }

    @Test fun oldBindingAndConversationCanBeRead() {
        val old = """{"pairingCode":"","owner":"owner","chat":"chat","conversation":"mcp-old","taskId":"legacy","attempts":[],"localResults":{}}"""
        assertEquals("mcp-old", FeishuJournal({ old }, {}).state.selectedWindow)
    }

    @Test fun menusArePaginatedWithoutDroppingWindows() = runTest {
        pair(); repeat(45) { host.entries["w$it"] = FeishuWindow("w$it", "title-$it", "done") }
        say("/tasks"); val first = journal.state.menus.getValue(token())
        assertEquals(20, first.windows.size)
        bot.enqueue(callback("list", token(), page = 2)); bot.tick()
        assertEquals(setOf("w40", "w41", "w42", "w43", "w44"), journal.state.menus.getValue(token()).windows)
    }

    @Test fun replyUsesExactWindowAndQuestionAndNeverApprovesPayment() = runTest {
        pair(); say("first"); val id = journal.state.selectedWindow!!
        host.entries[id] = host.entries.getValue(id).copy(status = "waiting_for_answer", askId = "ask-1")
        say("回复：second")
        assertEquals(listOf(Triple(id, "ask-1", "second")), host.answered)
        host.entries[id] = host.entries.getValue(id).copy(status = "waiting_on_phone", askId = null)
        say("回复：approve")
        assertEquals(1, host.answered.size)
    }

    @Test fun cardCallbackTimestampsSupportDocumentedTimeUnits() {
        assertEquals(now, FeishuBot.eventTime("1800000000000"))
        assertEquals(now, FeishuBot.eventTime("1800000000000000"))
        assertEquals(now, FeishuBot.eventTime("1800000000.000"))
    }

    @Test fun expiredMessageAndClaimedInboxAreNeverExecutedAfterRestart() = runTest {
        pair()
        journal.update { it.copy(inbox = listOf(FeishuMessage("old", "owner", "chat", "work", now - 900_000))) }
        bot.tick(); assertTrue(host.submitted.isEmpty())
        val m = event("work", id = "claimed"); bot.enqueue(m)
        journal.update { it.copy(seen = it.seen + ("claimed" to now)) }
        bot.tick(); assertTrue(host.submitted.isEmpty())
    }

    @Test fun mirrorNoticesRemainIndependentOfSelectedWindowAndAmbiguousSendsAreNotRetried() = runTest {
        pair(); say("/new"); val selected = journal.state.selectedWindow
        outcome = SendOutcome.Uncertain
        bot.mirrorResult("scheduled:1", "result"); repeat(3) { bot.tick() }
        assertEquals(listOf("result"), texts); assertEquals(selected, journal.state.selectedWindow)
    }

    @Test fun failedCardDoesNotChangeWindowAndBindingOwnerCannotCrossChat() = runTest {
        pair(); outcome = SendOutcome.Failed; say("/tasks")
        assertNull(journal.state.selectedWindow)
        bot.enqueue(callback("new", token(), chat = "other-chat")); bot.tick()
        assertNull(journal.state.selectedWindow)
    }

    @Test fun diskFailureDoesNotAcknowledgeInboundMessage() {
        val disk = FeishuJournal({ null }, { error("disk full") })
        val b = FeishuBot("cli_test", disk, host, { _, _, _ -> SendOutcome.Delivered }, { _, _, _ -> SendOutcome.Delivered }, { now })
        assertThrows(IllegalStateException::class.java) { b.enqueue(event("/pair ${disk.state.pairingCode}")) }
        assertTrue(disk.state.inbox.isEmpty())
    }
}
