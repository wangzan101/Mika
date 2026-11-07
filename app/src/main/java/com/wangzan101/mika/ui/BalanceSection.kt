package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.balance.BalanceFailure
import com.wangzan101.mika.settings.balance.BalanceLine
import com.wangzan101.mika.settings.balance.BalanceLineKind
import com.wangzan101.mika.settings.balance.BalanceNote
import com.wangzan101.mika.settings.balance.BalanceView
import com.wangzan101.mika.settings.balance.formatDecimal
import com.wangzan101.mika.settings.balance.formatPercent
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * 添加服务商和详情页上的余额。本机服务(Ollama、LM Studio)和账号套餐(ChatGPT、xAI)是 [BalanceView.Hidden],什么都不画。
 */
@Composable
fun BalanceSection(
    state: BalanceView,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is BalanceView.Hidden) return
    val c = MikaTheme.colors
    val context = LocalContext.current
    Column(modifier) {
        when (state) {
            BalanceView.Hidden -> Unit
            is BalanceView.Loading -> GroupCard {
                ListRow(null, BalanceCopy.TITLE, BalanceCopy.CHECKING, first = true)
            }
            is BalanceView.NeedKey -> GroupCard {
                ListRow(null, BalanceCopy.TITLE, BalanceCopy.NEED_KEY, first = true)
            }
            is BalanceView.Unsupported -> GroupCard {
                ListRow(
                    null, BalanceCopy.TITLE, BalanceCopy.note(state.note), first = true,
                    onClick = state.consoleUrl?.let { url -> { openInBrowser(context, url) } },
                ) {
                    if (state.consoleUrl != null) RowValue(BalanceCopy.CONSOLE, ValueTone.Link)
                }
            }
            is BalanceView.Failed -> GroupCard {
                ListRow(null, BalanceCopy.TITLE, BalanceCopy.failure(state.failure), first = true, onClick = onRefresh) {
                    RowValue(BalanceCopy.REFRESH, ValueTone.Link)
                }
            }
            is BalanceView.Ready -> {
                val singleTotal = state.lines.singleOrNull()?.kind == BalanceLineKind.Total
                if (!singleTotal) GroupLabel(BalanceCopy.TITLE)
                GroupCard {
                    state.lines.forEachIndexed { i, line ->
                        ListRow(null, BalanceCopy.rowTitle(line, state.lines.size == 1), first = i == 0, onClick = onRefresh) {
                            RowValue(BalanceCopy.amount(line), if (line.low) ValueTone.Warn else ValueTone.Ok)
                            if (i == 0) RowValue(BalanceCopy.REFRESH, ValueTone.Link)
                        }
                    }
                }
            }
        }
        val experimental = when (state) {
            is BalanceView.Loading -> state.experimental
            is BalanceView.NeedKey -> state.experimental
            is BalanceView.Ready -> state.experimental
            is BalanceView.Failed -> state.experimental
            else -> false
        }
        if (experimental) FooterNote(BalanceCopy.EXPERIMENTAL, color = c.warn)
    }
}

object BalanceCopy {
    val TITLE: String get() = tr(R.string.balance_title)
    val CHECKING: String get() = tr(R.string.balance_checking)
    val NEED_KEY: String get() = tr(R.string.balance_need_key)
    val CONSOLE: String get() = tr(R.string.balance_console)
    val REFRESH: String get() = tr(R.string.balance_refresh)
    val EXPERIMENTAL: String get() = tr(R.string.balance_experimental)

    fun note(note: BalanceNote): String = tr(
        when (note) {
            BalanceNote.Generic -> R.string.balance_unsupported
            BalanceNote.SiliconFlowRetired -> R.string.balance_siliconflow
        },
    )

    fun failure(failure: BalanceFailure): String = when (failure) {
        BalanceFailure.Auth -> tr(R.string.balance_auth)
        BalanceFailure.Network -> tr(R.string.balance_network)
        BalanceFailure.Unreadable -> tr(R.string.balance_unreadable)
        is BalanceFailure.Http -> tr(R.string.balance_http, failure.code)
        is BalanceFailure.Message -> failure.text
        BalanceFailure.NoCodingPlan -> tr(R.string.balance_no_plan)
    }

    fun rowTitle(line: BalanceLine, only: Boolean): String = when (line.kind) {
        BalanceLineKind.Total -> if (only) TITLE else line.currency.orEmpty()
        BalanceLineKind.FiveHour -> tr(R.string.balance_window_5h)
        BalanceLineKind.Weekly -> tr(R.string.balance_window_week)
    }

    fun amount(line: BalanceLine): String = if (line.currency == null) {
        tr(R.string.balance_percent_left, formatPercent(line.amount))
    } else {
        tr(R.string.balance_money, formatDecimal(line.amount), line.currency)
    }
}
