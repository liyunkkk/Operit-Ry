package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.ui.components.CustomScaffold
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.ai.assistance.operit.util.AssetCopyUtils
import com.ai.assistance.operit.api.chat.llmprovider.AIServiceFactory
import com.ai.assistance.operit.api.chat.llmprovider.withFunctionalReasoning
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkBuilder
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.chat.hooks.toPromptTurns
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.forSelectedModel
import com.ai.assistance.operit.data.model.getModelByIndex
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.getValidModelIndex
import com.ai.assistance.operit.data.model.multimodalCapabilitiesForModel
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.FunctionConfigMapping
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.core.config.FunctionalPrompts
import com.ai.assistance.operit.core.tools.PermissionReviewSubmissionTool
import com.ai.assistance.operit.ui.permissions.PermissionReviewResponsePolicy
import com.ai.assistance.operit.ui.permissions.PermissionReviewPolicyStore
import com.ai.assistance.operit.ui.permissions.PermissionRiskScorer
import com.ai.assistance.operit.ui.permissions.parsePermissionRiskVerdict
import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.util.MediaPoolManager
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.thinkingQualityLevelLabelRes
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsSectionTitle
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.ui.features.settings.components.settingsSearchTarget
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FunctionalConfigScreen(
        onBackPressed: () -> Unit = {},
        onNavigateToModelConfig: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 配置管理器
    val functionalConfigManager = remember { FunctionalConfigManager(context) }
    val modelConfigManager = remember { ModelConfigManager(context) }

    // 配置映射状态
    val configMapping =
            functionalConfigManager.functionConfigMappingFlow.collectAsState(initial = emptyMap())
    val configMappingWithIndex =
            functionalConfigManager.functionConfigMappingWithIndexFlow.collectAsState(initial = emptyMap())

    // 配置摘要列表
    val configSummaries by modelConfigManager.configSummariesFlow.collectAsState(initial = emptyList())

    // UI状态
    var isLoading by remember { mutableStateOf(true) }
    var showSaveSuccess by remember { mutableStateOf(false) }
    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage.collectAsState(initial = false)
    val cardColor = settingsCardColor(hasBackgroundImage)

    // 加载配置摘要
    LaunchedEffect(Unit) {
        isLoading = true
        functionalConfigManager.initializeIfNeeded()
        isLoading = false
    }

    CustomScaffold() { paddingValues ->
        if (isLoading) {
            Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                    modifier =
                            Modifier.fillMaxSize()
                                    .padding(paddingValues),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
            ) {
                item {
                    SettingsInfoBanner(
                            text = stringResource(id = R.string.functional_model_config_desc),
                            backgroundColor = cardColor
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingsCard(
                            backgroundColor = cardColor,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        SettingsNavRow(
                                title = stringResource(id = R.string.manage_all_model_configs),
                                icon = Icons.Default.Settings,
                                searchable = false,
                                onClick = onNavigateToModelConfig
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsSectionTitle(
                            text = stringResource(id = R.string.functional_model_config_title),
                            icon = Icons.Default.Tune
                    )
                }

                // 功能类型列表
                items(FunctionType.values().filter { it != FunctionType.UI_CONTROLLER }) { functionType ->
                    val currentConfigMapping =
                            configMappingWithIndex.value[functionType]
                                    ?: FunctionConfigMapping(FunctionalConfigManager.DEFAULT_CONFIG_ID, 0)
                    val currentConfig = configSummaries.find { it.id == currentConfigMapping.configId }

                    FunctionConfigCard(
                            functionType = functionType,
                            cardColor = cardColor,
                            currentConfig = currentConfig,
                            currentModelIndex = currentConfigMapping.modelIndex,
                            selectedModelName = currentConfigMapping.modelName,
                            currentThinkingQualityLevel =
                                currentConfigMapping.thinkingQualityLevel,
                            availableConfigs = configSummaries,
                            onConfigSelected = { configId, modelIndex ->
                                scope.launch {
                                    functionalConfigManager.setConfigForFunction(
                                            functionType,
                                            configId,
                                            modelIndex
                                    )
                                    // 刷新服务实例
                                    EnhancedAIService.refreshServiceForFunction(
                                            context,
                                            functionType
                                    )
                                    showSaveSuccess = true
                                }
                            },
                            onThinkingQualitySelected = { qualityLevel ->
                                scope.launch {
                                    functionalConfigManager.setThinkingQualityForFunction(
                                        functionType,
                                        qualityLevel,
                                    )
                                    EnhancedAIService.refreshServiceForFunction(
                                        context,
                                        functionType,
                                    )
                                    showSaveSuccess = true
                                }
                            }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    // 重置按钮
                    OutlinedButton(
                            onClick = {
                                scope.launch {
                                    functionalConfigManager.resetAllFunctionConfigs()
                                    // 刷新所有服务实例
                                    EnhancedAIService.refreshAllServices(context)
                                    showSaveSuccess = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                                    .settingsSearchTarget(stringResource(id = R.string.reset_all_functions_to_default)),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(id = R.string.reset_all_functions_to_default))
                    }

                    // 成功提示
                    androidx.compose.animation.AnimatedVisibility(
                            visible = showSaveSuccess,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                    ) {
                        Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                    text = stringResource(id = R.string.config_saved),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        LaunchedEffect(showSaveSuccess) {
                            kotlinx.coroutines.delay(2000)
                            showSaveSuccess = false
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FunctionConfigCard(
        functionType: FunctionType,
        cardColor: androidx.compose.ui.graphics.Color,
        currentConfig: ModelConfigSummary?,
        currentModelIndex: Int,
        selectedModelName: String?,
        currentThinkingQualityLevel: Int,
        availableConfigs: List<ModelConfigSummary>,
        onConfigSelected: (String, Int) -> Unit,
        onThinkingQualitySelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedConfigId by remember { mutableStateOf<String?>(null) } // 记录当前展开的配置的模型列表
    var pendingReasoningLevel by
        remember(functionType, currentThinkingQualityLevel) {
            mutableFloatStateOf(
                currentThinkingQualityLevel
                    .coerceIn(
                        ApiPreferences.MIN_THINKING_QUALITY_LEVEL,
                        ApiPreferences.MAX_THINKING_QUALITY_LEVEL,
                    )
                    .toFloat()
            )
        }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelConfigManager = remember { ModelConfigManager(context) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<String>?>(null) }
    val selectionKey = Triple(currentConfig, currentModelIndex, currentThinkingQualityLevel)
    val latestSelectionKey by rememberUpdatedState(selectionKey)
    LaunchedEffect(selectionKey) { testResult = null }

    var mediaSupportWarningResId by remember { mutableStateOf<Int?>(null) }
    val selectionAvailable = currentConfig != null &&
        (currentModelIndex in getModelList(currentConfig.modelName).indices ||
            (currentModelIndex == 0 && currentConfig.modelName.isBlank() &&
                com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry.get(currentConfig.apiProviderTypeId) != null))

    val permissionReviewerTestInvalidMessage =
        stringResource(R.string.functional_config_permission_reviewer_test_invalid)
    val permissionReviewerTestSuccessTemplate =
        stringResource(R.string.functional_config_permission_reviewer_test_success)
    val riskScorerTestInvalidMessage =
        stringResource(R.string.functional_config_risk_scorer_test_invalid)
    val riskScorerTestSuccessTemplate =
        stringResource(R.string.functional_config_risk_scorer_test_success)

    LaunchedEffect(functionType, currentConfig, currentModelIndex) {
        mediaSupportWarningResId = null
        if (!selectionAvailable) return@LaunchedEffect
        if (
            functionType != FunctionType.IMAGE_RECOGNITION &&
            functionType != FunctionType.AUDIO_RECOGNITION &&
            functionType != FunctionType.VIDEO_RECOGNITION
        ) {
            return@LaunchedEffect
        }

        val configId = currentConfig?.id ?: FunctionalConfigManager.DEFAULT_CONFIG_ID
        val fullConfig: ModelConfigData = try {
            modelConfigManager.getModelConfigFlow(configId).first()
        } catch (e: Exception) {
            return@LaunchedEffect
        }

        val multimodalCapabilities =
            fullConfig.multimodalCapabilitiesForModel(currentModelIndex)
        val isSupported = when (functionType) {
            FunctionType.IMAGE_RECOGNITION -> multimodalCapabilities.image
            FunctionType.AUDIO_RECOGNITION -> multimodalCapabilities.audio
            FunctionType.VIDEO_RECOGNITION -> multimodalCapabilities.video
            else -> true
        }

        if (!isSupported) {
            mediaSupportWarningResId = when (functionType) {
                FunctionType.IMAGE_RECOGNITION -> R.string.functional_config_warning_image_unsupported
                FunctionType.AUDIO_RECOGNITION -> R.string.functional_config_warning_audio_unsupported
                FunctionType.VIDEO_RECOGNITION -> R.string.functional_config_warning_video_unsupported
                else -> null
            }
        }
    }

    val showAutoGlmError: () -> Unit = {
        if (functionType == FunctionType.CHAT) {
            Toast.makeText(
                context,
                context.getString(R.string.chat_autoglm_warning),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(testResult) {
        if (testResult != null) {
            kotlinx.coroutines.delay(5000)
            testResult = null
        }
    }

    SettingsCard(
            backgroundColor = cardColor,
            modifier = Modifier.settingsSearchTarget(getFunctionDisplayName(functionType)),
            contentPadding = PaddingValues(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 功能标题和描述
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = getFunctionDisplayName(functionType),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                )

                Text(
                        text = getFunctionDescription(functionType),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 当前配置
                Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { expanded = !expanded },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
                ) {
                    Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                    text = stringResource(
                                        id = R.string.current_config_label,
                                        currentConfig?.name ?: stringResource(id = R.string.functional_model_reselect)
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                            )

                            if (currentConfig != null || !selectedModelName.isNullOrBlank()) {
                                val displayModel = selectedModelName
                                    ?: currentConfig?.let {
                                        getModelList(it.modelName).getOrNull(currentModelIndex)
                                    }.orEmpty()
                                Text(
                                        text = stringResource(id = R.string.model_label, displayModel),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!selectionAvailable) {
                                Text(
                                    text = stringResource(R.string.functional_model_selection_unavailable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            androidx.compose.animation.AnimatedVisibility(
                                visible = mediaSupportWarningResId != null
                            ) {
                                val warningResId = mediaSupportWarningResId
                                if (warningResId != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(id = warningResId),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            
                            // 测试结果显示
                            androidx.compose.animation.AnimatedVisibility(
                                visible = testResult != null,
                                enter = fadeIn() + slideInHorizontally(),
                                exit = fadeOut() + slideOutHorizontally()
                            ) {
                                testResult?.let { result ->
                                    val isSuccess = result.isSuccess
                                    val message =
                                            if (isSuccess) result.getOrNull() ?: stringResource(id = R.string.test_connection_success)
                                            else stringResource(id = R.string.test_connection_failed, result.exceptionOrNull()?.message?.take(30) ?: "")
                                    val color =
                                            if (isSuccess) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                    val icon =
                                            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Icon(
                                                icon,
                                                contentDescription = null,
                                                tint = color,
                                                modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                                message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = color,
                                                maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 测试按钮
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val testedSelectionKey = selectionKey
                                        isTestingConnection = true
                                        testResult = null
                                        val cleanupTasks = mutableListOf<() -> Unit>()
                                        try {
                                            val configWithSelectedModel =
                                                EnhancedAIService.getInstance(context)
                                                    .getModelConfigForFunction(functionType)

                                            val baseService =
                                                    AIServiceFactory.createService(
                                                            config = configWithSelectedModel,
                                                            modelConfigManager = modelConfigManager,
                                                            context = context
                                                    )
                                            val service =
                                                if (functionType == FunctionType.CHAT) {
                                                    baseService
                                                } else {
                                                    baseService.withFunctionalReasoning(
                                                        config = configWithSelectedModel,
                                                        thinkingQualityLevel =
                                                            currentThinkingQualityLevel,
                                                    )
                                                }

                                            val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
                                            val result = when (functionType) {
                                                FunctionType.SUMMARY -> {
                                                    val enhancedService = EnhancedAIService.getInstance(context)
                                                    val sampleMessages =
                                                        listOf(
                                                            "user" to "Connection test: summarize this short dialog.",
                                                            "assistant" to "Sure, I can help with summaries."
                                                        )
                                                    enhancedService.generateSummary(sampleMessages)
                                                }
                                                FunctionType.TITLE_GENERATION -> {
                                                    val enhancedService = EnhancedAIService.getInstance(context)
                                                    enhancedService.generateConversationTitle(
                                                        userText = context.getString(R.string.functional_config_test_title_generation_user_text)
                                                    )
                                                }
                                                FunctionType.TRANSLATION -> {
                                                    val enhancedService = EnhancedAIService.getInstance(context)
                                                    enhancedService.translateText("Connection test: translate me.")
                                                }
                                                FunctionType.IMAGE_RECOGNITION -> {
                                                    val imageFile = AssetCopyUtils.copyAssetToCache(context, "test/1.jpg")
                                                    val imageId = ImagePoolManager.addImage(imageFile.absolutePath)
                                                    if (imageId == "error") {
                                                        throw IllegalStateException("Failed to create test image")
                                                    }
                                                    cleanupTasks.add {
                                                        ImagePoolManager.removeImage(imageId)
                                                        runCatching { imageFile.delete() }
                                                    }
                                                    val prompt =
                                                        buildString {
                                                            append(MediaLinkBuilder.image(context, imageId))
                                                            append("\n")
                                                            append(context.getString(R.string.conversation_analyze_image_prompt))
                                                        }
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    )
                                                        .collect { chunk -> buffer.append(chunk) }
                                                    buffer.toString()
                                                }
                                                FunctionType.AUDIO_RECOGNITION -> {
                                                    val audioFile = AssetCopyUtils.copyAssetToCache(context, "test/1.mp3")
                                                    val audioId = MediaPoolManager.addMedia(audioFile.absolutePath, "audio/mpeg")
                                                    if (audioId == "error") {
                                                        throw IllegalStateException("Failed to create test audio")
                                                    }
                                                    cleanupTasks.add {
                                                        MediaPoolManager.removeMedia(audioId)
                                                        runCatching { audioFile.delete() }
                                                    }
                                                    val prompt =
                                                        buildString {
                                                            append(MediaLinkBuilder.audio(context, audioId))
                                                            append("\n")
                                                            append(context.getString(R.string.conversation_analyze_audio_prompt))
                                                        }
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    )
                                                        .collect { chunk -> buffer.append(chunk) }
                                                    buffer.toString()
                                                }
                                                FunctionType.VIDEO_RECOGNITION -> {
                                                    val videoFile = AssetCopyUtils.copyAssetToCache(context, "test/1.mp4")
                                                    val videoId = MediaPoolManager.addMedia(videoFile.absolutePath, "video/mp4")
                                                    if (videoId == "error") {
                                                        throw IllegalStateException("Failed to create test video")
                                                    }
                                                    cleanupTasks.add {
                                                        MediaPoolManager.removeMedia(videoId)
                                                        runCatching { videoFile.delete() }
                                                    }
                                                    val prompt =
                                                        buildString {
                                                            append(MediaLinkBuilder.video(context, videoId))
                                                            append("\n")
                                                            append(context.getString(R.string.conversation_analyze_video_prompt))
                                                        }
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    )
                                                        .collect { chunk -> buffer.append(chunk) }
                                                    buffer.toString()
                                                }
                                                FunctionType.UI_CONTROLLER -> {
                                                    val systemPrompt = FunctionalPrompts.uiControllerPrompt(useEnglish)
                                                    val userPrompt =
                                                        context.getString(R.string.functional_config_test_ui_controller_prompt)
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        listOf(
                                                            PromptTurn(kind = PromptTurnKind.SYSTEM, content = systemPrompt),
                                                            PromptTurn(kind = PromptTurnKind.USER, content = userPrompt)
                                                        ),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    ).collect { chunk -> buffer.append(chunk) }
                                                    buffer.toString()
                                                }
                                                FunctionType.MEMORY -> {
                                                    val existingMemoriesPrompt =
                                                        FunctionalPrompts.knowledgeGraphNoExistingMemoriesMessage(useEnglish)
                                                    val existingFoldersPrompt =
                                                        FunctionalPrompts.knowledgeGraphExistingFoldersPrompt(emptyList(), useEnglish)
                                                    val systemPrompt =
                                                        FunctionalPrompts.buildKnowledgeGraphExtractionPrompt(
                                                            duplicatesPromptPart = "",
                                                            existingMemoriesPrompt = existingMemoriesPrompt,
                                                            existingFoldersPrompt = existingFoldersPrompt,
                                                            useEnglish = useEnglish
                                                        )
                                                    val userPrompt =
                                                        context.getString(R.string.functional_config_test_memory_prompt)
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        listOf(
                                                            PromptTurn(kind = PromptTurnKind.SYSTEM, content = systemPrompt),
                                                            PromptTurn(kind = PromptTurnKind.USER, content = userPrompt)
                                                        ),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    ).collect { chunk -> buffer.append(chunk) }
                                                    buffer.toString()
                                                }
                                                FunctionType.CHAT -> {
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        listOf(PromptTurn(kind = PromptTurnKind.USER, content = "Hi")),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    )
                                                        .collect { chunk -> buffer.append(chunk) }
                                                    buffer.toString()
                                                }
                                                FunctionType.ROLE_RESPONSE_PLANNER -> {
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val prompt =
                                                        "Connection test: return {\"order\":[{\"id\":\"test\",\"speak\":true}]} only."
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    )
                                                        .collect { chunk -> buffer.append(chunk) }
                                                    buffer.toString()
                                                }
                                                FunctionType.PERMISSION_REVIEWER -> {
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val compatibilityReviewId = "compat-test-review-id"
                                                    val prompt =
                                                        "Compatibility test: call ${PermissionReviewSubmissionTool.NAME} " +
                                                            "exactly once with review_id=$compatibilityReviewId, " +
                                                            "outcome=allow, risk_level=low, " +
                                                            "user_authorization=low, and a concise rationale. " +
                                                            "Echo review_id=$compatibilityReviewId unchanged. " +
                                                            "Do not call any other tool."
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        availableTools =
                                                            listOf(PermissionReviewSubmissionTool.prompt),
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    )
                                                        .collect { chunk -> buffer.append(chunk) }
                                                    val decision =
                                                        PermissionReviewResponsePolicy.extractToolCallAndEnforce(
                                                            buffer.toString(),
                                                            expectedReviewId = compatibilityReviewId,
                                                        )
                                                            ?: error(
                                                                permissionReviewerTestInvalidMessage
                                                            )
                                                    permissionReviewerTestSuccessTemplate.format(
                                                        decision.outcome.name.lowercase(),
                                                        decision.riskLevel.name.lowercase(),
                                                        decision.userAuthorization.name.lowercase(),
                                                    )
                                                }
                                                FunctionType.PERMISSION_RISK_SCORER -> {
                                                    val parameters =
                                                        modelConfigManager.getModelParametersForConfig(configWithSelectedModel.id)
                                                    val buffer = StringBuilder()
                                                    service.sendMessage(
                                                        context,
                                                        PermissionRiskScorer.buildSelfTestTurns(
                                                            PermissionReviewPolicyStore(context)
                                                                .getSnapshot()
                                                                .text
                                                        ),
                                                        parameters,
                                                        stream = false,
                                                        enableRetry = false,
                                                        statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.CONNECTION_TEST
                                                    )
                                                        .collect { chunk -> buffer.append(chunk) }
                                                    val verdict =
                                                        parsePermissionRiskVerdict(buffer.toString())
                                                            ?: error(riskScorerTestInvalidMessage)
                                                    riskScorerTestSuccessTemplate.format(
                                                        verdict.name.lowercase()
                                                    )
                                                }
                                            }
                                            if (testedSelectionKey == latestSelectionKey) {
                                                testResult = Result.success(result)
                                            }
                                        } catch (e: Exception) {
                                            if (testedSelectionKey == latestSelectionKey) {
                                                testResult = Result.failure(e)
                                            }
                                        } finally {
                                            cleanupTasks.forEach { task -> runCatching { task() } }
                                        }
                                        isTestingConnection = false
                                    }
                                },
                                enabled = selectionAvailable && !isTestingConnection,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                            Icons.Default.Dns,
                                            contentDescription = stringResource(id = R.string.test_connection_desc),
                                            modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(id = R.string.test), style = MaterialTheme.typography.bodySmall)
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Icon(
                                    imageVector =
                                            if (expanded) Icons.Default.KeyboardArrowUp
                                            else Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(id = R.string.expand_desc),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (functionType == FunctionType.CHAT) {
                    Text(
                        text = stringResource(R.string.functional_reasoning_chat_managed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val selectedReasoningLevel = pendingReasoningLevel.roundToInt()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.functional_reasoning_intensity),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = stringResource(thinkingQualityLevelLabelRes(selectedReasoningLevel)),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = pendingReasoningLevel,
                            onValueChange = { value ->
                                pendingReasoningLevel = value.roundToInt().toFloat()
                            },
                            onValueChangeFinished = {
                                onThinkingQualitySelected(pendingReasoningLevel.roundToInt())
                            },
                            valueRange =
                                ApiPreferences.MIN_THINKING_QUALITY_LEVEL.toFloat()..
                                    ApiPreferences.MAX_THINKING_QUALITY_LEVEL.toFloat(),
                            steps =
                                ApiPreferences.MAX_THINKING_QUALITY_LEVEL -
                                    ApiPreferences.MIN_THINKING_QUALITY_LEVEL -
                                    1,
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            (
                                ApiPreferences.MIN_THINKING_QUALITY_LEVEL..
                                    ApiPreferences.MAX_THINKING_QUALITY_LEVEL
                            ).forEach { level ->
                                Text(
                                    text = stringResource(thinkingQualityLevelLabelRes(level)),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                        if (level == selectedReasoningLevel) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.functional_reasoning_intensity_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 配置列表
            Box(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                                text = stringResource(id = R.string.select_config),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 8.dp)
                        )

                        availableConfigs.forEach { config ->
                            val isSelected =
                                    config.id ==
                                            (currentConfig?.id
                                                    ?: FunctionalConfigManager.DEFAULT_CONFIG_ID)
                            val modelList = getModelList(config.modelName)
                            val hasMultipleModels = modelList.size > 1
                            val isExpanded = expandedConfigId == config.id

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                        modifier =
                                                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                                    if (hasMultipleModels) {
                                                        // 如果有多个模型，切换展开状态
                                                        expandedConfigId = if (isExpanded) null else config.id
                                                    } else {
                                                        // 如果只有一个模型，直接选择
                                                        val singleModelName = modelList.firstOrNull().orEmpty()
                                                        if (functionType == FunctionType.CHAT && singleModelName.contains("autoglm", ignoreCase = true)) {
                                                            showAutoGlmError()
                                                        } else {
                                                            onConfigSelected(config.id, 0)
                                                            expanded = false
                                                        }
                                                    }
                                                },
                                        shape = RoundedCornerShape(8.dp),
                                        color =
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surface,
                                        border =
                                                BorderStroke(
                                                        width = if (isSelected) 0.dp else 0.5.dp,
                                                        color =
                                                                if (isSelected)
                                                                        MaterialTheme.colorScheme.primary
                                                                else
                                                                        MaterialTheme.colorScheme
                                                                                .outlineVariant.copy(
                                                                                alpha = 0.5f
                                                                        )
                                                )
                                ) {
                                    Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSelected && !hasMultipleModels) {
                                            Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = stringResource(id = R.string.selected_desc),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                    text = config.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight =
                                                            if (isSelected) FontWeight.Bold
                                                            else FontWeight.Normal,
                                                    color =
                                                            if (isSelected)
                                                                    MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.onSurface
                                            )

                                            if (hasMultipleModels) {
                                                Text(
                                                        text = stringResource(R.string.functional_config_model_count, modelList.size),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            } else {
                                                Text(
                                                        text = config.modelName,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        
                                        if (hasMultipleModels) {
                                            Icon(
                                                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                
                                // 如果有多个模型且已展开，显示模型列表
                                androidx.compose.animation.AnimatedVisibility(visible = hasMultipleModels && isExpanded) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
                                    ) {
                                        // 计算有效索引一次，避免重复计算
                                        val validIndex = currentModelIndex
                                        modelList.forEachIndexed { index, modelName ->
                                            val isModelSelected = isSelected && validIndex == index
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp)
                                                    .clickable {
                                                        if (functionType == FunctionType.CHAT && modelName.contains("autoglm", ignoreCase = true)) {
                                                            showAutoGlmError()
                                                        } else {
                                                            onConfigSelected(config.id, index)
                                                            expanded = false
                                                            expandedConfigId = null
                                                        }
                                                    },
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isModelSelected)
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    if (isModelSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = stringResource(id = R.string.selected_desc),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                    }
                                                    Text(
                                                        text = modelName,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isModelSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isModelSelected)
                                                            MaterialTheme.colorScheme.primary
                                                        else
                                                            MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f)
            )
        }
    }
}

// 获取功能类型的显示名称
@Composable
fun getFunctionDisplayName(functionType: FunctionType): String {
    return when (functionType) {
        FunctionType.CHAT -> stringResource(id = R.string.function_type_chat)
        FunctionType.SUMMARY -> stringResource(id = R.string.function_type_summary)
        FunctionType.TITLE_GENERATION -> stringResource(id = R.string.function_type_title_generation)
        FunctionType.MEMORY -> stringResource(id = R.string.function_type_memory)
        FunctionType.UI_CONTROLLER -> stringResource(id = R.string.function_type_ui_controller)
        FunctionType.TRANSLATION -> stringResource(id = R.string.function_type_translation)
        FunctionType.ROLE_RESPONSE_PLANNER -> stringResource(id = R.string.function_type_role_response_planner)
        FunctionType.PERMISSION_REVIEWER -> stringResource(id = R.string.function_type_permission_reviewer)
        FunctionType.PERMISSION_RISK_SCORER ->
            stringResource(id = R.string.function_type_permission_risk_scorer)
        FunctionType.IMAGE_RECOGNITION -> stringResource(id = R.string.function_type_image_recognition)
        FunctionType.AUDIO_RECOGNITION -> stringResource(id = R.string.function_type_audio_recognition)
        FunctionType.VIDEO_RECOGNITION -> stringResource(id = R.string.function_type_video_recognition)
    }
}

// 获取功能类型的描述
@Composable
fun getFunctionDescription(functionType: FunctionType): String {
    return  when (functionType) {
        FunctionType.CHAT -> stringResource(id = R.string.function_desc_chat)
        FunctionType.SUMMARY -> stringResource(id = R.string.function_desc_summary)
        FunctionType.TITLE_GENERATION -> stringResource(id = R.string.function_desc_title_generation)
        FunctionType.MEMORY -> stringResource(id = R.string.function_desc_memory)
        FunctionType.UI_CONTROLLER -> stringResource(id = R.string.function_desc_ui_controller)
        FunctionType.TRANSLATION -> stringResource(id = R.string.function_desc_translation)
        FunctionType.ROLE_RESPONSE_PLANNER -> stringResource(id = R.string.function_desc_role_response_planner)
        FunctionType.PERMISSION_REVIEWER -> stringResource(id = R.string.function_desc_permission_reviewer)
        FunctionType.PERMISSION_RISK_SCORER ->
            stringResource(id = R.string.function_desc_permission_risk_scorer)
        FunctionType.IMAGE_RECOGNITION -> stringResource(id = R.string.function_desc_image_recognition)
        FunctionType.AUDIO_RECOGNITION -> stringResource(id = R.string.function_desc_audio_recognition)
        FunctionType.VIDEO_RECOGNITION -> stringResource(id = R.string.function_desc_video_recognition)
    }
}
