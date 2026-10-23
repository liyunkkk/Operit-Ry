package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.WaifuPreferences
import com.ai.assistance.operit.ui.components.CustomScaffold
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsIconBadge
import com.ai.assistance.operit.ui.features.settings.components.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.SettingsSectionTitle
import com.ai.assistance.operit.ui.features.settings.components.SettingsSwitchRow
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.ui.features.settings.components.settingsSearchTarget
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaifuModeSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCustomEmoji: () -> Unit = {}
) {
    val context = LocalContext.current
    val waifuPreferences = remember { WaifuPreferences.getInstance(context) }
    val characterCardManager = remember { CharacterCardManager.getInstance(context) }
    val characterGroupCardManager = remember { CharacterGroupCardManager.getInstance(context) }
    val activePromptManager = remember { ActivePromptManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    val activePrompt by activePromptManager.activePromptFlow.collectAsState(
        initial = ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID)
    )
    val activeCharacterCard by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterCard -> characterCardManager.getCharacterCardFlow(prompt.id)
            is ActivePrompt.CharacterGroup -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCharacterGroup by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterGroup -> characterGroupCardManager.getCharacterGroupCardFlow(prompt.id)
            is ActivePrompt.CharacterCard -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeTargetName = activeCharacterGroup?.name ?: activeCharacterCard?.name ?: ""

    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage.collectAsState(initial = false)
    val cardColor = settingsCardColor(hasBackgroundImage)

    // 状态
    var showSaveSuccess by remember { mutableStateOf(false) }
    val isWaifuModeEnabled = waifuPreferences.enableWaifuModeFlow.collectAsState(initial = false).value
    val charDelay = waifuPreferences.waifuCharDelayFlow.collectAsState(initial = 250).value
    val removePunctuation = waifuPreferences.waifuRemovePunctuationFlow.collectAsState(initial = false).value
    val enableEmoticons = waifuPreferences.waifuEnableEmoticonsFlow.collectAsState(initial = false).value
    val enableSelfie = waifuPreferences.waifuEnableSelfieFlow.collectAsState(initial = false).value
    val enableMergeSend = waifuPreferences.waifuEnableMergeSendFlow.collectAsState(initial = false).value
    val mergeSendDelayMs =
        waifuPreferences.waifuMergeSendDelayMsFlow.collectAsState(
            initial = WaifuPreferences.DEFAULT_WAIFU_MERGE_SEND_DELAY_MS
        ).value
    val waifuCustomPrompt = waifuPreferences.waifuCustomPromptFlow.collectAsState(initial = "").value
    val selfiePrompt = waifuPreferences.waifuSelfiePromptFlow.collectAsState(initial = "").value

    // 辅助保存函数，同时保存到角色卡
    val saveSettings: (suspend () -> Unit) -> Unit = { saveAction ->
        scope.launch {
            saveAction()
            when (activePrompt) {
                is ActivePrompt.CharacterGroup -> {
                    activeCharacterGroup?.id?.let { waifuPreferences.saveCurrentWaifuSettingsToCharacterGroup(it) }
                }
                is ActivePrompt.CharacterCard -> {
                    activeCharacterCard?.id?.let { waifuPreferences.saveCurrentWaifuSettingsToCharacterCard(it) }
                }
            }
            showSaveSuccess = true
        }
    }

    // 显示保存成功的提示
    LaunchedEffect(showSaveSuccess) {
        if (showSaveSuccess) {
            kotlinx.coroutines.delay(2000)
            showSaveSuccess = false
        }
    }

    CustomScaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsInfoBanner(
                text = stringResource(R.string.waifu_mode_description),
                backgroundColor = cardColor
            )

            // 角色卡绑定：设置会存到这张卡上，用主题色底让它最显眼
            SettingsCard(
                backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    .compositeOver(cardColor)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(icon = Icons.Outlined.Link, size = 36)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.waifu_config_bound_to_card),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = activeTargetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 基本开关
            Column {
                SettingsSectionTitle(text = stringResource(R.string.waifu_mode), icon = Icons.Outlined.Face)
                SettingsCard(
                    backgroundColor = cardColor,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.enable_waifu_mode),
                        subtitle = stringResource(R.string.waifu_mode_toggle_desc),
                        checked = isWaifuModeEnabled,
                        onCheckedChange = { enabled ->
                            saveSettings { waifuPreferences.saveEnableWaifuMode(enabled) }
                        }
                    )
                    SettingsRowDivider(inset = false)
                    SettingsSwitchRow(
                        title = stringResource(R.string.remove_punctuation),
                        subtitle = stringResource(R.string.remove_punctuation_desc),
                        checked = removePunctuation,
                        onCheckedChange = { enabled ->
                            saveSettings { waifuPreferences.saveWaifuRemovePunctuation(enabled) }
                        }
                    )
                    SettingsRowDivider(inset = false)
                    SettingsSwitchRow(
                        title = stringResource(R.string.waifu_merge_send),
                        subtitle = stringResource(R.string.waifu_merge_send_desc),
                        checked = enableMergeSend,
                        onCheckedChange = { enabled ->
                            saveSettings { waifuPreferences.saveWaifuEnableMergeSend(enabled) }
                        }
                    )
                    SliderBlock(
                        title = stringResource(R.string.waifu_merge_send_delay),
                        description = stringResource(R.string.waifu_merge_send_delay_desc),
                        valueText = stringResource(R.string.waifu_merge_send_delay_format, mergeSendDelayMs)
                    ) {
                        Slider(
                            value = mergeSendDelayMs.toFloat(),
                            onValueChange = { newDelay ->
                                saveSettings { waifuPreferences.saveWaifuMergeSendDelayMs(newDelay.toInt()) }
                            },
                            valueRange = 500f..10000f,
                            steps = 94,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 打字速度
            Column {
                val typingTitle = stringResource(R.string.typing_speed_settings)
                SettingsSectionTitle(
                    text = typingTitle,
                    icon = Icons.Outlined.Speed,
                    modifier = Modifier.fillMaxWidth().settingsSearchTarget(typingTitle)
                )
                SettingsCard(
                    backgroundColor = cardColor,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    val charsPerSecond = if (charDelay > 0) 1000f / charDelay else 0f
                    SliderBlock(
                        title = null,
                        description = stringResource(R.string.typing_speed_desc),
                        valueText = stringResource(R.string.current_speed_format, charsPerSecond)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.speed_fast),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = charDelay.toFloat(),
                                onValueChange = { newDelay ->
                                    saveSettings { waifuPreferences.saveWaifuCharDelay(newDelay.toInt()) }
                                },
                                valueRange = 200f..1000f, // 200ms-1000ms per character (5-1字符/秒)
                                steps = 39, // 20ms步长
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text(
                                text = stringResource(R.string.speed_slow),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(R.string.current_delay_format, charDelay),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }

            // Waifu 提示词
            Column {
                var customPromptText by remember { mutableStateOf(waifuCustomPrompt) }
                LaunchedEffect(waifuCustomPrompt) {
                    if (customPromptText != waifuCustomPrompt) {
                        customPromptText = waifuCustomPrompt
                    }
                }
                val promptTitle = stringResource(R.string.waifu_custom_prompt)
                SettingsSectionTitle(
                    text = promptTitle,
                    icon = Icons.Outlined.EditNote,
                    modifier = Modifier.fillMaxWidth().settingsSearchTarget(promptTitle)
                )
                SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
                    HintText(stringResource(R.string.waifu_custom_prompt_desc))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customPromptText,
                        onValueChange = { newText ->
                            customPromptText = newText
                            saveSettings { waifuPreferences.saveWaifuCustomPrompt(newText) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.waifu_custom_prompt_placeholder)) },
                        minLines = 4,
                        maxLines = 8,
                        shape = MaterialTheme.shapes.large
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HintText(stringResource(R.string.waifu_custom_prompt_tip))
                }
            }

            // 表情与自拍
            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.waifu_section_expression),
                    icon = Icons.Outlined.EmojiEmotions
                )
                SettingsCard(
                    backgroundColor = cardColor,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.enable_emoticons),
                        subtitle = stringResource(R.string.emoticons_desc) + "\n" +
                            stringResource(R.string.available_emoticons),
                        checked = enableEmoticons,
                        onCheckedChange = { enabled ->
                            saveSettings { waifuPreferences.saveWaifuEnableEmoticons(enabled) }
                        }
                    )
                    SettingsRowDivider(inset = false)
                    SettingsNavRow(
                        title = stringResource(R.string.manage_custom_emoji),
                        subtitle = stringResource(R.string.custom_emoji_desc),
                        onClick = onNavigateToCustomEmoji
                    )
                    SettingsRowDivider(inset = false)
                    SettingsSwitchRow(
                        title = stringResource(R.string.enable_selfie_feature),
                        subtitle = stringResource(R.string.selfie_feature_desc),
                        checked = enableSelfie,
                        onCheckedChange = { enabled ->
                            saveSettings { waifuPreferences.saveWaifuEnableSelfie(enabled) }
                        }
                    )
                    // 启用自拍后显示外貌提示词
                    if (enableSelfie) {
                        var promptText by remember { mutableStateOf(selfiePrompt) }
                        val appearanceTitle = stringResource(R.string.appearance_prompt)
                        Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp)) {
                            Text(
                                text = appearanceTitle,
                                modifier = Modifier.fillMaxWidth().settingsSearchTarget(appearanceTitle),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            HintText(stringResource(R.string.appearance_prompt_desc))
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = promptText,
                                onValueChange = { newText ->
                                    promptText = newText
                                    saveSettings { waifuPreferences.saveWaifuSelfiePrompt(newText) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.appearance_description_label)) },
                                placeholder = { Text(stringResource(R.string.appearance_prompt_placeholder)) },
                                minLines = 3,
                                maxLines = 6,
                                shape = MaterialTheme.shapes.large
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            HintText(stringResource(R.string.appearance_prompt_tip))
                        }
                    }
                }
            }

            // 功能说明
            Column {
                SettingsSectionTitle(
                    text = stringResource(R.string.feature_explanation),
                    icon = Icons.Outlined.Info
                )
                SettingsInfoBanner(
                    text = stringResource(R.string.waifu_mode_explanation),
                    backgroundColor = cardColor
                )
            }

            // 保存成功提示
            AnimatedVisibility(visible = showSaveSuccess) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.settings_saved_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** A slider setting inside a card: optional title, a description, the current value and the slider. */
@Composable
private fun SliderBlock(
    title: String?,
    description: String,
    valueText: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        HintText(description)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
