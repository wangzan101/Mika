package com.wangzan101.mika.settings.balance

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 一把 API Key 查到的余额(或编程套餐剩余额度)。金额用 [currency](CNY、USD);
 * 套餐窗口没有币种,[kind] 是 5 小时或每周,数字是剩余百分比。
 */
data class BalanceLine(
    val kind: BalanceLineKind,
    val amount: Double,
    val currency: String? = null,
) {
    val low: Boolean get() = amount <= 0.0
}

enum class BalanceLineKind { Total, FiveHour, Weekly }

/** 查余额时确定能说清的失败。不带服务端原文,免得把 Key 或整段网页露到界面上。 */
sealed interface BalanceFailure {
    data object Auth : BalanceFailure
    data object Network : BalanceFailure
    data object Unreadable : BalanceFailure
    data class Http(val code: Int) : BalanceFailure
    /** 服务端自己的短说明(已截断)。 */
    data class Message(val text: String) : BalanceFailure
    /** 智谱 Coding Plan 明确说这把 Key 没有套餐,又没有查到按量余额。 */
    data object NoCodingPlan : BalanceFailure
}

/** 一次查询的结果。[experimental] 为真时界面标明这份查询没有公开文档(智谱按量余额)。 */
sealed interface BalanceOutcome {
    data class Ready(val lines: List<BalanceLine>, val experimental: Boolean) : BalanceOutcome
    data class Failed(val failure: BalanceFailure, val experimental: Boolean) : BalanceOutcome
}

/** 界面上的一种状态。添加页和详情页共用。 */
sealed interface BalanceView {
    data object Hidden : BalanceView
    data class Loading(val experimental: Boolean) : BalanceView
    data class NeedKey(val experimental: Boolean) : BalanceView
    data class Unsupported(val note: BalanceNote, val consoleUrl: String?) : BalanceView
    data class Ready(val lines: List<BalanceLine>, val experimental: Boolean) : BalanceView
    data class Failed(val failure: BalanceFailure, val experimental: Boolean) : BalanceView
}

/** 没有查询方法时的说明。硅基流动单独一句:它的余额接口已经停用,不能再调。 */
enum class BalanceNote { Generic, SiliconFlowRetired }

/** 和表单对得上的一次结果。对不上(换了预设或 Key)就丢,不把上一家的余额留在这一家。 */
data class BalanceSnapshot(val token: String, val view: BalanceView)

internal fun BalanceOutcome.toView(): BalanceView = when (this) {
    is BalanceOutcome.Ready -> BalanceView.Ready(lines, experimental)
    is BalanceOutcome.Failed -> BalanceView.Failed(failure, experimental)
}

/** 金额最多四位小数,去掉末尾的 0。套餐百分比最多一位。 */
internal fun formatDecimal(amount: Double): String {
    if (!amount.isFinite()) return ""
    return BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

internal fun formatPercent(amount: Double): String {
    if (!amount.isFinite()) return ""
    return BigDecimal.valueOf(amount).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
