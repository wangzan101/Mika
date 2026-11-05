package com.wangzan101.mika.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.R
import com.wangzan101.mika.core.usage.AllowanceSource
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.balance.BalanceFailure
import com.wangzan101.mika.settings.balance.BalanceView
import com.wangzan101.mika.settings.balance.depleted
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * 添加服务商和详情页上的余额。本机服务(Ollama、LM Studio)和账号套餐(ChatGPT、xAI)是 [BalanceView.Hidden]，什么都不画。
 * 查到的数字和用量页用同一句 [allowanceText]。
 */
@Composable
fun BalanceSection(
    state: BalanceView,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is BalanceView.Hidden) return
    val context = LocalContext.current
    val warn = MikaTheme.colors.warn
    Column(modifier) {
        when (state) {
            BalanceView.Hidden -> Unit
            BalanceView.Loading -> GroupCard {
                ListRow(null, BalanceCopy.TITLE, BalanceCopy.CHECKING, first = true)
            }
            BalanceView.NeedKey -> GroupCard {
                ListRow(null, BalanceCopy.TITLE, BalanceCopy.NEED_KEY, first = true)
            }
            is BalanceView.Unsupported -> GroupCard {
                ListRow(null, BalanceCopy.TITLE, tr(R.string.usage_allowance_unsupported), first = true)
                DashboardRow(state.dashboard, openRouter = false, context)
            }
            is BalanceView.Failed -> GroupCard {
                ListRow(null, BalanceCopy.TITLE, BalanceCopy.failure(state.failure), first = true, onClick = onRefresh) {
                    RowValue(BalanceCopy.REFRESH, ValueTone.Link)
                }
                DashboardRow(state.dashboard, state.source == AllowanceSource.OpenRouter, context)
            }
            is BalanceView.Ready -> GroupCard {
                ListRow(
                    null, BalanceCopy.TITLE, allowanceText(state.allowance), first = true,
                    titleColor = if (state.allowance.depleted()) warn else Color.Unspecified,
                    onClick = onRefresh,
                ) {
                    RowValue(BalanceCopy.REFRESH, ValueTone.Link)
                }
                DashboardRow(state.dashboard, state.source == AllowanceSource.OpenRouter, context)
            }
        }
    }
}

@Composable
private fun DashboardRow(url: String?, openRouter: Boolean, context: Context) {
    if (url == null) return
    val label = if (openRouter) R.string.usage_allowance_view_balance else R.string.chatgpt_manage_usage
    ListRow(null, tr(label), onClick = { openInBrowser(context, url) }) { RowChevron() }
}

private object BalanceCopy {
    val TITLE: String get() = tr(R.string.balance_title)
    val CHECKING: String get() = tr(R.string.balance_checking)
    val NEED_KEY: String get() = tr(R.string.balance_need_key)
    val REFRESH: String get() = tr(R.string.balance_refresh)

    fun failure(failure: BalanceFailure): String = when (failure) {
        BalanceFailure.Auth -> tr(R.string.balance_auth)
        BalanceFailure.Network -> tr(R.string.balance_network)
        BalanceFailure.Unreadable -> tr(R.string.balance_unreadable)
        is BalanceFailure.Http -> tr(R.string.balance_http, failure.code)
        BalanceFailure.Unavailable -> tr(R.string.usage_allowance_unavailable)
    }
}
