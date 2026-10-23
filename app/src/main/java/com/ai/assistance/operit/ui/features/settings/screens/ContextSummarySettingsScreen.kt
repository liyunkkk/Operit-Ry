package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.config.FunctionalPrompts
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.SummarySectionConfig
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.ui.components.CustomScaffold
import com.ai.assistance.operit.ui.features.settings.components.SettingsSearchFocus
import com.ai.assistance.operit.ui.features.settings.components.settingsSearchTarget
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
fun ContextSummarySettingsScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    val userPreferences = remember { UserPreferencesManager.getInstance(context) }
    val functionalConfigManager = remember { FunctionalConfigManager(context) }
    val modelConfigManager = remember { ModelConfigManager(context) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val hasBackgroundImage by userPreferences.useBackgroundImage.collectAsState(initial = false)
    val componentBackgroundColor =
        if (hasBackgroundImage) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        }

    val functionMappings by
        functionalConfigManager.functionConfigMappingWithIndexFlow.collectAsState(initial = emptyMap())
    val currentChatConfigMapping = functionMappings[FunctionType.CHAT] ?: FunctionConfigMapping()
    val currentChatConfigId = currentChatConfigMapping.configId

    val currentConfig by produceState<ModelConfigData?>(initialValue = null, key1 = currentChatConfigId) {
        if (currentChatConfigId.isBlank()) {
            value = null
            return@produceState
        }
        modelConfigManager.getModelConfigFlow(currentChatConfigId).collect { config ->
            value = config
        }
    }
    var summaryCustomRulesInput by remember(currentConfig?.id) {
        mutableStateOf(currentConfig?.summaryCustomRules.orEmpty())
    }
    var dialogueReviewEnabled by remember(currentConfig?.id) {
        mutableStateOf(currentConfig?.enableSummaryDialogueReview ?: true)
    }
    var dialogueReviewTitleInput by remember(currentConfig?.id) {
        mutableStateOf(currentConfig?.summaryDialogueReviewTitle.orEmpty())
    }
    val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
    var summarySectionsInput by remember(currentConfig?.id) {
        mutableStateOf(emptyList<SummarySectionConfig>())
    }
    var fullscreenTextEditor by remember(currentConfig?.id) {
        mutableStateOf<FullscreenTextEditorRequest?>(null)
    }
    var summaryError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentConfig?.id, currentConfig?.summaryCustomRules) {
        summaryCustomRulesInput = currentConfig?.summaryCustomRules.orEmpty()
    }
    LaunchedEffect(currentConfig?.id, currentConfig?.enableSummaryDialogueReview) {
        dialogueReviewEnabled = currentConfig?.enableSummaryDialogueReview ?: true
    }
    LaunchedEffect(currentConfig?.id, currentConfig?.summaryDialogueReviewTitle) {
        dialogueReviewTitleInput = currentConfig?.summaryDialogueReviewTitle.orEmpty()
    }
    LaunchedEffect(currentConfig?.id, currentConfig?.summarySectionOverrides, useEnglish) {
        summarySectionsInput = FunctionalPrompts.resolveSummarySections(
            currentConfig?.summarySectionOverrides.orEmpty(),
            useEnglish
        )
    }

    val errorSaveFailed = stringResource(id = R.string.model_config_error_save_failed)

    var showSaveSuccessMessage by remember { mutableStateOf(false) }

    val currentConfigDisplayName = remember(currentConfig, currentChatConfigId) {
        buildBoundConfigDisplayName(currentConfig, currentChatConfigId)
    }

    ContextSummarySectionsAutoSaveEffect(
        currentConfig = currentConfig,
        summarySectionsInputProvider = { summarySectionsInput },
        useEnglish = useEnglish,
        modelConfigManager = modelConfigManager,
        errorSaveFailed = errorSaveFailed,
        onSummaryErrorChange = { summaryError = it }
    )
    ContextSummaryDialogueReviewAutoSaveEffect(
        currentConfig = currentConfig,
        dialogueReviewInputProvider = { dialogueReviewEnabled to dialogueReviewTitleInput },
        modelConfigManager = modelConfigManager,
        errorSaveFailed = errorSaveFailed,
        onSummaryErrorChange = { summaryError = it }
    )

    // 自定义总结规则自动保存
    ContextSummaryCustomRulesAutoSaveEffect(
        currentConfig = currentConfig,
        summaryCustomRulesInputProvider = { summaryCustomRulesInput },
        modelConfigManager = modelConfigManager,
        errorSaveFailed = errorSaveFailed,
        onSummaryErrorChange = { summaryError = it }
    )

    val contextDefaults = remember { com.ai.assistance.operit.data.preferences.ChatContextPreferences(context) }
    val defaultPolicy by contextDefaults.defaults.collectAsState(initial = com.ai.assistance.operit.data.model.ChatContextPolicy())
    var savingDefaults by remember { mutableStateOf(false) }
    // A settings search for an option inside the folded section opens it, so the row can be shown.
    val advancedTitles = listOf(
        stringResource(id = R.string.settings_summary_custom_rules),
        stringResource(id = R.string.settings_summary_structure),
        stringResource(id = R.string.settings_summary_dialogue_review)
    )
    var advancedExpanded by rememberSaveable { mutableStateOf(SettingsSearchFocus.peek() in advancedTitles) }

    CustomScaffold() { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Column(
                modifier =
                    Modifier.fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .verticalScroll(scrollState)
            ) {
                SettingsInfoBanner(
                    text = stringResource(id = R.string.context_summary_note),
                    backgroundColor = componentBackgroundColor
                )
                Spacer(modifier = Modifier.size(20.dp))

                SectionTitle(text = stringResource(R.string.chat_context_defaults), icon = Icons.Default.Tune)
                SettingsCard(backgroundColor = componentBackgroundColor) {
                    com.ai.assistance.operit.ui.features.chat.components.ChatContextPolicyEditor(defaultPolicy, saving = savingDefaults, onSave = { policy ->
                        savingDefaults = true
                        scope.launch {
                            try { contextDefaults.saveDefaults(policy); showSaveSuccessMessage = true }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { summaryError = errorSaveFailed }
                            finally { savingDefaults = false }
                        }
                    })
                }
                summaryError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.size(20.dp))

                AdvancedSummaryHeader(
                    configDisplayName = currentConfigDisplayName,
                    expanded = advancedExpanded,
                    onExpandedChange = { advancedExpanded = it },
                    backgroundColor = componentBackgroundColor
                )
                AnimatedVisibility(visible = advancedExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = stringResource(id = R.string.context_summary_bound_config_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
                )
                RenderContextSummaryConfigSections(
                    componentBackgroundColor = componentBackgroundColor,
                    summaryCustomRulesInput = summaryCustomRulesInput,
                    onSummaryCustomRulesInputChange = {
                        summaryCustomRulesInput = it
                    },
                    dialogueReviewEnabled = dialogueReviewEnabled,
                    onDialogueReviewEnabledChange = { dialogueReviewEnabled = it },
                    dialogueReviewTitleInput = dialogueReviewTitleInput,
                    onDialogueReviewTitleChange = { dialogueReviewTitleInput = it },
                    summarySectionsInput = summarySectionsInput,
                    onSummarySectionsInputChange = { summarySectionsInput = it },
                    onOpenFullscreenEditor = { title, value, onValueChange ->
                        fullscreenTextEditor = FullscreenTextEditorRequest(
                            title = title,
                            value = value,
                            onValueChange = onValueChange
                        )
                    }
                )
                }
                }

                Spacer(modifier = Modifier.size(24.dp))
            }

            RenderContextSummaryDialogs(
                showSaveSuccessMessage = showSaveSuccessMessage,
                onDismissSaveSuccess = { showSaveSuccessMessage = false }
            )
            fullscreenTextEditor?.let { request ->
                FullscreenSettingsTextEditor(
                    request = request,
                    onDismiss = { fullscreenTextEditor = null }
                )
            }
        }
    }
}

@Composable
private fun ContextSummaryCustomRulesAutoSaveEffect(
    currentConfig: ModelConfigData?,
    summaryCustomRulesInputProvider: () -> String,
    modelConfigManager: ModelConfigManager,
    errorSaveFailed: String,
    onSummaryErrorChange: (String?) -> Unit
) {
    val latestConfig by rememberUpdatedState(currentConfig)

    LaunchedEffect(currentConfig?.id) {
        val configId = currentConfig?.id ?: return@LaunchedEffect
        snapshotFlow { summaryCustomRulesInputProvider() }
            .drop(1)
            .debounce(700)
            .distinctUntilChanged()
            .collectLatest { rulesText ->
                val current = latestConfig ?: return@collectLatest
                if (current.id != configId) return@collectLatest
                if (current.summaryCustomRules == rulesText) return@collectLatest
                try {
                    modelConfigManager.updateSummarySettings(
                        configId = current.id,
                        summaryCustomRules = rulesText
                    )
                    onSummaryErrorChange(null)
                } catch (e: Exception) {
                    onSummaryErrorChange(e.message ?: errorSaveFailed)
                }
            }
    }
}

@Composable
private fun ContextSummarySectionsAutoSaveEffect(
    currentConfig: ModelConfigData?,
    summarySectionsInputProvider: () -> List<SummarySectionConfig>,
    useEnglish: Boolean,
    modelConfigManager: ModelConfigManager,
    errorSaveFailed: String,
    onSummaryErrorChange: (String?) -> Unit
) {
    val latestConfig by rememberUpdatedState(currentConfig)

    LaunchedEffect(currentConfig?.id) {
        val configId = currentConfig?.id ?: return@LaunchedEffect
        snapshotFlow { summarySectionsInputProvider() }
            .drop(1)
            .debounce(700)
            .distinctUntilChanged()
            .collectLatest { sections ->
                val current = latestConfig ?: return@collectLatest
                if (current.id != configId) return@collectLatest
                val overrides =
                    FunctionalPrompts.buildSummarySectionOverrides(sections, useEnglish)
                if (current.summarySectionOverrides == overrides) return@collectLatest
                try {
                    modelConfigManager.updateSummarySettings(
                        configId = current.id,
                        summarySectionOverrides = overrides
                    )
                    onSummaryErrorChange(null)
                } catch (e: Exception) {
                    onSummaryErrorChange(e.message ?: errorSaveFailed)
                }
            }
    }
}

@Composable
private fun ContextSummaryDialogueReviewAutoSaveEffect(
    currentConfig: ModelConfigData?,
    dialogueReviewInputProvider: () -> Pair<Boolean, String>,
    modelConfigManager: ModelConfigManager,
    errorSaveFailed: String,
    onSummaryErrorChange: (String?) -> Unit
) {
    val latestConfig by rememberUpdatedState(currentConfig)

    LaunchedEffect(currentConfig?.id) {
        val configId = currentConfig?.id ?: return@LaunchedEffect
        snapshotFlow { dialogueReviewInputProvider() }
            .drop(1)
            .debounce(700)
            .distinctUntilChanged()
            .collectLatest { (enabled, title) ->
                val current = latestConfig ?: return@collectLatest
                if (current.id != configId) return@collectLatest
                val normalizedTitle = title.trim()
                if (current.enableSummaryDialogueReview == enabled &&
                    current.summaryDialogueReviewTitle == normalizedTitle
                ) {
                    return@collectLatest
                }
                try {
                    modelConfigManager.updateSummaryDialogueReviewSettings(
                        configId = current.id,
                        enabled = enabled,
                        title = normalizedTitle
                    )
                    onSummaryErrorChange(null)
                } catch (e: Exception) {
                    AppLogger.w("ContextSummarySettings", "保存对话回顾设置失败", e)
                    onSummaryErrorChange(e.message ?: errorSaveFailed)
                }
            }
    }
}

@Composable
private fun RenderContextSummaryConfigSections(
    componentBackgroundColor: Color,
    summaryCustomRulesInput: String,
    onSummaryCustomRulesInputChange: (String) -> Unit,
    dialogueReviewEnabled: Boolean,
    onDialogueReviewEnabledChange: (Boolean) -> Unit,
    dialogueReviewTitleInput: String,
    onDialogueReviewTitleChange: (String) -> Unit,
    summarySectionsInput: List<SummarySectionConfig>,
    onSummarySectionsInputChange: (List<SummarySectionConfig>) -> Unit,
    onOpenFullscreenEditor: (String, String, (String) -> Unit) -> Unit
) {
    val globalRulesTitle = stringResource(id = R.string.settings_summary_custom_rules)
    GlobalSummaryRulesEditor(
        title = globalRulesTitle,
        subtitle = stringResource(id = R.string.settings_summary_custom_rules_desc),
        backgroundColor = componentBackgroundColor,
        enabled = true,
        value = summaryCustomRulesInput,
        onValueChange = onSummaryCustomRulesInputChange,
        onOpenFullscreenEditor = onOpenFullscreenEditor
    )
    Spacer(modifier = Modifier.size(16.dp))
    SectionTitle(
        text = stringResource(id = R.string.settings_summary_structure),
        icon = Icons.Default.ViewAgenda
    )
    summarySectionsInput.forEachIndexed { index, section ->
        SummarySectionEditor(
            section = section,
            onSectionChange = { updatedSection ->
                onSummarySectionsInputChange(
                    summarySectionsInput.mapIndexed { currentIndex, currentSection ->
                        if (currentIndex == index) updatedSection else currentSection
                    }
                )
            },
            backgroundColor = componentBackgroundColor,
            enabled = true,
            onOpenFullscreenEditor = onOpenFullscreenEditor
        )
    }
    DialogueReviewEditor(
        enabled = dialogueReviewEnabled,
        onEnabledChange = onDialogueReviewEnabledChange,
        title = dialogueReviewTitleInput,
        onTitleChange = onDialogueReviewTitleChange,
        backgroundColor = componentBackgroundColor,
        summaryEnabled = true
    )
}

@Composable
private fun GlobalSummaryRulesEditor(
    title: String,
    subtitle: String,
    backgroundColor: Color,
    enabled: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onOpenFullscreenEditor: (String, String, (String) -> Unit) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SummaryEditorHeader(
        title = title,
        subtitle = subtitle,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        backgroundColor = backgroundColor,
        enabled = enabled
    )
    AnimatedVisibility(visible = expanded) {
        SettingsMultilineTextField(
            title = title,
            subtitle = subtitle,
            value = value,
            onValueChange = onValueChange,
            backgroundColor = backgroundColor,
            enabled = enabled,
            onOpenFullscreen = {
                onOpenFullscreenEditor(title, value, onValueChange)
            }
        )
    }
    Spacer(modifier = Modifier.size(8.dp))
}

@Composable
private fun SummarySectionEditor(
    section: SummarySectionConfig,
    onSectionChange: (SummarySectionConfig) -> Unit,
    backgroundColor: Color,
    enabled: Boolean,
    onOpenFullscreenEditor: (String, String, (String) -> Unit) -> Unit
) {
    var expanded by rememberSaveable(section.id) { mutableStateOf(false) }
    SummaryConfigEditorHeader(
        title = section.title,
        subtitle = stringResource(id = R.string.settings_summary_section_enabled_desc),
        checked = section.enabled,
        onCheckedChange = { onSectionChange(section.copy(enabled = it)) },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        backgroundColor = backgroundColor,
        enabled = enabled
    )
    AnimatedVisibility(visible = expanded) {
        Column {
            SettingsMultilineTextField(
                title = stringResource(id = R.string.settings_summary_section_title),
                subtitle = stringResource(id = R.string.settings_summary_section_title_desc),
                value = section.title,
                onValueChange = { onSectionChange(section.copy(title = it)) },
                backgroundColor = backgroundColor,
                enabled = enabled && section.enabled,
                singleLine = true,
                minHeight = 40.dp
            )
            SettingsMultilineTextField(
                title = stringResource(id = R.string.settings_summary_section_instruction),
                subtitle = stringResource(id = R.string.settings_summary_section_instruction_desc),
                value = section.instruction,
                onValueChange = { onSectionChange(section.copy(instruction = it)) },
                backgroundColor = backgroundColor,
                enabled = enabled && section.enabled,
                onOpenFullscreen = {
                    onOpenFullscreenEditor(section.title, section.instruction) { value ->
                        onSectionChange(section.copy(instruction = value))
                    }
                }
            )
        }
    }
    Spacer(modifier = Modifier.size(8.dp))
}

@Composable
private fun DialogueReviewEditor(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    backgroundColor: Color,
    summaryEnabled: Boolean
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SummaryConfigEditorHeader(
        title = stringResource(id = R.string.settings_summary_dialogue_review),
        subtitle = stringResource(id = R.string.settings_summary_dialogue_review_desc),
        checked = enabled,
        onCheckedChange = onEnabledChange,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        backgroundColor = backgroundColor,
        enabled = summaryEnabled
    )
    AnimatedVisibility(visible = expanded) {
        SettingsMultilineTextField(
            title = stringResource(id = R.string.settings_summary_dialogue_review_title),
            subtitle = stringResource(id = R.string.settings_summary_dialogue_review_title_desc),
            value = title,
            onValueChange = onTitleChange,
            backgroundColor = backgroundColor,
            enabled = summaryEnabled && enabled,
            singleLine = true,
            minHeight = 40.dp
        )
    }
    Spacer(modifier = Modifier.size(8.dp))
}

@Composable
private fun SummaryConfigEditorHeader(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    backgroundColor: Color,
    enabled: Boolean
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(backgroundColor)
                .settingsSearchTarget(title)
                .alpha(contentAlpha)
                .clickable(enabled = enabled) { onExpandedChange(!expanded) }
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        IconButton(onClick = { onExpandedChange(!expanded) }, enabled = enabled) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.model_config_collapse else R.string.model_config_expand
                )
            )
        }
    }
}

@Composable
private fun SummaryEditorHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    backgroundColor: Color,
    enabled: Boolean
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(backgroundColor)
                .settingsSearchTarget(title)
                .alpha(contentAlpha)
                .clickable(enabled = enabled) { onExpandedChange(!expanded) }
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { onExpandedChange(!expanded) }, enabled = enabled) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.model_config_collapse else R.string.model_config_expand
                )
            )
        }
    }
}

@Composable
private fun BoxScope.RenderContextSummaryDialogs(
    showSaveSuccessMessage: Boolean,
    onDismissSaveSuccess: () -> Unit
) {
    if (showSaveSuccessMessage) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            onDismissSaveSuccess()
        }
        Snackbar(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            action = {
                TextButton(onClick = onDismissSaveSuccess) {
                    Text(stringResource(id = android.R.string.ok))
                }
            }
        ) {
            Text(stringResource(id = R.string.settings_saved))
        }
    }
}

private fun buildBoundConfigDisplayName(config: ModelConfigData?, fallbackConfigId: String): String {
    val effectiveConfig = config ?: return fallbackConfigId.ifBlank { FunctionalConfigManager.DEFAULT_CONFIG_ID }
    return if (effectiveConfig.name.isBlank()) {
        effectiveConfig.id
    } else {
        effectiveConfig.name
    }
}

/** One rounded block of related settings; every group on this page sits in one. */
@Composable
private fun SettingsCard(backgroundColor: Color, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        tonalElevation = 1.dp
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

/** The folded summary format settings, named after the model configuration they belong to. */
@Composable
private fun AdvancedSummaryHeader(
    configDisplayName: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    backgroundColor: Color
) {
    val advancedTitle = stringResource(R.string.chat_context_advanced)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        tonalElevation = 1.dp,
        onClick = { onExpandedChange(!expanded) }
    ) {
        Row(
            modifier = Modifier
                .settingsSearchTarget(advancedTitle)
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Summarize,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = advancedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(id = R.string.context_summary_current_model_config, configDisplayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.model_config_collapse else R.string.model_config_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun SettingsInfoBanner(text: String, backgroundColor: Color) {
    // The tint sits on the page's card color, which is opaque over a background image, so the
    // note stays readable on any wallpaper.
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.settingsSearchTarget(text).padding(start = 4.dp, bottom = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backgroundColor: Color,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(backgroundColor)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(contentAlpha),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SettingsMultilineTextField(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    backgroundColor: Color,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minHeight: Dp = 80.dp,
    onOpenFullscreen: (() -> Unit)? = null
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(bottom = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(backgroundColor)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(contentAlpha)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = { newText ->
                if (enabled) {
                    onValueChange(newText)
                }
            },
            enabled = enabled,
            modifier =
                Modifier.fillMaxWidth()
                    .let { if (singleLine) it.heightIn(min = minHeight) else it.heightIn(min = 120.dp) }
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            textStyle =
                TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 5,
            maxLines = if (singleLine) 1 else 5,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
                ) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentAlignment =
                            if (singleLine) Alignment.CenterStart else Alignment.TopStart
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        innerTextField()
                    }
                    if (!singleLine && onOpenFullscreen != null) {
                        IconButton(
                            onClick = onOpenFullscreen,
                            enabled = enabled,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fullscreen,
                                contentDescription = stringResource(id = R.string.chat_fullscreen_input),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

private data class FullscreenTextEditorRequest(
    val title: String,
    val value: String,
    val onValueChange: (String) -> Unit
)

@Composable
private fun FullscreenSettingsTextEditor(
    request: FullscreenTextEditorRequest,
    onDismiss: () -> Unit
) {
    var editorValue by remember(request.title, request.value) { mutableStateOf(request.value) }
    fun finishEditing() {
        request.onValueChange(editorValue)
        onDismiss()
    }

    Dialog(
        onDismissRequest = { finishEditing() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize().imePadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { finishEditing() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(id = R.string.common_close)
                        )
                    }
                    Text(
                        text = request.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { finishEditing() }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(id = R.string.save)
                        )
                    }
                }
                HorizontalDivider()
                TextField(
                    value = editorValue,
                    onValueChange = { editorValue = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
