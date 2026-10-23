package com.ai.assistance.operit.ui.features.settings.screens.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.settings.components.SettingsCardShape
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.ChatStyleOption
import com.ai.assistance.operit.ui.features.settings.components.SettingsSwitchRow
import com.ai.assistance.operit.ui.features.settings.sections.ThemeSettingsSectionTitle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Opacity

@Composable
internal fun ThemeSettingsInputTab(
    shared: ThemeSettingsShared,
    cardColors: CardColors,
    onShowSaveSuccessMessage: () -> Unit,
) {
    val preferencesManager = shared.preferencesManager
    val inputStyle by preferencesManager.inputStyle.collectAsState(
        initial = UserPreferencesManager.INPUT_STYLE_CLASSIC,
    )
    val chatInputTransparent by preferencesManager.chatInputTransparent.collectAsState(initial = false)
    val chatInputFloating by preferencesManager.chatInputFloating.collectAsState(initial = true)
    val chatInputLiquidGlass by preferencesManager.chatInputLiquidGlass.collectAsState(initial = false)
    val chatInputWaterGlass by preferencesManager.chatInputWaterGlass.collectAsState(initial = false)
    var inputStyleInput by remember { mutableStateOf(inputStyle) }
    var chatInputTransparentInput by remember { mutableStateOf(chatInputTransparent) }
    var chatInputFloatingInput by remember { mutableStateOf(chatInputFloating) }
    var chatInputLiquidGlassInput by remember { mutableStateOf(chatInputLiquidGlass) }
    var chatInputWaterGlassInput by remember { mutableStateOf(chatInputWaterGlass) }

    LaunchedEffect(
        inputStyle,
        chatInputTransparent,
        chatInputFloating,
        chatInputLiquidGlass,
        chatInputWaterGlass,
    ) {
        inputStyleInput = inputStyle
        chatInputTransparentInput = chatInputTransparent
        chatInputFloatingInput = chatInputFloating
        chatInputLiquidGlassInput = chatInputLiquidGlass
        chatInputWaterGlassInput = chatInputWaterGlass
    }

    ThemeSettingsSectionTitle(
        title = stringResource(id = R.string.input_style_title),
        icon = Icons.Default.Keyboard,
    )
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.input_style_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChatStyleOption(
                    title = stringResource(id = R.string.input_style_classic),
                    selected = inputStyleInput == UserPreferencesManager.INPUT_STYLE_CLASSIC,
                    modifier = Modifier.weight(1f),
                ) {
                    inputStyleInput = UserPreferencesManager.INPUT_STYLE_CLASSIC
                    shared.saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            inputStyle = UserPreferencesManager.INPUT_STYLE_CLASSIC,
                        )
                    }
                    onShowSaveSuccessMessage()
                }

                ChatStyleOption(
                    title = stringResource(id = R.string.input_style_agent),
                    selected = inputStyleInput == UserPreferencesManager.INPUT_STYLE_AGENT,
                    modifier = Modifier.weight(1f),
                ) {
                    inputStyleInput = UserPreferencesManager.INPUT_STYLE_AGENT
                    shared.saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            inputStyle = UserPreferencesManager.INPUT_STYLE_AGENT,
                        )
                    }
                    onShowSaveSuccessMessage()
                }
            }
        }
    }

    ThemeSettingsSectionTitle(
        title = stringResource(id = R.string.theme_chat_input_transparent_title),
        icon = Icons.Default.Opacity,
    )
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_chat_input_transparent),
                subtitle = stringResource(id = R.string.theme_chat_input_transparent_desc),
                checked = chatInputTransparentInput,
                onCheckedChange = {
                    chatInputTransparentInput = it
                    shared.saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(chatInputTransparent = it)
                    }
                    onShowSaveSuccessMessage()
                },
                horizontalPadding = 0.dp,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_chat_input_floating),
                subtitle = stringResource(id = R.string.theme_chat_input_floating_desc),
                checked = chatInputFloatingInput,
                onCheckedChange = {
                    chatInputFloatingInput = it
                    shared.saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(chatInputFloating = it)
                    }
                    onShowSaveSuccessMessage()
                },
                horizontalPadding = 0.dp,
            )

            if (chatInputTransparentInput) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsSwitchRow(
                    title = stringResource(id = R.string.theme_chat_input_liquid_glass),
                    subtitle = stringResource(id = R.string.theme_chat_input_liquid_glass_desc),
                    checked = chatInputLiquidGlassInput,
                    onCheckedChange = {
                        chatInputLiquidGlassInput = it
                        if (it) {
                            chatInputWaterGlassInput = false
                        }
                        shared.saveThemeSettingsWithCharacterCard {
                            preferencesManager.saveThemeSettings(
                                chatInputLiquidGlass = it,
                                chatInputWaterGlass = if (it) false else null,
                            )
                        }
                        onShowSaveSuccessMessage()
                    },
                    horizontalPadding = 0.dp,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsSwitchRow(
                    title = stringResource(id = R.string.theme_chat_input_water_glass),
                    subtitle = stringResource(id = R.string.theme_chat_input_water_glass_desc),
                    checked = chatInputWaterGlassInput,
                    onCheckedChange = {
                        chatInputWaterGlassInput = it
                        if (it) {
                            chatInputLiquidGlassInput = false
                        }
                        shared.saveThemeSettingsWithCharacterCard {
                            preferencesManager.saveThemeSettings(
                                chatInputWaterGlass = it,
                                chatInputLiquidGlass = if (it) false else null,
                            )
                        }
                        onShowSaveSuccessMessage()
                    },
                    horizontalPadding = 0.dp,
                )
            }
        }
    }
}
