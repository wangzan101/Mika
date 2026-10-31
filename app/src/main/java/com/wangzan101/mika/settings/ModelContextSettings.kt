package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities

enum class ThresholdMode { Percent, Tokens }
enum class ContextInputError { PositiveInteger, PercentRange, BelowWindow }

/** 仅保存在 App 的设置;装配模型时换算成已有压缩器接收的比例。 */
data class ModelContextSettings(
    val windowTokens: Int? = null,
    val threshold: Int = 60,
    val mode: ThresholdMode = ThresholdMode.Percent,
) {
    init {
        require(windowTokens == null || windowTokens > 0)
        require(threshold > 0 && (mode != ThresholdMode.Percent || threshold <= 90))
        require(windowTokens == null || validFor(windowTokens))
    }

    fun window(automatic: Int): Int = windowTokens ?: automatic
    fun thresholdTokens(automatic: Int): Double = when (mode) {
        ThresholdMode.Percent -> window(automatic) * (threshold / 100.0)
        ThresholdMode.Tokens -> threshold.toDouble()
    }
    fun validFor(automatic: Int): Boolean = window(automatic) > 0 &&
        (mode == ThresholdMode.Percent || threshold.toLong() * 100 <= window(automatic).toLong() * 90)

    fun applyTo(caps: Capabilities): Capabilities = caps.copy(contextTokens = window(caps.contextTokens),
        // 重新选模型后自动窗口可能变小:运行时保留原压缩器的 90% 上限,不改写用户存下的值。
        compactAt = (thresholdTokens(caps.contextTokens) / window(caps.contextTokens)).coerceAtMost(0.9))
}

/** Pure form validation is also applied at the storage boundary. Blank window means automatic. */
data class ModelContextForm(
    val window: String = "",
    val mode: ThresholdMode = ThresholdMode.Percent,
    val threshold: String = "60",
) {
    fun windowError(): ContextInputError? =
        if (window.isBlank() || positiveInt(window) != null) null else ContextInputError.PositiveInteger

    fun thresholdError(automaticWindow: Int): ContextInputError? {
        val value = positiveInt(threshold)
        if (mode == ThresholdMode.Percent) return if (value != null && value in 1..90) null else ContextInputError.PercentRange
        if (value == null) return ContextInputError.PositiveInteger
        if (windowError() != null) return null
        return if (value.toLong() * 100 <= (positiveInt(window) ?: automaticWindow).toLong() * 90) null else ContextInputError.BelowWindow
    }

    fun settings(automaticWindow: Int): ModelContextSettings? {
        if (windowError() != null || thresholdError(automaticWindow) != null) return null
        val value = positiveInt(threshold) ?: return null
        return ModelContextSettings(positiveInt(window), value, mode).takeIf { it.validFor(automaticWindow) }
    }

    companion object {
        private fun positiveInt(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it > 0 }
    }
}
