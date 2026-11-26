package com.wangzan101.mika.cap.phone.shell

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 只读读屏时看到的一个控件;真机上包着 AccessibilityNodeInfo,测试里换成假的。 */
internal interface InspectNode {
    val packageName: CharSequence?
    val className: CharSequence?
    val resourceId: String?
    val text: CharSequence?
    val description: CharSequence?
    val isPassword: Boolean
    /** left, top, right, bottom(屏幕坐标)。 */
    val bounds: IntArray
    val isClickable: Boolean
    val isEditable: Boolean
    val isEnabled: Boolean
    val isVisible: Boolean
    val childCount: Int
    fun child(index: Int): InspectNode?
    /** 用完交还(旧系统上要回收节点)。 */
    fun release()
}

/** 一次读屏的上限:控件总数、树的深度、每段文字的长度;超出就截断并记下 [truncated]。 */
internal class InspectBudget(
    val maxNodes: Int = ScreenInspectTree.MAX_NODES,
    val maxDepth: Int = ScreenInspectTree.MAX_DEPTH,
    val maxText: Int = ScreenInspectTree.MAX_TEXT,
) {
    var nodes = 0
    var truncated = false
}

/**
 * 把控件树写成 JSON。密码框(以及它下面的所有子控件)不写文字和描述,只标 `password: true`;
 * 超出 [InspectBudget] 的部分不读(连子节点都不去取),只把 `truncated` 记为真。
 */
internal object ScreenInspectTree {
    const val MAX_NODES = 2_500
    const val MAX_DEPTH = 60
    const val MAX_TEXT = 1_000

    /** [node] 由这里交还;超出总数上限时返回 null。 */
    fun node(node: InspectNode, budget: InspectBudget, depth: Int = 0, inSecret: Boolean = false): JsonObject? {
        try {
            if (budget.nodes >= budget.maxNodes || depth >= budget.maxDepth) {
                budget.truncated = true
                return null
            }
            budget.nodes++
            val secret = inSecret || node.isPassword
            val children = buildJsonArray {
                val count = node.childCount
                for (i in 0 until count) {
                    if (budget.nodes >= budget.maxNodes || depth + 1 >= budget.maxDepth) {
                        budget.truncated = true
                        break
                    }
                    val child = node.child(i) ?: continue
                    node(child, budget, depth + 1, secret)?.let { add(it) }
                }
            }
            return buildJsonObject {
                put("package", plain(node.packageName, budget))
                put("class", plain(node.className, budget))
                put("resource_id", plain(node.resourceId, budget))
                put("text", if (secret) JsonNull else plain(node.text, budget))
                put("description", if (secret) JsonNull else plain(node.description, budget))
                put("password", secret)
                put("bounds", bounds(node.bounds))
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("enabled", node.isEnabled)
                put("visible", node.isVisible)
                put("children", children)
            }
        } finally {
            node.release()
        }
    }

    fun plain(value: CharSequence?, budget: InspectBudget): JsonPrimitive {
        if (value == null) return JsonNull
        val s = value.toString()
        if (s.length <= budget.maxText) return JsonPrimitive(s)
        budget.truncated = true
        // 不把一个字符的代理对切成两半
        val end = if (s[budget.maxText - 1].isHighSurrogate()) budget.maxText - 1 else budget.maxText
        return JsonPrimitive(s.substring(0, end))
    }

    fun bounds(b: IntArray): JsonArray = buildJsonArray { for (v in b.take(4)) add(JsonPrimitive(v)) }
}
