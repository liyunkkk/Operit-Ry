package com.ai.assistance.operit.ui.features.settings.sections

import com.ai.assistance.operit.ui.features.settings.components.SettingsSwitchRow
import com.ai.assistance.operit.ui.features.settings.components.settingsSearchTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.settings.components.SettingsCardShape
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.ColorSelectionItem
import com.ai.assistance.operit.ui.features.settings.components.ThemeModeOption
import com.ai.assistance.operit.ui.theme.getTextColorForBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class ThemeSettingsColorContentMode {
    PALETTE,
    INTERFACE,
}


@Composable
internal fun ThemeSettingsColorCustomizationSection(
    cardColors: CardColors,
    preferencesManager: UserPreferencesManager,
    scope: CoroutineScope,
    saveThemeSettingsWithCharacterCard: SaveThemeSettingsAction,
    statusBarHiddenInput: Boolean,
    onStatusBarHiddenInputChange: (Boolean) -> Unit,
    statusBarTransparentInput: Boolean,
    onStatusBarTransparentInputChange: (Boolean) -> Unit,
    useCustomStatusBarColorInput: Boolean,
    onUseCustomStatusBarColorInputChange: (Boolean) -> Unit,
    customStatusBarColorInput: Int,
    toolbarTransparentInput: Boolean,
    onToolbarTransparentInputChange: (Boolean) -> Unit,
    useCustomAppBarColorInput: Boolean,
    onUseCustomAppBarColorInputChange: (Boolean) -> Unit,
    customAppBarColorInput: Int,
    navigationDrawerWaterGlassInput: Boolean,
    onNavigationDrawerWaterGlassInputChange: (Boolean) -> Unit,
    navigationDrawerButtonLiquidGlassInput: Boolean,
    onNavigationDrawerButtonLiquidGlassInputChange: (Boolean) -> Unit,
    useCustomNavigationDrawerBackgroundColorInput: Boolean,
    onUseCustomNavigationDrawerBackgroundColorInputChange: (Boolean) -> Unit,
    navigationDrawerBackgroundColorInput: Int,
    useCustomNavigationDrawerAccentColorInput: Boolean,
    onUseCustomNavigationDrawerAccentColorInputChange: (Boolean) -> Unit,
    navigationDrawerAccentColorInput: Int,
    chatHeaderTransparentInput: Boolean,
    onChatHeaderTransparentInputChange: (Boolean) -> Unit,
    chatHeaderOverlayModeInput: Boolean,
    onChatHeaderOverlayModeInputChange: (Boolean) -> Unit,
    forceAppBarContentColorInput: Boolean,
    onForceAppBarContentColorInputChange: (Boolean) -> Unit,
    appBarContentColorModeInput: String,
    onAppBarContentColorModeInputChange: (String) -> Unit,
    chatHeaderHistoryIconColorInput: Int,
    chatHeaderPipIconColorInput: Int,
    useCustomColorsInput: Boolean,
    onUseCustomColorsInputChange: (Boolean) -> Unit,
    primaryColorInput: Int,
    secondaryColorInput: Int,
    onColorModeInput: String,
    onOnColorModeInputChange: (String) -> Unit,
    onShowColorPicker: (String) -> Unit,
    onShowSaveSuccessMessage: () -> Unit,
    contentMode: ThemeSettingsColorContentMode,
) {
    val showPaletteControls = contentMode == ThemeSettingsColorContentMode.PALETTE
    val showInterfaceControls = contentMode == ThemeSettingsColorContentMode.INTERFACE

    ThemeSettingsSectionTitle(
        title = stringResource(id = R.string.theme_title_color),
        icon = Icons.Default.ColorLens,
    )

    if (showInterfaceControls) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.theme_statusbar_color),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.settingsSearchTarget(stringResource(id = R.string.theme_statusbar_color)).padding(bottom = 8.dp),
            )

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_statusbar_hidden),
                subtitle = stringResource(id = R.string.theme_statusbar_hidden_desc),
                checked = statusBarHiddenInput,
                onCheckedChange = {
                    onStatusBarHiddenInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(statusBarHidden = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_statusbar_transparent),
                subtitle = stringResource(id = R.string.theme_statusbar_transparent_desc),
                checked = statusBarTransparentInput,
                enabled = !statusBarHiddenInput,
                onCheckedChange = {
                    onStatusBarTransparentInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(statusBarTransparent = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_use_custom_statusbar_color),
                subtitle = stringResource(id = R.string.theme_use_custom_statusbar_color_desc),
                checked = useCustomStatusBarColorInput,
                enabled = !statusBarTransparentInput && !statusBarHiddenInput,
                onCheckedChange = {
                    onUseCustomStatusBarColorInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(useCustomStatusBarColor = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            if (useCustomStatusBarColorInput) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ColorSelectionItem(
                    title = stringResource(id = R.string.theme_statusbar_color),
                    color = Color(customStatusBarColorInput),
                    enabled = !statusBarTransparentInput && !statusBarHiddenInput,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!statusBarTransparentInput && !statusBarHiddenInput) {
                            onShowColorPicker("statusBar")
                        }
                    },
                )
            }
        }
    }
    }

    if (showInterfaceControls) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.theme_toolbar_transparent),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_toolbar_transparent_desc),
                subtitle = stringResource(id = R.string.theme_toolbar_transparent_desc_desc),
                checked = toolbarTransparentInput,
                onCheckedChange = {
                    onToolbarTransparentInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(toolbarTransparent = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_use_custom_appbar_color),
                subtitle = stringResource(id = R.string.theme_use_custom_appbar_color_desc),
                checked = useCustomAppBarColorInput,
                enabled = !toolbarTransparentInput,
                onCheckedChange = {
                    onUseCustomAppBarColorInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(useCustomAppBarColor = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            if (useCustomAppBarColorInput && !toolbarTransparentInput) {
                ColorSelectionItem(
                    title = stringResource(id = R.string.theme_appbar_color),
                    color = Color(customAppBarColorInput),
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onShowColorPicker("appBar") },
                )
            }
        }
    }
    }

    if (showInterfaceControls) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.theme_navigation_drawer_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.settingsSearchTarget(stringResource(id = R.string.theme_navigation_drawer_title)).padding(bottom = 8.dp),
            )

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_navigation_drawer_water_glass),
                subtitle = stringResource(id = R.string.theme_navigation_drawer_water_glass_desc),
                checked = navigationDrawerWaterGlassInput,
                onCheckedChange = {
                    onNavigationDrawerWaterGlassInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            navigationDrawerWaterGlass = it,
                        )
                    }
                },
                horizontalPadding = 0.dp,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_navigation_drawer_button_liquid_glass),
                subtitle = stringResource(id = R.string.theme_navigation_drawer_button_liquid_glass_desc),
                checked = navigationDrawerButtonLiquidGlassInput,
                onCheckedChange = {
                    onNavigationDrawerButtonLiquidGlassInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            navigationDrawerButtonLiquidGlass = it,
                        )
                    }
                },
                horizontalPadding = 0.dp,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_use_custom_navigation_drawer_background_color),
                subtitle = stringResource(id = R.string.theme_use_custom_navigation_drawer_background_color_desc),
                checked = useCustomNavigationDrawerBackgroundColorInput,
                onCheckedChange = {
                    onUseCustomNavigationDrawerBackgroundColorInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            useCustomNavigationDrawerBackgroundColor = it,
                        )
                    }
                },
                horizontalPadding = 0.dp,
            )

            if (useCustomNavigationDrawerBackgroundColorInput) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ColorSelectionItem(
                    title = stringResource(id = R.string.theme_navigation_drawer_background_color),
                    color = Color(navigationDrawerBackgroundColorInput),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onShowColorPicker("navigationDrawerBackground") },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_use_custom_navigation_drawer_accent_color),
                subtitle = stringResource(id = R.string.theme_use_custom_navigation_drawer_accent_color_desc),
                checked = useCustomNavigationDrawerAccentColorInput,
                onCheckedChange = {
                    onUseCustomNavigationDrawerAccentColorInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(
                            useCustomNavigationDrawerAccentColor = it,
                        )
                    }
                },
                horizontalPadding = 0.dp,
            )

            if (useCustomNavigationDrawerAccentColorInput) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ColorSelectionItem(
                    title = stringResource(id = R.string.theme_navigation_drawer_accent_color),
                    color = Color(navigationDrawerAccentColorInput),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onShowColorPicker("navigationDrawerAccent") },
                )
            }
        }
    }
    }

    if (showInterfaceControls) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.theme_chat_header_transparent_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.settingsSearchTarget(stringResource(id = R.string.theme_chat_header_transparent_title)).padding(bottom = 8.dp),
            )
            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_chat_header_transparent),
                subtitle = stringResource(id = R.string.theme_chat_header_transparent_desc),
                checked = chatHeaderTransparentInput,
                onCheckedChange = {
                    onChatHeaderTransparentInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(chatHeaderTransparent = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            if (chatHeaderTransparentInput) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SettingsSwitchRow(
                    title = stringResource(id = R.string.theme_chat_header_overlay_mode),
                    subtitle = stringResource(id = R.string.theme_chat_header_overlay_mode_desc),
                    checked = chatHeaderOverlayModeInput,
                    onCheckedChange = {
                        onChatHeaderOverlayModeInputChange(it)
                        saveThemeSettingsWithCharacterCard {
                            preferencesManager.saveThemeSettings(chatHeaderOverlayMode = it)
                        }
                    },
                    horizontalPadding = 0.dp,
                )
            }
        }
    }
    }

    if (showInterfaceControls) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.theme_appbar_content_color_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_force_appbar_content_color),
                subtitle = stringResource(id = R.string.theme_force_appbar_content_color_desc),
                checked = forceAppBarContentColorInput,
                onCheckedChange = {
                    onForceAppBarContentColorInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(forceAppBarContentColor = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            if (forceAppBarContentColorInput) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = stringResource(id = R.string.theme_appbar_content_color_mode),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeModeOption(
                        title = stringResource(id = R.string.theme_appbar_content_color_light),
                        selected =
                            appBarContentColorModeInput ==
                                UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onAppBarContentColorModeInputChange(
                                UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT,
                            )
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(
                                    appBarContentColorMode =
                                        UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_LIGHT,
                                )
                            }
                        },
                    )
                    ThemeModeOption(
                        title = stringResource(id = R.string.theme_appbar_content_color_dark),
                        selected =
                            appBarContentColorModeInput ==
                                UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_DARK,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onAppBarContentColorModeInputChange(
                                UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_DARK,
                            )
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(
                                    appBarContentColorMode =
                                        UserPreferencesManager.APP_BAR_CONTENT_COLOR_MODE_DARK,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.theme_chat_header_icons_color_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ColorSelectionItem(
                title = stringResource(id = R.string.theme_chat_header_history_icon_color),
                color = Color(chatHeaderHistoryIconColorInput),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onShowColorPicker("historyIcon") },
            )
            Spacer(modifier = Modifier.height(8.dp))
            ColorSelectionItem(
                title = stringResource(id = R.string.theme_chat_header_pip_icon_color),
                color = Color(chatHeaderPipIconColorInput),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onShowColorPicker("pipIcon") },
            )
        }
    }
    }

    if (showPaletteControls) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = cardColors, shape = SettingsCardShape) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.theme_custom_color),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.settingsSearchTarget(stringResource(id = R.string.theme_custom_color)).padding(bottom = 8.dp),
            )

            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_use_custom_color),
                subtitle = stringResource(id = R.string.theme_custom_color_desc),
                checked = useCustomColorsInput,
                onCheckedChange = {
                    onUseCustomColorsInputChange(it)
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(useCustomColors = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            // Applies to the default palette as well as custom colors, so it sits outside the
            // custom-color block. Off by default: the soft Rainy tones stay as designed.
            val enforceColorContrast by
                preferencesManager.enforceColorContrast.collectAsState(initial = false)
            SettingsSwitchRow(
                title = stringResource(id = R.string.theme_enforce_contrast),
                subtitle = stringResource(id = R.string.theme_enforce_contrast_desc),
                checked = enforceColorContrast,
                onCheckedChange = {
                    saveThemeSettingsWithCharacterCard {
                        preferencesManager.saveThemeSettings(enforceColorContrast = it)
                    }
                },
                horizontalPadding = 0.dp,
            )

            if (useCustomColorsInput) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = stringResource(id = R.string.theme_select_color),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    ColorSelectionItem(
                        title = stringResource(id = R.string.theme_primary_color),
                        color = Color(primaryColorInput),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onShowColorPicker("primary") },
                    )

                    ColorSelectionItem(
                        title = stringResource(id = R.string.theme_secondary_color),
                        color = Color(secondaryColorInput),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onShowColorPicker("secondary") },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = stringResource(id = R.string.theme_on_color_mode),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeModeOption(
                        title = stringResource(id = R.string.theme_on_color_auto),
                        selected = onColorModeInput == UserPreferencesManager.ON_COLOR_MODE_AUTO,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOnColorModeInputChange(UserPreferencesManager.ON_COLOR_MODE_AUTO)
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(
                                    onColorMode = UserPreferencesManager.ON_COLOR_MODE_AUTO,
                                )
                            }
                        },
                    )
                    ThemeModeOption(
                        title = stringResource(id = R.string.theme_on_color_light),
                        selected = onColorModeInput == UserPreferencesManager.ON_COLOR_MODE_LIGHT,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOnColorModeInputChange(UserPreferencesManager.ON_COLOR_MODE_LIGHT)
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(
                                    onColorMode = UserPreferencesManager.ON_COLOR_MODE_LIGHT,
                                )
                            }
                        },
                    )
                    ThemeModeOption(
                        title = stringResource(id = R.string.theme_on_color_dark),
                        selected = onColorModeInput == UserPreferencesManager.ON_COLOR_MODE_DARK,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOnColorModeInputChange(UserPreferencesManager.ON_COLOR_MODE_DARK)
                            saveThemeSettingsWithCharacterCard {
                                preferencesManager.saveThemeSettings(
                                    onColorMode = UserPreferencesManager.ON_COLOR_MODE_DARK,
                                )
                            }
                        },
                    )
                }

                Text(
                    text = stringResource(id = R.string.theme_preview),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        val primaryColor = Color(primaryColorInput)
                        val onPrimaryColor = getTextColorForBackground(primaryColor)

                        Surface(
                            modifier =
                                Modifier.weight(1f).height(40.dp).padding(end = 8.dp),
                            color = primaryColor,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(id = R.string.theme_primary_button),
                                    color = onPrimaryColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        val secondaryColor = Color(secondaryColorInput)
                        val onSecondaryColor = getTextColorForBackground(secondaryColor)

                        Surface(
                            modifier = Modifier.weight(1f).height(40.dp),
                            color = secondaryColor,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(id = R.string.theme_secondary_button),
                                    color = onSecondaryColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(id = R.string.theme_contrast_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            preferencesManager.saveThemeSettings(
                                customPrimaryColor = primaryColorInput,
                                customSecondaryColor = secondaryColorInput,
                            )
                            onShowSaveSuccessMessage()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(stringResource(id = R.string.theme_save_colors))
                }
            }
        }
    }
}

}
