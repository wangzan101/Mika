package com.wangzan101.mika.core.model

/** Local input-budget policy, independent of the provider's actual model limits. */
sealed interface CompactionThreshold {
    data class Percent(val value: Int = 60) : CompactionThreshold {
        init { require(value in 1..99) }
    }
    data class Tokens(val value: Int) : CompactionThreshold {
        init { require(value > 0) }
    }

    fun tokens(window: Int): Double = when (this) {
        is Percent -> window * (value / 100.0)
        is Tokens -> value.toDouble()
    }

    fun fits(window: Int): Boolean = window > 0 && (this !is Tokens || value < window)
}

data class ModelContextSettings(
    val windowTokens: Int? = null,
    val threshold: CompactionThreshold = CompactionThreshold.Percent(),
) {
    init {
        require(windowTokens == null || windowTokens > 0)
        require(windowTokens == null || threshold.fits(windowTokens))
    }

    fun window(automatic: Int): Int = windowTokens ?: automatic
    fun validFor(automatic: Int): Boolean = threshold.fits(window(automatic))

    /** Metadata may shrink after a refresh; keep the window override but discard an invalid threshold. */
    fun forWindow(automatic: Int): ModelContextSettings =
        if (validFor(automatic)) this else copy(threshold = CompactionThreshold.Percent())
}

/** Keep streaming and all adapter behaviour; only the local context budget changes. */
fun ChatModel.withContextSettings(settings: ModelContextSettings): ChatModel {
    val safe = settings.forWindow(capabilities.contextTokens)
    val caps = capabilities.copy(contextTokens = safe.window(capabilities.contextTokens))
    return if (this is StreamingChatModel) object : StreamingChatModel by this {
        override val capabilities = caps
        override val compactionThreshold = safe.threshold
    } else object : ChatModel by this {
        override val capabilities = caps
        override val compactionThreshold = safe.threshold
    }
}
