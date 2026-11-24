package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.*
import org.junit.Assert.*
import org.junit.Test

class RequestPrefixTest {
    private fun msg(text: String) = Message(Role.User, listOf(Part.Text(text)), Author.User)
    private val tools = listOf(ToolSpec("read", "read a page", "{}"))

    @Test fun finalRequestPrefixStaysStableOnAppendAndResetsForEveryInputChange() {
        val prefix = RequestPrefix()
        val first = prefix.sent("system", tools, listOf(msg("goal")))
        val appended = prefix.sent("system", tools, listOf(msg("goal"), msg("result")))
        assertEquals(first.digest, appended.digest)
        assertEquals(first.epoch, appended.epoch)
        assertFalse(appended.changed)
        val prompt = prefix.sent("transformed system", tools, listOf(msg("goal"), msg("result")))
        assertTrue(prompt.epoch > first.epoch)
        assertNotEquals(prompt.digest, first.digest)
        val transformedMessages = prefix.sent("transformed system", tools, listOf(msg("restored/cropped goal")))
        assertTrue(transformedMessages.epoch > prompt.epoch)
        val changedTools = prefix.sent("transformed system", emptyList(), listOf(msg("restored/cropped goal")))
        assertTrue(changedTools.epoch > transformedMessages.epoch)
    }

    @Test fun semanticInvalidationAdvancesEpochEvenWhenRequestBytesAreIdentical() {
        val p = RequestPrefix()
        var last = p.sent("s", tools, listOf(msg("goal")))
        for (reason in listOf("user_steer", "place_changed", "page_changed", "facts_changed", "model_changed", "context_overflow")) {
            p.invalidate(reason)
            val next = p.sent("s", tools, listOf(msg("goal")))
            assertTrue(next.epoch > last.epoch)
            assertEquals(reason, next.reason)
            last = next
        }
    }
}