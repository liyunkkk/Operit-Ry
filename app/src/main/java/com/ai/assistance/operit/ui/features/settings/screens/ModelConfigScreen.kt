package com.ai.assistance.operit.ui.features.settings.screens

import android.annotation.SuppressLint
import androidx.compose.animation.*
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.ai.assistance.operit.ui.features.settings.components.rememberSettingsCardColor
import com.ai.assistance.operit.ui.features.settings.components.SettingsCardShape
import com.ai.assistance.operit.ui.components.CustomScaffold
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.data.api.CodexAuthManager
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.api.chat.llmprovider.ApiKeyPoolAvailabilityTester
import com.ai.assistance.operit.api.chat.llmprovider.ChatConfigReadiness
import com.ai.assistance.operit.api.chat.llmprovider.ChatConfigReadinessIssue
import com.ai.assistance.operit.api.chat.llmprovider.ModelConfigConnectionTester
import com.ai.assistance.operit.api.chat.llmprovider.ModelConnectionTestType
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.partitionConfigsByCollapsedIds
import com.ai.assistance.operit.data.model.normalizeProviderId
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.ui.features.settings.DebouncedModelConfigAutoSaveEffect
import com.ai.assistance.operit.ui.features.settings.RegisterModelConfigSaveAction
import com.ai.assistance.operit.ui.features.settings.rememberModelConfigSaveCoordinator
import com.ai.assistance.operit.ui.features.settings.sections.AdvancedSettingsSection
import com.ai.assistance.operit.ui.features.settings.sections.ModelApiSettingsSection
import com.ai.assistance.operit.ui.features.settings.sections.ModelParametersSection
import com.ai.assistance.operit.ui.features.settings.sections.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.sections.SettingsSectionHeader
import com.ai.assistance.operit.ui.features.settings.sections.SettingsSwitchRow
import com.ai.assistance.operit.ui.features.settings.sections.SettingsTextField
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry
import com.ai.assistance.operit.ui.main.navigation.RegisterRouteBackGuard
import com.ai.assistance.operit.util.AppLogger
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class HeaderPreset(val nameResId: Int, val headers: Map<String, String>)

enum class ModelConfigEntryMode {
    STANDARD,
    CHAT_ONBOARDING
}

private val headerPresets =
    listOf(
        HeaderPreset(
            nameResId = R.string.headers_preset_android_browser,
            headers =
                mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                )
        ),
        HeaderPreset(
            nameResId = R.string.headers_preset_desktop_browser_win,
            headers =
                mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                )
        ),
        HeaderPreset(
            nameResId = R.string.headers_preset_lang_zh,
            headers = mapOf("Accept-Language" to "zh-CN,zh;q=0.9")
        ),
        HeaderPreset(
            nameResId = R.string.headers_preset_lang_en,
            headers = mapOf("Accept-Language" to "en-US,en;q=0.9")
        ),
        HeaderPreset(
            nameResId = R.string.headers_preset_cmcc_gateway,
            headers = mapOf("X-Forwarded-For" to "211.136.1.10", "Via" to "CMNET")
        ),
        HeaderPreset(
            nameResId = R.string.headers_preset_us_la,
            headers =
                mapOf(
                    "Accept-Language" to "en-US,en;q=0.9",
                    "X-Forwarded-For" to "38.107.226.5"
                )
        )
    )

private fun parseHeaderEntries(headersJson: String): List<Pair<String, String>> {
    return runCatching {
        if (headersJson.isBlank() || headersJson == "{}") {
            emptyList()
        } else {
            val jsonObject = JSONObject(headersJson)
            buildList {
                for (key in jsonObject.keys()) {
                    add(key to jsonObject.getString(key))
                }
            }
        }
    }.getOrElse { emptyList() }
}

private fun serializeHeaderEntries(headers: List<Pair<String, String>>): String {
    return JSONObject().apply {
        headers.forEach { (key, value) ->
            val normalizedKey = key.trim()
            if (normalizedKey.isNotEmpty()) {
                put(normalizedKey, value)
            }
        }
    }.toString()
}

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelConfigScreen(
    entryMode: ModelConfigEntryMode = ModelConfigEntryMode.STANDARD
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configManager = remember { ModelConfigManager(context) }
    val functionalConfigManager = remember { FunctionalConfigManager(context) }
    val codexAuthManager = remember { CodexAuthManager.getInstance(context) }
    val codexAuthState by codexAuthManager.authState.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val saveCoordinator = rememberModelConfigSaveCoordinator()
    val snackbarHostState = remember { SnackbarHostState() }

    // 配置状态
    val configList = configManager.configListFlow.collectAsState(initial = listOf("default")).value
    // 进入页面时默认选中“对话功能”当前绑定的模型配置
    var selectedConfigId by remember { mutableStateOf(ModelConfigManager.DEFAULT_CONFIG_ID) }
    val selectedConfig = remember { mutableStateOf<ModelConfigData?>(null) }
    val keyAvailabilityTester =
        remember(selectedConfigId) {
            ApiKeyPoolAvailabilityTester(selectedConfigId, configManager)
        }
    var hasInitializedSelection by remember { mutableStateOf(false) }
    var isCompletingOnboarding by remember { mutableStateOf(false) }

    // 配置名称映射
    val configNameMap = remember { mutableStateMapOf<String, String>() }

    // UI状态
    var showAddConfigDialog by remember { mutableStateOf(false) }
    var showRenameConfigDialog by remember { mutableStateOf(false) }
    var showSaveSuccessMessage by remember { mutableStateOf(false) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var showConfigSelectDialog by remember { mutableStateOf(false) }
    var newConfigName by remember { mutableStateOf("") }
    var renameConfigName by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // 排序、收藏与折叠相关
    var configSummaries by remember { mutableStateOf<List<ModelConfigSummary>>(emptyList()) }
    val collapsedConfigIds by configManager.collapsedConfigIdsFlow.collectAsState(initial = emptySet())
    val sortedConfigIds = configList  // configList 已按全局顺序排列

    // 加载摘要列表
    LaunchedEffect(configList) {
        configSummaries = configManager.getAllConfigSummaries()
    }

    // 连接测试状态
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<List<ConnectionTestItem>?>(null) }
    var connectionTestJob by remember { mutableStateOf<Job?>(null) }
    var activeConnectionTestService by remember { mutableStateOf<AIService?>(null) }

    DisposableEffect(keyAvailabilityTester) {
        onDispose { keyAvailabilityTester.close() }
    }

    // 初始化配置，并默认定位到“对话功能模型”所使用的配置
    LaunchedEffect(Unit) {
        configManager.initializeIfNeeded()
        functionalConfigManager.initializeIfNeeded()

        val chatConfigId = functionalConfigManager.getConfigIdForFunction(FunctionType.CHAT)
        val availableConfigIds = configManager.configListFlow.first()
        selectedConfigId =
            availableConfigIds.firstOrNull { it == chatConfigId }
                ?: availableConfigIds.firstOrNull { it == ModelConfigManager.DEFAULT_CONFIG_ID }
                ?: availableConfigIds.firstOrNull()
                ?: ModelConfigManager.DEFAULT_CONFIG_ID
        hasInitializedSelection = true
    }

    // 加载所有配置名称
    LaunchedEffect(configList) {
        configList.forEach { id ->
            val config = configManager.getModelConfigFlow(id).first()
            configNameMap[id] = config.name
        }
    }

    // 加载选中的配置
    LaunchedEffect(selectedConfigId) {
        testResults = null
        selectedConfig.value = null
        configManager.getModelConfigFlow(selectedConfigId).collect { config ->
            selectedConfig.value = config
        }
    }

    // 显示通知消息
    fun showNotification(message: String) {
        confirmMessage = message
        showSaveSuccessMessage = true
        scope.launch {
            kotlinx.coroutines.delay(3000)
            showSaveSuccessMessage = false
        }
    }

    fun showOnboardingError(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    if (entryMode == ModelConfigEntryMode.CHAT_ONBOARDING) {
        RegisterRouteBackGuard {
            if (!hasInitializedSelection || isCompletingOnboarding) {
                return@RegisterRouteBackGuard false
            }

            val targetConfigId = selectedConfigId
            isCompletingOnboarding = true
            try {
                saveCoordinator.flushAll(showSuccess = false)
                if (selectedConfigId != targetConfigId) {
                    showOnboardingError(
                        context.getString(R.string.onboarding_config_apply_failed)
                    )
                    return@RegisterRouteBackGuard false
                }

                val targetConfig = configManager.getModelConfig(targetConfigId)
                if (targetConfig == null) {
                    showOnboardingError(
                        context.getString(R.string.onboarding_config_not_found)
                    )
                    return@RegisterRouteBackGuard false
                }

                val currentMapping =
                    functionalConfigManager.getConfigMappingForFunction(FunctionType.CHAT)
                val targetModelIndex =
                    if (currentMapping.configId == targetConfigId) {
                        currentMapping.modelIndex
                    } else {
                        0
                    }
                val registeredPluginProviderIds =
                    ToolPkgAiProviderRegistry.list().mapTo(mutableSetOf()) { it.providerId }
                val readiness =
                    ChatConfigReadiness.evaluate(
                        config = targetConfig,
                        modelIndex = targetModelIndex,
                        registeredPluginProviderIds = registeredPluginProviderIds,
                        codexAuthenticated = codexAuthState != null,
                        accountAuthenticated = com.ai.assistance.operit.data.api.AccountProvider.from(
                            com.ai.assistance.operit.data.model.ApiProviderType.fromProviderTypeId(targetConfig.apiProviderTypeId))?.let {
                                com.ai.assistance.operit.data.api.ProviderAccountManager.get(context, it).account.value != null
                            } == true,
                    )
                if (!readiness.isReady) {
                    val messageResId =
                        when (readiness.issue) {
                            ChatConfigReadinessIssue.PROVIDER_MISSING ->
                                R.string.onboarding_config_provider_missing
                            ChatConfigReadinessIssue.PROVIDER_UNAVAILABLE ->
                                R.string.onboarding_config_provider_unavailable
                            ChatConfigReadinessIssue.ENDPOINT_INVALID ->
                                R.string.onboarding_config_endpoint_invalid
                            ChatConfigReadinessIssue.MODEL_MISSING ->
                                R.string.onboarding_config_model_missing
                            ChatConfigReadinessIssue.CODEX_LOGIN_REQUIRED ->
                                R.string.onboarding_config_codex_login_required
                            ChatConfigReadinessIssue.ACCOUNT_LOGIN_REQUIRED ->
                                R.string.provider_account_login_required
                            ChatConfigReadinessIssue.API_KEY_MISSING ->
                                R.string.onboarding_config_api_key_missing
                            ChatConfigReadinessIssue.API_KEY_INVALID ->
                                R.string.onboarding_config_api_key_invalid
                            null -> R.string.onboarding_config_apply_failed
                        }
                    showOnboardingError(context.getString(messageResId))
                    return@RegisterRouteBackGuard false
                }

                functionalConfigManager.setConfigForFunction(
                    FunctionType.CHAT,
                    targetConfigId,
                    targetModelIndex
                )
                val savedMapping =
                    functionalConfigManager.getConfigMappingForFunction(FunctionType.CHAT)
                check(
                    savedMapping.configId == targetConfigId &&
                        savedMapping.modelIndex == targetModelIndex
                )
                EnhancedAIService.refreshServiceForFunction(
                    context.applicationContext,
                    FunctionType.CHAT
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("ModelConfigScreen", "首次引导配置保存或绑定失败", e)
                showOnboardingError(
                    context.getString(R.string.onboarding_config_apply_failed)
                )
                false
            } finally {
                isCompletingOnboarding = false
            }
        }
    }

    DisposableEffect(lifecycleOwner, saveCoordinator) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    saveCoordinator.flushAllInBackground(showSuccess = false)
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 主界面内容
    CustomScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SettingsCardShape,
                    colors = CardDefaults.cardColors(containerColor = rememberSettingsCardColor())
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.select_model_config),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            OutlinedButton(
                                onClick = { showAddConfigDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                                colors =
                                    ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    stringResource(R.string.new_action),
                                    fontSize = 12.sp,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        val selectedConfigName =
                            configNameMap[selectedConfigId]
                                ?: stringResource(R.string.default_profile)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showConfigSelectDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            tonalElevation = 0.5.dp,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = selectedConfigName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                @OptIn(ExperimentalAnimationApi::class)
                                AnimatedContent(
                                    targetState = isDropdownExpanded,
                                    transitionSpec = {
                                        (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                                    }
                                ) { expanded ->
                                    Icon(
                                        if (expanded) Icons.Default.KeyboardArrowUp
                                        else Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.select_config),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        FlowRow(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (selectedConfigId != "default") {
                                TextButton(
                                    onClick = {
                                        renameConfigName = selectedConfig.value?.name ?: ""
                                        showRenameConfigDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.rename_action), fontSize = 14.sp)
                                }

                                TextButton(
                                    onClick = {
                                        showDeleteConfirmDialog = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.delete_action), fontSize = 14.sp)
                                }
                            }

                            TextButton(
                                onClick = {
                                    if (isTestingConnection) {
                                        activeConnectionTestService?.cancelStreaming()
                                        connectionTestJob?.cancel()
                                        return@TextButton
                                    }

                                    connectionTestJob = scope.launch {
                                        try {
                                            isTestingConnection = true
                                            val results = mutableListOf<ConnectionTestItem>()
                                            try {
                                                saveCoordinator.flushAll(showSuccess = false)

                                                val latestConfig =
                                                    configManager.getModelConfig(selectedConfigId)

                                                latestConfig?.let { config ->
                                                    val report =
                                                        ModelConfigConnectionTester.run(
                                                            context = context,
                                                            modelConfigManager = configManager,
                                                            config = config,
                                                            onActiveServiceChanged = {
                                                                activeConnectionTestService = it
                                                            }
                                                        )

                                                    report.items.forEach { item ->
                                                        val result =
                                                            if (item.success) {
                                                                Result.success(Unit)
                                                            } else {
                                                                Result.failure(
                                                                    Exception(item.error ?: "Unknown error")
                                                                )
                                                            }
                                                        results.add(
                                                            ConnectionTestItem(
                                                                labelResId = item.type.toLabelResId(),
                                                                result = result
                                                            )
                                                        )
                                                    }
                                                } ?: run {
                                                    results.add(
                                                        ConnectionTestItem(
                                                            R.string.test_item_chat,
                                                            Result.failure<Unit>(
                                                                Exception(
                                                                    context.getString(
                                                                        R.string.no_config_selected
                                                                    )
                                                                )
                                                            )
                                                        )
                                                    )
                                                }
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                results.add(
                                                    ConnectionTestItem(
                                                        R.string.test_item_chat,
                                                        Result.failure<Unit>(e)
                                                    )
                                                )
                                            }
                                            testResults = results
                                        } finally {
                                            activeConnectionTestService = null
                                            isTestingConnection = false
                                            connectionTestJob = null
                                        }
                                    }
                                },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Dns,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(
                                        if (isTestingConnection) R.string.cancel
                                        else R.string.test_connection_desc
                                    ),
                                    fontSize = 14.sp
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = testResults != null,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            testResults?.let { results ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        results.forEachIndexed { index, item ->
                                            val isSuccess = item.result.isSuccess
                                            val statusText =
                                                if (isSuccess) {
                                                    context.getString(R.string.test_connection_success)
                                                } else {
                                                    context.getString(
                                                        R.string.test_connection_failed,
                                                        item.result.exceptionOrNull()?.message ?: ""
                                                    )
                                                }
                                            val contentColor =
                                                if (isSuccess) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.error
                                            val icon =
                                                if (isSuccess) Icons.Default.CheckCircle
                                                else Icons.Default.Warning

                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        tint = contentColor,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = stringResource(item.labelResId),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (isSuccess) {
                                                        Text(
                                                            text = statusText,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = contentColor
                                                        )
                                                    }
                                                }
                                                if (!isSuccess) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = statusText,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = contentColor
                                                    )
                                                }
                                            }

                                            if (index != results.lastIndex) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            selectedConfig.value?.let { config ->
                item {
                    ModelApiSettingsSection(
                        config = config,
                        configManager = configManager,
                        saveCoordinator = saveCoordinator,
                        showNotification = { message -> showNotification(message) },
                    )
                }

                item {
                    ContextSummarySettingsSection(
                        config = config,
                        configManager = configManager,
                        scope = scope,
                        showNotification = { message -> showNotification(message) }
                    )
                }

                item {
                    ModelParametersSection(
                        config = config,
                        configManager = configManager,
                        showNotification = { message -> showNotification(message) }
                    )
                }

                item {
                    CustomHeadersSettingsSection(
                        config = config,
                        configManager = configManager,
                        saveCoordinator = saveCoordinator,
                        showNotification = { message -> showNotification(message) }
                    )
                }

                item {
                    AdvancedSettingsSection(
                        config = config,
                        configManager = configManager,
                        saveCoordinator = saveCoordinator,
                        keyAvailabilityTester = keyAvailabilityTester,
                        showNotification = { message -> showNotification(message) }
                    )
                }
            }

            if (showSaveSuccessMessage) {
                item {
                    AnimatedVisibility(
                        visible = showSaveSuccessMessage,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = confirmMessage,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
            }
        }
        } // end LazyColumn

        // ====== 打开配置选择弹窗时先刷新 Summary ======
        LaunchedEffect(showConfigSelectDialog) {
            if (showConfigSelectDialog) {
                configSummaries = configManager.getAllConfigSummaries()
            }
        }

        // 配置选择拖拽弹窗
        if (showConfigSelectDialog) {
            ConfigSelectDialog(
                configSummaries = configSummaries,
                collapsedConfigIds = collapsedConfigIds,
                selectedConfigId = selectedConfigId,
                onSelectConfig = { configId ->
                    selectedConfigId = configId
                    showConfigSelectDialog = false
                },
                onReorder = { orderedIds ->
                    scope.launch {
                        configManager.updateConfigOrder(orderedIds)
                    }
                },
                onToggleConfigCollapsed = { configId ->
                    scope.launch {
                        configManager.toggleConfigCollapsed(configId)
                    }
                },
                onDismiss = { showConfigSelectDialog = false },
            )
        }

        // 新建配置对话框
        if (showAddConfigDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAddConfigDialog = false
                    newConfigName = ""
                },
                title = {
                    Text(
                        stringResource(R.string.new_model_config),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.new_model_config_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newConfigName,
                            onValueChange = { newConfigName = it },
                            label = {
                                Text(
                                    stringResource(R.string.model_config_name),
                                    fontSize = 12.sp
                                )
                            },
                            placeholder = {
                                Text(
                                    stringResource(R.string.model_config_name_placeholder),
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newConfigName.isNotBlank()) {
                                scope.launch {
                                    val configId = configManager.createConfig(newConfigName)
                                    selectedConfigId = configId
                                    showAddConfigDialog = false
                                    newConfigName = ""
                                    showNotification(context.getString(R.string.new_config_created))
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) { Text(stringResource(R.string.create_action), fontSize = 13.sp) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAddConfigDialog = false
                            newConfigName = ""
                        }
                    ) { Text(stringResource(R.string.cancel_action), fontSize = 13.sp) }
                },
                shape = RoundedCornerShape(12.dp)
            )
        }

        // 重命名配置对话框
        if (showRenameConfigDialog) {
            AlertDialog(
                onDismissRequest = {
                    showRenameConfigDialog = false
                    renameConfigName = ""
                },
                title = {
                    Text(
                        stringResource(R.string.rename_model_config),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.rename_model_config_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = renameConfigName,
                            onValueChange = { renameConfigName = it },
                            label = {
                                Text(
                                    stringResource(R.string.model_config_name),
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (renameConfigName.isNotBlank()) {
                                scope.launch {
                                    configManager.updateConfigBase(
                                        selectedConfigId,
                                        renameConfigName
                                    )
                                    configNameMap[selectedConfigId] = renameConfigName
                                    showRenameConfigDialog = false
                                    renameConfigName = ""
                                    showNotification(context.getString(R.string.config_renamed))
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) { Text(stringResource(R.string.confirm_rename), fontSize = 13.sp) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showRenameConfigDialog = false
                            renameConfigName = ""
                        }
                    ) { Text(stringResource(R.string.cancel_action), fontSize = 13.sp) }
                },
                shape = RoundedCornerShape(12.dp)
            )
        }

        // 删除配置确认对话框
        if (showDeleteConfirmDialog) {
            val configName = selectedConfig.value?.name ?: ""
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = {
                    Text(
                        stringResource(R.string.delete_config_confirm_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.delete_config_confirm_message, configName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                configManager.deleteConfig(selectedConfigId)
                                selectedConfigId = ModelConfigManager.DEFAULT_CONFIG_ID
                                showNotification(context.getString(R.string.config_deleted))
                            }
                            showDeleteConfirmDialog = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text(stringResource(R.string.delete_config_confirm_button), fontSize = 13.sp) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirmDialog = false },
                    ) { Text(stringResource(R.string.cancel_action), fontSize = 13.sp) }
                },
                shape = RoundedCornerShape(12.dp),
            )
        }

        if (isCompletingOnboarding) {
            Surface(
                modifier = Modifier.matchParentSize().clickable(enabled = true) {},
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomHeadersSettingsSection(
    config: ModelConfigData,
    configManager: ModelConfigManager,
    saveCoordinator: com.ai.assistance.operit.ui.features.settings.ModelConfigSaveCoordinator,
    showNotification: (String) -> Unit
) {
    val latestConfig by rememberUpdatedState(config)
    var headers by remember(config.id) { mutableStateOf(parseHeaderEntries(config.customHeaders)) }
    var headersExpanded by rememberSaveable(config.id) { mutableStateOf(false) }
    var showHeaderPresetsMenu by remember { mutableStateOf(false) }
    val saveFailedText = stringResource(R.string.save_failed)
    val saveMutex = remember(config.id) { Mutex() }

    LaunchedEffect(config.id, config.customHeaders) {
        headers = parseHeaderEntries(config.customHeaders)
    }

    suspend fun persistHeaders(serializedHeaders: String) {
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                configManager.updateCustomHeaders(latestConfig.id, serializedHeaders)
                EnhancedAIService.refreshAllServices(configManager.appContext)
            }
        }
    }

    RegisterModelConfigSaveAction(
        coordinator = saveCoordinator,
        key = "headers:${config.id}",
        action = { _ ->
            persistHeaders(serializeHeaderEntries(headers))
        }
    )

    DebouncedModelConfigAutoSaveEffect(
        effectKey = config.id,
        valueProvider = { serializeHeaderEntries(headers) },
        persist = { serializedHeaders -> persistHeaders(serializedHeaders) },
        onError = { e ->
            showNotification(e.message ?: saveFailedText)
        }
    )

    val configuredHeadersCount = headers.count { it.first.trim().isNotEmpty() }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { headersExpanded = !headersExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_custom_headers),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_custom_headers_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (configuredHeadersCount > 0) {
                    Text(
                        text =
                            stringResource(
                                R.string.headers_configured_count,
                                configuredHeadersCount
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector =
                        if (headersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = headersExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = { showHeaderPresetsMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.List,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.headers_load_preset))
                            }

                            OutlinedButton(onClick = { headers = headers + ("" to "") }) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.headers_add_header))
                            }
                        }

                        DropdownMenu(
                            expanded = showHeaderPresetsMenu,
                            onDismissRequest = { showHeaderPresetsMenu = false }
                        ) {
                            headerPresets.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(preset.nameResId)) },
                                    onClick = {
                                        val mergedHeaders =
                                            headers.associate { it.first to it.second }.toMutableMap()
                                        mergedHeaders.putAll(preset.headers)
                                        headers = mergedHeaders.toList()
                                        showHeaderPresetsMenu = false
                                    }
                                )
                            }
                        }
                    }

                    headers.forEachIndexed { index, header ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = header.first,
                                onValueChange = { newValue ->
                                    headers =
                                        headers.toMutableList().also {
                                            it[index] = newValue to header.second
                                        }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.headers_key_label)) },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = header.second,
                                onValueChange = { newValue ->
                                    headers =
                                        headers.toMutableList().also {
                                            it[index] = header.first to newValue
                                        }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.headers_value_label)) },
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    headers = headers.toMutableList().apply { removeAt(index) }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription =
                                        stringResource(R.string.headers_delete_header)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextSummarySettingsSection(
    config: ModelConfigData,
    configManager: ModelConfigManager,
    scope: CoroutineScope,
    showNotification: (String) -> Unit
) {
    var capacity by remember(config.id, config.maxContextLength) { mutableStateOf(formatFloatValue(config.maxContextLength)) }
    val value = capacity.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f && it <= 1_000_000f }
    val errorText = stringResource(R.string.chat_context_save_failed)
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsTextField(
            title = stringResource(R.string.settings_max_context_length),
            subtitle = stringResource(R.string.chat_context_model_hint),
            value = capacity, onValueChange = { capacity = it }, unitText = "K",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        )
        Button(enabled = value != null, onClick = {
            scope.launch {
                try {
                    val latest = configManager.getModelConfig(config.id) ?: return@launch
                    configManager.updateContextSettings(config.id, latest.contextLength,
                        requireNotNull(value), latest.enableMaxContextMode)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { showNotification(errorText) }
            }
        }) { Text(stringResource(R.string.save)) }
    }
}

private data class ConnectionTestItem(
    val labelResId: Int,
    val result: Result<Unit>
)

private fun ModelConnectionTestType.toLabelResId(): Int {
    return when (this) {
        ModelConnectionTestType.CHAT -> R.string.test_item_chat
        ModelConnectionTestType.TOOL_CALL -> R.string.test_item_toolcall
        ModelConnectionTestType.IMAGE -> R.string.test_item_image
        ModelConnectionTestType.AUDIO -> R.string.test_item_audio
        ModelConnectionTestType.VIDEO -> R.string.test_item_video
    }
}

private fun formatFloatValue(value: Float): String {
    return if (value % 1f == 0f) value.toInt().toString() else String.format("%.2f", value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigSelectDialog(
    configSummaries: List<ModelConfigSummary>,
    collapsedConfigIds: Set<String>,
    selectedConfigId: String,
    onSelectConfig: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onToggleConfigCollapsed: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var orderedSummaries by remember { mutableStateOf(configSummaries) }
    var isDragInProgress by remember { mutableStateOf(false) }
    var deferredSummaries by remember { mutableStateOf<List<ModelConfigSummary>?>(null) }
    val pendingOrder = remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(configSummaries) {
        if (isDragInProgress) {
            deferredSummaries = configSummaries
        } else {
            orderedSummaries = configSummaries
        }
    }

    // 分区分割
    val (normalConfigs, collapsedConfigs) = remember(orderedSummaries, collapsedConfigIds) {
        partitionConfigsByCollapsedIds(orderedSummaries, collapsedConfigIds)
    }

    var collapsedSectionExpanded by remember { mutableStateOf(false) }

    // 构建扁平化项目列表
    data class FlatItem(val type: String, val config: ModelConfigSummary? = null)
    val flatItems = buildList {
        normalConfigs.forEach { add(FlatItem("normal", it)) }
        if (collapsedConfigs.isNotEmpty()) {
            add(FlatItem("header"))
            if (collapsedSectionExpanded) {
                collapsedConfigs.forEach { add(FlatItem("collapsed", it)) }
            }
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromItem = flatItems.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        val toItem = flatItems.getOrNull(to.index) ?: return@rememberReorderableLazyListState

        if (fromItem.type == "header" || toItem.type == "header") return@rememberReorderableLazyListState
        if (fromItem.type != toItem.type) return@rememberReorderableLazyListState
        if (fromItem.type != "normal" || toItem.type != "normal") return@rememberReorderableLazyListState

        val newNormalIds = flatItems.filter { it.type == "normal" }.map { it.config!!.id }.toMutableList()
        val movedId = fromItem.config!!.id
        val fromIdx = newNormalIds.indexOf(movedId)
        val toIdx = if (to.index < flatItems.size) {
            val targetId = toItem.config!!.id
            newNormalIds.indexOf(targetId)
        } else fromIdx

        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        newNormalIds.add(toIdx, newNormalIds.removeAt(fromIdx))

        // 构建最终全局顺序: 正常区新顺序 + 折叠区原相对顺序（来自完整 collapsedConfigs，不是 flatItems）
        val collapsedOrderIds = collapsedConfigs.map { it.id }
        val newGlobalOrder = newNormalIds + collapsedOrderIds

        val summariesById = orderedSummaries.associateBy { it.id }
        orderedSummaries = newGlobalOrder.mapNotNull(summariesById::get)
        pendingOrder.value = newGlobalOrder
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.model_selector_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.weight(1f),
                ) {
                    items(flatItems.size, key = { index ->
                        val item = flatItems.getOrNull(index) ?: return@items "unknown"
                        when (item.type) {
                            "normal", "collapsed" -> "cfg-${item.config?.id}"
                            "header" -> "header"
                            else -> "item-$index"
                        }
                    }) { index ->
                        val item = flatItems[index]
                        when (item.type) {
                            "normal", "collapsed" -> {
                                val config = item.config!!
                                val isSelected = config.id == selectedConfigId
                                val isCollapsed = item.type == "collapsed"
                                ReorderableItem(
                                    reorderableState,
                                    key = "cfg-${config.id}",
                                    enabled = !isCollapsed,
                                ) { isDragging ->
                                    val elevation = if (isDragging) 4.dp else 0.dp
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        shadowElevation = elevation,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onSelectConfig(config.id) }
                                                .padding(vertical = 10.dp, horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // 正常区显示拖动手柄
                                            if (!isCollapsed) {
                                                Icon(
                                                    Icons.Default.DragHandle,
                                                    contentDescription = stringResource(R.string.drag_to_reorder),
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .draggableHandle(
                                                            onDragStarted = {
                                                                pendingOrder.value = null
                                                                deferredSummaries = null
                                                                isDragInProgress = true
                                                            },
                                                            onDragStopped = {
                                                                val finalOrder = pendingOrder.value
                                                                deferredSummaries?.let { latestSummaries ->
                                                                    val latestById = latestSummaries.associateBy { it.id }
                                                                    val orderedIds =
                                                                        finalOrder
                                                                            ?: orderedSummaries.map { it.id }
                                                                    val orderedIdSet = orderedIds.toSet()
                                                                    orderedSummaries =
                                                                        orderedIds.mapNotNull(latestById::get) +
                                                                            latestSummaries.filter {
                                                                                it.id !in orderedIdSet
                                                                            }
                                                                }
                                                                isDragInProgress = false
                                                                deferredSummaries = null
                                                                finalOrder?.let(onReorder)
                                                                pendingOrder.value = null
                                                            },
                                                        ),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            // 选中标记在折叠按钮左侧
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = stringResource(R.string.selected_desc),
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = config.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                                )
                                                val provider = config.apiProviderType
                                                val modelInfo = getModelList(config.modelName).let { models ->
                                                    if (models.size == 1) models.first()
                                                    else "${models.size} models"
                                                }
                                                if (provider.name.isNotEmpty()) {
                                                    Text(
                                                        text = "${provider.name} · $modelInfo",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            // 折叠/取消折叠按钮
                                            IconButton(
                                                onClick = { onToggleConfigCollapsed(config.id) },
                                                modifier = Modifier.size(28.dp),
                                            ) {
                                                Icon(
                                                    imageVector = if (isCollapsed) Icons.Default.ArrowBack
                                                    else Icons.Default.UnfoldLess,
                                                    contentDescription = if (isCollapsed)
                                                        stringResource(R.string.uncollapse_model_config)
                                                    else
                                                        stringResource(R.string.collapse_model_config),
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            "header" -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { collapsedSectionExpanded = !collapsedSectionExpanded }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.collapsed_model_configs_count, collapsedConfigs.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = if (collapsedSectionExpanded) Icons.Default.KeyboardArrowUp
                                        else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel_action))
                    }
                }
            }
        }
    }
}
