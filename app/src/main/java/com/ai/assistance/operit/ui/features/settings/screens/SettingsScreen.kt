package com.ai.assistance.operit.ui.features.settings.screens

import android.view.WindowManager
import com.ai.assistance.operit.ui.main.components.LocalIsCurrentScreen
import com.ai.assistance.operit.ui.main.components.LocalSetScreenSoftInputMode
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.standard.CookiePrivacyManager
import com.ai.assistance.operit.core.tools.skill.SkillManager
import com.ai.assistance.operit.data.mcp.MCPRepository
import com.ai.assistance.operit.data.preferences.LegacyStoragePreferences
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.repository.WorkflowRepository
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.SettingsSearchFocus
import com.ai.assistance.operit.ui.features.settings.components.SettingsSectionTitle
import com.ai.assistance.operit.ui.features.settings.components.SettingsSwitchRow
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 保存滑动状态变量，使其跨重组保持
private val SettingsScreenScrollPosition = mutableStateOf(0)

/** A row on the settings home page; also what the search box looks through. */
private data class SettingsEntry(
        val title: String,
        val subtitle: String,
        val icon: ImageVector,
        val onClick: () -> Unit = {},
        /** Set for an on/off switch shown in place of a link. */
        val toggle: SettingsToggle? = null,
        /** Extra words that should find this row, such as its group name. */
        val keywords: String = "",
        /** Deletes data: shown in red and without the "open page" arrow. */
        val destructive: Boolean = false,
        /** An option inside a sub page: found by its own name, not just by its page name. */
        val isOption: Boolean = false
)

private data class SettingsToggle(
        val checked: Boolean,
        val enabled: Boolean,
        val onCheckedChange: (Boolean) -> Unit
)

private data class SettingsGroup(
        val title: String,
        val icon: ImageVector,
        val entries: List<SettingsEntry>,
        val note: String? = null
)

@Composable
fun SettingsScreen(
        onNavigateToUserPreferences: () -> Unit,
        navigateToToolPermissions: () -> Unit,
        navigateToModelConfig: () -> Unit,
        navigateToAgentProfiles: () -> Unit,
        navigateToThemeSettings: () -> Unit,
        navigateToGlobalDisplaySettings: () -> Unit,
        navigateToModelPrompts: () -> Unit,
        navigateToFunctionalConfig: () -> Unit,
        navigateToChatHistorySettings: () -> Unit,
        navigateToChatBackupSettings: () -> Unit,
        navigateToLanguageSettings: () -> Unit,
        navigateToSpeechServicesSettings: () -> Unit,
        navigateToComponents: () -> Unit,
        navigateToStorage: () -> Unit,
        navigateToExternalHttpChatSettings: () -> Unit,
        navigateToPersonaCardGeneration: () -> Unit,
        navigateToWaifuModeSettings: () -> Unit,
        navigateToTokenUsageStatistics: () -> Unit,
        navigateToContextSummarySettings: () -> Unit,
        navigateToLayoutAdjustmentSettings: () -> Unit
) {
        val context = LocalContext.current
        val userPreferences = remember { UserPreferencesManager.getInstance(context) }
        val legacyStorage = remember { LegacyStoragePreferences.getInstance(context) }
        val skillManager = remember { SkillManager.getInstance(context) }
        val mcpRepository = remember { MCPRepository(context.applicationContext) }
        val workflowRepository = remember { WorkflowRepository(context.applicationContext) }
        val scope = rememberCoroutineScope()
        var showClearCookieConfirm by remember { mutableStateOf(false) }

        // The search bar lifts itself above the keyboard (imePadding). Stop the window from also
        // panning the whole page up, which pushed the bar far above the keyboard.
        val isCurrentScreen = LocalIsCurrentScreen.current
        val setScreenSoftInputMode = LocalSetScreenSoftInputMode.current
        SideEffect {
                if (isCurrentScreen) {
                        setScreenSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
        }

        val readLegacySkills = legacyStorage.readLegacySkillsFlow().collectAsState(initial = false).value
        val readLegacyMcp = legacyStorage.readLegacyMcpFlow().collectAsState(initial = false).value
        val readLegacyWorkflows = legacyStorage.readLegacyWorkflowsFlow().collectAsState(initial = false).value
        val legacyStorageUpdateFailedMessage =
                stringResource(R.string.settings_legacy_storage_update_failed)
        val clearCookiesSuccessMessage = stringResource(R.string.clear_cookies_success)
        val clearCookiesFailedMessage = stringResource(R.string.clear_cookies_failed)
        var legacySwitchUpdate by remember { mutableStateOf<String?>(null) }

        // 创建和记住滚动状态，设置为上次保存的位置
        val scrollState = rememberScrollState(SettingsScreenScrollPosition.value)
        LaunchedEffect(scrollState) {
                snapshotFlow { scrollState.value }.collect { position ->
                        SettingsScreenScrollPosition.value = position
                }
        }

        val hasBackgroundImage = userPreferences.useBackgroundImage.collectAsState(initial = false).value
        val cardColor = settingsCardColor(hasBackgroundImage)

        fun updateLegacySwitch(key: String, logMessage: String, action: suspend () -> Unit) {
                scope.launch {
                        legacySwitchUpdate = key
                        try {
                                action()
                        } catch (e: Exception) {
                                AppLogger.e("SettingsScreen", logMessage, e)
                                Toast.makeText(context, legacyStorageUpdateFailedMessage, Toast.LENGTH_SHORT).show()
                        } finally {
                                legacySwitchUpdate = null
                        }
                }
        }
        val legacyEnabled = legacySwitchUpdate == null

        val groups = listOf(
                SettingsGroup(
                        title = stringResource(R.string.settings_section_personalization),
                        icon = Icons.Default.Person,
                        entries = listOf(
                                SettingsEntry(stringResource(R.string.settings_user_preferences), stringResource(R.string.settings_user_preferences_subtitle), Icons.Default.Face, onNavigateToUserPreferences),
                                SettingsEntry(stringResource(R.string.language_settings), stringResource(R.string.settings_language_subtitle), Icons.Default.Language, navigateToLanguageSettings),
                                SettingsEntry(stringResource(R.string.settings_theme_appearance), stringResource(R.string.settings_theme_subtitle), Icons.Default.Palette, navigateToThemeSettings),
                                SettingsEntry(stringResource(R.string.settings_global_display), stringResource(R.string.settings_global_display_subtitle), Icons.Default.Visibility, navigateToGlobalDisplaySettings),
                                SettingsEntry(stringResource(R.string.layout_adjustment), stringResource(R.string.layout_adjustment_subtitle), Icons.Default.AspectRatio, navigateToLayoutAdjustmentSettings)
                        )
                ),
                SettingsGroup(
                        title = stringResource(R.string.settings_section_ai_model),
                        icon = Icons.Default.Settings,
                        entries = listOf(
                                SettingsEntry(stringResource(R.string.settings_model_parameters), stringResource(R.string.settings_model_params_subtitle), Icons.Default.Api, navigateToModelConfig),
                                SettingsEntry(stringResource(R.string.settings_agent_profiles), stringResource(R.string.settings_agent_profiles_subtitle), Icons.Default.AccountTree, navigateToAgentProfiles),
                                SettingsEntry(stringResource(R.string.settings_functional_model), stringResource(R.string.settings_functional_model_subtitle), Icons.Default.Tune, navigateToFunctionalConfig),
                                SettingsEntry(stringResource(R.string.settings_section_context_summary), stringResource(R.string.settings_context_summary_subtitle), Icons.Default.Summarize, navigateToContextSummarySettings),
                                SettingsEntry(stringResource(R.string.settings_speech_services), stringResource(R.string.settings_speech_services_subtitle), Icons.Default.RecordVoiceOver, navigateToSpeechServicesSettings)
                        )
                ),
                SettingsGroup(
                        title = stringResource(R.string.settings_prompt_section),
                        icon = Icons.Default.Message,
                        entries = listOf(
                                SettingsEntry(stringResource(R.string.settings_prompt_title), stringResource(R.string.settings_system_prompts_subtitle), Icons.Default.ChatBubble, navigateToModelPrompts),
                                SettingsEntry(stringResource(R.string.persona_card_generation), stringResource(R.string.persona_card_generation_desc), Icons.Default.Face, navigateToPersonaCardGeneration),
                                SettingsEntry(stringResource(R.string.waifu_mode_settings), stringResource(R.string.waifu_mode_settings_desc), Icons.Default.EmojiEmotions, navigateToWaifuModeSettings)
                        )
                ),
                SettingsGroup(
                        title = stringResource(R.string.settings_data_permissions),
                        icon = Icons.Default.Security,
                        entries = listOf(
                                SettingsEntry(stringResource(R.string.components_title), stringResource(R.string.components_subtitle), Icons.Default.Extension, navigateToComponents),
                                SettingsEntry(stringResource(R.string.storage_title), stringResource(R.string.storage_subtitle), Icons.Default.Storage, navigateToStorage),
                                SettingsEntry(stringResource(R.string.settings_tool_permissions), stringResource(R.string.settings_tool_permissions_subtitle), Icons.Default.AdminPanelSettings, navigateToToolPermissions),
                                SettingsEntry(stringResource(R.string.settings_data_backup), stringResource(R.string.settings_data_backup_desc), Icons.Default.CloudUpload, navigateToChatBackupSettings),
                                SettingsEntry(stringResource(R.string.settings_chat_history_management), stringResource(R.string.settings_chat_history_management_subtitle), Icons.Default.ManageHistory, navigateToChatHistorySettings),
                                SettingsEntry(stringResource(R.string.settings_token_usage_stats), stringResource(R.string.settings_token_usage_subtitle), Icons.Default.Analytics, navigateToTokenUsageStatistics)
                        )
                ),
                SettingsGroup(
                        title = stringResource(R.string.settings_legacy_storage_title),
                        icon = Icons.Default.FolderOpen,
                        note = stringResource(R.string.settings_legacy_storage_description),
                        entries = listOf(
                                SettingsEntry(
                                        stringResource(R.string.settings_legacy_skills_title),
                                        stringResource(R.string.settings_legacy_skills_description),
                                        Icons.Default.FolderOpen,
                                        toggle = SettingsToggle(readLegacySkills, legacyEnabled) { enabled ->
                                                updateLegacySwitch("skills", "Failed to update legacy Skills switch") {
                                                        legacyStorage.setReadLegacySkills(enabled)
                                                        withContext(Dispatchers.IO) { skillManager.refreshAvailableSkills() }
                                                }
                                        }
                                ),
                                SettingsEntry(
                                        stringResource(R.string.settings_legacy_mcp_title),
                                        stringResource(R.string.settings_legacy_mcp_description),
                                        Icons.Default.FolderOpen,
                                        toggle = SettingsToggle(readLegacyMcp, legacyEnabled) { enabled ->
                                                updateLegacySwitch("mcp", "Failed to update legacy MCP switch") {
                                                        legacyStorage.setReadLegacyMcp(enabled)
                                                        mcpRepository.onLegacyReadSwitchChanged(enabled)
                                                }
                                        }
                                ),
                                SettingsEntry(
                                        stringResource(R.string.settings_legacy_workflows_title),
                                        stringResource(R.string.settings_legacy_workflows_description),
                                        Icons.Default.FolderOpen,
                                        toggle = SettingsToggle(readLegacyWorkflows, legacyEnabled) { enabled ->
                                                updateLegacySwitch("workflows", "Failed to update legacy workflow switch") {
                                                        legacyStorage.setReadLegacyWorkflows(enabled)
                                                        workflowRepository.onLegacyReadSwitchChanged(enabled)
                                                }
                                        }
                                )
                        )
                ),
                SettingsGroup(
                        title = stringResource(R.string.settings_privacy_data_cleanup),
                        icon = Icons.Default.DeleteSweep,
                        entries = listOf(
                                SettingsEntry(stringResource(R.string.settings_clear_cookies), stringResource(R.string.settings_clear_cookies_subtitle), Icons.Default.DeleteSweep, { showClearCookieConfirm = true }, destructive = true)
                        )
                ),
                SettingsGroup(
                        title = stringResource(R.string.settings_section_external_calls),
                        icon = Icons.Default.SettingsEthernet,
                        entries = listOf(
                                SettingsEntry(stringResource(R.string.settings_external_http_chat), stringResource(R.string.settings_external_http_chat_subtitle), Icons.Default.SettingsEthernet, navigateToExternalHttpChatSettings)
                        )
                )
        )

        // Options inside sub pages, so a search for "帧率" finds the page that holds it.
        val pageEntries = groups.flatMap { it.entries }
        fun page(title: String) = pageEntries.first { it.title == title }
        val optionEntries = settingsOptionEntries(pageOf = ::page)

        var query by rememberSaveable { mutableStateOf("") }
        val focusManager = LocalFocusManager.current
        val searching = query.isNotBlank()
        BackHandler(enabled = searching) {
                query = ""
                focusManager.clearFocus()
        }

        Box(modifier = Modifier.fillMaxSize()) {
                if (searching) {
                        val results = searchSettings(
                                query,
                                groups.flatMap { group -> group.entries.map { it.copy(keywords = group.title) } } + optionEntries
                        )
                        SettingsSearchResults(
                                results = results,
                                cardColor = cardColor,
                                onOpen = { entry ->
                                        focusManager.clearFocus()
                                        entry.onClick()
                                }
                        )
                } else {
                        Column(
                                modifier = Modifier.fillMaxSize()
                                        .verticalScroll(scrollState)
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 8.dp, bottom = SearchBarClearance),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                                groups.forEach { group ->
                                        Column {
                                                SettingsSectionTitle(text = group.title, icon = group.icon)
                                                if (group.note != null) {
                                                        SettingsInfoBanner(
                                                                text = group.note,
                                                                backgroundColor = cardColor,
                                                                modifier = Modifier.padding(bottom = 8.dp)
                                                        )
                                                }
                                                SettingsEntriesCard(entries = group.entries, cardColor = cardColor)
                                        }
                                }
                        }
                }

                SettingsSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        onClear = {
                                query = ""
                                focusManager.clearFocus()
                        },
                        onDone = { focusManager.clearFocus() },
                        modifier = Modifier.align(Alignment.BottomCenter)
                )
        }

        if (showClearCookieConfirm) {
                AlertDialog(
                        onDismissRequest = { showClearCookieConfirm = false },
                        title = { Text(stringResource(R.string.clear_cookies_dialog_title)) },
                        text = { Text(stringResource(R.string.clear_cookies_dialog_message)) },
                        confirmButton = {
                                TextButton(
                                        onClick = {
                                                showClearCookieConfirm = false
                                                scope.launch {
                                                        try {
                                                                CookiePrivacyManager.clearAllCookies()
                                                                Toast.makeText(context, clearCookiesSuccessMessage, Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                                AppLogger.e("SettingsScreen", "Failed to clear cookies", e)
                                                                Toast.makeText(context, clearCookiesFailedMessage, Toast.LENGTH_SHORT).show()
                                                        }
                                                }
                                        }
                                ) {
                                        Text(stringResource(R.string.clear_cookies_confirm))
                                }
                        },
                        dismissButton = {
                                TextButton(onClick = { showClearCookieConfirm = false }) {
                                        Text(stringResource(android.R.string.cancel))
                                }
                        }
                )
        }
}

/** Space left under the list so the floating search bar never covers the last row. */
private val SearchBarClearance = 88.dp

@Composable
private fun settingsOptionEntries(pageOf: (String) -> SettingsEntry): List<SettingsEntry> {
        fun option(page: SettingsEntry, title: String) =
                SettingsEntry(
                        title = title,
                        subtitle = page.title,
                        icon = page.icon,
                        // Remember the option so the opened page scrolls to it and flashes it.
                        onClick = {
                                SettingsSearchFocus.request(title)
                                page.onClick()
                        },
                        keywords = page.keywords,
                        isOption = true
                )

        @Composable
        fun page(titleRes: Int) = pageOf(stringResource(titleRes))

        return listOf(
                page(R.string.settings_global_display) to listOf(
                        R.string.collapse_completed_process_title,
                        R.string.tool_collapse_mode_title,
                        R.string.global_user_name,
                        R.string.show_fps_counter,
                        R.string.enable_reply_notification,
                        R.string.enable_reply_notification_sound,
                        R.string.enable_reply_notification_vibration,
                        R.string.enable_enter_to_send,
                        R.string.composer_predictions,
                        R.string.keep_screen_on,
                        R.string.enable_navigation_animation,
                        R.string.start_with_new_chat,
                        R.string.ui_accessibility_mode,
                        R.string.enable_background_keep_alive,
                        R.string.experimental_virtual_display,
                        R.string.hide_runtime_task_view,
                        R.string.visit_web_wait_time_title,
                        R.string.global_display_virtual_screen_bitrate,
                        R.string.global_display_status_indicator_style,
                        R.string.global_display_image_format,
                        R.string.global_display_image_quality,
                        R.string.global_display_resolution_scale,
                        R.string.root_execution_mode_title
                ),
                page(R.string.layout_adjustment) to listOf(
                        R.string.chat_settings_button_end_padding,
                        R.string.chat_area_horizontal_padding,
                        R.string.global_text_line_height_multiplier,
                        R.string.global_text_letter_spacing,
                        R.string.global_text_paragraph_spacing
                ),
                page(R.string.settings_section_context_summary) to listOf(
                        R.string.max_mode_title,
                        R.string.chat_context_budget,
                        R.string.chat_context_auto_compact,
                        R.string.chat_context_advanced,
                        R.string.settings_summary_custom_rules,
                        R.string.settings_summary_structure,
                        R.string.settings_summary_dialogue_review
                ),
                page(R.string.settings_data_backup) to listOf(
                        R.string.backup_room_db_title,
                        R.string.backup_room_db_enable_daily,
                        R.string.backup_raw_snapshot_title,
                        R.string.backup_official_operit_migration_action,
                        R.string.stats_quarantine_title
                ),
                page(R.string.settings_chat_history_management) to listOf(
                        R.string.character_card_statistics,
                        R.string.chat_history_batch_operations_title,
                        R.string.chat_memory_rebuild_title,
                        R.string.unbound_workspaces_title
                ),
                page(R.string.settings_theme_appearance) to listOf(
                        R.string.theme_title_mode,
                        R.string.theme_custom_color,
                        R.string.theme_enforce_contrast,
                        R.string.theme_font_settings,
                        R.string.avatar_customization_title,
                        R.string.avatar_shape_title,
                        R.string.input_style_title,
                        R.string.theme_chat_input_transparent_title,
                        R.string.chat_style_title,
                        R.string.chat_style_bubble_color_title,
                        R.string.chat_style_bubble_user_image_title,
                        R.string.chat_style_bubble_ai_image_title,
                        R.string.display_options_title,
                        R.string.show_thinking_process,
                        R.string.show_model_name,
                        R.string.show_user_name,
                        R.string.theme_title_color,
                        R.string.theme_statusbar_color,
                        R.string.theme_navigation_drawer_title,
                        R.string.theme_chat_header_transparent_title,
                        R.string.theme_title_background,
                        R.string.theme_background_blur,
                        R.string.theme_reset
                ),
                page(R.string.settings_functional_model) to listOf(
                        R.string.function_type_chat,
                        R.string.function_type_summary,
                        R.string.function_type_title_generation,
                        R.string.function_type_memory,
                        R.string.function_type_translation,
                        R.string.function_type_permission_reviewer,
                        R.string.function_type_image_recognition,
                        R.string.function_type_audio_recognition,
                        R.string.function_type_video_recognition,
                        R.string.reset_all_functions_to_default
                ),
                page(R.string.settings_agent_profiles) to listOf(
                        R.string.agent_profile_add,
                        R.string.agent_profile_model_binding
                ),
                page(R.string.settings_speech_services) to listOf(
                        R.string.speech_services_tts_engine,
                        R.string.speech_services_stt_engine,
                        R.string.speech_services_tts_profiles,
                        R.string.speech_services_stt_profiles,
                        R.string.speech_services_tts_cleaner_title,
                        R.string.speech_services_test_tts
                ),
                page(R.string.waifu_mode_settings) to listOf(
                        R.string.enable_waifu_mode,
                        R.string.waifu_merge_send,
                        R.string.typing_speed_settings,
                        R.string.remove_punctuation,
                        R.string.waifu_custom_prompt,
                        R.string.enable_emoticons,
                        R.string.manage_custom_emoji,
                        R.string.enable_selfie_feature,
                        R.string.appearance_prompt
                ),
                page(R.string.settings_tool_permissions) to listOf(
                        R.string.global_permission_switch,
                        R.string.permission_review_policy_title,
                        R.string.permission_level_auto_review,
                        R.string.add_tool
                ),
                page(R.string.settings_external_http_chat) to listOf(
                        R.string.external_http_chat_enable,
                        R.string.external_http_chat_port,
                        R.string.external_http_chat_token,
                        R.string.external_http_chat_web_title
                )
        ).flatMap { (page, titles) -> titles.map { option(page, stringResource(it)) } }
}

/**
 * Every word of the query must appear in the title or subtitle; title hits come first.
 * An option also needs a word in its own name, so "主题" does not list every option on the theme page.
 */
private fun searchSettings(query: String, entries: List<SettingsEntry>): List<SettingsEntry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries
                .filter { entry ->
                        val text = (entry.title + " " + entry.subtitle + " " + entry.keywords).lowercase()
                        val title = entry.title.lowercase()
                        words.all { text.contains(it) } && (!entry.isOption || words.any { title.contains(it) })
                }
                .distinctBy { it.title to it.subtitle }
                .sortedBy { entry ->
                        val title = entry.title.lowercase()
                        when {
                                words.all { title.contains(it) } -> 0
                                else -> 1
                        }
                }
}

@Composable
private fun SettingsEntriesCard(entries: List<SettingsEntry>, cardColor: androidx.compose.ui.graphics.Color) {
        SettingsCard(
                backgroundColor = cardColor,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
                entries.forEachIndexed { index, entry ->
                        if (index > 0) SettingsRowDivider(inset = entry.toggle == null)
                        val toggle = entry.toggle
                        if (toggle != null) {
                                SettingsSwitchRow(
                                        title = entry.title,
                                        subtitle = entry.subtitle,
                                        checked = toggle.checked,
                                        enabled = toggle.enabled,
                                        onCheckedChange = toggle.onCheckedChange,
                                        searchable = false
                                )
                        } else {
                                SettingsNavRow(
                                        title = entry.title,
                                        subtitle = entry.subtitle,
                                        icon = entry.icon,
                                        onClick = entry.onClick,
                                        destructive = entry.destructive,
                                        searchable = false
                                )
                        }
                }
        }
}

@Composable
private fun SettingsSearchResults(
        results: List<SettingsEntry>,
        cardColor: androidx.compose.ui.graphics.Color,
        onOpen: (SettingsEntry) -> Unit
) {
        Column(
                modifier = Modifier.fillMaxSize()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = SearchBarClearance)
        ) {
                if (results.isEmpty()) {
                        Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 72.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Icon(
                                        imageVector = Icons.Default.SearchOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                        text = stringResource(R.string.settings_search_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }
                } else {
                        SettingsSectionTitle(
                                text = stringResource(R.string.settings_search_results, results.size),
                                icon = Icons.Default.Search
                        )
                        SettingsEntriesCard(
                                entries = results.map { entry ->
                                        if (entry.toggle != null) entry else entry.copy(onClick = { onOpen(entry) })
                                },
                                cardColor = cardColor
                        )
                }
        }
}

@Composable
private fun SettingsSearchBar(
        query: String,
        onQueryChange: (String) -> Unit,
        onClear: () -> Unit,
        onDone: () -> Unit,
        modifier: Modifier = Modifier
) {
        Surface(
                modifier = modifier
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp
        ) {
                Row(
                        modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                        Text(
                                                text = stringResource(R.string.settings_search_hint),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }
                                BasicTextField(
                                        value = query,
                                        onValueChange = onQueryChange,
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { onDone() }),
                                        modifier = Modifier.fillMaxWidth()
                                )
                        }
                        if (query.isNotEmpty()) {
                                IconButton(onClick = onClear) {
                                        Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(R.string.clear_search),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }
                        } else {
                                Spacer(modifier = Modifier.width(14.dp))
                        }
                }
        }
}
