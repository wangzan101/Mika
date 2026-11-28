package com.wangzan101.mika.task

import android.widget.Toast
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.MikaPause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

// 任务服务:用户暂停了 Mika 时挡住新任务、停下手上的任务

/**
 * 用户暂停了 Mika([MikaPause]):不开始任何新任务,也不从排队里取。
 * 各入口在发请求前已经查过一次;这里是服务门口的第二道,挡住查过之后、到服务之前用户才暂停的那些。
 */
internal fun TaskService.pausedBlocks(): Boolean = MikaPause.get(this).paused()

/**
 * 暂停时被挡在门口的开始请求:把它的来源一一了结,不留到恢复后自动跑。
 * 定时的这一次记成「因暂停跳过」;电脑交来的报失败;工作流这一步记失败(停在待确认,恢复后由用户决定);
 * 排队项从持久队列里拿掉;渠道消息先确认收到,再尽力回一句「已暂停」;界面上发起的提示一声。
 */
internal fun TaskService.finishPausedStart(
    provenance: RunProvenance,
    triggerKey: String?,
    mcpExternalId: String?,
    workflowAttemptId: String?,
    channelPeer: ChannelPeer?,
    channelMessageId: String?,
    followUp: QueuedFollowUp?,
) {
    val app = application as MikaApp
    triggerKey?.let { TriggerRuntime.skippedPaused(this, it) }
    mcpExternalId?.let {
        app.remoteTasks.ended(it, TaskState.Failed(com.wangzan101.mika.core.run.PauseFlag.REMOTE_MESSAGE, FailKind.Environment), emptyList())
    }
    workflowAttemptId?.let(app::workflowPaused)
    followUp?.let { center.followUps.remove(it.id) }
    val peer = channelPeer ?: followUp?.channelPeer
    val messageId = channelMessageId ?: followUp?.channelMessageId
    if (peer != null && messageId != null) {
        val channel = runCatching { channelFor(peer) }.getOrNull()
        if (channel != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.acknowledge(messageId)
            replyPaused(outbox, channel, peer, messageId)
        }
    }
    if (provenance.origin == RunOrigin.Ui && followUp == null) {
        Toast.makeText(this, tr(R.string.pause_blocked_task), Toast.LENGTH_LONG).show()
    }
    // 前台启动的约定要兑现;没有任务在前台时马上撤下,不让「准备中」通知闪一下又消失
    if (foreground != null || taskState._busy.value) keepForeground()
    else {
        startInForeground(null, preparingNotification())
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    }
}

/**
 * 用户刚暂停:停下所有在跑和排着的任务,持久排队里的下一件也一并拿掉(不留暗队列,恢复后不会自己跑起来)。
 * 排着的直接记为已取消;在跑的走正常取消,收尾时各自了结定时、电脑、工作流和渠道。
 */
internal fun TaskService.stopForPause() {
    center.followUps.clear()
    for (run in center.runs()) {
        if (center.cancelIfQueued(run)) cancelQueued(run) else run.cancel()
    }
}

/**
 * 渠道主人发了停止指令(#861):停下这个渠道对话里在跑和排着的任务。只看同一渠道、同一对话的任务,
 * 手机上、电脑交来的和别的对话的任务都不碰;等人到手机上处理的任务也照常走取消收尾。
 */
internal fun TaskService.stopChannelThread(channelId: String, threadId: String) {
    for (run in center.runs()) {
        val peer = run.channelPeer ?: continue
        if (peer.channelId != channelId || peer.threadId != threadId) continue
        if (center.cancelIfQueued(run)) cancelQueued(run) else run.cancel()
    }
    // 卡住的任务后面排着的同一对话消息也撤掉,不然停下后它们接着跑起来
    for (item in center.followUps.pendingFor(threadId)) {
        if (item.channelPeer?.channelId == channelId) center.followUps.cancel(item.id)
    }
}

internal fun ChannelPeer?.sameThread(peer: ChannelPeer) =
    this != null && channelId == peer.channelId && threadId == peer.threadId

/** 停止指令的回复:停下了几个就说停下了,一个都没有就照实说。经可靠送达账本发,同一条指令只回一次,发不出去就算了。 */
internal suspend fun replyStopped(outbox: Outbox, channel: Channel, peer: ChannelPeer, messageId: String, stopped: Int) {
    val text = if (stopped > 0) tr(R.string.channel_stop_reply, stopped) else tr(R.string.channel_stop_none)
    try {
        outbox.deliver(channel, peer, Delivery.Notice("stopped:${peer.channelId}:$messageId", peer.threadId, text))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

/**
 * 暂停期间渠道来的新消息(调用方已确认收到):尽力回一句「Mika 已暂停,这条没有开始办」。经可靠送达账本发,
 * 同一条消息只回一次;发不出去就算了,不让它挡住确认、也不让渠道重交。
 */
internal suspend fun replyPaused(outbox: Outbox, channel: Channel, peer: ChannelPeer, messageId: String) {
    try {
        outbox.deliver(channel, peer, Delivery.Notice("paused:${peer.channelId}:$messageId", peer.threadId, tr(R.string.pause_channel_reply)))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}
