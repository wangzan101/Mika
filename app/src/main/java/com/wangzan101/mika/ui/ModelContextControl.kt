package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.CompactionThreshold
import com.wangzan101.mika.core.model.ModelContextSettings
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ContextInputError
import com.wangzan101.mika.settings.ModelContextForm
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.ThresholdMode
import com.wangzan101.mika.settings.automaticContextWindow
import com.wangzan101.mika.settings.contextFor
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.theme.MikaTheme

@Composable
internal fun ModelContextControl(ui: ModelSetupUi, role: ModelRole,
    onSave: (String, String, ModelContextSettings) -> Boolean,
) {
    val cfg = ui.configFor(role) ?: return
    if (ui.roles[role] == RoleChoice.SameAsMain) return
    val provider = ui.providerOf(cfg) ?: return
    val automatic = provider.automaticContextWindow(cfg.model, cfg.capabilities.contextTokens)
    val settings = provider.contextFor(cfg.model, automatic)
    var open by rememberSaveable(role, cfg.providerId, cfg.model) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, Modifier.padding(start = 8.dp).testTag("context-${role.name.lowercase()}")) {
        Text(stringResource(R.string.model_context_summary,
            settings.windowTokens?.toString() ?: stringResource(R.string.model_context_auto),
            thresholdLabel(settings.threshold)))
    }
    if (open) ModelContextDialog(cfg.model, automatic, settings, onDismiss = { open = false },
        onSave = { onSave(cfg.providerId, cfg.model, it) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelContextDialog(model: String, automaticWindow: Int, initial: ModelContextSettings,
    onDismiss: () -> Unit, onSave: (ModelContextSettings) -> Boolean,
) {
    var window by rememberSaveable(model) { mutableStateOf(initial.windowTokens?.toString().orEmpty()) }
    var mode by rememberSaveable(model) { mutableStateOf(if (initial.threshold is CompactionThreshold.Tokens) ThresholdMode.Tokens else ThresholdMode.Percent) }
    var percent by rememberSaveable(model) { mutableStateOf((initial.threshold as? CompactionThreshold.Percent)?.value?.toString() ?: "60") }
    var tokens by rememberSaveable(model) { mutableStateOf((initial.threshold as? CompactionThreshold.Tokens)?.value?.toString()
        ?: initial.threshold.tokens(initial.window(automaticWindow)).toLong().coerceAtLeast(1).toString()) }
    var saveFailed by remember { mutableStateOf(false) }
    val form = ModelContextForm(window, mode, if (mode == ThresholdMode.Percent) percent else tokens)
    val valid = form.settings(automaticWindow)
    val windowError = form.windowError()?.let { inputError(it) }
    val thresholdError = form.thresholdError(automaticWindow)?.let { inputError(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.model_context_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model)
                Text(stringResource(R.string.model_context_note))
                OutlinedTextField(window, { window = it; saveFailed = false }, Modifier.fillMaxWidth().testTag("context-window"),
                    label = { Text(stringResource(R.string.model_context_window)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto_value, automaticWindow)) },
                    supportingText = { Text(windowError ?: stringResource(R.string.model_context_auto_value, automaticWindow)) },
                    isError = windowError != null, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(stringResource(R.string.model_context_threshold))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThresholdMode.entries.forEach { choice -> SelectChip(
                        stringResource(if (choice == ThresholdMode.Percent) R.string.model_context_percent else R.string.model_context_tokens),
                        mode == choice, { mode = choice; saveFailed = false }, Modifier.testTag("context-mode-${choice.name.lowercase()}")) }
                }
                OutlinedTextField(form.threshold, {
                    if (mode == ThresholdMode.Percent) percent = it else tokens = it
                    saveFailed = false
                }, Modifier.fillMaxWidth().testTag("context-threshold"),
                    label = { Text(stringResource(if (mode == ThresholdMode.Percent) R.string.model_context_percent_value else R.string.model_context_token_value)) },
                    supportingText = { Text(thresholdError ?: stringResource(R.string.model_context_trigger_note)) },
                    isError = thresholdError != null, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (valid != null) Text(stringResource(R.string.model_context_effective,
                    valid.window(automaticWindow), valid.threshold.tokens(valid.window(automaticWindow)).toLong().coerceAtLeast(1)),
                    Modifier.testTag("context-effective").semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = { window = ""; mode = ThresholdMode.Percent; percent = "60"; tokens = (automaticWindow * 0.6).toLong().coerceAtLeast(1).toString(); saveFailed = false },
                    Modifier.testTag("context-reset")) { Text(stringResource(R.string.model_context_reset)) }
                if (saveFailed) Text(stringResource(R.string.model_context_save_failed), color = MikaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton(onClick = {
            valid?.let { if (onSave(it)) onDismiss() else saveFailed = true }
        }, enabled = valid != null, modifier = Modifier.testTag("context-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun thresholdLabel(value: CompactionThreshold): String = when (value) {
    is CompactionThreshold.Percent -> "${value.value}%"
    is CompactionThreshold.Tokens -> stringResource(R.string.model_context_token_summary, value.value)
}

@Composable
private fun inputError(error: ContextInputError): String = stringResource(when (error) {
    ContextInputError.PositiveInteger -> R.string.model_context_error_integer
    ContextInputError.PercentRange -> R.string.model_context_error_percent
    ContextInputError.BelowWindow -> R.string.model_context_error_window
})
