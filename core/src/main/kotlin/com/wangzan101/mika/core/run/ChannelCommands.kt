package com.wangzan101.mika.core.run

import java.util.Locale

/**
 * 渠道里主人发来的整句指令。只认整句完全一致的几种写法(去掉首尾空白、不分大小写),
 * 句子里带这些字的普通消息照常当新任务,免得「停止播放音乐」这类任务被当成指令。
 * 单独的「取消」不算:它常是在回答 Mika 的选项。
 */
object ChannelCommands {
    private val STOP = setOf("停止", "停止任务", "取消任务", "全部停止", "/停止", "stop", "/stop", "/cancel")

    fun isStop(text: String): Boolean = text.trim().lowercase(Locale.ROOT) in STOP
}
