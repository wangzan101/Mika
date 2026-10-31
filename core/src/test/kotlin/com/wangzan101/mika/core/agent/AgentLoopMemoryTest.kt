package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.memory.finishReply
import com.wangzan101.mika.core.memory.memStore
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopMemoryTest {
    private val manual = MemorySource("manual", atMs = 1)
    private fun Message.text() = plainText()

    private fun store() = memStore().apply {
        add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), manual).getOrThrow()
        add(MemoryKind.fact, "用户的公司在张江", listOf("公司"), manual).getOrThrow()
    }

    @Test fun blockInsertedBeforeTaskAfterContext() {
        val s = store()
        val model = FakeModel(finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("帮我点个外卖", listOf(Turn("查一下天气", "晴", "succeeded")))
        }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith(Conversation.HEADER))
        assertTrue(msgs[1].startsWith("Memories about the user"))
        // 记忆总量装得下就全量注入:与本次任务词面无关的「公司在张江」也在(以前会被 BM25 检索丢掉)
        assertTrue(msgs[1].contains("用户点外卖不要香菜"))
        assertTrue(msgs[1].contains("张江"))
        assertEquals("帮我点个外卖", msgs[2])
    }

    @Test fun unrelatedMemoryStillInjectedInFullMode() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
        runBlocking { loop.run("看看明天天气") }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith("Memories about the user"))
        assertEquals("看看明天天气", msgs[1])
        assertEquals(s.items.value.map { it.id }, loop.usedMemoryIds)
        assertTrue(s.items.value.all { it.useCount == 1 })
    }

    @Test fun usedIdsRecordedAndMarked() {
        val s = store()
        val events = mutableListOf<RunEvent>()
        val loop = AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = s)
        runBlocking { loop.run("帮我点个外卖") }
        val ids = s.items.value.map { it.id }
        assertEquals(ids, loop.usedMemoryIds)
        assertTrue(s.items.value.all { it.useCount == 1 })
        assertEquals(listOf(ids), events.filterIsInstance<RunEvent.MemoryUsed>().map { it.ids })
    }

    // 浏览器页上问:页面说明里的标题、网址、选中的字是网页上的,不算用户说的话;记忆的引文出自它们时不收
    @Test fun pageDescriptionIsNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理", "默认地址 幸福路 8 号")
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户的收货地址是幸福路 8 号","quote":"收货地址是幸福路 8 号"}""")), StopReason.ToolUse, null)
        val model = FakeModel(save, finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
                .run(AttachedPage.compose(page, "这一页的地址对不对"))
        }
        val result = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(result, "quote_not_from_user" in result)
        assertTrue(s.items.value.none { "幸福路" in it.content })
        assertEquals(listOf("这一页的地址对不对"), said.all)
    }

    // 浏览器页上插话带着新选中的字:只收插话里打的字,页面说明不收
    @Test fun pageSteerAddsOnlyTheTypedWords() = runBlocking {
        assertEquals(listOf("这一页的地址对不对", "改成这个"), saidWithPageSteer("改成这个"))
    }

    // 插话只选了字、没打字:这一句什么也不收
    @Test fun selectionOnlySteerAddsNothing() = runBlocking {
        assertEquals(listOf("这一页的地址对不对"), saidWithPageSteer(""))
    }

    /** 在浏览器页上开局,插话带着新选中的字和 [words];返回这次记下的用户的话。 */
    private suspend fun saidWithPageSteer(words: String): List<String> {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", AttachedPage.compose(page.copy(selection = "默认地址 幸福路 8 号"), words))) }
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
            .run(RunSpec(RunId("r1"), AttachedPage.compose(page, "这一页的地址对不对"), "thread-1"), inbox)
        return said.all
    }

    @Test fun disabledMeansNoBlockNoTools() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = null)
        runBlocking { loop.run("帮我点个外卖") }
        assertEquals(listOf("帮我点个外卖"), model.requests.first().withoutRuntime().map { it.text() })
        assertFalse(model.offeredTools.first().any { it.startsWith("memory_") })
        assertTrue(loop.usedMemoryIds.isEmpty())
    }
}

class MemoryBlockCompressionTest {
    @Test fun memoryBlockSurvivesAggressiveCompression() {
        val block = Message(Role.User, listOf(Part.Text("关于用户的记忆(用户告诉过你的;可能过时,和用户这次说的冲突时以这次为准):\n1. 用户点外卖不要香菜")), Author.Mika)
        val history = mutableListOf(block, Message(Role.User, listOf(Part.Text("帮我点个外卖")), Author.Mika))
        repeat(30) { i ->
            history += Message(Role.Assistant, listOf(Part.ToolCall("c$i", "phone_observe", "{}")), Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult("c$i", "{\"ok\":true,\"x\":\"" + "很".repeat(500) + "\"}", false)), Author.Mika)
        }
        val out = ContextCompressor(TOOLS).compress(history, contextTokens = 2_000, aggressive = true)
        assertTrue(out.foldedSteps > 0)
        assertEquals(block, out.messages.first())
    }
}
