package com.wangzan101.mika.settings.balance

import com.wangzan101.mika.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 用某一种查法问一次余额。测试里换成不联网的实现。 */
internal fun interface BalanceLookup {
    fun query(method: BalanceMethod, apiKey: String): BalanceOutcome
}

/**
 * 真正发出余额请求。走和模型调用同一个网络代理。
 * 智谱 Coding Plan 明确说这把 Key 没有套餐时,改查实验的按量余额,不把「没有套餐」直接当成失败。
 */
internal class HttpBalanceLookup(private val http: OkHttpClient = defaultClient()) : BalanceLookup {
    override fun query(method: BalanceMethod, apiKey: String): BalanceOutcome {
        val parsed = execute(method, apiKey)
        if (parsed is ParseResult.NoCodingPlan && method is ZhipuCodingBalance) {
            return execute(method.account(), apiKey).toOutcome(experimental = true)
        }
        return parsed.toOutcome(method.experimental)
    }

    private fun execute(method: BalanceMethod, apiKey: String): ParseResult {
        val key = apiKey.trim()
        if (key.isEmpty() || key.any { it == '\n' || it == '\r' }) return ParseResult.Failed(BalanceFailure.Auth)
        val call = try {
            http.newCall(method.request(key))
        } catch (e: IllegalArgumentException) {
            return ParseResult.Failed(BalanceFailure.Auth)
        }
        return try {
            call.execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 -> ParseResult.Failed(BalanceFailure.Auth)
                    !resp.isSuccessful -> ParseResult.Failed(BalanceFailure.Http(resp.code))
                    else -> method.parse(body)
                }
            }
        } catch (e: IOException) {
            ParseResult.Failed(BalanceFailure.Network)
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = NetProxy.shared.client(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS),
        )
    }
}

private fun ParseResult.toOutcome(experimental: Boolean): BalanceOutcome = when (this) {
    is ParseResult.Ok -> BalanceOutcome.Ready(lines, experimental)
    is ParseResult.Failed -> BalanceOutcome.Failed(failure, experimental)
    // 没有套餐又没有回落时,照实说。智谱的回落在 [HttpBalanceLookup.query]。
    ParseResult.NoCodingPlan -> BalanceOutcome.Failed(BalanceFailure.NoCodingPlan, experimental)
}
