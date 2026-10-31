package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.CompactionThreshold
import com.wangzan101.mika.core.model.ModelContextSettings

enum class ThresholdMode { Percent, Tokens }
enum class ContextInputError { PositiveInteger, PercentRange, BelowWindow }

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
        if (mode == ThresholdMode.Percent) return if (value != null && value in 1..99) null else ContextInputError.PercentRange
        if (value == null) return ContextInputError.PositiveInteger
        if (windowError() != null) return null
        return if (value < (positiveInt(window) ?: automaticWindow)) null else ContextInputError.BelowWindow
    }

    fun settings(automaticWindow: Int): ModelContextSettings? {
        if (windowError() != null || thresholdError(automaticWindow) != null) return null
        val value = positiveInt(threshold) ?: return null
        return ModelContextSettings(positiveInt(window), when (mode) {
            ThresholdMode.Percent -> CompactionThreshold.Percent(value)
            ThresholdMode.Tokens -> CompactionThreshold.Tokens(value)
        }).takeIf { it.validFor(automaticWindow) }
    }

    companion object {
        private fun positiveInt(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it > 0 }
    }
}

/** Fresh model metadata wins over the cached config; overrides are stored separately. */
fun Provider.automaticContextWindow(model: String, fallback: Int): Int =
    models?.firstOrNull { it.id == model }?.contextTokens?.takeIf { it > 0 } ?: fallback

fun Provider.contextFor(model: String, automaticWindow: Int): ModelContextSettings =
    (contextSettings[model] ?: ModelContextSettings()).forWindow(automaticWindow)
