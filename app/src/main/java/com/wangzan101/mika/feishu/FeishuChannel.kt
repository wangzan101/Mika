package com.wangzan101.mika.feishu

import com.wangzan101.mika.core.feishu.FeishuApi
import com.wangzan101.mika.core.run.*
import java.util.UUID

/** Native TaskService/Outbox owns acceptance, questions and final results for every window. */
class FeishuChannel(private val runtime: FeishuRuntime) : Channel {
    override val id = ID
    override val confirms = false
    override fun isAlive(peer: ChannelPeer): Boolean = peer.channelId == id && runtime.pairedOwner() == peer.peerId && runtime.config().enabled
    override fun start(sink: ChannelInputSink): AutoCloseable = AutoCloseable {}
    override fun acknowledge(messageId: String) = runtime.acknowledge(messageId)
    override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
        if (!isAlive(peer)) return SendOutcome.Failed
        if (delivery is Delivery.Progress) return SendOutcome.Delivered
        val chat = runtime.pairedChat() ?: return SendOutcome.Failed
        val config = runtime.config()
        val text = when (delivery) {
            is Delivery.Ask -> delivery.text
            is Delivery.Result -> "${delivery.title}\n${delivery.text}"
            is Delivery.Notice -> delivery.text
            is Delivery.Progress -> return SendOutcome.Delivered
        }
        val title = runtime.windowTitle(peer.threadId)
        return FeishuApi(config.appId, config.appSecret).send(chat, "【$title】\n$text",
            UUID.nameUUIDFromBytes("feishu:${delivery.id}".toByteArray()).toString())
    }
    companion object { const val ID = "feishu" }
}
