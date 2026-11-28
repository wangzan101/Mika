package com.wangzan101.mika.device

/**
 * 锁屏凭据页从哪里读:先用主屏的无障碍窗口列表;里面没有 SystemUI 窗口时(部分系统锁屏时整份列表为空,#307),
 * 依次退到旧版的主屏窗口列表、当前活动窗口的根节点。退路只换「从哪读」,仍只认 SystemUI 的内容,
 * 凭据按键照旧只定向投给 SystemUI。
 */
internal object KeyguardRoots {
    enum class Source(val tag: String) { Display("display"), Legacy("legacy"), ActiveRoot("active_root"), None("none") }

    data class Pick<W>(val source: Source, val roots: List<W>)

    fun <W> pick(display: List<W>, legacy: () -> List<W>, activeRoot: () -> W?, systemUi: (W) -> Boolean): Pick<W> {
        display.filter(systemUi).takeIf { it.isNotEmpty() }?.let { return Pick(Source.Display, it) }
        legacy().filter(systemUi).takeIf { it.isNotEmpty() }?.let { return Pick(Source.Legacy, it) }
        activeRoot()?.takeIf(systemUi)?.let { return Pick(Source.ActiveRoot, listOf(it)) }
        return Pick(Source.None, emptyList())
    }
}
