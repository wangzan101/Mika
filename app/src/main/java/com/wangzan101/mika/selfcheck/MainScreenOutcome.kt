package com.wangzan101.mika.selfcheck

import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckOutcome

/**
 * 「主屏不受打扰」的结论。虚拟屏没有自己的焦点(安卓 14 以下,或系统没给这个标志)时,
 * 按键和输入可能落到主屏,主屏最上面的任务跟着变是已知的系统限制:照实写出变化的节点,
 * 但按部分支持报,建议与虚拟屏那一项相同;有独立焦点还被动到才算失败。
 */
internal object MainScreenOutcome {
    fun of(changed: List<String>, focusShared: Boolean, who: String): CheckOutcome {
        if (changed.isEmpty()) return CheckOutcome.pass("top task unchanged ($who stayed on top)")
        val detail = "top task changed during: ${changed.joinToString()}"
        return if (focusShared) CheckOutcome.partial("$detail (virtual display has no own focus)", Advice.FocusShared)
        else CheckOutcome.fail(detail, Advice.MainScreenChanged)
    }
}
