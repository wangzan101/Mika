package com.wangzan101.mika.settings.balance

import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPreset
import com.wangzan101.mika.settings.ProviderPresets
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 按预设名或地址决定查不查余额、用哪一种查法。
 *
 * 第一期会查的:DeepSeek、Kimi、阶跃星辰、OpenRouter、MiniMax(Coding Plan 剩余额度)、
 * 智谱(按量余额,实验)、智谱 Coding Plan(套餐额度;没有套餐时回落到实验的按量余额)。
 * Ollama 和 LM Studio 是本机服务,不显示余额。硅基流动的余额接口已停用,不再调用,只说明并给控制台。
 * 其余内置预设没有核实过的查询接口,显示不支持,不编一个余额出来。
 */
object BalanceCatalog {
    const val SILICONFLOW_CONSOLE = "https://cloud.siliconflow.cn/"

    /** 没有余额接口时,打开这家控制台看账单。只放核实过的地址。 */
    private val consoles = mapOf(
        "OpenAI" to "https://platform.openai.com/settings/organization/billing",
        "Anthropic" to "https://console.anthropic.com/settings/billing",
        "Gemini" to "https://aistudio.google.com/",
        "xAI" to "https://console.x.ai/",
        "Mistral" to "https://console.mistral.ai/",
        "Groq" to "https://console.groq.com/",
        "通义千问" to "https://bailian.console.aliyun.com/",
        "火山方舟" to "https://console.volcengine.com/ark",
        "百度千帆" to "https://console.bce.baidu.com/qianfan/overview",
        "腾讯混元" to "https://console.cloud.tencent.com/hunyuan",
        "OpenCode Go" to "https://opencode.ai/",
    )

    fun resolve(name: String, baseUrl: String, kind: ProviderConfig.Kind? = null): BalanceTarget {
        if (kind == ProviderConfig.Kind.ChatGpt || kind == ProviderConfig.Kind.Xai) return BalanceTarget.Hidden
        val preset = ProviderPresets.all.firstOrNull { it.label == name }
        if (preset?.selfHosted == true) return BalanceTarget.Hidden
        if (preset != null && preset != ProviderPresets.custom) return forPreset(preset)
        return forUrl(baseUrl)
    }

    /** 表单上还没有查询结果时显示什么:不支持、没填 Key,或正在查。 */
    fun preview(name: String, baseUrl: String, apiKey: String): BalanceView = when (val target = resolve(name, baseUrl)) {
        BalanceTarget.Hidden -> BalanceView.Hidden
        is BalanceTarget.Unsupported -> BalanceView.Unsupported(target.note, target.consoleUrl)
        is BalanceTarget.Query -> if (apiKey.isBlank()) BalanceView.NeedKey(target.method.experimental) else BalanceView.Loading(target.method.experimental)
    }

    fun shownDraft(snapshot: BalanceSnapshot?, name: String, baseUrl: String, apiKey: String): BalanceView {
        val token = token(name, baseUrl, apiKey)
        return snapshot?.takeIf { it.token == token }?.view ?: preview(name, baseUrl, apiKey)
    }

    /**
     * 详情页。没有 Key 时不沿用上一次的数字。ChatGPT、xAI 账号登录走各自的用量页,这里不显示。
     */
    fun shownProvider(
        stored: BalanceView?,
        name: String,
        baseUrl: String,
        kind: ProviderConfig.Kind,
        hasKey: Boolean,
    ): BalanceView {
        val target = resolve(name, baseUrl, kind)
        if (target !is BalanceTarget.Query) return target.view()
        if (!hasKey) return BalanceView.NeedKey(target.method.experimental)
        return stored ?: BalanceView.Loading(target.method.experimental)
    }

    fun token(name: String, baseUrl: String, apiKey: String): String =
        name + "\n" + baseUrl.trim() + "\n" + apiKey.trim().hashCode()

    private fun forPreset(preset: ProviderPreset): BalanceTarget = when (preset.label) {
        "DeepSeek" -> query(DeepSeekBalance)
        "Kimi" -> query(KimiBalance.of(preset.baseUrl))
        "阶跃星辰" -> query(StepFunBalance.of(preset.baseUrl))
        "OpenRouter" -> query(OpenRouterBalance)
        "MiniMax" -> query(MiniMaxCodingBalance.of(preset.baseUrl))
        "智谱" -> query(ZhipuAccountBalance.of(preset.baseUrl))
        "智谱 Coding Plan" -> query(ZhipuCodingBalance.of(preset.baseUrl))
        "硅基流动" -> siliconFlow()
        else -> BalanceTarget.Unsupported(BalanceNote.Generic, consoles[preset.label])
    }

    private fun forUrl(url: String): BalanceTarget {
        val http = url.trim().toHttpUrlOrNull() ?: return BalanceTarget.Unsupported(BalanceNote.Generic, null)
        val host = http.host.lowercase()
        val path = http.encodedPath.lowercase()
        return when (host) {
            "api.deepseek.com" -> query(DeepSeekBalance)
            "api.moonshot.cn" -> query(KimiBalance.cn())
            "api.moonshot.ai" -> query(KimiBalance.en())
            "api.stepfun.com", "api.stepfun.ai" -> query(StepFunBalance.ofHost(host))
            "openrouter.ai" -> query(OpenRouterBalance)
            "api.minimax.cn", "api.minimaxi.com" -> query(MiniMaxCodingBalance.cn())
            "api.minimax.io" -> query(MiniMaxCodingBalance.en())
            "open.bigmodel.cn" -> query(if ("/coding/" in path) ZhipuCodingBalance.cn() else ZhipuAccountBalance.cn())
            "api.z.ai" -> query(if ("/coding/" in path) ZhipuCodingBalance.en() else ZhipuAccountBalance.en())
            "api.siliconflow.cn", "api.siliconflow.com" -> siliconFlow()
            else -> BalanceTarget.Unsupported(BalanceNote.Generic, null)
        }
    }

    private fun query(method: BalanceMethod) = BalanceTarget.Query(method)
    private fun siliconFlow() = BalanceTarget.Unsupported(BalanceNote.SiliconFlowRetired, SILICONFLOW_CONSOLE)
}

sealed interface BalanceTarget {
    data object Hidden : BalanceTarget
    data class Unsupported(val note: BalanceNote, val consoleUrl: String?) : BalanceTarget
    class Query internal constructor(internal val method: BalanceMethod) : BalanceTarget
}

internal fun BalanceTarget.view(): BalanceView = when (this) {
    BalanceTarget.Hidden -> BalanceView.Hidden
    is BalanceTarget.Unsupported -> BalanceView.Unsupported(note, consoleUrl)
    is BalanceTarget.Query -> BalanceView.Loading(method.experimental)
}

internal val BalanceTarget.methodOrNull: BalanceMethod?
    get() = (this as? BalanceTarget.Query)?.method
