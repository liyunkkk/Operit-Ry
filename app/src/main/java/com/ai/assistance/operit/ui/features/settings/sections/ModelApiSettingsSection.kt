package com.ai.assistance.operit.ui.features.settings.sections

import android.annotation.SuppressLint
import com.ai.assistance.operit.ui.features.settings.components.rememberSettingsCardColor
import com.ai.assistance.operit.ui.features.settings.components.SettingsCardShape
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.VisualTransformation
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.llmprovider.EndpointCompleter
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.llmprovider.AIServiceFactory
import com.ai.assistance.operit.api.chat.llmprovider.CodexModelListFetcher
import com.ai.assistance.operit.api.chat.llmprovider.OpenCodeZenFree
import com.ai.assistance.operit.api.chat.llmprovider.ModelListFetcher
import com.ai.assistance.operit.api.chat.llmprovider.parseProviderCustomHeaders
import com.ai.assistance.operit.data.api.CodexAuthManager
import com.ai.assistance.operit.data.api.AccountProvider
import com.ai.assistance.operit.data.api.ClaudeOAuthProtocol
import com.ai.assistance.operit.data.api.ProviderAccountManager
import com.ai.assistance.operit.data.api.OpenCodeGoQuotaClient
import com.ai.assistance.operit.data.api.ProviderQuota
import com.ai.assistance.operit.api.chat.llmprovider.AntigravityTransport
import com.ai.assistance.operit.ui.features.codex.ProviderAccountSettings
import com.ai.assistance.operit.ui.features.settings.components.ProviderQuotaPanel
import com.ai.assistance.operit.data.api.CodexUsageSnapshot
import com.ai.assistance.operit.data.api.CodexUsageWindow
import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelProtocolCatalogRepository
import com.ai.assistance.operit.data.model.ModelProtocolSettings
import com.ai.assistance.operit.data.model.supportsModelProtocolOverrides
import com.ai.assistance.operit.data.model.ModelMultimodalCapabilities
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.OfficialModelCapabilitiesRepository
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.multimodalCapabilitiesForModel
import com.ai.assistance.operit.data.model.normalizeProviderId
import com.ai.assistance.operit.data.preferences.CodexAuthState
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry
import com.ai.assistance.operit.ui.common.input.bringIntoViewOnImeFocus
import com.ai.assistance.operit.ui.features.settings.DebouncedModelConfigAutoSaveEffect
import com.ai.assistance.operit.ui.features.settings.ModelConfigSaveCoordinator
import com.ai.assistance.operit.ui.features.settings.RegisterModelConfigSaveAction
import com.ai.assistance.operit.ui.features.codex.CodexLoginDialog
import com.ai.assistance.operit.ui.features.codex.CodexDeviceLoginDialog
import com.ai.assistance.operit.util.LocationUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

val TAG = "ModelApiSettings"

private val modelApiSettingsSaveMutex = Mutex()

private data class ProviderSelectionOption(
    val id: String,
    val displayName: String
)

@Composable
@SuppressLint("MissingPermission")
fun ModelApiSettingsSection(
        config: ModelConfigData,
        configManager: ModelConfigManager,
        saveCoordinator: ModelConfigSaveCoordinator,
        showNotification: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val codexLogoutSuccessText = stringResource(R.string.codex_logout_success)
    val codexLoginSuccessText = stringResource(R.string.codex_login_success)
    val codexAuthManager = remember { CodexAuthManager.getInstance(context) }
    val codexAuthState by codexAuthManager.authState.collectAsState()
    val persistedCodexUsage by codexAuthManager.usageSnapshotFlow.collectAsState(initial = null)
    var showCodexLoginDialog by remember(config.id) { mutableStateOf(false) }
    var showCodexDeviceLoginDialog by remember(config.id) { mutableStateOf(false) }
    var codexUsageLoading by remember(config.id) { mutableStateOf(false) }
    var codexUsageError by remember(config.id) { mutableStateOf(false) }
    var codexUsageNow by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }

    // 区域告警可见性
    var showRegionWarning by remember { mutableStateOf(false) }

    fun getDefaultModelName(providerTypeId: String): String {
        val providerType = ApiProviderType.fromProviderTypeId(providerTypeId) ?: return ""
        return ApiProviderConfigs.getDefaultModelName(providerType)
    }

    fun getEndpointOptions(providerTypeId: String): List<Pair<String, String>>? {
        val providerType = ApiProviderType.fromProviderTypeId(providerTypeId) ?: return null
        return ApiProviderConfigs.getEndpointOptions(providerType)
            ?.map { it.endpoint to it.label }
    }

    // 检查当前模型名称是否是某个提供商的默认值
    fun isDefaultModelName(modelName: String): Boolean {
        return ApiProviderConfigs.isDefaultModelName(modelName)
    }

    // API编辑状态
    var apiEndpointInput by remember(config.id) { mutableStateOf(config.apiEndpoint) }
    var apiKeyInput by remember(config.id) { mutableStateOf(config.apiKey) }
    var modelNameInput by remember(config.id) { mutableStateOf(config.modelName) }
    var selectedProviderTypeId by remember(config.id) { mutableStateOf(config.apiProviderTypeId) }
    var hasInitializedProviderEndpointSync by remember(config.id) { mutableStateOf(false) }
    var previousProviderTypeId by remember(config.id) { mutableStateOf(config.apiProviderTypeId) }
    val selectedApiProvider = ApiProviderType.fromProviderTypeId(selectedProviderTypeId)
    val isCodexProvider = selectedApiProvider == ApiProviderType.OPENAI_CODEX
    val currentModelSelection by rememberUpdatedState(config.id to selectedProviderTypeId)
    val browserAccountType = AccountProvider.from(selectedApiProvider)
    val browserAccountManager = remember(browserAccountType) {
        browserAccountType?.let { ProviderAccountManager.get(context, it) }
    }
    val browserAccountState = browserAccountManager?.account?.collectAsState()?.value
    val codexUsage = persistedCodexUsage
        ?.takeIf { it.accountId == codexAuthState?.accountId }
        ?.usage

    LaunchedEffect(codexAuthState?.accountId) {
        codexUsageError = false
    }

    LaunchedEffect(codexUsage) {
        while (isActive && codexUsage != null) {
            codexUsageNow = System.currentTimeMillis() / 1000L
            delay(60_000L)
        }
    }

    fun refreshCodexUsage() {
        if (!isCodexProvider || codexAuthState == null || codexUsageLoading) return
        scope.launch {
            codexUsageLoading = true
            codexUsageError = false
            val result = codexAuthManager.fetchUsage()
            result.exceptionOrNull()?.let { error ->
                AppLogger.e(TAG, "获取 Codex 额度失败", error)
            }
            codexUsageError = result.isFailure
            codexUsageLoading = false
        }
    }

    // OpenCode Go is a plain key-based provider rather than a browser account, so its quota probe
    // lives here next to the fields that supply the key and endpoint it reads from.
    val openCodeGoQuotaClient = remember { OpenCodeGoQuotaClient() }
    var openCodeGoQuota by remember(config.id) { mutableStateOf<ProviderQuota?>(null) }
    var openCodeGoQuotaLoading by remember(config.id) { mutableStateOf(false) }
    var openCodeGoQuotaFailed by remember(config.id) { mutableStateOf(false) }
    var openCodeGoRequestId by remember(config.id) { mutableLongStateOf(0L) }

    // No automatic probe: the key field keeps the previous provider's value while the user switches
    // providers, and autosave then persists that key into this row. Probing on arrival would send a
    // credential the user never associated with OpenCode Go, so the probe only runs when asked for.
    val isOpenCodeGoProvider = selectedApiProvider == ApiProviderType.OPENCODE_GO
    val openCodeGoProbeKey = apiKeyInput.trim()
    val openCodeGoProbeEndpoint = apiEndpointInput

    fun refreshOpenCodeGoQuota() {
        val key = openCodeGoProbeKey
        if (key.isEmpty()) return
        val requestId = ++openCodeGoRequestId
        openCodeGoQuotaFailed = false
        openCodeGoQuotaLoading = true
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    openCodeGoQuotaClient.fetch(key, openCodeGoProbeEndpoint)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogger.e(TAG, "获取 OpenCode Go 余额失败", error)
                null
            }
            // A slow probe for a superseded key must not land on the current one's panel.
            if (requestId != openCodeGoRequestId) return@launch
            openCodeGoQuotaLoading = false
            // A transient failure keeps the last good numbers; only the note changes.
            if (result != null) openCodeGoQuota = result
            openCodeGoQuotaFailed = result == null
        }
    }

    LaunchedEffect(isOpenCodeGoProvider, openCodeGoProbeKey, openCodeGoProbeEndpoint) {
        // Supersede any in-flight probe so neither its result nor its spinner outlives the target,
        // and drop the previous target's numbers rather than attribute them to the new one.
        openCodeGoRequestId++
        openCodeGoQuotaLoading = false
        openCodeGoQuotaFailed = false
        openCodeGoQuota = null
    }

    // MNN特定配置状态

    // llama.cpp 常用配置状态

    // 多模态处理配置状态
    var enableDirectImageProcessingInput by remember(config.id) { mutableStateOf(config.enableDirectImageProcessing) }
    var modelMultimodalCapabilitiesInput by remember(config.id) {
        mutableStateOf(
            getModelList(config.modelName).associateWith { modelName ->
                config.multimodalCapabilitiesForModel(modelName)
            }
        )
    }
    var automaticallyMatchedZenModels by remember(config.id) { mutableStateOf(emptySet<String>()) }
    var modelProtocolSettingsInput by remember(config.id) { mutableStateOf(config.modelProtocolSettings) }
    var isConfiguringProtocols by remember(config.id) { mutableStateOf(false) }
    var protocolSyncJob by remember(config.id) { mutableStateOf<Job?>(null) }
    val protocolCatalogRepository = remember(context.applicationContext) {
        ModelProtocolCatalogRepository(context.applicationContext)
    }
    val protocolCatalogUpdatedAt by protocolCatalogRepository.updatedAt.collectAsState()
    LaunchedEffect(protocolCatalogRepository) {
        protocolCatalogRepository.loadCatalog()
    }
    LaunchedEffect(selectedApiProvider, modelNameInput, protocolCatalogUpdatedAt) {
        if (selectedApiProvider == ApiProviderType.OPENCODE_ZEN_FREE) {
            val configuredModels = getModelList(modelNameInput)
            if (configuredModels.isNotEmpty()) {
                val matched = protocolCatalogRepository.loadCatalog()
                    .matchAll(OpenCodeZenFree.CHAT_ENDPOINT, configuredModels)
                if (matched.any { (model, settings) -> modelProtocolSettingsInput[model] != settings }) {
                    modelProtocolSettingsInput = modelProtocolSettingsInput + matched
                }
            }
        }
    }
    DisposableEffect(config.id) {
        onDispose { protocolSyncJob?.cancel() }
    }
    var showMultimodalModelPicker by remember { mutableStateOf(false) }
    var selectedMultimodalModel by remember(config.id) {
        mutableStateOf(getModelList(config.modelName).firstOrNull().orEmpty())
    }

    var enableDirectAudioProcessingInput by remember(config.id) { mutableStateOf(config.enableDirectAudioProcessing) }

    var enableDirectVideoProcessingInput by remember(config.id) { mutableStateOf(config.enableDirectVideoProcessing) }
    val officialModelCapabilitiesRepository =
        remember(context.applicationContext) {
            OfficialModelCapabilitiesRepository(context.applicationContext)
        }
    val multimodalCatalogUpdatedAt by officialModelCapabilitiesRepository.updatedAt.collectAsState()
    LaunchedEffect(officialModelCapabilitiesRepository) {
        officialModelCapabilitiesRepository.loadCatalog()
    }
    LaunchedEffect(selectedApiProvider, modelNameInput, multimodalCatalogUpdatedAt) {
        if (selectedApiProvider == ApiProviderType.OPENCODE_ZEN_FREE) {
            val missingModels = getModelList(modelNameInput).filter {
                it !in config.modelMultimodalCapabilities && it !in automaticallyMatchedZenModels
            }
            if (missingModels.isNotEmpty()) {
                val matched = OpenCodeZenFree.matchMultimodalCapabilities(
                    officialModelCapabilitiesRepository.loadCatalog(),
                    missingModels,
                )
                modelMultimodalCapabilitiesInput = modelMultimodalCapabilitiesInput + matched
                automaticallyMatchedZenModels = automaticallyMatchedZenModels + matched.keys
            }
        }
    }
    var isSyncingMultimodalCapabilities by remember(config.id) { mutableStateOf(false) }
    var isRefreshingModelCatalog by remember(config.id) { mutableStateOf(false) }
    var multimodalSyncJob by remember(config.id) { mutableStateOf<Job?>(null) }

    DisposableEffect(config.id) {
        onDispose {
            multimodalSyncJob?.cancel()
        }
    }
    
    // Google Search Grounding 配置状态 (仅Gemini)
    var enableGoogleSearchInput by remember(config.id) { mutableStateOf(config.enableGoogleSearch) }

    // Claude 1小时提示缓存配置状态 (仅Claude)
    var enableClaude1hPromptCacheInput by remember(config.id) {
        mutableStateOf(config.enableClaude1hPromptCache)
    }
    
    // Tool Call配置状态
    var enableToolCallInput by remember(config.id) { mutableStateOf(config.enableToolCall) }

    var previousDefaultsProvider by remember(config.id) { mutableStateOf(selectedProviderTypeId) }
    LaunchedEffect(selectedProviderTypeId) {
        if ((isCodexProvider || browserAccountType != null) && previousDefaultsProvider != selectedProviderTypeId) {
            enableDirectImageProcessingInput = true
            enableDirectAudioProcessingInput = false
            enableDirectVideoProcessingInput = false
            enableToolCallInput = true
        }
        previousDefaultsProvider = selectedProviderTypeId
    }

    data class ApiAutoSaveState(
        val apiEndpoint: String,
        val apiKey: String,
        val modelName: String,
        val providerTypeId: String,
        val provider: ApiProviderType,
        val enableDirectImageProcessing: Boolean,
        val enableDirectAudioProcessing: Boolean,
        val enableDirectVideoProcessing: Boolean,
        val modelMultimodalCapabilities: Map<String, ModelMultimodalCapabilities>,
        val modelProtocolSettings: Map<String, ModelProtocolSettings>,
        val enableGoogleSearch: Boolean,
        val enableClaude1hPromptCache: Boolean,
        val enableToolCall: Boolean,
    )

    // 保存设置的通用函数
    suspend fun persist(state: ApiAutoSaveState) {
        modelApiSettingsSaveMutex.withLock {
            withContext(Dispatchers.IO) {
                configManager.updateApiSettingsFull(
                    configId = config.id,
                    apiKey = state.apiKey,
                    apiEndpoint = state.apiEndpoint,
                    modelName = state.modelName,
                    apiProviderType = state.provider,
                    apiProviderTypeId = state.providerTypeId,
                    enableDirectImageProcessing = state.enableDirectImageProcessing,
                    enableDirectAudioProcessing = state.enableDirectAudioProcessing,
                    enableDirectVideoProcessing = state.enableDirectVideoProcessing,
                    modelMultimodalCapabilities = state.modelMultimodalCapabilities,
                    modelProtocolSettings = state.modelProtocolSettings,
                    enableGoogleSearch = state.enableGoogleSearch,
                    enableClaude1hPromptCache = state.enableClaude1hPromptCache,
                    enableToolCall = state.enableToolCall,
                    // Preserve retired fields when editing/importing an older configuration.
                    mnnForwardType = config.mnnForwardType,
                    mnnThreadCount = config.mnnThreadCount,
                    llamaThreadCount = config.llamaThreadCount,
                    llamaContextSize = config.llamaContextSize,
                    llamaGpuLayers = config.llamaGpuLayers,
                )

                EnhancedAIService.refreshAllServices(
                    configManager.appContext
                )
            }
        }
    }

    fun buildAutoSaveState(): ApiAutoSaveState {
        val configuredModelNames = getModelList(modelNameInput)
        return ApiAutoSaveState(
            apiEndpoint = apiEndpointInput,
            apiKey = apiKeyInput,
            modelName = modelNameInput,
            providerTypeId = selectedProviderTypeId,
            provider = selectedApiProvider ?: ApiProviderType.OTHER,
            enableDirectImageProcessing = enableDirectImageProcessingInput,
            enableDirectAudioProcessing = enableDirectAudioProcessingInput,
            enableDirectVideoProcessing = enableDirectVideoProcessingInput,
            modelMultimodalCapabilities =
                configuredModelNames.associateWith { modelName ->
                    modelMultimodalCapabilitiesInput[modelName]
                        ?: ModelMultimodalCapabilities()
                },
            modelProtocolSettings = modelProtocolSettingsInput.filterKeys { it in configuredModelNames },
            enableGoogleSearch = enableGoogleSearchInput,
            enableClaude1hPromptCache = enableClaude1hPromptCacheInput,
            enableToolCall = enableToolCallInput,
        )
    }

    suspend fun flushSettings(showSuccess: Boolean) {
        val state = buildAutoSaveState()
        try {
            AppLogger.d(
                TAG,
                "保存API设置: apiKey=${state.apiKey.take(5)}..., endpoint=${state.apiEndpoint}, model=${state.modelName}, providerType=${state.provider.name}"
            )
            persist(state)
            AppLogger.d(TAG, "API设置保存完成并刷新服务")
            if (showSuccess) {
                showNotification(context.getString(R.string.api_settings_saved))
            }
        } catch (e: Exception) {
            if (showSuccess) {
                showNotification((e.message ?: context.getString(R.string.save_failed)))
            } else {
                AppLogger.e(TAG, "API设置自动保存失败: ${e.message}", e)
            }
            throw e
        }
    }

    RegisterModelConfigSaveAction(
        coordinator = saveCoordinator,
        key = "api:${config.id}",
        action = { showSuccess -> flushSettings(showSuccess) }
    )

    DebouncedModelConfigAutoSaveEffect(
        effectKey = config.id,
        valueProvider = { buildAutoSaveState() },
        persist = { state -> persist(state) },
        onError = { e ->
            showNotification((e.message ?: context.getString(R.string.auto_save_failed)))
        }
    )

    // 根据API提供商获取默认的API端点URL
    fun getDefaultApiEndpoint(providerType: ApiProviderType): String {
        return ApiProviderConfigs.getDefaultApiEndpoint(providerType)
    }

    // 添加一个函数检查当前API端点是否为某个提供商的默认端点
    fun isDefaultApiEndpoint(endpoint: String): Boolean {
        return ApiProviderConfigs.isDefaultApiEndpoint(endpoint)
    }

    fun syncMoonshotModelForEndpoint(endpoint: String) {
        if (selectedApiProvider != ApiProviderType.MOONSHOT) {
            return
        }

        val moonshotDefaultModel = getDefaultModelName(ApiProviderType.MOONSHOT.name)
        val isKimiCodeEndpoint = endpoint.contains("api.kimi.com/coding/v1", ignoreCase = true)

        if (isKimiCodeEndpoint) {
            if (modelNameInput.isEmpty() || modelNameInput == moonshotDefaultModel) {
                modelNameInput = "kimi-for-coding"
            }
        } else if (modelNameInput == "kimi-for-coding") {
            modelNameInput = moonshotDefaultModel
        }
    }

    // 当API提供商改变时更新端点
    LaunchedEffect(selectedProviderTypeId) {
        AppLogger.d("ModelApiSettingsSection", "API提供商改变")
        if (
            selectedApiProvider == ApiProviderType.OPENAI ||
                selectedApiProvider == ApiProviderType.OPENAI_RESPONSES ||
                selectedApiProvider == ApiProviderType.OPENAI_RESPONSES_GENERIC ||
                selectedApiProvider == ApiProviderType.OPENAI_GENERIC ||
                selectedApiProvider == ApiProviderType.GOOGLE ||
                selectedApiProvider == ApiProviderType.GEMINI_GENERIC ||
                selectedApiProvider == ApiProviderType.ANTHROPIC ||
                selectedApiProvider == ApiProviderType.ANTHROPIC_GENERIC ||
                selectedApiProvider == ApiProviderType.MISTRAL ||
                selectedApiProvider == ApiProviderType.NVIDIA ||
                selectedApiProvider == ApiProviderType.NOUS_PORTAL
        ) {
            val inChina = LocationUtils.isDeviceInMainlandChina(context)
            showRegionWarning = inChina
            if (inChina) {
                AppLogger.d("ModelApiSettingsSection", "检测到位于中国大陆")
                showNotification(context.getString(R.string.overseas_provider_warning))
            } else {
                AppLogger.d("ModelApiSettingsSection", "检测到位于海外")
            }
        } else {
            showRegionWarning = false
        }

        val shouldSyncEndpointByProviderChange = hasInitializedProviderEndpointSync
        hasInitializedProviderEndpointSync = true
        if (!shouldSyncEndpointByProviderChange) {
            // 首次进入页面时保留持久化配置，避免把用户已选择的端点覆盖成默认值。
            if (selectedApiProvider in setOf(ApiProviderType.OPENAI_CODEX, ApiProviderType.OPENCODE_ZEN_FREE)
                || browserAccountType != null) {
                apiEndpointInput = getDefaultApiEndpoint(requireNotNull(selectedApiProvider))
            }
            return@LaunchedEffect
        }

        if (selectedApiProvider == null) {
            previousProviderTypeId = selectedProviderTypeId
            return@LaunchedEffect
        }

        val previousProvider = ApiProviderType.fromProviderTypeId(previousProviderTypeId)
        val previousDefaultEndpoint =
            previousProvider?.let { getDefaultApiEndpoint(it) }.orEmpty()
        val shouldApplyNewProviderDefault =
            selectedApiProvider in setOf(ApiProviderType.OPENAI_CODEX, ApiProviderType.OPENCODE_ZEN_FREE)
                || browserAccountType != null ||
            apiEndpointInput.isEmpty() ||
                isDefaultApiEndpoint(apiEndpointInput) ||
                (previousDefaultEndpoint.isNotEmpty() && apiEndpointInput == previousDefaultEndpoint)

        if (shouldApplyNewProviderDefault) {
            apiEndpointInput = getDefaultApiEndpoint(selectedApiProvider)
        }
        previousProviderTypeId = selectedProviderTypeId
    }

    // 模型列表状态
    var isLoadingModels by remember(config.id, selectedProviderTypeId) { mutableStateOf(false) }
    var showModelsDialog by remember(config.id, selectedProviderTypeId) { mutableStateOf(false) }
    var modelsList by remember(config.id, selectedProviderTypeId) {
        mutableStateOf<List<ModelOption>>(emptyList())
    }
    var modelLoadError by remember(config.id, selectedProviderTypeId) { mutableStateOf<String?>(null) }
    var modelFetchGeneration by remember(config.id) { mutableIntStateOf(0) }
    var showEndpointDialog by remember(config.id) { mutableStateOf(false) }
    LaunchedEffect(config.id, selectedProviderTypeId) {
        modelFetchGeneration++
    }
    fun isCurrentModelRequest(
        selection: Pair<String, String>,
        generation: Int,
        codexAccountId: String?,
        wasCodexProvider: Boolean,
    ): Boolean =
        selection == currentModelSelection &&
            generation == modelFetchGeneration &&
            (!wasCodexProvider || codexAccountId == codexAuthManager.currentAccountId())

    // 检查是否未填写API密钥（仅用于UI显示）
    val isUsingDefaultApiKey = apiKeyInput.isBlank()
    val providerRequiresApiKey =
        ApiProviderConfigs.requiresApiKey(selectedProviderTypeId, apiEndpointInput)
    val isMnnProvider = selectedApiProvider == ApiProviderType.MNN
    val isLlamaProvider = selectedApiProvider == ApiProviderType.LLAMA_CPP
    val isToolPkgProvider = selectedApiProvider == null
    val canUseKeylessModelUi = isToolPkgProvider || !providerRequiresApiKey
    val canEditModelName =
        !isMnnProvider &&
            !isLlamaProvider &&
            (canUseKeylessModelUi || !isUsingDefaultApiKey)
    val canRequestModelList = when {
        browserAccountType != null -> browserAccountState != null
        isCodexProvider -> codexAuthState != null && apiEndpointInput.isNotBlank()
        isMnnProvider || isLlamaProvider -> false
        isToolPkgProvider -> true
        else ->
            apiEndpointInput.isNotBlank() &&
                (!providerRequiresApiKey || (!isUsingDefaultApiKey && apiKeyInput.isNotBlank()))
    }
    val endpointOptions = getEndpointOptions(selectedProviderTypeId)
    val selectableEndpointOptions =
        when {
            endpointOptions != null -> endpointOptions
            selectedApiProvider != null -> {
                val defaultEndpoint = getDefaultApiEndpoint(selectedApiProvider)
                if (defaultEndpoint.isNotBlank()) {
                    listOf(defaultEndpoint to defaultEndpoint)
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }

    suspend fun fetchAvailableModels(): Result<List<ModelOption>> {
        return when {
            browserAccountType == AccountProvider.COMMAND_CODE -> runCatching {
                requireNotNull(browserAccountManager).availableCommandCodeModels()
            }
            browserAccountType == AccountProvider.ANTIGRAVITY -> runCatching {
                AntigravityTransport(requireNotNull(browserAccountManager), "").models()
            }
            browserAccountType == AccountProvider.GROK -> runCatching {
                requireNotNull(browserAccountManager).availableGrokModels()
            }
            browserAccountType == AccountProvider.CLAUDE -> runCatching {
                val token = requireNotNull(browserAccountManager).validAccount().accessToken
                ModelListFetcher.getModelsList(
                    context = context,
                    apiKey = token,
                    apiEndpoint = getDefaultApiEndpoint(ApiProviderType.CLAUDE_ACCOUNT),
                    apiProviderType = ApiProviderType.CLAUDE_ACCOUNT,
                ).getOrThrow()
            }.recoverCatching { error ->
                if (error is CancellationException) throw error
                // 订阅账户没有可靠的 /v1/models 保障；列不出来时回落到 Claude Code 的模型表，
                // 让模型选择器保持可用（会话本身用的是已保存的模型名）。
                AppLogger.w(TAG, "Claude 模型列表获取失败，改用内置列表: ${error.message}")
                ClaudeOAuthProtocol.SUBSCRIPTION_MODELS.map { ModelOption(id = it, name = it) }
            }
            isCodexProvider -> CodexModelListFetcher.getModelsList(context)
            isToolPkgProvider -> runCatching {
                val service =
                    AIServiceFactory.createService(
                        config =
                            config.copy(
                                apiKey = apiKeyInput,
                                apiEndpoint = apiEndpointInput,
                                modelName = modelNameInput,
                                apiProviderType = ApiProviderType.OTHER,
                                apiProviderTypeId = selectedProviderTypeId,
                                enableDirectImageProcessing = enableDirectImageProcessingInput,
                                enableDirectAudioProcessing = enableDirectAudioProcessingInput,
                                enableDirectVideoProcessing = enableDirectVideoProcessingInput,
                                modelMultimodalCapabilities =
                                    modelMultimodalCapabilitiesInput,
                                enableGoogleSearch = enableGoogleSearchInput,
                                enableClaude1hPromptCache = enableClaude1hPromptCacheInput,
                                enableToolCall = enableToolCallInput
                            ),
                        modelConfigManager = configManager,
                        context = context
                    )
                try {
                    service.getModelsList(context).getOrThrow()
                } finally {
                    service.release()
                }
            }

            else ->
                ModelListFetcher.getModelsList(
                    context,
                    apiKeyInput,
                    apiEndpointInput,
                    selectedApiProvider ?: ApiProviderType.OPENAI_GENERIC,
                    customHeaders = parseProviderCustomHeaders(config.customHeaders),
                )
        }
    }

    fun configureModelProtocols() {
        if (isConfiguringProtocols || isRefreshingModelCatalog) return
        val names = getModelList(modelNameInput)
        val endpoint = apiEndpointInput
        val providerId = selectedProviderTypeId
        val previous = modelProtocolSettingsInput
        isConfiguringProtocols = true
        protocolSyncJob = scope.launch {
            try {
                val catalog = protocolCatalogRepository.loadCatalog()
                if (apiEndpointInput != endpoint || selectedProviderTypeId != providerId ||
                    getModelList(modelNameInput) != names) {
                    showNotification(context.getString(R.string.model_protocol_auto_changed))
                    return@launch
                }
                val matched = catalog.matchAll(endpoint, names)
                    .filter { (model, _) -> modelProtocolSettingsInput[model] == previous[model] }
                    .mapValues { (model, settings) ->
                        settings.copy(endpoint = previous[model]?.endpoint?.takeIf { it.isNotBlank() }
                            ?: settings.endpoint)
                    }
                modelProtocolSettingsInput = modelProtocolSettingsInput + matched
                showNotification(context.getString(
                    R.string.model_protocol_auto_result,
                    matched.size, names.size - matched.size,
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showNotification(context.getString(R.string.model_protocol_auto_failed))
            } finally {
                isConfiguringProtocols = false
                protocolSyncJob = null
            }
        }
    }

    fun syncOfficialModelCapabilities() {
        if (isSyncingMultimodalCapabilities || isRefreshingModelCatalog) return
        val configuredModelNames = getModelList(modelNameInput)
        if (configuredModelNames.isEmpty()) return
        isSyncingMultimodalCapabilities = true
        multimodalSyncJob = scope.launch {
            try {
                val catalog = officialModelCapabilitiesRepository.loadCatalog()
                val matchedCapabilities =
                    if (selectedApiProvider == ApiProviderType.OPENCODE_ZEN_FREE) {
                        OpenCodeZenFree.matchMultimodalCapabilities(catalog, configuredModelNames)
                    } else {
                        catalog.matchAll(configuredModelNames)
                    }
                val unmatchedCount = configuredModelNames.size - matchedCapabilities.size

                if (matchedCapabilities.isNotEmpty()) {
                    modelMultimodalCapabilitiesInput =
                        modelMultimodalCapabilitiesInput + matchedCapabilities
                }
                showNotification(
                    when {
                        matchedCapabilities.isEmpty() ->
                            context.getString(R.string.model_multimodal_auto_sync_none_matched)
                        unmatchedCount == 0 ->
                            context.getString(
                                R.string.model_multimodal_auto_sync_all_matched,
                                matchedCapabilities.size,
                            )
                        else ->
                            context.getString(
                                R.string.model_multimodal_auto_sync_partially_matched,
                                matchedCapabilities.size,
                                unmatchedCount,
                            )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "同步官方模型多模态能力失败: ${e.message}", e)
                showNotification(context.getString(R.string.model_multimodal_auto_sync_failed))
            } finally {
                isSyncingMultimodalCapabilities = false
                multimodalSyncJob = null
            }
        }
    }

    fun refreshLocalModelCatalogs() {
        if (isConfiguringProtocols || isSyncingMultimodalCapabilities || isRefreshingModelCatalog) return
        isRefreshingModelCatalog = true
        multimodalSyncJob = scope.launch {
            suspend fun refresh(block: suspend () -> Unit): Boolean = try {
                block()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Local model catalog refresh failed", e)
                false
            }
            try {
                val capabilitiesUpdated = refresh { officialModelCapabilitiesRepository.refreshCatalog() }
                val protocolsUpdated = refresh { protocolCatalogRepository.refreshCatalog() }
                showNotification(context.getString(when {
                    capabilitiesUpdated && protocolsUpdated -> R.string.model_catalog_refresh_success
                    capabilitiesUpdated -> R.string.model_catalog_refresh_protocol_failed
                    protocolsUpdated -> R.string.model_catalog_refresh_multimodal_failed
                    else -> R.string.model_multimodal_catalog_refresh_failed
                }))
            } finally {
                isRefreshingModelCatalog = false
                multimodalSyncJob = null
            }
        }
    }
    // 移除了强制锁定模型名称的逻辑，允许用户自由修改

    Card(
            modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            shape = SettingsCardShape,
            colors = CardDefaults.cardColors(containerColor = rememberSettingsCardColor())
    ) {
        Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsSectionHeader(
                    icon = Icons.Default.Api,
                    title = stringResource(R.string.api_settings)
            )

            var showApiProviderDialog by remember { mutableStateOf(false) }

            SettingsSelectorRow(
                    title = stringResource(R.string.api_provider),
                    subtitle = stringResource(R.string.select_api_provider),
                    value = getProviderDisplayName(selectedProviderTypeId, context),
                    onClick = { showApiProviderDialog = true }
            )

            if (showApiProviderDialog) {
                ApiProviderDialog(
                         onDismissRequest = { showApiProviderDialog = false },
                         onProviderSelected = { provider ->
                             val oldProviderTypeId = selectedProviderTypeId
                             selectedProviderTypeId = provider.id
                              if (provider.id != oldProviderTypeId &&
                                  (provider.id == ApiProviderType.OPENCODE_ZEN_FREE.name ||
                                      oldProviderTypeId == ApiProviderType.OPENCODE_ZEN_FREE.name)) {
                                 apiKeyInput = ""
                             }
                             if (provider.id == ApiProviderType.OPENCODE_ZEN_FREE.name) {
                                 apiEndpointInput =
                                     ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.OPENCODE_ZEN_FREE)
                             }

                            // 对有默认模型名的供应商，视为"有强制内容"：切换时总是重置为该供应商默认模型名
                            val hasForcedModelName = getDefaultModelName(provider.id).isNotEmpty()
                            if (hasForcedModelName) {
                                modelNameInput = getDefaultModelName(provider.id)
                            } else if (modelNameInput.isEmpty() || isDefaultModelName(modelNameInput)) {
                                // 通用/无默认模型名的供应商仍沿用旧逻辑
                                modelNameInput = getDefaultModelName(provider.id)
                            }

                            showApiProviderDialog = false
                        },
                        configManager = configManager
                )
            }

            AnimatedVisibility(visible = showRegionWarning) {
                SettingsInfoBanner(text = stringResource(R.string.overseas_provider_warning))
            }
            if (selectedApiProvider == ApiProviderType.OPENCODE_ZEN_FREE) {
                SettingsInfoBanner(text = stringResource(R.string.provider_opencode_zen_free_warning))
            }

            if (isMnnProvider || isLlamaProvider) {
                SettingsInfoBanner(text = stringResource(R.string.local_llm_removed))
            } else if (browserAccountType != null) {
                ProviderAccountSettings(browserAccountType) {
                    scope.launch { EnhancedAIService.refreshAllServices(configManager.appContext) }
                }
            } else if (isCodexProvider) {
                CodexAuthSettingsBlock(
                     authState = codexAuthState,
                     usage = codexUsage,
                     usageLoading = codexUsageLoading,
                     usageError = codexUsageError,
                     usageNowEpochSeconds = codexUsageNow,
                     onLogin = { showCodexLoginDialog = true },
                     onDeviceLogin = { showCodexDeviceLoginDialog = true },
                      onRefreshUsage = ::refreshCodexUsage,
                      onLogout = {
                          scope.launch {
                              modelFetchGeneration++
                              isLoadingModels = false
                              codexAuthManager.logout()
                             modelsList = emptyList()
                             showModelsDialog = false
                             modelLoadError = null
                             codexUsageError = false
                             EnhancedAIService.refreshAllServices(configManager.appContext)
                             showNotification(codexLogoutSuccessText)
                         }
                    },
                )
                SettingsTextField(
                    title = stringResource(R.string.api_endpoint),
                    subtitle = stringResource(R.string.codex_endpoint_fixed) + "\n" +
                        stringResource(R.string.codex_model_directory_hint),
                    value = apiEndpointInput,
                    onValueChange = {},
                    enabled = false,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )
            } else if (selectedApiProvider == ApiProviderType.OPENCODE_ZEN_FREE) {
                SettingsTextField(
                    title = stringResource(R.string.api_endpoint),
                    subtitle = stringResource(R.string.provider_opencode_zen_free_endpoint_fixed),
                    value = ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.OPENCODE_ZEN_FREE),
                    onValueChange = {},
                    enabled = false,
                )
            } else {
                SettingsTextField(
                        title = stringResource(R.string.api_endpoint),
                        subtitle = stringResource(R.string.api_endpoint_placeholder),
                        value = apiEndpointInput,
                        onValueChange = {
                            apiEndpointInput = it.replace("\n", "").replace("\r", "").replace(" ", "")
                        },
                        enabled = true,
                        keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next
                        ),
                        trailingContent =
                                if (selectableEndpointOptions.isNotEmpty()) {
                                    {
                                        IconButton(onClick = { showEndpointDialog = true }) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                } else {
                                    null
                                }
                )

                if (showEndpointDialog && selectableEndpointOptions.isNotEmpty()) {
                    Dialog(onDismissRequest = { showEndpointDialog = false }) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.api_endpoint),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                selectableEndpointOptions.forEach { (endpoint, label) ->
                                    val isSelected = apiEndpointInput == endpoint
                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    apiEndpointInput = endpoint
                                                    syncMoonshotModelForEndpoint(endpoint)
                                                    showEndpointDialog = false
                                                }
                                                .background(
                                                    if (isSelected) {
                                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                    } else {
                                                        MaterialTheme.colorScheme.surface
                                                    }
                                                )
                                                .padding(vertical = 10.dp, horizontal = 12.dp)
                                    ) {
                                        Text(
                                            text = endpoint,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (label.isNotBlank() && label != endpoint) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                }

            val completedEndpoint =
                selectedApiProvider?.let {
                    EndpointCompleter.completeEndpoint(apiEndpointInput, it)
                } ?: apiEndpointInput
            if (completedEndpoint != apiEndpointInput) {
                Text(
                    text = stringResource(R.string.actual_request_url, completedEndpoint),
                    style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.endpoint_completion_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            }
            if (selectedApiProvider == ApiProviderType.OPENCODE_ZEN_FREE ||
                (!isMnnProvider && !isLlamaProvider && browserAccountType == null && !isCodexProvider)) {
                val apiKeyInteractionSource = remember { MutableInteractionSource() }
                val isApiKeyFocused by apiKeyInteractionSource.collectIsFocusedAsState()
                SettingsTextField(
                    title = stringResource(R.string.api_key),
                    subtitle = if (selectedApiProvider == ApiProviderType.OPENCODE_ZEN_FREE) {
                        stringResource(R.string.provider_opencode_zen_free_key_optional)
                    } else if (isUsingDefaultApiKey) {
                        stringResource(R.string.api_key_placeholder_default)
                    } else {
                        stringResource(R.string.api_key_placeholder_custom)
                    },
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it.replace("\n", "").replace("\r", "").replace(" ", "")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                    visualTransformation = if (isApiKeyFocused || apiKeyInput.isEmpty()) {
                        VisualTransformation.None
                    } else {
                        ApiKeyVisualTransformation()
                    },
                    interactionSource = apiKeyInteractionSource,
                )
            }
            SettingsTextField(
                    title = stringResource(R.string.model_name),
                    subtitle = when {
                        isMnnProvider -> stringResource(R.string.mnn_select_downloaded_model)
                        isLlamaProvider -> stringResource(R.string.llama_select_downloaded_model)
                        else -> stringResource(R.string.model_name_placeholder) + stringResource(R.string.model_name_multiple_hint)
                    },
                        value = modelNameInput,
                        onValueChange = {
                        if (canEditModelName) {
                                modelNameInput = it.replace("\n", "").replace("\r", "")
                            }
                        },
                    enabled = !isMnnProvider && !isLlamaProvider && canEditModelName,
                    trailingContent = {
                val fillEndpointKeyText = stringResource(R.string.fill_endpoint_and_key)
                val modelsListSuccessText = stringResource(R.string.models_list_success)
                IconButton(
                        onClick = {
                            AppLogger.d(
                                    TAG,
                                    "模型列表按钮被点击 - API端点: $apiEndpointInput, API类型: $selectedProviderTypeId"
                            )
                            val gettingModelsText = context.getString(R.string.getting_models_list)
                            val unknownErrorText = context.getString(R.string.unknown_error)
                            val getModelsFailedText = context.getString(R.string.get_models_list_failed)
                            val defaultConfigNoModelsText = context.getString(R.string.default_config_no_models_list)
                            
                            showNotification(gettingModelsText)

                            val requestSelection = config.id to selectedProviderTypeId
                            val requestGeneration = ++modelFetchGeneration
                            val wasCodexProvider = isCodexProvider
                            val requestAccountId =
                                if (wasCodexProvider) codexAuthManager.currentAccountId() else null
                            scope.launch {
                                if (canRequestModelList) {
                                    isLoadingModels = true
                                    modelLoadError = null
                                    AppLogger.d(
                                            TAG,
                                            "开始获取模型列表: 端点=$apiEndpointInput, API类型=$selectedProviderTypeId"
                                    )

                                    try {
                                        val result = fetchAvailableModels()
                                        if (!isCurrentModelRequest(
                                                requestSelection,
                                                requestGeneration,
                                                requestAccountId,
                                                wasCodexProvider,
                                            )
                                        ) return@launch
                                        if (result.isSuccess) {
                                            val models = result.getOrThrow()
                                            AppLogger.d(TAG, "模型列表获取成功，共 ${models.size} 个模型")
                                            modelsList = models
                                            showModelsDialog = true
                                            showNotification(modelsListSuccessText.format(models.size))
                                        } else {
                                            val errorMsg =
                                                    result.exceptionOrNull()?.message ?: unknownErrorText
                                            AppLogger.e(TAG, "模型列表获取失败: $errorMsg")
                                            modelLoadError = getModelsFailedText.format(errorMsg)
                                            showNotification(modelLoadError ?: getModelsFailedText.format(""))
                                        }
                                    } catch (e: Exception) {
                                        if (!isCurrentModelRequest(
                                                requestSelection,
                                                requestGeneration,
                                                requestAccountId,
                                                wasCodexProvider,
                                            )
                                        ) return@launch
                                        AppLogger.e(TAG, "获取模型列表发生异常", e)
                                        modelLoadError = getModelsFailedText.format(e.message ?: "")
                                        showNotification(modelLoadError ?: getModelsFailedText.format(""))
                                    } finally {
                                        if (isCurrentModelRequest(
                                                requestSelection,
                                                requestGeneration,
                                                requestAccountId,
                                                wasCodexProvider,
                                            )
                                        ) isLoadingModels = false
                                        AppLogger.d(TAG, "模型列表获取流程完成")
                                    }
                                } else if (!isToolPkgProvider && isUsingDefaultApiKey && providerRequiresApiKey) {
                                    AppLogger.d(TAG, "使用默认配置，不获取模型列表")
                                    showNotification(defaultConfigNoModelsText)
                                } else {
                                    AppLogger.d(TAG, "API端点或密钥为空")
                                    showNotification(fillEndpointKeyText)
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        colors =
                                IconButtonDefaults.iconButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                ),
                                enabled = canRequestModelList
                ) {
                    if (isLoadingModels) {
                        CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                                imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = stringResource(R.string.get_models_list),
                                tint =
                                                if (!canRequestModelList && !isToolPkgProvider)
                                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                                else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
            )

            if (isOpenCodeGoProvider && openCodeGoProbeKey.isNotEmpty()) {
                ProviderQuotaPanel(
                    quota = openCodeGoQuota,
                    loading = openCodeGoQuotaLoading,
                    failed = openCodeGoQuotaFailed,
                    onRefresh = { refreshOpenCodeGoQuota() },
                )
            }

            val configuredModels = getModelList(modelNameInput)
            val activeModel =
                configuredModels.firstOrNull()?.let { firstModel ->
                    selectedMultimodalModel
                        .takeIf { it in configuredModels }
                        ?: firstModel
                }.orEmpty()
            // 配置的模型列表变化后，保持当前选择；若失效则回退到第一个模型
            LaunchedEffect(configuredModels) {
                if (selectedMultimodalModel !in configuredModels) {
                    selectedMultimodalModel = configuredModels.firstOrNull().orEmpty()
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsInfoBanner(
                    text = stringResource(R.string.model_multimodal_capabilities_desc)
                )
                if (supportsModelProtocolOverrides(selectedProviderTypeId)) {
                    OutlinedButton(
                        onClick = { configureModelProtocols() },
                        enabled = configuredModels.isNotEmpty() &&
                            !isConfiguringProtocols && !isRefreshingModelCatalog,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isConfiguringProtocols) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.model_protocol_auto_configure))
                    }
                    Text(
                        stringResource(
                            if (selectedApiProvider == ApiProviderType.OPENCODE_GO)
                                R.string.provider_opencode_go_protocol_hint
                            else R.string.model_protocol_auto_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                OutlinedButton(
                    onClick = { syncOfficialModelCapabilities() },
                    enabled =
                        configuredModels.isNotEmpty() &&
                            !isSyncingMultimodalCapabilities &&
                            !isRefreshingModelCatalog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSyncingMultimodalCapabilities) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.model_multimodal_auto_sync))
                }
                TextButton(
                    onClick = { refreshLocalModelCatalogs() },
                    enabled =
                        !isConfiguringProtocols && !isSyncingMultimodalCapabilities &&
                            !isRefreshingModelCatalog,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (isRefreshingModelCatalog) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.model_multimodal_catalog_refresh))
                }
                Text(
                    text = multimodalCatalogUpdatedAt?.let {
                        stringResource(
                            R.string.model_multimodal_catalog_updated_at,
                            android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(it)) +
                                " " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)),
                        )
                    } ?: stringResource(R.string.model_multimodal_catalog_bundled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (supportsModelProtocolOverrides(selectedProviderTypeId)) {
                    Text(
                        text = protocolCatalogUpdatedAt?.let {
                            stringResource(
                                R.string.model_protocol_catalog_updated_at,
                                android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(it)) +
                                    " " + android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)),
                            )
                        } ?: stringResource(R.string.model_protocol_catalog_bundled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (configuredModels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.model_multimodal_capabilities_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                } else {
                    // 选择器：先选择一个模型，再配置它的能力
                    SettingsSelectorRow(
                        title = stringResource(R.string.model_multimodal_select_model),
                        subtitle = stringResource(R.string.select),
                        value = activeModel,
                        onClick = { showMultimodalModelPicker = true }
                    )

                    activeModel.takeIf { it.isNotEmpty() }?.let { modelName ->
                        val capabilities =
                            modelMultimodalCapabilitiesInput[modelName]
                                ?: ModelMultimodalCapabilities()
                        Column {
                            if (supportsModelProtocolOverrides(selectedProviderTypeId)) {
                                ModelProtocolSettingsEditor(
                                    modelName = modelName,
                                    settings = modelProtocolSettingsInput[modelName] ?: ModelProtocolSettings(),
                                    onChange = { settings ->
                                        modelProtocolSettingsInput = modelProtocolSettingsInput +
                                            (modelName to settings)
                                    },
                                )
                            }
                            SettingsSwitchRow(
                                title = stringResource(R.string.enable_direct_image_processing),
                                subtitle = stringResource(R.string.enable_direct_image_processing_desc),
                                checked = capabilities.image,
                                onCheckedChange = { enabled ->
                                    modelMultimodalCapabilitiesInput =
                                        modelMultimodalCapabilitiesInput +
                                            (modelName to capabilities.copy(image = enabled))
                                },
                            )
                            SettingsSwitchRow(
                                title = stringResource(R.string.enable_direct_audio_processing),
                                subtitle = stringResource(R.string.enable_direct_audio_processing_desc),
                                checked = capabilities.audio,
                                onCheckedChange = { enabled ->
                                    modelMultimodalCapabilitiesInput =
                                        modelMultimodalCapabilitiesInput +
                                            (modelName to capabilities.copy(audio = enabled))
                                },
                            )
                            SettingsSwitchRow(
                                title = stringResource(R.string.enable_direct_video_processing),
                                subtitle = stringResource(R.string.enable_direct_video_processing_desc),
                                checked = capabilities.video,
                                onCheckedChange = { enabled ->
                                    modelMultimodalCapabilitiesInput =
                                        modelMultimodalCapabilitiesInput +
                                            (modelName to capabilities.copy(video = enabled))
                                },
                            )
                        }
                    }
                }
            }

            // 多模态配置模型选择对话框
            if (showMultimodalModelPicker) {
                val pickerModels = getModelList(modelNameInput)
                var pickerSearch by remember { mutableStateOf("") }
                val filteredPickerModels =
                    remember(pickerSearch, pickerModels) {
                        if (pickerSearch.isEmpty()) pickerModels
                        else
                            pickerModels.filter {
                                it.contains(pickerSearch, ignoreCase = true)
                            }
                    }
                Dialog(onDismissRequest = { showMultimodalModelPicker = false }) {
                    Surface(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.model_multimodal_select_model_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            OutlinedTextField(
                                value = pickerSearch,
                                onValueChange = { pickerSearch = it },
                                placeholder = {
                                    Text(
                                        stringResource(R.string.model_multimodal_search_models),
                                        fontSize = 14.sp
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription =
                                            stringResource(R.string.model_multimodal_search_models),
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (pickerSearch.isNotEmpty()) {
                                        IconButton(
                                            onClick = { pickerSearch = "" },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = stringResource(R.string.clear),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier =
                                    Modifier.fillMaxWidth().padding(bottom = 12.dp).height(48.dp),
                                colors =
                                    OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                        focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                        unfocusedLeadingIconColor =
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                textStyle = TextStyle(fontSize = 14.sp)
                            )

                            if (filteredPickerModels.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(160.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.model_multimodal_no_models_found),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f)
                                ) {
                                    items(filteredPickerModels.size) { index ->
                                        val modelName = filteredPickerModels[index]
                                        val isSelected = modelName == activeModel
                                        Row(
                                            modifier =
                                                Modifier.fillMaxWidth()
                                                    .clickable {
                                                        selectedMultimodalModel = modelName
                                                        showMultimodalModelPicker = false
                                                    }
                                                    .background(
                                                        if (isSelected)
                                                            MaterialTheme.colorScheme.primaryContainer
                                                                .copy(alpha = 0.5f)
                                                        else MaterialTheme.colorScheme.surface
                                                    )
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = modelName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight =
                                                    if (isSelected) FontWeight.SemiBold
                                                    else FontWeight.Normal,
                                                color =
                                                    if (isSelected)
                                                        MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        if (index < filteredPickerModels.lastIndex) {
                                            HorizontalDivider(
                                                thickness = 0.5.dp,
                                                color =
                                                    MaterialTheme.colorScheme.outlineVariant.copy(
                                                        alpha = 0.5f
                                                    ),
                                                modifier = Modifier.padding(horizontal = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                            ) {
                                FilledTonalButton(
                                    onClick = { showMultimodalModelPicker = false },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(stringResource(R.string.close), fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
            
            // Google Search Grounding 开关 (仅Gemini支持)
            if (selectedApiProvider == ApiProviderType.GOOGLE ||
                selectedApiProvider == ApiProviderType.GEMINI_GENERIC) {
                SettingsSwitchRow(
                        title = stringResource(R.string.enable_google_search),
                        subtitle = stringResource(R.string.enable_google_search_desc),
                            checked = enableGoogleSearchInput,
                            onCheckedChange = { enableGoogleSearchInput = it }
                    )
            }

            // Claude 1小时提示缓存开关 (仅Claude支持)
            if (selectedApiProvider == ApiProviderType.ANTHROPIC ||
                selectedApiProvider == ApiProviderType.ANTHROPIC_GENERIC) {
                SettingsSwitchRow(
                        title = stringResource(R.string.enable_claude_1h_prompt_cache),
                        subtitle = stringResource(R.string.enable_claude_1h_prompt_cache_desc),
                        checked = enableClaude1hPromptCacheInput,
                        onCheckedChange = { enableClaude1hPromptCacheInput = it }
                    )
            }
            
            // Tool Call 开关
            SettingsSwitchRow(
                title = stringResource(R.string.enable_tool_call),
                subtitle = stringResource(R.string.enable_tool_call_desc),
                checked = enableToolCallInput,
                onCheckedChange = { enableToolCallInput = it }
            )

        }
    }

    val onCodexLoginSuccess: (CodexAuthState) -> Unit = { state ->
                showCodexLoginDialog = false
                showCodexDeviceLoginDialog = false
                val requestSelection = config.id to selectedProviderTypeId
                val requestGeneration = ++modelFetchGeneration
                scope.launch {
                    EnhancedAIService.refreshAllServices(configManager.appContext)
                    showNotification(codexLoginSuccessText)
                    isLoadingModels = true
                    modelLoadError = null
                    try {
                        val result = CodexModelListFetcher.getModelsList(context)
                        if (isCurrentModelRequest(
                                requestSelection,
                                requestGeneration,
                                state.accountId,
                                wasCodexProvider = true,
                            )
                        ) {
                            result.fold(
                                onSuccess = {
                                    modelsList = it
                                    showModelsDialog = true
                                },
                                onFailure = {
                                    modelLoadError = resources.getString(
                                        R.string.codex_model_catalog_refresh_failed,
                                        it.message.orEmpty(),
                                    )
                                    showNotification(requireNotNull(modelLoadError))
                                },
                            )
                        }
                    } finally {
                        if (isCurrentModelRequest(
                                requestSelection,
                                requestGeneration,
                                state.accountId,
                                wasCodexProvider = true,
                            )
                        ) isLoadingModels = false
                    }
                }
    }
    if (showCodexLoginDialog) {
        CodexLoginDialog(
            onDismissRequest = { showCodexLoginDialog = false },
            onLoginSuccess = onCodexLoginSuccess,
        )
    }
    if (showCodexDeviceLoginDialog) {
        CodexDeviceLoginDialog(
            onDismissRequest = { showCodexDeviceLoginDialog = false },
            onLoginSuccess = onCodexLoginSuccess,
        )
    }

    // 模型列表对话框
    if (showModelsDialog) {
        var searchQuery by remember { mutableStateOf("") }
        // 维护已选中的模型集合
        val selectedModels = remember {
            mutableStateOf(
                modelNameInput.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            )
        }
        val filteredModelsList =
                remember(searchQuery, modelsList) {
                    if (searchQuery.isEmpty()) modelsList
                    else modelsList.filter { it.id.contains(searchQuery, ignoreCase = true) }
                }

        Dialog(onDismissRequest = { showModelsDialog = false }) {
            Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val refreshUnknownErrorText = stringResource(R.string.unknown_error)
                    val refreshModelsListFailedText = stringResource(R.string.refresh_models_list_failed)
                    val refreshModelsFailedText = stringResource(R.string.refresh_models_failed)
                    // 标题栏
                    Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                                stringResource(R.string.available_models_list),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                        )

                        FilledIconButton(
                                onClick = {
                                    val requestSelection = config.id to selectedProviderTypeId
                                    val requestGeneration = ++modelFetchGeneration
                                    val wasCodexProvider = isCodexProvider
                                    val requestAccountId =
                                        if (wasCodexProvider) codexAuthManager.currentAccountId() else null
                                    scope.launch {
                                        if (canRequestModelList) {
                                            isLoadingModels = true
                                            try {
                                                val result = fetchAvailableModels()
                                                if (!isCurrentModelRequest(
                                                        requestSelection,
                                                        requestGeneration,
                                                        requestAccountId,
                                                        wasCodexProvider,
                                                    )
                                                ) return@launch
                                                if (result.isSuccess) {
                                                    modelsList = result.getOrThrow()
                                                } else {
                                                    val errorMsg = result.exceptionOrNull()?.message ?: refreshUnknownErrorText
                                                    modelLoadError = refreshModelsListFailedText.format(errorMsg)
                                                    showNotification(modelLoadError ?: refreshModelsFailedText)
                                                }
                                            } catch (e: Exception) {
                                                if (!isCurrentModelRequest(
                                                        requestSelection,
                                                        requestGeneration,
                                                        requestAccountId,
                                                        wasCodexProvider,
                                                    )
                                                ) return@launch
                                                val errorMsg = e.message ?: refreshUnknownErrorText
                                                modelLoadError = refreshModelsListFailedText.format(errorMsg)
                                                showNotification(modelLoadError ?: refreshModelsFailedText)
                                            } finally {
                                                if (isCurrentModelRequest(
                                                        requestSelection,
                                                        requestGeneration,
                                                        requestAccountId,
                                                        wasCodexProvider,
                                                    )
                                                ) isLoadingModels = false
                                            }
                                        }
                                    }
                                },
                                colors =
                                        IconButtonDefaults.filledIconButtonColors(
                                                containerColor =
                                                        MaterialTheme.colorScheme.primaryContainer,
                                                contentColor =
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                modifier = Modifier.size(36.dp)
                        ) {
                            if (isLoadingModels) {
                                CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.refresh_models_list),
                                        modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // 搜索框 - 用普通的OutlinedTextField替代实验性的SearchBar
                    OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.search_models), fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(
                                        Icons.Default.Search,
                                        contentDescription = stringResource(R.string.search),
                                        modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                                Icons.Default.Clear,
                                                contentDescription = stringResource(R.string.clear),
                                                modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            modifier =
                                    Modifier.fillMaxWidth().padding(bottom = 12.dp).height(48.dp),
                            colors =
                                    OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor =
                                                    MaterialTheme.colorScheme.outline,
                                            focusedLeadingIconColor =
                                                    MaterialTheme.colorScheme.primary,
                                            unfocusedLeadingIconColor =
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                    )

                    // 模型列表
                    if (modelsList.isEmpty()) {
                        Box(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                        ) {
                            Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                        imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint =
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                        alpha = 0.6f
                                                )
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                        text = modelLoadError ?: stringResource(R.string.no_models_found),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            items(filteredModelsList.size) { index ->
                                val model = filteredModelsList[index]
                                val isSelected = selectedModels.value.contains(model.id)
                                
                                // 使用带Checkbox的Row实现多选
                                Row(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .clickable {
                                                            // 切换选中状态
                                                            val newSelection = selectedModels.value.toMutableSet()
                                                            if (isSelected) {
                                                                newSelection.remove(model.id)
                                                            } else {
                                                                newSelection.add(model.id)
                                                            }
                                                            selectedModels.value = newSelection
                                                        }
                                                        .padding(
                                                                horizontal = 12.dp,
                                                                vertical = 6.dp
                                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                val newSelection = selectedModels.value.toMutableSet()
                                                if (checked) {
                                                    newSelection.add(model.id)
                                                } else {
                                                    newSelection.remove(model.id)
                                                }
                                                selectedModels.value = newSelection
                                            },
                                            colors = CheckboxDefaults.colors(
                                                    checkedColor = MaterialTheme.colorScheme.primary
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) 
                                                    MaterialTheme.colorScheme.primary 
                                                else 
                                                    MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                if (index < filteredModelsList.size - 1) {
                                    HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color =
                                                    MaterialTheme.colorScheme.outlineVariant.copy(
                                                            alpha = 0.5f
                                                    ),
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 底部信息
                    if (filteredModelsList.isNotEmpty()) {
                        Text(
                                text =
                                        stringResource(R.string.models_displayed, filteredModelsList.size) +
                                                (if (searchQuery.isNotEmpty()) stringResource(R.string.models_displayed_filtered) else "") +
                                                (if (selectedModels.value.isNotEmpty()) " • ${selectedModels.value.size}" + stringResource(R.string.models_selected_suffix) else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                                fontSize = 12.sp
                        )
                    }

                    // 底部按钮
                    Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        FilledTonalButton(
                                onClick = { showModelsDialog = false },
                                modifier = Modifier.height(36.dp)
                        ) { Text(stringResource(R.string.close), fontSize = 14.sp) }
                        
                        Button(
                                onClick = {
                                    // 将选中的模型用逗号连接
                                    val orderedSelection = modelsList.map { it.id }
                                        .filter { selectedModels.value.contains(it) }
                                    modelNameInput = orderedSelection.joinToString(",")
                                    if (selectedApiProvider == ApiProviderType.MNN) {
                                        AppLogger.d(TAG, "选择MNN模型: $modelNameInput")
                                    }
                                    showModelsDialog = false
                                },
                                modifier = Modifier.height(36.dp),
                                enabled = selectedModels.value.isNotEmpty()
                        ) { 
                            Text(
                                stringResource(R.string.confirm_action) + 
                                    if (selectedModels.value.isNotEmpty()) " (${selectedModels.value.size})" else "",
                                fontSize = 14.sp
                            ) 
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodexAuthSettingsBlock(
    authState: CodexAuthState?,
    usage: CodexUsageSnapshot?,
    usageLoading: Boolean,
    usageError: Boolean,
    usageNowEpochSeconds: Long,
    onLogin: () -> Unit,
    onDeviceLogin: () -> Unit,
    onRefreshUsage: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.codex_auth_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (authState == null) {
            Text(
                text = stringResource(R.string.codex_auth_not_logged_in),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Login, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.codex_login_action))
            }
            OutlinedButton(onClick = onDeviceLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.codex_device_login_action))
            }
        } else {
            Text(
                text = authState.email?.let { email ->
                    stringResource(R.string.codex_auth_account, email)
                } ?: stringResource(R.string.codex_auth_account, authState.accountId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.codex_plan,
                        formatCodexPlanType(usage?.planType),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefreshUsage,
                    enabled = !usageLoading,
                ) {
                    if (usageLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.codex_usage_refresh),
                        )
                    }
                }
            }

            CodexQuotaCapsule(
                usage = usage,
                usageError = usageError,
                nowEpochSeconds = usageNowEpochSeconds,
            )

            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.codex_logout_action))
            }
        }
    }
}

@Composable
private fun CodexQuotaCapsule(
    usage: CodexUsageSnapshot?,
    usageError: Boolean,
    nowEpochSeconds: Long,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CodexQuotaWindowRow(
                label = stringResource(R.string.codex_quota_five_hour),
                window = usage?.fiveHourWindow,
                nowEpochSeconds = nowEpochSeconds,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            CodexQuotaWindowRow(
                label = stringResource(R.string.codex_quota_seven_day),
                window = usage?.sevenDayWindow,
                nowEpochSeconds = nowEpochSeconds,
            )
            if (usageError) {
                Text(
                    text = stringResource(R.string.codex_usage_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CodexQuotaWindowRow(
    label: String,
    window: CodexUsageWindow?,
    nowEpochSeconds: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = window?.let {
                    stringResource(R.string.codex_quota_remaining, it.remainingPercent)
                }
                    ?: stringResource(R.string.codex_quota_no_data),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (window != null) {
            LinearProgressIndicator(
                progress = { window.remainingPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        }

        val resetAt = window?.resetsAtEpochSeconds
        if (resetAt != null) {
            Text(
                text = formatCodexResetCountdown(resetAt, nowEpochSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCodexPlanType(planType: String?): String {
    val normalized = planType?.trim()?.takeIf { it.isNotEmpty() } ?: return "-"
    return normalized
        .lowercase()
        .split('_', '-')
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
}

@Composable
private fun formatCodexResetCountdown(resetAtEpochSeconds: Long, nowEpochSeconds: Long): String {
    val seconds = (resetAtEpochSeconds - nowEpochSeconds).coerceAtLeast(0L)
    val days = seconds / 86_400L
    if (days > 0L) {
        return stringResource(R.string.codex_reset_after_days, days)
    }

    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    return stringResource(R.string.codex_reset_after_clock, hours, minutes)
}

private fun getBuiltInProviderDisplayName(provider: ApiProviderType, context: android.content.Context): String {
    return when (provider) {
        ApiProviderType.OPENAI -> context.getString(R.string.provider_openai)
        ApiProviderType.OPENAI_RESPONSES -> context.getString(R.string.provider_openai_responses)
        ApiProviderType.GROK_ACCOUNT -> context.getString(R.string.provider_grok_account)
        ApiProviderType.COMMAND_CODE -> context.getString(R.string.provider_command_code)
        ApiProviderType.GOOGLE_ANTIGRAVITY -> context.getString(R.string.provider_google_antigravity)
        ApiProviderType.OPENAI_CODEX -> context.getString(R.string.provider_openai_codex)
        ApiProviderType.CLAUDE_ACCOUNT -> context.getString(R.string.provider_claude_account)
        ApiProviderType.OPENAI_RESPONSES_GENERIC -> context.getString(R.string.provider_openai_responses_generic)
        ApiProviderType.OPENAI_GENERIC -> context.getString(R.string.provider_openai_generic)
        ApiProviderType.ANTHROPIC -> context.getString(R.string.provider_anthropic)
        ApiProviderType.ANTHROPIC_GENERIC -> context.getString(R.string.provider_anthropic_generic)
        ApiProviderType.GOOGLE -> context.getString(R.string.provider_google)
        ApiProviderType.GEMINI_GENERIC -> context.getString(R.string.provider_gemini_generic)
        ApiProviderType.BAIDU -> context.getString(R.string.provider_baidu)
        ApiProviderType.ALIYUN -> context.getString(R.string.provider_aliyun)
        ApiProviderType.XUNFEI -> context.getString(R.string.provider_xunfei)
        ApiProviderType.ZHIPU -> context.getString(R.string.provider_zhipu)
        ApiProviderType.BAICHUAN -> context.getString(R.string.provider_baichuan)
        ApiProviderType.MOONSHOT -> context.getString(R.string.provider_moonshot)
        ApiProviderType.MIMO -> context.getString(R.string.provider_mimo)
        ApiProviderType.DEEPSEEK -> context.getString(R.string.provider_deepseek)
        ApiProviderType.MISTRAL -> context.getString(R.string.provider_mistral)
        ApiProviderType.SILICONFLOW -> context.getString(R.string.provider_siliconflow)
        ApiProviderType.IFLOW -> context.getString(R.string.provider_iflow)
        ApiProviderType.OPENROUTER -> context.getString(R.string.provider_openrouter)
        ApiProviderType.OPENCODE_GO -> context.getString(R.string.provider_opencode_go)
        ApiProviderType.OPENCODE_ZEN_FREE -> context.getString(R.string.provider_opencode_zen_free)
        ApiProviderType.FOUR_ROUTER -> context.getString(R.string.provider_4router)
        ApiProviderType.NOUS_PORTAL -> context.getString(R.string.provider_nous_portal)
        ApiProviderType.INFINIAI -> context.getString(R.string.provider_infiniai)
        ApiProviderType.ALIPAY_BAILING -> context.getString(R.string.provider_alipay_bailing)
        ApiProviderType.DOUBAO -> context.getString(R.string.provider_doubao)
        ApiProviderType.NVIDIA -> context.getString(R.string.provider_nvidia)
        ApiProviderType.LMSTUDIO -> context.getString(R.string.provider_lmstudio)
        ApiProviderType.OLLAMA -> context.getString(R.string.provider_ollama)
        ApiProviderType.OLLAMA_CLOUD -> context.getString(R.string.provider_ollama_cloud)
        ApiProviderType.OPENAI_LOCAL -> context.getString(R.string.provider_openai_local)
        ApiProviderType.MNN -> context.getString(R.string.provider_mnn)
        ApiProviderType.LLAMA_CPP -> context.getString(R.string.provider_llama_cpp)
        ApiProviderType.PPINFRA -> context.getString(R.string.provider_ppinfra)
        ApiProviderType.NOVITA -> context.getString(R.string.provider_novita)
        ApiProviderType.OTHER -> context.getString(R.string.provider_other)
    }
}

private fun getProviderDisplayName(providerTypeId: String, context: android.content.Context): String {
    val builtInProvider = ApiProviderType.fromProviderTypeId(providerTypeId)
    if (builtInProvider != null) {
        return getBuiltInProviderDisplayName(builtInProvider, context)
    }
    return ToolPkgAiProviderRegistry.get(providerTypeId)?.displayName ?: providerTypeId
}

private fun getProviderSelectionOptions(context: android.content.Context): List<ProviderSelectionOption> {
    val builtInProviders =
        ApiProviderType.values().filterNot { it == ApiProviderType.MNN || it == ApiProviderType.LLAMA_CPP }.map { provider ->
            ProviderSelectionOption(
                id = provider.name,
                displayName = getBuiltInProviderDisplayName(provider, context)
            )
        }
    val toolPkgProviders =
        ToolPkgAiProviderRegistry.list().map { provider ->
            ProviderSelectionOption(
                id = provider.providerId,
                displayName = provider.displayName
            )
        }
    return builtInProviders + toolPkgProviders
}


@Composable
internal fun SettingsSectionHeader(
        icon: ImageVector,
        title: String,
        subtitle: String? = null
) {
    Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
            )
            subtitle?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun SettingsInfoBanner(text: String) {
    Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
internal fun SettingsTextField(
    title: String,
    subtitle: String? = null,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
    valueFilter: ((String) -> String)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    unitText: String? = null,
    onClick: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val inputEnabled = enabled && !readOnly

    Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable { onClick() }
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
            )
            subtitle?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        color = backgroundColor,
                        tonalElevation = 0.dp
                ) {
                    BasicTextField(
                            value = value,
                            onValueChange = { newValue ->
                                if (!inputEnabled) return@BasicTextField
                                val filtered = valueFilter?.invoke(newValue) ?: newValue
                                onValueChange(filtered)
                            },
                            singleLine = singleLine,
                            enabled = inputEnabled,
                            keyboardOptions = keyboardOptions,
                            keyboardActions = keyboardActions,
                            visualTransformation = visualTransformation,
                            interactionSource = resolvedInteractionSource,
                            modifier = Modifier.fillMaxWidth().bringIntoViewOnImeFocus(),
                            textStyle =
                                    TextStyle(
                                            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                    ),
                            decorationBox = { innerTextField ->
                                Row(
                                        modifier =
                                                Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (value.isEmpty()) {
                                            Text(
                                                    text = placeholder,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                    unitText?.let {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                    )
                }
                trailingContent?.invoke()
            }
        }
    }
}

@Composable
internal fun SettingsSelectorRow(
        title: String,
        subtitle: String,
        value: String,
        onClick: () -> Unit
) {
    Surface(
            modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClick() },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                            .padding(end = 8.dp)
                            .weight(0.5f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        enabled: Boolean = true
) {
    Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Row(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}



@Composable
private fun ApiProviderDialog(
        onDismissRequest: () -> Unit,
        onProviderSelected: (ProviderSelectionOption) -> Unit,
        configManager: ModelConfigManager
) {
    val context = LocalContext.current
    val providers = remember { getProviderSelectionOptions(context) }
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val collapsedProviderIds by configManager.collapsedProviderIdsFlow.collectAsState(initial = emptySet())
    
    // 投影为正常/折叠两组
    val normalProviders: List<ProviderSelectionOption>
    val collapsedProviders: List<ProviderSelectionOption>
    if (searchQuery.isNotEmpty()) {
        normalProviders = providers
        collapsedProviders = emptyList()
    } else {
        val norm = mutableListOf<ProviderSelectionOption>()
        val coll = mutableListOf<ProviderSelectionOption>()
        for (p in providers) {
            val normalized = normalizeProviderId(p.id)
            if (normalized.isNotEmpty() && collapsedProviderIds.contains(normalized)) {
                coll.add(p)
            } else {
                norm.add(p)
            }
        }
        normalProviders = norm
        collapsedProviders = coll
    }
    var collapsedExpanded by remember { mutableStateOf(false) }

    val filteredProviders = remember(searchQuery, providers) {
        if (searchQuery.isEmpty()) {
            providers
        } else {
            providers.filter { provider ->
                provider.displayName.contains(searchQuery, ignoreCase = true) ||
                    provider.id.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val showSearchResults = searchQuery.isNotEmpty()

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 标题和搜索框
                Text(
                        stringResource(R.string.select_api_provider_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp)
                )
                
                // 搜索框
                OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.search_providers), fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(
                                    Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search),
                                    modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                            Icons.Default.Clear,
                                            contentDescription = stringResource(R.string.clear),
                                            modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = RoundedCornerShape(8.dp)
                )

                // 提供商列表
                androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.weight(1f)
                ) {
                    // 搜索结果或正常模式
                    if (showSearchResults) {
                        items(filteredProviders.size) { index ->
                            val provider = filteredProviders[index]
                            ProviderRow(
                                provider = provider,
                                onSelect = { onProviderSelected(provider) },
                                collapsed = collapsedProviderIds.contains(normalizeProviderId(provider.id)),
                                onToggleCollapse = {
                                    scope.launch {
                                        configManager.toggleProviderCollapsed(provider.id)
                                    }
                                },
                            )
                        }
                    } else {
                        // 正常提供商
                        items(normalProviders.size) { index ->
                            val provider = normalProviders[index]
                            ProviderRow(
                                provider = provider,
                                onSelect = { onProviderSelected(provider) },
                                collapsed = false,
                                onToggleCollapse = {
                                    scope.launch {
                                        configManager.toggleProviderCollapsed(provider.id)
                                    }
                                },
                            )
                        }
                        // 已折叠提供商分区
                        if (collapsedProviders.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { collapsedExpanded = !collapsedExpanded }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.collapsed_providers_count, collapsedProviders.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = if (collapsedExpanded) Icons.Default.KeyboardArrowUp
                                        else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (collapsedExpanded) {
                                items(collapsedProviders.size) { index ->
                                    val provider = collapsedProviders[index]
                                    ProviderRow(
                                        provider = provider,
                                        onSelect = { onProviderSelected(provider) },
                                        collapsed = true,
                                        onToggleCollapse = {
                                            scope.launch {
                                                configManager.toggleProviderCollapsed(provider.id)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 底部按钮
                Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderSelectionOption,
    onSelect: () -> Unit,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onSelect() },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 提供商图标（使用圆形背景色）
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        getProviderColor(provider.id),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = provider.displayName.firstOrNull()?.toString() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )

            // 折叠/取消折叠按钮
            IconButton(
                onClick = {
                    onToggleCollapse()
                },
                modifier = Modifier.size(36.dp),
            ) {
                Text(
                    text = if (collapsed) "+" else "\u2212", // × -> − for collapse icon
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// 为不同提供商生成不同的颜色
@Composable
private fun getProviderColor(providerTypeId: String): androidx.compose.ui.graphics.Color {
    val provider = ApiProviderType.fromProviderTypeId(providerTypeId)
    if (provider == null) {
        val palette = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer
        )
        val paletteIndex = kotlin.math.abs(providerTypeId.lowercase().hashCode()) % palette.size
        return palette[paletteIndex]
    }
    return when (provider) {
        ApiProviderType.OPENAI -> MaterialTheme.colorScheme.primary
        ApiProviderType.OPENAI_RESPONSES -> MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
        ApiProviderType.GROK_ACCOUNT,
        ApiProviderType.COMMAND_CODE,
        ApiProviderType.GOOGLE_ANTIGRAVITY,
        ApiProviderType.CLAUDE_ACCOUNT,
        ApiProviderType.OPENAI_CODEX -> MaterialTheme.colorScheme.primary.copy(alpha = 0.98f)
        ApiProviderType.OPENAI_RESPONSES_GENERIC -> MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
        ApiProviderType.OPENAI_GENERIC -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        ApiProviderType.ANTHROPIC -> MaterialTheme.colorScheme.tertiary
        ApiProviderType.ANTHROPIC_GENERIC -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
        ApiProviderType.GOOGLE -> MaterialTheme.colorScheme.secondary
        ApiProviderType.GEMINI_GENERIC -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f)
        ApiProviderType.BAIDU -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        ApiProviderType.ALIYUN -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
        ApiProviderType.XUNFEI -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
        ApiProviderType.ZHIPU -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        ApiProviderType.BAICHUAN -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        ApiProviderType.MOONSHOT -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
        ApiProviderType.MIMO -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.66f)
        ApiProviderType.DEEPSEEK -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        ApiProviderType.MISTRAL -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
        ApiProviderType.SILICONFLOW -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
        ApiProviderType.IFLOW -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
        ApiProviderType.OPENROUTER -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
        ApiProviderType.OPENCODE_GO -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        ApiProviderType.OPENCODE_ZEN_FREE -> MaterialTheme.colorScheme.tertiary
        ApiProviderType.FOUR_ROUTER -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.56f)
        ApiProviderType.NOUS_PORTAL -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.52f)
        ApiProviderType.INFINIAI -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        ApiProviderType.ALIPAY_BAILING -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
        ApiProviderType.DOUBAO -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
        ApiProviderType.NVIDIA -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        ApiProviderType.LMSTUDIO -> MaterialTheme.colorScheme.tertiary
        ApiProviderType.OLLAMA -> MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
        ApiProviderType.OLLAMA_CLOUD -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
        ApiProviderType.OPENAI_LOCAL -> MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
        ApiProviderType.MNN -> MaterialTheme.colorScheme.secondary
        ApiProviderType.LLAMA_CPP -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f)
        ApiProviderType.PPINFRA -> MaterialTheme.colorScheme.primaryContainer
        ApiProviderType.NOVITA -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
        ApiProviderType.OTHER -> MaterialTheme.colorScheme.surfaceVariant
    }
}
