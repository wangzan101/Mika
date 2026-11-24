package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec

/** Fingerprints the actual prompt/tool/message prefix after every request transformation. */
internal class RequestPrefix {
    data class Stamp(val digest: String, val epoch: Long, val reason: String, val messages: Int, val changed: Boolean)
    private var prefix: List<Message>? = null
    private var header: List<Message>? = null
    private var digest = ""
    private var epoch = 0L
    private var invalidated: String? = null

    fun invalidate(reason: String) { invalidated = reason }

    fun sent(system: String, tools: List<ToolSpec>, messages: List<Message>): Stamp {
        val currentHeader = listOf(Message(Role.System, listOf(Part.Text(system)), Author.Mika)) + tools.map {
            Message(Role.System, listOf(Part.Text(it.name), Part.Text(it.description), Part.Text(it.parametersJsonSchema)), Author.Mika)
        }
        val old = prefix
        val same = invalidated == null && old != null && header == currentHeader &&
            old.size <= messages.size && messages.take(old.size) == old
        val reason = if (same) "append" else invalidated ?: if (old == null) "initial" else "request_prefix_changed"
        if (!same) {
            epoch++
            header = currentHeader
            prefix = messages.toList()
            digest = PrefixDigest.of(currentHeader + messages)
        }
        invalidated = null
        return Stamp(digest, epoch, reason, prefix!!.size, !same)
    }
}