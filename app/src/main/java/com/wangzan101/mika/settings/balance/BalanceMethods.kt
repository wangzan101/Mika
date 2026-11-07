package com.wangzan101.mika.settings.balance

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * 一家服务商的余额查询:请求怎么发、响应怎么读。互不调用,测试可以单独断言地址、请求头和解析。
 * 智谱按量余额标 [experimental]:那个接口没有公开文档。
 */
internal interface BalanceMethod {
    val id: String
    val experimental: Boolean
    fun request(apiKey: String): Request
    fun parse(body: String): ParseResult
}

internal object DeepSeekBalance : BalanceMethod {
    override val id = "deepseek"
    override val experimental = false
    override fun request(apiKey: String) = bearer("https://api.deepseek.com/user/balance", apiKey)
    override fun parse(body: String) = parseDeepSeek(body)
}

/** 国内站人民币,国际站美元。两站的 Key 不通用,地址必须和 Key 的站点一致。 */
internal class KimiBalance private constructor(private val currency: String, private val url: String) : BalanceMethod {
    override val id = "kimi"
    override val experimental = false
    override fun request(apiKey: String) = bearer(url, apiKey)
    override fun parse(body: String) = parseKimi(body, currency)

    companion object {
        fun of(baseUrl: String): KimiBalance = if (host(baseUrl) == "api.moonshot.ai") en() else cn()
        fun cn() = KimiBalance("CNY", "https://api.moonshot.cn/v1/users/me/balance")
        fun en() = KimiBalance("USD", "https://api.moonshot.ai/v1/users/me/balance")
    }
}

/** 阶跃星辰账户。接口不回币种;国内站(也是预设用的那一个)按人民币。 */
internal class StepFunBalance private constructor(private val url: String) : BalanceMethod {
    override val id = "stepfun"
    override val experimental = false
    override fun request(apiKey: String) = bearer(url, apiKey)
    override fun parse(body: String) = parseStepFun(body)

    companion object {
        fun of(baseUrl: String): StepFunBalance = ofHost(host(baseUrl))
        fun ofHost(host: String): StepFunBalance {
            val chosen = if (host == "api.stepfun.ai") "api.stepfun.ai" else "api.stepfun.com"
            return StepFunBalance("https://$chosen/v1/accounts")
        }
    }
}

internal object OpenRouterBalance : BalanceMethod {
    override val id = "openrouter"
    override val experimental = false
    override fun request(apiKey: String) = bearer("https://openrouter.ai/api/v1/credits", apiKey)
    override fun parse(body: String) = parseOpenRouter(body)
}

/**
 * MiniMax Coding Plan 的剩余额度。额度接口的公开地址在 api.minimaxi.com / api.minimax.io,
 * 推理用的 api.minimax.cn 没有这份文档;同一把 Key,沿用 cc-switch 的旧域名。
 */
internal class MiniMaxCodingBalance private constructor(private val url: String) : BalanceMethod {
    override val id = "minimax-coding"
    override val experimental = false
    override fun request(apiKey: String) = bearer(url, apiKey) {
        header("Content-Type", "application/json")
    }
    override fun parse(body: String) = parseMiniMaxCoding(body)

    companion object {
        fun of(baseUrl: String): MiniMaxCodingBalance = if (host(baseUrl) == "api.minimax.io") en() else cn()
        fun cn() = MiniMaxCodingBalance("https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains")
        fun en() = MiniMaxCodingBalance("https://api.minimax.io/v1/api/openplatform/coding_plan/remains")
    }
}

/** 智谱按量账户余额。实验:没有公开文档,请求头不加 Bearer,和套餐接口一致。 */
internal class ZhipuAccountBalance(private val url: String, val cn: Boolean) : BalanceMethod {
    override val id = "zhipu"
    override val experimental = true
    override fun request(apiKey: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", apiKey.trim())
        .header("Accept", "application/json")
        .header("Accept-Language", "en-US,en")
        .get()
        .build()
    override fun parse(body: String) = parseZhipuAccount(body, cn)

    companion object {
        private const val PATH = "/api/biz/account/query-customer-account-report"
        fun of(baseUrl: String): ZhipuAccountBalance = if (host(baseUrl) == "api.z.ai") en() else cn()
        fun cn() = ZhipuAccountBalance("https://open.bigmodel.cn$PATH", true)
        fun en() = ZhipuAccountBalance("https://api.z.ai$PATH", false)
    }
}

/** 智谱 Coding Plan 额度。按量 Key 会回「没有套餐」,由 [HttpBalanceLookup] 改查 [account]。 */
internal class ZhipuCodingBalance(private val url: String, private val account: ZhipuAccountBalance) : BalanceMethod {
    override val id = "zhipu-coding"
    override val experimental = false
    override fun request(apiKey: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", apiKey.trim())
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("Accept-Language", "en-US,en")
        .get()
        .build()
    override fun parse(body: String) = parseZhipuCoding(body)
    fun account(): ZhipuAccountBalance = account

    companion object {
        private const val PATH = "/api/monitor/usage/quota/limit"
        fun of(baseUrl: String): ZhipuCodingBalance = if (host(baseUrl) == "api.z.ai") en() else cn()
        fun cn() = ZhipuCodingBalance("https://open.bigmodel.cn$PATH", ZhipuAccountBalance.cn())
        fun en() = ZhipuCodingBalance("https://api.z.ai$PATH", ZhipuAccountBalance.en())
    }
}

private fun host(url: String): String = url.trim().toHttpUrlOrNull()?.host?.lowercase().orEmpty()

private fun bearer(url: String, apiKey: String, extra: Request.Builder.() -> Unit = {}): Request =
    Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .header("Accept", "application/json")
        .apply(extra)
        .get()
        .build()
