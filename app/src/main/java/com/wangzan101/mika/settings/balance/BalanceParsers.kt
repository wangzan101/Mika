package com.wangzan101.mika.settings.balance

import kotlinx.serialization.json.JsonObject

/**
 * 各家余额响应的纯解析。查询方法只负责地址和请求头,解析放在这里,测试不用起服务。
 * 字段对照 cc-switch 的 balance.rs / coding_plan.rs,以及各家公开文档(Kimi、阶跃星辰、OpenRouter)。
 */

internal sealed interface ParseResult {
    data class Ok(val lines: List<BalanceLine>) : ParseResult
    data class Failed(val failure: BalanceFailure) : ParseResult
    /** 智谱这把 Key 没有 Coding Plan(按量付费)。调用方改查账户余额。 */
    data object NoCodingPlan : ParseResult
}

private fun unreadable(): ParseResult = ParseResult.Failed(BalanceFailure.Unreadable)

private fun messageOrUnreadable(text: String?): ParseResult {
    val short = text?.shortMessage()
    return ParseResult.Failed(if (short == null) BalanceFailure.Unreadable else BalanceFailure.Message(short))
}

/**
 * DeepSeek `GET /user/balance`。
 * `balance_infos[].total_balance` 是该币种钱包;同一账号会同时列出 CNY 和 USD。
 * 有的钱包是 0 时不再列出空的,免得空行盖过有余额的那个。全是 0 或都有钱时都保留。
 * `is_available == false` 只表示余额不够调用,不是 Key 失效。
 */
internal fun parseDeepSeek(body: String): ParseResult {
    val infos = parseObject(body)?.array("balance_infos") ?: return unreadable()
    val lines = infos.mapNotNull { el ->
        val info = el.asObject() ?: return@mapNotNull null
        val amount = info.double("total_balance") ?: return@mapNotNull null
        val currency = info.str("currency")?.trim()?.uppercase()?.ifEmpty { null } ?: "CNY"
        BalanceLine(BalanceLineKind.Total, amount, currency)
    }
    if (lines.isEmpty()) return unreadable()
    val kept = if (lines.any { it.amount > 0.0 }) lines.filter { it.amount > 0.0 } else lines
    return ParseResult.Ok(kept)
}

/**
 * Kimi 开放平台 `GET /v1/users/me/balance`。
 * `available_balance` 是能用来调用的余额(现金加代金券)。`code != 0` 或 `status == false` 是业务失败。
 * 国内站(api.moonshot.cn)单位是人民币,国际站(api.moonshot.ai)是美元;响应里没有币种字段。
 */
internal fun parseKimi(body: String, currency: String): ParseResult {
    val root = parseObject(body) ?: return unreadable()
    val code = root.int("code")
    if (code != null && code != 0) return messageOrUnreadable(root.str("msg") ?: root.str("message"))
    if (root.bool("status") == false) return messageOrUnreadable(root.str("msg") ?: root.str("message"))
    val available = root.obj("data")?.double("available_balance") ?: return unreadable()
    return ParseResult.Ok(listOf(BalanceLine(BalanceLineKind.Total, available, currency)))
}

/** 阶跃星辰 `GET /v1/accounts`。`balance` 是当前可用余额。接口不回币种,国内站按人民币。 */
internal fun parseStepFun(body: String): ParseResult {
    val balance = parseObject(body)?.double("balance") ?: return unreadable()
    return ParseResult.Ok(listOf(BalanceLine(BalanceLineKind.Total, balance, "CNY")))
}

/**
 * OpenRouter `GET /api/v1/credits`。
 * 剩余 = `total_credits - total_usage`(美元)。`/key` 的 `limit_remaining` 是花费上限,不是账户余额,不用。
 * 余额为 0 或为负不代表 Key 失效(免费账号可以没有额度)。
 */
internal fun parseOpenRouter(body: String): ParseResult {
    val root = parseObject(body) ?: return unreadable()
    val data = root.obj("data") ?: root
    val credits = data.double("total_credits") ?: return unreadable()
    val usage = data.double("total_usage") ?: return unreadable()
    val remaining = credits - usage
    if (!remaining.isFinite()) return unreadable()
    return ParseResult.Ok(listOf(BalanceLine(BalanceLineKind.Total, remaining, "USD")))
}

/**
 * MiniMax Coding Plan `GET /v1/api/openplatform/coding_plan/remains`。
 * `current_*_remaining_percent` 是剩余百分比。只取 `model_name == general`,跳过 video。
 * 周额度 `current_weekly_status`:1 正常、2 已用完(仍展示)、3 这个套餐没有周限额(不展示)。
 */
internal fun parseMiniMaxCoding(body: String): ParseResult {
    val root = parseObject(body) ?: return unreadable()
    val base = root.obj("base_resp")
    val status = base?.int("status_code")
    if (status != null && status != 0) return messageOrUnreadable(base.str("status_msg"))
    val item = root.array("model_remains").orEmpty().firstNotNullOfOrNull { el ->
        el.asObject()?.takeIf { it.str("model_name") == "general" }
    } ?: return unreadable()
    val lines = mutableListOf<BalanceLine>()
    item.double("current_interval_remaining_percent")?.let { remain ->
        lines += BalanceLine(BalanceLineKind.FiveHour, remain.coerceIn(0.0, 100.0))
    }
    val weekly = item.int("current_weekly_status")
    if (weekly == 1 || weekly == 2) {
        item.double("current_weekly_remaining_percent")?.let { remain ->
            lines += BalanceLine(BalanceLineKind.Weekly, remain.coerceIn(0.0, 100.0))
        }
    }
    if (lines.isEmpty()) return unreadable()
    return ParseResult.Ok(lines)
}

/**
 * 智谱按量余额 `GET /api/biz/account/query-customer-account-report`。
 * 没有公开文档(cc-switch 2026-10 用国内站按量 Key 对过):`availableBalance` 是扣掉冻结后的可用余额,
 * 没有时退回 `balance`。国内站人民币,国际站按同一结构记美元。`success == false` 是业务失败。
 */
internal fun parseZhipuAccount(body: String, cn: Boolean): ParseResult {
    val root = parseObject(body) ?: return unreadable()
    if (root.bool("success") == false) return messageOrUnreadable(root.str("msg"))
    val data = root.obj("data") ?: root
    val available = data.double("availableBalance") ?: data.double("balance") ?: return unreadable()
    return ParseResult.Ok(listOf(BalanceLine(BalanceLineKind.Total, available, if (cn) "CNY" else "USD")))
}

/**
 * 智谱 Coding Plan `GET /api/monitor/usage/quota/limit`。
 * `percentage` 是已用百分比,界面显示剩余。`unit == 3` 是 5 小时窗口,`unit == 6` 是每周窗口
 * (cc-switch 对 bigmodel.cn 和 z.ai 的实测)。认不出 unit 时:没有重置时间的优先当 5 小时,其余按重置时间先后。
 * `TIME_LIMIT` 是 MCP 次数,不是这把 Key 的额度,略过。
 * 按量 Key 会回「没有 coding plan」,交给调用方改查账户余额。
 */
internal fun parseZhipuCoding(body: String): ParseResult {
    val root = parseObject(body) ?: return unreadable()
    if (root.bool("success") == false) {
        if (zhipuHasNoCodingPlan(root)) return ParseResult.NoCodingPlan
        return messageOrUnreadable(root.str("msg"))
    }
    val data = root.obj("data") ?: return unreadable()
    val lines = zhipuQuotaLines(data)
    if (lines.isEmpty()) return unreadable()
    return ParseResult.Ok(lines)
}

private fun zhipuHasNoCodingPlan(body: JsonObject): Boolean {
    val msg = body.str("msg").orEmpty().lowercase()
    val compact = msg.filter { !it.isWhitespace() && it != '_' && it != '-' }
    if (!compact.contains("codingplan")) return false
    return listOf(
        "不存在", "没有", "未开通",
        "not exist", "not found", "not have", "doesn't have", "no coding plan", "not subscribed",
    ).any { msg.contains(it) }
}

private data class ZhipuSlot(val reset: Long?, val usedPercent: Double)

private fun zhipuQuotaLines(data: JsonObject): List<BalanceLine> {
    var fiveHour: ZhipuSlot? = null
    var weekly: ZhipuSlot? = null
    val unclassified = mutableListOf<ZhipuSlot>()
    for (el in data.array("limits").orEmpty()) {
        val item = el.asObject() ?: continue
        val type = item.str("type").orEmpty()
        if (!type.equals("TOKENS_LIMIT", ignoreCase = true) && !type.equals("CREDIT_LIMIT", ignoreCase = true)) continue
        val used = item.double("percentage") ?: continue
        val slot = ZhipuSlot(item.long("nextResetTime"), used)
        when (item.int("unit")) {
            3 -> if (fiveHour == null) fiveHour = slot
            6 -> if (weekly == null) weekly = slot
            else -> unclassified += slot
        }
    }
    unclassified.sortWith(compareBy({ it.reset != null }, { it.reset ?: Long.MIN_VALUE }))
    for (slot in unclassified) {
        if (fiveHour == null) fiveHour = slot
        else if (weekly == null) weekly = slot
    }
    return listOfNotNull(
        fiveHour?.let { BalanceLine(BalanceLineKind.FiveHour, remainingPercent(it.usedPercent)) },
        weekly?.let { BalanceLine(BalanceLineKind.Weekly, remainingPercent(it.usedPercent)) },
    )
}

private fun remainingPercent(used: Double): Double = (100.0 - used).coerceIn(0.0, 100.0)
