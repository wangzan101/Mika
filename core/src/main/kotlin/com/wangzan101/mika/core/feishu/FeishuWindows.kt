package com.wangzan101.mika.core.feishu

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

/** A real Mika conversation window, not a second scheduler or a single mutable task pointer. */
data class FeishuWindow(
    val id: String,
    val title: String,
    val status: String,
    val detail: String = "",
    val runKey: String? = null,
    val askId: String? = null,
    val controllable: Boolean = false,
    val active: Boolean = false,
)

interface FeishuWindowHost {
    fun list(): List<FeishuWindow>
    fun get(id: String): FeishuWindow?
    fun newId(): String = UUID.randomUUID().toString()
    /** Host must persistently deduplicate messageId through the native channel task registry. */
    fun submit(window: String, messageId: String, text: String)
    suspend fun answer(window: String, runKey: String, askId: String, text: String): Boolean
    fun steer(window: String, runKey: String, messageId: String, text: String): Boolean
    fun cancel(window: String, runKey: String): Boolean
}

@Serializable data class FeishuMenu(val windows: Set<String>, val revision: Long, val created: Long, val page: Int)
@Serializable data class FeishuSubmission(val window: String, val messageId: String, val text: String)

/** Card 1.0 static selector opens Feishu's native option picker on mobile and desktop clients. */
internal object FeishuCards {
    const val PAGE_SIZE = 20
    fun menu(windows: List<FeishuWindow>, selected: String?, nonce: String, page: Int): JsonObject {
        val pages = ((windows.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        return card("Mika · 任务窗口", buildJsonArray {
            add(div("共 ${windows.size} 个窗口 · 第 ${current + 1}/$pages 页\n" +
                (windows.firstOrNull { it.id == selected }?.let { "当前：${it.title}" } ?: "请选择窗口，或新建任务。")))
            val visible = windows.drop(current * PAGE_SIZE).take(PAGE_SIZE)
            if (visible.isNotEmpty()) add(action(buildJsonArray {
                add(buildJsonObject {
                    put("tag", "select_static"); put("placeholder", plain("选择任务窗口"))
                    put("value", value("select", nonce))
                    putJsonArray("options") {
                        visible.forEach { window -> add(buildJsonObject {
                            put("text", plain("${window.title.take(60)} · ${label(window.status)} · ${window.id.takeLast(6)}"))
                            put("value", window.id)
                        }) }
                    }
                })
            }))
            add(action(buildJsonArray {
                add(button("＋ 新建任务", "new", nonce, primary = true))
                add(button("刷新列表", "list", nonce, page = current))
                if (current > 0) add(button("上一页", "list", nonce, page = current - 1))
                if (current + 1 < pages) add(button("下一页", "list", nonce, page = current + 1))
            }))
            add(div("选择窗口只改变飞书查看和输入的目标，不会更改或取消其他窗口的任务。"))
        })
    }

    fun detail(window: FeishuWindow, nonce: String): JsonObject = card("Mika · ${window.title.take(80)}", buildJsonArray {
        add(div("${label(window.status)}\n${window.detail.take(6000)}"))
        if (window.active && !window.controllable) add(div("这是手机本地或其他渠道的运行中任务，可查看；远程追加、回答和取消只用于本机器人创建的运行。"))
        if (window.status == "draft") add(div("下一条普通文字会在此新窗口创建任务，其他窗口继续运行。"))
        add(action(buildJsonArray {
            add(button("查看当前进度", "view", nonce, window.id))
            add(button("所有任务", "list", nonce))
            add(button("＋ 新建任务", "new", nonce, primary = true))
            if (window.active && window.controllable && window.runKey != null)
                add(button("取消此运行", "cancel", nonce, window.id, window.runKey))
        }))
        add(div("当前输入目标：${window.title.take(100)} · ${window.id.takeLast(6)}。收到窗口详情后再发送要求。执行中普通文字不会自动追加；请明确使用“追加：内容”。"))
    })

    private fun label(status: String) = when (status) {
        "running" -> "执行中"; "queued" -> "排队中"; "starting" -> "启动中"; "waiting_for_answer" -> "等待回答"
        "waiting_on_phone" -> "需要手机处理"; "paused" -> "暂停"; "done", "succeeded", "unverified" -> "已完成"
        "failed" -> "失败"; "cancelled" -> "已取消"; "draft" -> "新任务"; else -> "空闲"
    }
    private fun card(title: String, elements: JsonArray) = buildJsonObject {
        putJsonObject("config") { put("wide_screen_mode", true) }
        putJsonObject("header") { put("template", "blue"); put("title", plain(title)) }
        put("elements", elements)
    }
    private fun plain(text: String) = buildJsonObject { put("tag", "plain_text"); put("content", text) }
    private fun div(text: String) = buildJsonObject { put("tag", "div"); put("text", plain(text)) }
    private fun action(actions: JsonArray) = buildJsonObject { put("tag", "action"); put("actions", actions) }
    private fun value(op: String, nonce: String, window: String? = null, run: String? = null, page: Int = 0) = buildJsonObject {
        put("op", op); put("menu", nonce); put("page", page)
        window?.let { put("window", it) }; run?.let { put("run", it) }
    }
    private fun button(text: String, op: String, nonce: String, window: String? = null, run: String? = null, page: Int = 0, primary: Boolean = false) = buildJsonObject {
        put("tag", "button"); put("text", plain(text)); put("type", if (primary) "primary" else "default")
        put("value", value(op, nonce, window, run, page))
    }
}
