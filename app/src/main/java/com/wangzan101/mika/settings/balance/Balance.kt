package com.wangzan101.mika.settings.balance

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.usage.AllowanceSource
import com.wangzan101.mika.core.usage.ProviderAllowance
import com.wangzan101.mika.settings.AllowanceRoute
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets

/**
 * 添加页和详情页上的余额状态。查不查、打开哪一家的控制台，跟 Token 用量页用同一套 [AllowanceRoute]；
 * 请求本身由 [com.wangzan101.mika.core.usage.ProviderAllowanceClient] 发出，这里不再各写一套。
 */
sealed interface BalanceFailure {
    data object Auth : BalanceFailure
    data object Network : BalanceFailure
    data object Unreadable : BalanceFailure
    data class Http(val code: Int) : BalanceFailure
    /** 用量页同样归成「暂不可查」的失败，例如请求太频繁。 */
    data object Unavailable : BalanceFailure
}

sealed interface BalanceView {
    data object Hidden : BalanceView
    data object Loading : BalanceView
    data object NeedKey : BalanceView
    data class Unsupported(val dashboard: String?) : BalanceView
    data class Ready(
        val allowance: ProviderAllowance,
        val source: AllowanceSource,
        val dashboard: String?,
    ) : BalanceView
    data class Failed(
        val failure: BalanceFailure,
        val source: AllowanceSource,
        val dashboard: String?,
    ) : BalanceView
}

/** 和表单对得上的一次结果。对不上(换了预设、地址或 Key)就丢，不把上一家的余额留在这一家。 */
data class BalanceSnapshot(val token: String, val view: BalanceView)

internal fun Throwable.toBalanceFailure(): BalanceFailure = when (this) {
    is ModelError.Auth -> BalanceFailure.Auth
    is ModelError.Network -> BalanceFailure.Network
    is ModelError.BadResponse -> BalanceFailure.Unreadable
    is ModelError.Server -> BalanceFailure.Http(status)
    else -> BalanceFailure.Unavailable
}

/** 每一项都不大于 0 才算用完。DeepSeek 会同时列出空钱包，有一个还有余额就不标成用完。 */
internal fun ProviderAllowance.depleted(): Boolean = when (this) {
    is ProviderAllowance.Balance -> amounts.isNotEmpty() && amounts.all { it.amount.signum() <= 0 }
    is ProviderAllowance.KeyLimit -> remaining?.amount?.let { it.signum() <= 0 } == true
}

internal sealed interface BalanceTarget {
    data object Hidden : BalanceTarget
    data class Routed(val route: AllowanceRoute) : BalanceTarget
}

internal val BalanceTarget.source: AllowanceSource?
    get() = (this as? BalanceTarget.Routed)?.route?.source

internal val BalanceTarget.dashboard: String?
    get() = (this as? BalanceTarget.Routed)?.route?.dashboard

internal fun BalanceTarget.idle(hasKey: Boolean): BalanceView = when (this) {
    BalanceTarget.Hidden -> BalanceView.Hidden
    is BalanceTarget.Routed -> when {
        route.selfHosted -> BalanceView.Hidden
        route.source == null -> BalanceView.Unsupported(route.dashboard)
        hasKey -> BalanceView.Loading
        else -> BalanceView.NeedKey
    }
}

object BalanceCatalog {
    fun shownDraft(
        snapshot: BalanceSnapshot?,
        preset: String,
        kind: ProviderConfig.Kind,
        baseUrl: String,
        apiKey: String,
        providerName: String,
        selfHostedPreset: Boolean,
    ): BalanceView {
        val token = token(preset, kind, baseUrl, apiKey, providerName)
        return snapshot?.takeIf { it.token == token }?.view
            ?: targetForDraft(preset, kind, baseUrl, providerName, selfHostedPreset).idle(apiKey.isNotBlank())
    }

    fun shownProvider(stored: BalanceView?, provider: Provider, hasKey: Boolean): BalanceView {
        val target = targetFor(provider)
        if (target.source == null) return target.idle(hasKey = false)
        if (!hasKey) return BalanceView.NeedKey
        return stored ?: BalanceView.Loading
    }

    fun token(
        preset: String,
        kind: ProviderConfig.Kind,
        baseUrl: String,
        apiKey: String,
        providerName: String,
    ): String = preset + "\n" + kind + "\n" + providerName + "\n" + baseUrl.trim() + "\n" + apiKey.trim().hashCode()

    internal fun targetFor(provider: Provider): BalanceTarget {
        if (provider.kind == ProviderConfig.Kind.ChatGpt || provider.kind == ProviderConfig.Kind.Xai) return BalanceTarget.Hidden
        if (ProviderPresets.all.firstOrNull { it.label == provider.name }?.selfHosted == true) return BalanceTarget.Hidden
        return routed(AllowanceRoute.forProvider(provider))
    }

    internal fun targetForDraft(
        preset: String,
        kind: ProviderConfig.Kind,
        baseUrl: String,
        providerName: String,
        selfHostedPreset: Boolean,
    ): BalanceTarget {
        if (kind == ProviderConfig.Kind.ChatGpt || kind == ProviderConfig.Kind.Xai || selfHostedPreset) return BalanceTarget.Hidden
        return routed(AllowanceRoute.forProvider(Provider("draft", providerName, kind, baseUrl.trim())))
    }

    private fun routed(route: AllowanceRoute): BalanceTarget =
        if (route.selfHosted) BalanceTarget.Hidden else BalanceTarget.Routed(route)
}
