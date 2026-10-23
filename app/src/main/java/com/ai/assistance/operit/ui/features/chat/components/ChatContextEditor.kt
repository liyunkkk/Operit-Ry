package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatContextPolicy
import com.ai.assistance.operit.ui.features.chat.util.parseContextK
import com.ai.assistance.operit.ui.features.chat.util.formatContextK
import com.ai.assistance.operit.ui.features.settings.components.settingsSearchTarget

data class ChatContextEditorTarget(
    val chatId: String,
    val configId: String,
    val policy: ChatContextPolicy,
    val capacityK: Float,
    val draft: String? = null,
    val draftRevision: Long = 0,
)

/** Budgets offered as one-tap choices; any other value can still be typed. */
private val BUDGET_PRESETS = listOf(200f, 300f, 400f, 500f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatContextPolicyEditor(
    initial: ChatContextPolicy,
    onSave: (ChatContextPolicy) -> Unit,
    saving: Boolean = false,
) {
    var window by remember(initial) { mutableStateOf("${formatContextK(initial.windowK)}K") }
    var maxMode by remember(initial) { mutableStateOf(initial.maxMode) }
    var enabled by remember(initial) { mutableStateOf(initial.autoCompact) }
    var trigger by remember(initial) { mutableStateOf(initial.compactAtK?.let { "${formatContextK(it)}K" } ?: "") }
    var retainTurnLimit by remember(initial) { mutableStateOf(initial.compactEveryUserTurns != null) }
    val windowValue = parseContextK(window)
    val triggerValue = parseContextK(trigger)
    // While Max is on the budget field is hidden, so an unfinished edit there cannot block saving.
    val windowK = windowValue ?: initial.windowK.takeIf { maxMode }
    val draft = windowK?.takeIf { !enabled || trigger.isBlank() || triggerValue != null }?.let {
        initial.copy(windowK = it, maxMode = maxMode,
            autoCompact = enabled, compactAtK = if (enabled) triggerValue else initial.compactAtK,
            compactEveryUserTurns = initial.compactEveryUserTurns.takeIf { retainTurnLimit })
    }
    Column {
        ContextSwitchRow(
            title = stringResource(R.string.max_mode_title),
            subtitle = stringResource(R.string.chat_context_max_hint),
            checked = maxMode, onCheckedChange = { maxMode = it }, enabled = !saving,
        )
        val budgetLabel = stringResource(R.string.chat_context_budget)
        AnimatedVisibility(!maxMode) {
            Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                OutlinedTextField(
                    value = window, onValueChange = { window = it }, singleLine = true, enabled = !saving,
                    label = { Text(budgetLabel) },
                    supportingText = { Text(stringResource(R.string.chat_context_budget_hint)) },
                    isError = windowValue == null,
                    modifier = Modifier.fillMaxWidth().settingsSearchTarget(budgetLabel),
                    shape = RoundedCornerShape(12.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BUDGET_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = windowValue == preset,
                            onClick = { window = "${formatContextK(preset)}K" },
                            label = { Text("${formatContextK(preset)}K") },
                            enabled = !saving,
                        )
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        ContextSwitchRow(
            title = stringResource(R.string.chat_context_auto_compact),
            subtitle = stringResource(R.string.chat_context_auto_compact_hint),
            checked = enabled, onCheckedChange = { enabled = it }, enabled = !saving,
        )
        AnimatedVisibility(enabled) {
            Column(Modifier.padding(top = 4.dp)) {
                OutlinedTextField(
                    value = trigger, onValueChange = { trigger = it }, singleLine = true,
                    label = { Text(stringResource(R.string.chat_context_compact_at)) },
                    supportingText = { Text(stringResource(R.string.chat_context_compact_hint)) },
                    enabled = !saving, isError = trigger.isNotBlank() && triggerValue == null,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                )
                if (initial.compactEveryUserTurns != null) {
                    ContextSwitchRow(
                        title = stringResource(R.string.chat_context_legacy_turns, initial.compactEveryUserTurns),
                        subtitle = null,
                        checked = retainTurnLimit, onCheckedChange = { retainTurnLimit = it }, enabled = !saving,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !saving && draft != null,
            onClick = { draft?.let(onSave) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun ContextSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .settingsSearchTarget(title)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked, onCheckedChange, enabled = enabled)
    }
}

@Composable
fun ChatContextEditorDialog(target: ChatContextEditorTarget, onDismiss: () -> Unit, onSave: (ChatContextPolicy) -> Unit, saving: Boolean = false) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.chat_context_current)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.chat_context_capacity, formatContextK(target.capacityK)))
                Spacer(Modifier.height(12.dp))
                ChatContextPolicyEditor(target.policy, onSave, saving)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
    )
}
