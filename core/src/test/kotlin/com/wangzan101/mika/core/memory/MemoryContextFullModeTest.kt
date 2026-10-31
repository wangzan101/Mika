package com.wangzan101.mika.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆全量注入(产品的默认:装得下就一条不落,装不下才退回检索)。
 */
class MemoryContextFullModeTest {
    @Test fun fullGivesEveryActiveItem() {
        // 与 goal 词面完全无关,以前会被检索丢掉
        val items = (1..12).map { mem("无关事实第${it}条:用户周末去爬山") }
        val block = MemoryContext.build(items, goal = "帮我点个外卖", lastUserTurn = null, mode = MemoryContext.Mode.Full)
        assertEquals(items.map { it.id }.toSet(), block.usedIds.toSet())
        assertEquals(12, block.usedIds.size)
    }

    @Test fun autoGivesEveryItemWhenItFits() {
        val items = (1..30).map { mem("事实第${it}条") }
        val block = MemoryContext.build(items, goal = "随便", lastUserTurn = null, mode = MemoryContext.Mode.Auto)
        assertEquals(30, block.usedIds.size)
    }

    @Test fun autoFallsBackToRelevantWhenTooBig() {
        val filler = "很".repeat(120)
        val items = (1..120).map { mem("第${it}条$filler", keywords = listOf("外卖")) }
        val block = MemoryContext.build(items, goal = "点外卖", lastUserTurn = null, mode = MemoryContext.Mode.Auto)
        assertTrue(block.usedIds.size <= MemoryContext.MAX_PINNED + MemoryContext.MAX_RELEVANT)
        block.message?.let { assertTrue(it.length <= MemoryContext.MAX_CHARS) }
    }

    @Test fun fullDropsOldestFactsBeforePinned() {
        val filler = "很".repeat(120)
        val pinned = mem("置顶的住址在张江$filler", pinned = true)
        val facts = (1..80).map { mem("第${it}条$filler") }
        val block = MemoryContext.build(facts + pinned, goal = "随便", lastUserTurn = null, mode = MemoryContext.Mode.Full)
        assertTrue(pinned.id in block.usedIds)
        assertTrue(block.message!!.length <= MemoryContext.FULL_MAX_CHARS)
        assertTrue(block.usedIds.size < facts.size)
    }

    @Test fun pendingNeverInjected() {
        val pending = mem("还没批准的做法", kind = MemoryKind.procedure, status = MemoryStatus.pending)
        assertNull(MemoryContext.build(listOf(pending), "随便", null, mode = MemoryContext.Mode.Full).message)
    }

    @Test fun fullKeepsProceduresCapped() {
        val procs = (1..6).map { mem("做法$it", kind = MemoryKind.procedure) }
        val block = MemoryContext.build(procs, "随便", null, mode = MemoryContext.Mode.Full)
        assertEquals(MemoryContext.MAX_PROCEDURES, block.usedIds.size)
    }
}
