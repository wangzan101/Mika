package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ListedModel

/** 只给实际走 Responses 的模型配置思考档位,不把这个参数发给其他协议。 */
data class ReasoningOptions(val levels: List<String>, val fromProvider: Boolean) {
    companion object {
        // 列表没说明档位时供手动选择,不声称每个模型都支持;界面会说明,默认始终不指定。
        private val manualLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun of(config: ProviderConfig, listed: ListedModel?): ReasoningOptions? {
            val kind = ProviderStore.adapterFor(config, listed?.endpoint).first
            if (kind != ProviderConfig.Kind.OpenAiResponses && kind != ProviderConfig.Kind.ChatGpt) return null
            listed?.reasoningEfforts?.let { return ReasoningOptions(it, fromProvider = true) }
            // 仍返回空选项,使已保存的旧档位能在界面恢复默认。
            if (listed?.reasoning == false) return ReasoningOptions(emptyList(), fromProvider = true)
            return ReasoningOptions(manualLevels, fromProvider = false)
        }
    }
}
