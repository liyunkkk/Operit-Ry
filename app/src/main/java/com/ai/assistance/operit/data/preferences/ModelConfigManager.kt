package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.R
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.CustomParameterData
import com.ai.assistance.operit.data.model.FavoriteModelRef
import com.ai.assistance.operit.data.model.ModelConfigBackup
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelMultimodalCapabilities
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.mergeCollapsedConfigIds
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.model.StandardModelParameters
import com.ai.assistance.operit.data.model.SummarySectionOverride
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ApiKeyInfo
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.data.model.normalizeProviderId
import com.ai.assistance.operit.data.model.normalizeConfigOrder
import com.ai.assistance.operit.data.model.withModelNames
import com.ai.assistance.operit.data.model.withDirectAudioProcessingForAllModels
import com.ai.assistance.operit.data.model.withDirectImageProcessingForAllModels
import com.ai.assistance.operit.data.model.withDirectVideoProcessingForAllModels
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 为ModelConfig创建专用的DataStore
private val Context.modelConfigDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "model_configs")

// 获取ApiPreferences的DataStore
private val Context.apiDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "api_settings")

class ModelConfigManager(
        private val context: Context,
        configDataStore: DataStore<Preferences> = context.modelConfigDataStore
) {

    private val configDataStore = configDataStore

    // 提供context访问器
    val appContext: Context
        get() = context

    // 定义key
    companion object {
        // 配置相关key
        val CONFIG_LIST_KEY = stringPreferencesKey("config_list")
        val FAVORITE_MODELS_KEY = stringPreferencesKey("favorite_models")
        val COLLAPSED_PROVIDER_IDS_KEY = stringPreferencesKey("collapsed_provider_ids")
        val COLLAPSED_CONFIG_IDS_KEY = stringPreferencesKey("collapsed_config_ids")

        // 当前备份版本号
        const val BACKUP_VERSION = 2

        // 默认值
        const val DEFAULT_CONFIG_ID = "default"
        const val DEFAULT_CONFIG_NAME = "model_config_default_name"

        // Default API provider type
        private val DEFAULT_API_PROVIDER_TYPE = ApiProviderType.DEEPSEEK
    }

    // Json解析器，支持宽松模式
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 获取所有配置ID列表
    val configListFlow: Flow<List<String>> =
            configDataStore.data.map { preferences ->
                val configList = preferences[CONFIG_LIST_KEY] ?: ""
                if (configList.isEmpty()) emptyList()
                else json.decodeFromString<List<String>>(configList)
            }

    // 所有配置摘要的响应式版本。主界面模型选择器必须持续收集它，才能在配置页
    // 新增或修改模型后立即更新，而不需要重新进入配置页。
    val configSummariesFlow: Flow<List<ModelConfigSummary>> =
            configDataStore.data.map { preferences ->
                readConfigSummariesFromPrefs(preferences)
            }

    // 收藏模型列表 Flow
    val favoriteModelsFlow: Flow<List<FavoriteModelRef>> =
            configDataStore.data.map { preferences ->
                val raw = preferences[FAVORITE_MODELS_KEY] ?: ""
                if (raw.isEmpty()) emptyList()
                else {
                    try {
                        json.decodeFromString<List<FavoriteModelRef>>(raw)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }

    // 折叠的提供商 ID 集合 Flow（外部使用 Set 语义）
    val collapsedProviderIdsFlow: Flow<Set<String>> =
            configDataStore.data.map { preferences ->
                val raw = preferences[COLLAPSED_PROVIDER_IDS_KEY] ?: ""
                if (raw.isEmpty()) emptySet()
                else {
                    try {
                        json.decodeFromString<List<String>>(raw)
                            .map { normalizeProviderId(it) }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    } catch (_: Exception) {
                        emptySet()
                    }
                }
            }

    // 折叠的配置 ID 集合 Flow（使用 config ID 精确匹配，不做大小写归一化）
    val collapsedConfigIdsFlow: Flow<Set<String>> =
            configDataStore.data.map { preferences ->
                val raw = preferences[COLLAPSED_CONFIG_IDS_KEY] ?: ""
                if (raw.isEmpty()) emptySet()
                else {
                    try {
                        json.decodeFromString<List<String>>(raw)
                            .filter { it.isNotEmpty() }
                            .toSet()
                    } catch (_: Exception) {
                        emptySet()
                    }
                }
            }

    // 删除获取当前活跃配置ID的流

    // 初始化，确保至少有一个默认配置
    suspend fun initializeIfNeeded() {
        // 检查配置列表，如果为空则创建默认配置
        // This is important for first-time users
        val configList = configListFlow.first()
        if (configList.isEmpty()) {
            val defaultConfig = createFreshDefaultConfig()
            saveConfigToDataStore(defaultConfig)

            // 保存配置列表，移除活跃ID
            configDataStore.edit { preferences ->
                preferences[CONFIG_LIST_KEY] = json.encodeToString(listOf(DEFAULT_CONFIG_ID))
            }
        } else {
            AppLogger.d("CONFIG_TIMING", "配置列表不为空，跳过初始化")
        }
    }

    // 从原有ApiPreferences创建默认配置
    private fun createFreshDefaultConfig(): ModelConfigData {
        return ModelConfigData(
                id = DEFAULT_CONFIG_ID,
                name = context.getString(R.string.model_config_default_name),
                apiKey = "",
                apiEndpoint = ApiPreferences.DEFAULT_API_ENDPOINT,
                modelName = ApiPreferences.DEFAULT_MODEL_NAME,
                apiProviderType = DEFAULT_API_PROVIDER_TYPE,
                apiProviderTypeId = DEFAULT_API_PROVIDER_TYPE.name,
                hasCustomParameters = false,
                maxTokensEnabled = false,
                temperatureEnabled = false,
                topPEnabled = false,
                topKEnabled = false,
                presencePenaltyEnabled = false,
                frequencyPenaltyEnabled = false,
                repetitionPenaltyEnabled = false,
                maxTokens = StandardModelParameters.DEFAULT_MAX_TOKENS,
                temperature = StandardModelParameters.DEFAULT_TEMPERATURE,
                topP = StandardModelParameters.DEFAULT_TOP_P,
                topK = StandardModelParameters.DEFAULT_TOP_K,
                presencePenalty = StandardModelParameters.DEFAULT_PRESENCE_PENALTY,
                frequencyPenalty = StandardModelParameters.DEFAULT_FREQUENCY_PENALTY,
                repetitionPenalty = StandardModelParameters.DEFAULT_REPETITION_PENALTY,
                customParameters = "[]"
        )
    }

    // 保存配置
    suspend fun saveModelConfig(config: ModelConfigData) {
        val changed = prefixCapabilities(loadConfigFromDataStore(config.id)) != prefixCapabilities(config)
        val configKey = stringPreferencesKey("config_${config.id}")
        configDataStore.edit { preferences ->
            preferences[configKey]?.let { raw ->
                val old = json.decodeFromString<ModelConfigData>(raw)
                if (old.modelName != config.modelName) {
                    FunctionalConfigManager(context)
                        .pinModelBindingsBeforeConfigChange(config.id, old.modelName)
                }
            }
            preferences[configKey] = json.encodeToString(config)
        }
        if (changed) LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    private fun prefixCapabilities(config: ModelConfigData?) = config?.let {
        listOf(it.enableDirectImageProcessing, it.enableDirectAudioProcessing,
            it.enableDirectVideoProcessing, it.enableToolCall,
            it.modelMultimodalCapabilities, it.modelProtocolSettings, it.apiProviderType, it.modelName)
    }

    // 从DataStore加载配置
    private suspend fun loadConfigFromDataStore(configId: String): ModelConfigData? {
        val configKey = stringPreferencesKey("config_${configId}")
        return configDataStore.data.first().let { preferences ->
            val configJson = preferences[configKey]
            if (configJson != null) {
                try {
                    json.decodeFromString<ModelConfigData>(configJson)
                } catch (e: Exception) {
                    // 如果解析失败，回退到创建一个新配置
                    if (configId == DEFAULT_CONFIG_ID) {
                        createFreshDefaultConfig()
                    } else {
                        ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
                    }
                }
            } else {
                if (configId == DEFAULT_CONFIG_ID) {
                    createFreshDefaultConfig()
                } else {
                    ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
                }
            }
        }
    }

    // 将配置保存到DataStore
    private suspend fun saveConfigToDataStore(config: ModelConfigData) {
        val configKey = stringPreferencesKey("config_${config.id}")
        configDataStore.edit { preferences ->
            preferences[configKey] = json.encodeToString(config)
        }
    }

    // 从 Preferences 中读取配置 ID 列表（不做 Flow 操作，仅在 edit 块内调用）
    private fun readConfigListFromPrefs(prefs: Preferences): List<String> {
        val raw = prefs[CONFIG_LIST_KEY] ?: ""
        return if (raw.isEmpty()) emptyList()
        else {
            try {
                json.decodeFromString<List<String>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun readConfigSummariesFromPrefs(prefs: Preferences): List<ModelConfigSummary> {
        return readConfigListFromPrefs(prefs).map { configId ->
            val configJson = prefs[stringPreferencesKey("config_${configId}")]
            val config =
                    if (configJson != null) {
                        try {
                            json.decodeFromString<ModelConfigData>(configJson)
                        } catch (_: Exception) {
                            fallbackConfigFor(configId)
                        }
                    } else {
                        fallbackConfigFor(configId)
                    }
            ModelConfigSummary(
                    id = config.id,
                    name = config.name,
                    modelName = config.modelName,
                    apiEndpoint = config.apiEndpoint,
                    apiProviderType = config.apiProviderType,
                    apiProviderTypeId = config.apiProviderTypeId,
                    modelProtocolSettings = config.modelProtocolSettings,
            )
        }
    }

    private fun fallbackConfigFor(configId: String): ModelConfigData {
        return if (configId == DEFAULT_CONFIG_ID) {
            createFreshDefaultConfig()
        } else {
            ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
        }
    }

    private fun readFavoriteModelsFromPrefs(prefs: Preferences): List<FavoriteModelRef> {
        val raw = prefs[FAVORITE_MODELS_KEY] ?: ""
        return if (raw.isEmpty()) emptyList()
        else {
            try {
                json.decodeFromString<List<FavoriteModelRef>>(raw)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun readCollapsedProviderIdsFromPrefs(prefs: Preferences): Set<String> {
        val raw = prefs[COLLAPSED_PROVIDER_IDS_KEY] ?: ""
        return if (raw.isEmpty()) emptySet()
        else {
            try {
                json.decodeFromString<List<String>>(raw)
                    .map { normalizeProviderId(it) }
                    .filter { it.isNotEmpty() }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    private fun readCollapsedConfigIdsFromPrefs(prefs: Preferences): Set<String> {
        val raw = prefs[COLLAPSED_CONFIG_IDS_KEY] ?: ""
        return if (raw.isEmpty()) emptySet()
        else {
            try {
                json.decodeFromString<List<String>>(raw)
                    .filter { it.isNotEmpty() }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    private suspend fun updateConfigInternal(
            configId: String,
            transform: (ModelConfigData) -> ModelConfigData
    ): ModelConfigData {
        val configKey = stringPreferencesKey("config_${configId}")
        var updated: ModelConfigData? = null
        var prefixChanged = false
        configDataStore.edit { preferences ->
            val current =
                    run {
                        val configJson = preferences[configKey]
                        if (configJson != null) {
                            try {
                                json.decodeFromString<ModelConfigData>(configJson)
                            } catch (e: Exception) {
                                if (configId == DEFAULT_CONFIG_ID) {
                                    createFreshDefaultConfig()
                                } else {
                                    ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
                                }
                            }
                        } else {
                            if (configId == DEFAULT_CONFIG_ID) {
                                createFreshDefaultConfig()
                            } else {
                                ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
                            }
                        }
                    }

            val newConfig = transform(current)
            if (current.modelName != newConfig.modelName) {
                FunctionalConfigManager(context)
                    .pinModelBindingsBeforeConfigChange(configId, current.modelName)
            }
            prefixChanged = prefixCapabilities(current) != prefixCapabilities(newConfig)
            preferences[configKey] = json.encodeToString(newConfig)
            updated = newConfig
        }
        if (prefixChanged) LearningPromptSnapshotRepository.markChanged(context, "settings")
        return updated ?: ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
    }

    // 获取指定ID的配置
    fun getModelConfigFlow(configId: String): Flow<ModelConfigData> {
        return configDataStore.data.map { preferences ->
            val config = loadConfigFromDataStore(configId) ?: ModelConfigData(id = configId, name = context.getString(R.string.model_config_config_id, configId))
            config
        }
    }

    // 获取指定ID的配置的非Flow版本
    suspend fun getModelConfig(configId: String): ModelConfigData? {
        return loadConfigFromDataStore(configId)
    }

    // 更新API Key池的当前索引
    suspend fun updateConfigKeyIndex(configId: String, newIndex: Int) {
        updateConfigInternal(configId) { it.copy(currentKeyIndex = newIndex) }
    }

    suspend fun updateSingleApiKey(configId: String, apiKey: String): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(apiKey = apiKey, useMultipleApiKeys = false)
        }
    }

    // 获取所有配置的摘要信息
    suspend fun getAllConfigSummaries(): List<ModelConfigSummary> {
        return readConfigSummariesFromPrefs(configDataStore.data.first())
    }

    // ---------- 排序、收藏、折叠 ----------

    /**
     * 更新配置 ID 的全局排序。在单个 DataStore edit 中原子化完成。
     */
    suspend fun updateConfigOrder(requestedOrder: List<String>) {
        configDataStore.edit { preferences ->
            val currentIds = readConfigListFromPrefs(preferences)
            val normalized = normalizeConfigOrder(requestedOrder, currentIds)
            preferences[CONFIG_LIST_KEY] = json.encodeToString(normalized)
        }
    }

    /**
     * 切换某个模型（configId + modelName）的收藏状态。
     * 已在收藏中则移除，否则追加到末尾。
     */
    suspend fun toggleFavoriteModel(configId: String, modelName: String) {
        if (modelName.isBlank()) return
        configDataStore.edit { preferences ->
            val current = readFavoriteModelsFromPrefs(preferences)
            val ref = FavoriteModelRef(configId, modelName)
            val key = ref.configId to ref.modelName
            val updated = if (current.any { it.configId to it.modelName == key }) {
                current.filter { it.configId to it.modelName != key }
            } else {
                current + ref
            }
            preferences[FAVORITE_MODELS_KEY] = json.encodeToString(updated)
        }
    }

    /**
     * 切换某个提供商（providerTypeId）的折叠状态。
     * 已在折叠集中则移除，否则加入。
     */
    suspend fun toggleProviderCollapsed(providerTypeId: String) {
        val normalized = normalizeProviderId(providerTypeId)
        if (normalized.isEmpty()) return
        configDataStore.edit { preferences ->
            val current = readCollapsedProviderIdsFromPrefs(preferences)
            val updated = if (normalized in current) {
                current - normalized
            } else {
                current + normalized
            }
            preferences[COLLAPSED_PROVIDER_IDS_KEY] =
                json.encodeToString(updated.toList())
        }
    }

    /**
     * 切换某个配置（configId）的折叠状态。
     * 已在折叠集中则移除，否则加入。
     */
    suspend fun toggleConfigCollapsed(configId: String) {
        if (configId.isBlank()) return
        configDataStore.edit { preferences ->
            val current = readCollapsedConfigIdsFromPrefs(preferences)
            val updated = if (configId in current) {
                current - configId
            } else {
                current + configId
            }
            preferences[COLLAPSED_CONFIG_IDS_KEY] =
                json.encodeToString(updated.toList())
        }
    }

    // 创建新配置
    suspend fun createConfig(name: String): String {
        val configId = UUID.randomUUID().toString()

        val newConfig =
                ModelConfigData(
                        id = configId,
                        name = name,
                        apiProviderType = ApiProviderType.OPENAI_GENERIC,
                        apiProviderTypeId = ApiProviderType.OPENAI_GENERIC.name,
                        enableToolCall = true
                )

        configDataStore.edit { preferences ->
            // 保存新配置
            preferences[stringPreferencesKey("config_${configId}")] = json.encodeToString(newConfig)
            // 原子化更新配置列表
            val currentList = readConfigListFromPrefs(preferences).toMutableList()
            currentList.add(configId)
            preferences[CONFIG_LIST_KEY] = json.encodeToString(currentList)
        }

        return configId
    }

    // 删除配置
    suspend fun deleteConfig(configId: String) {
        if (configId == DEFAULT_CONFIG_ID) {
            // 不允许删除默认配置
            return
        }

        configDataStore.edit { preferences ->
            preferences[stringPreferencesKey("config_${configId}")]?.let { raw ->
                FunctionalConfigManager(context).pinModelBindingsBeforeConfigChange(
                    configId, json.decodeFromString<ModelConfigData>(raw).modelName,
                )
            }
            // 从列表中移除
            val currentList = readConfigListFromPrefs(preferences).toMutableList()
            currentList.remove(configId)
            preferences[CONFIG_LIST_KEY] = json.encodeToString(currentList)
            // 删除配置记录
            preferences.remove(stringPreferencesKey("config_${configId}"))
            // 清理关联的收藏
            val favorites = readFavoriteModelsFromPrefs(preferences)
                .filter { it.configId != configId }
            preferences[FAVORITE_MODELS_KEY] = json.encodeToString(favorites)
            // 清理该配置的折叠状态
            val collapsedConfigIds = readCollapsedConfigIdsFromPrefs(preferences) - configId
            preferences[COLLAPSED_CONFIG_IDS_KEY] =
                json.encodeToString(collapsedConfigIds.toList())
        }
    }

    // 更新配置基本信息（名称等）
    suspend fun updateConfigBase(configId: String, name: String): ModelConfigData {
        return updateConfigInternal(configId) { it.copy(name = name) }
    }

    // 更新模型配置
    suspend fun updateModelConfig(
            configId: String,
            apiKey: String,
            apiEndpoint: String,
            modelName: String
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.withModelNames(modelName).copy(apiKey = apiKey, apiEndpoint = apiEndpoint)
        }
    }

    // 更新模型配置 - 包含API提供商类型
    suspend fun updateModelConfig(
            configId: String,
            apiKey: String,
            apiEndpoint: String,
            modelName: String,
            apiProviderType: com.ai.assistance.operit.data.model.ApiProviderType,
            apiProviderTypeId: String = apiProviderType.name
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.withModelNames(modelName).copy(
                    apiKey = apiKey,
                    apiEndpoint = apiEndpoint,
                    apiProviderType = apiProviderType,
                    apiProviderTypeId = apiProviderTypeId
            )
        }
    }

    // 更新模型配置 - 包含API提供商类型和MNN配置
    suspend fun updateModelConfig(
            configId: String,
            apiKey: String,
            apiEndpoint: String,
            modelName: String,
            apiProviderType: com.ai.assistance.operit.data.model.ApiProviderType,
            apiProviderTypeId: String = apiProviderType.name,
            mnnForwardType: Int,
            mnnThreadCount: Int
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.withModelNames(modelName).copy(
                    apiKey = apiKey,
                    apiEndpoint = apiEndpoint,
                    apiProviderType = apiProviderType,
                    apiProviderTypeId = apiProviderTypeId,
                    mnnForwardType = mnnForwardType,
                    mnnThreadCount = mnnThreadCount
            )
        }
    }

    suspend fun updateApiSettingsFull(
            configId: String,
            apiKey: String,
            apiEndpoint: String,
            modelName: String,
            apiProviderType: ApiProviderType,
            apiProviderTypeId: String = apiProviderType.name,
            mnnForwardType: Int,
            mnnThreadCount: Int,
            llamaThreadCount: Int,
            llamaContextSize: Int,
            llamaGpuLayers: Int,
            enableDirectImageProcessing: Boolean,
            enableDirectAudioProcessing: Boolean,
            enableDirectVideoProcessing: Boolean,
            modelMultimodalCapabilities: Map<String, ModelMultimodalCapabilities>,
            enableGoogleSearch: Boolean,
            enableClaude1hPromptCache: Boolean,
            enableToolCall: Boolean,
            modelProtocolSettings: Map<String, com.ai.assistance.operit.data.model.ModelProtocolSettings>? = null,
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    apiKey = apiKey,
                    apiEndpoint = apiEndpoint,
                    modelName = modelName,
                    apiProviderType = apiProviderType,
                    apiProviderTypeId = apiProviderTypeId,
                    mnnForwardType = mnnForwardType,
                    mnnThreadCount = mnnThreadCount,
                    llamaThreadCount = llamaThreadCount.coerceAtLeast(1),
                    llamaContextSize = llamaContextSize.coerceAtLeast(1),
                    llamaGpuLayers = llamaGpuLayers.coerceAtLeast(0),
                    enableDirectImageProcessing = enableDirectImageProcessing,
                    enableDirectAudioProcessing = enableDirectAudioProcessing,
                    enableDirectVideoProcessing = enableDirectVideoProcessing,
                    modelMultimodalCapabilities = modelMultimodalCapabilities,
                    modelProtocolSettings = (modelProtocolSettings ?: it.modelProtocolSettings)
                        .filterKeys { model -> model in com.ai.assistance.operit.data.model.getModelList(modelName) },
                    enableGoogleSearch = enableGoogleSearch,
                    enableClaude1hPromptCache = enableClaude1hPromptCache,
                    enableToolCall = enableToolCall
            )
        }
    }

    suspend fun updateCustomHeaders(configId: String, customHeaders: String): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(customHeaders = customHeaders)
        }
    }

    suspend fun updateRequestQueueSettings(
            configId: String,
            requestLimitPerMinute: Int,
            maxConcurrentRequests: Int
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    requestLimitPerMinute = requestLimitPerMinute.coerceAtLeast(0),
                    maxConcurrentRequests = maxConcurrentRequests.coerceAtLeast(0)
            )
        }
    }

    suspend fun updateApiKeyPoolSettings(
            configId: String,
            useMultipleApiKeys: Boolean,
            apiKeyPool: List<ApiKeyInfo>
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    useMultipleApiKeys = useMultipleApiKeys,
                    apiKeyPool = apiKeyPool
            )
        }
    }

    // 更新自定义参数
    suspend fun updateCustomParameters(configId: String, parametersJson: String): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    customParameters = parametersJson,
                    hasCustomParameters = parametersJson.isNotBlank() && parametersJson != "[]"
            )
        }
    }

    // 更新参数 - 新增方法
    suspend fun updateParameters(configId: String, parameters: List<ModelParameter<*>>) {
        // 提取自定义参数并序列化
        val customParams = parameters.filter { it.isCustom }
        val customParamsJson = if (customParams.isNotEmpty()) {
            val customParamsData = customParams.map { it.toCustomParameterData() }
            json.encodeToString(customParamsData)
        } else {
            "[]"
        }

        updateConfigInternal(configId) { current ->
            current.copy(
                    maxTokens =
                            parameters.find { it.id == "max_tokens" }?.currentValue as Int?
                                    ?: current.maxTokens,
                    maxTokensEnabled =
                            parameters.find { it.id == "max_tokens" }?.isEnabled
                                    ?: current.maxTokensEnabled,
                    temperature =
                            parameters.find { it.id == "temperature" }?.currentValue as Float?
                                    ?: current.temperature,
                    temperatureEnabled =
                            parameters.find { it.id == "temperature" }?.isEnabled
                                    ?: current.temperatureEnabled,
                    topP =
                            parameters.find { it.id == "top_p" }?.currentValue as Float?
                                    ?: current.topP,
                    topPEnabled =
                            parameters.find { it.id == "top_p" }?.isEnabled
                                    ?: current.topPEnabled,
                    topK =
                            parameters.find { it.id == "top_k" }?.currentValue as Int?
                                    ?: current.topK,
                    topKEnabled =
                            parameters.find { it.id == "top_k" }?.isEnabled
                                    ?: current.topKEnabled,
                    presencePenalty =
                            parameters.find { it.id == "presence_penalty" }?.currentValue as Float?
                                    ?: current.presencePenalty,
                    presencePenaltyEnabled =
                            parameters.find { it.id == "presence_penalty" }?.isEnabled
                                    ?: current.presencePenaltyEnabled,
                    frequencyPenalty =
                            parameters.find { it.id == "frequency_penalty" }?.currentValue as Float?
                                    ?: current.frequencyPenalty,
                    frequencyPenaltyEnabled =
                            parameters.find { it.id == "frequency_penalty" }?.isEnabled
                                    ?: current.frequencyPenaltyEnabled,
                    repetitionPenalty =
                            parameters.find { it.id == "repetition_penalty" }?.currentValue as Float?
                                    ?: current.repetitionPenalty,
                    repetitionPenaltyEnabled =
                            parameters.find { it.id == "repetition_penalty" }?.isEnabled
                                    ?: current.repetitionPenaltyEnabled,
                    customParameters = customParamsJson,
                    hasCustomParameters = customParams.isNotEmpty()
            )
        }
    }

    // 更新图片直接处理配置
    suspend fun updateDirectImageProcessing(configId: String, enableDirectImageProcessing: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.withDirectImageProcessingForAllModels(enableDirectImageProcessing)
        }
    }

    suspend fun updateDirectAudioProcessing(configId: String, enableDirectAudioProcessing: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.withDirectAudioProcessingForAllModels(enableDirectAudioProcessing)
        }
    }

    suspend fun updateDirectVideoProcessing(configId: String, enableDirectVideoProcessing: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.withDirectVideoProcessingForAllModels(enableDirectVideoProcessing)
        }
    }

    // 更新 Google Search Grounding 配置 (仅Gemini支持)
    suspend fun updateGoogleSearch(configId: String, enableGoogleSearch: Boolean): ModelConfigData {
        return updateConfigInternal(configId) { it.copy(enableGoogleSearch = enableGoogleSearch) }
    }

    suspend fun updateClaude1hPromptCache(configId: String, enableClaude1hPromptCache: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(enableClaude1hPromptCache = enableClaude1hPromptCache)
        }
    }

    // 更新 Tool Call 配置
    suspend fun updateToolCall(configId: String, enableToolCall: Boolean): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(enableToolCall = enableToolCall)
        }
    }

    suspend fun updateContextSettings(
            configId: String,
            contextLength: Float,
            maxContextLength: Float,
            enableMaxContextMode: Boolean
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    contextLength = contextLength,
                    maxContextLength = maxContextLength,
                    enableMaxContextMode = enableMaxContextMode
            )
        }
    }

    suspend fun updateSummarySettings(
            configId: String,
            enableSummary: Boolean? = null,
            summaryTokenThreshold: Float? = null,
            enableSummaryByMessageCount: Boolean? = null,
            summaryMessageCountThreshold: Int? = null,
            summaryCustomRules: String? = null,
            summarySectionOverrides: List<SummarySectionOverride>? = null
    ): ModelConfigData {
        return updateConfigInternal(configId) {
            it.copy(
                    enableSummary = enableSummary ?: it.enableSummary,
                    summaryTokenThreshold = summaryTokenThreshold ?: it.summaryTokenThreshold,
                    enableSummaryByMessageCount = enableSummaryByMessageCount ?: it.enableSummaryByMessageCount,
                    summaryMessageCountThreshold = summaryMessageCountThreshold ?: it.summaryMessageCountThreshold,
                    summaryCustomRules = summaryCustomRules ?: it.summaryCustomRules,
                    summarySectionOverrides =
                        summarySectionOverrides ?: it.summarySectionOverrides
            )
        }
    }

    /**
     * 根据配置ID获取完整的模型参数列表（包括标准和自定义参数）
     * @param configId 配置ID
     * @return 模型参数列表
     */
    suspend fun updateSummaryDialogueReviewSettings(
        configId: String,
        enabled: Boolean,
        title: String,
    ): ModelConfigData = updateConfigInternal(configId) {
        it.copy(enableSummaryDialogueReview = enabled, summaryDialogueReviewTitle = title)
    }

    suspend fun getModelParametersForConfig(configId: String): List<ModelParameter<*>> {
        val config = getModelConfigFlow(configId).first()
        return getModelParametersForConfigSnapshot(config)
    }

    /**
     * 从已经读取的配置快照构造完整模型参数列表。
     *
     * 调用方需要让服务、模型配置与请求参数严格对应时，应使用创建服务时的同一份快照，
     * 避免再次读取 DataStore 后混入更新中的参数。
     */
    fun getModelParametersForConfigSnapshot(
        config: ModelConfigData,
    ): List<ModelParameter<*>> {
        val parameters = mutableListOf<ModelParameter<*>>()

        // 映射标准参数
        StandardModelParameters.DEFINITIONS.forEach { def ->
            val (currentValue, isEnabled) =
                    when (def.id) {
                        "max_tokens" -> config.maxTokens to config.maxTokensEnabled
                        "temperature" -> config.temperature to config.temperatureEnabled
                        "top_p" -> config.topP to config.topPEnabled
                        "top_k" -> config.topK to config.topKEnabled
                        "presence_penalty" -> config.presencePenalty to config.presencePenaltyEnabled
                        "frequency_penalty" ->
                                config.frequencyPenalty to config.frequencyPenaltyEnabled
                        "repetition_penalty" ->
                                config.repetitionPenalty to config.repetitionPenaltyEnabled
                        else -> null to null
                    }

            if (currentValue != null && isEnabled != null) {
                parameters.add(
                        ModelParameter(
                                id = def.id,
                                name = def.name,
                                apiName = def.apiName,
                                description = def.description,
                                defaultValue = def.defaultValue,
                                currentValue = currentValue,
                                isEnabled = isEnabled,
                                valueType = def.valueType,
                                minValue = def.minValue,
                                maxValue = def.maxValue,
                                category = def.category
                        )
                )
            }
        }

        // 添加自定义参数
        if (config.hasCustomParameters &&
                        config.customParameters.isNotBlank() &&
                        config.customParameters != "[]"
        ) {
            try {
                val customParamsData =
                        json.decodeFromString<List<com.ai.assistance.operit.data.model.CustomParameterData>>(
                                config.customParameters
                        )
                customParamsData.forEach { data ->
                    val valueType = ParameterValueType.valueOf(data.valueType)
                    val category = ParameterCategory.valueOf(data.category)

                    val convertedParam =
                            when (valueType) {
                                ParameterValueType.INT ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue.toInt(),
                                                currentValue = data.currentValue.toInt(),
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                minValue = data.minValue?.toInt(),
                                                maxValue = data.maxValue?.toInt(),
                                                category = category,
                                                isCustom = true
                                        )
                                ParameterValueType.FLOAT ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue.toFloat(),
                                                currentValue = data.currentValue.toFloat(),
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                minValue = data.minValue?.toFloat(),
                                                maxValue = data.maxValue?.toFloat(),
                                                category = category,
                                                isCustom = true
                                        )
                                ParameterValueType.BOOLEAN ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue.toBoolean(),
                                                currentValue = data.currentValue.toBoolean(),
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                category = category,
                                                isCustom = true
                                        )
                                ParameterValueType.STRING ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue,
                                                currentValue = data.currentValue,
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                category = category,
                                                isCustom = true
                                        )
                                ParameterValueType.OBJECT ->
                                        ModelParameter(
                                                id = data.id,
                                                name = data.name,
                                                apiName = data.apiName,
                                                description = data.description,
                                                defaultValue = data.defaultValue,
                                                currentValue = data.currentValue,
                                                isEnabled = data.isEnabled,
                                                valueType = valueType,
                                                category = category,
                                                isCustom = true
                                        )
                            }
                    parameters.add(convertedParam)
                }
            } catch (e: Exception) {
                AppLogger.e("ModelConfigManager", "Failed to parse or convert custom parameters", e)
            }
        }

        return parameters
    }
    
    /**
     * 导出所有模型配置为带版本号的 JSON 字符串。
     * 包含配置顺序、收藏和折叠状态。
     * @return JSON格式的 ModelConfigBackup 数据
     */
    suspend fun exportAllConfigs(): String {
        // 从同一个快照读取所有数据，保证一致性
        val snapshot = configDataStore.data.first()
        val configIds = readConfigListFromPrefs(snapshot)
        val allConfigs = mutableListOf<ModelConfigData>()

        for (configId in configIds) {
            val configKey = stringPreferencesKey("config_${configId}")
            val configJson = snapshot[configKey]
            if (configJson != null) {
                try {
                    allConfigs.add(json.decodeFromString<ModelConfigData>(configJson))
                } catch (_: Exception) {
                    // 跳过损坏的配置
                }
            }
        }

        val favorites = readFavoriteModelsFromPrefs(snapshot)
        val collapsedProviders = readCollapsedProviderIdsFromPrefs(snapshot).toList()
        val collapsedConfigs = readCollapsedConfigIdsFromPrefs(snapshot)
            .filter { configId -> configIds.contains(configId) }
            .toList()

        val backup = ModelConfigBackup(
            version = BACKUP_VERSION,
            configs = allConfigs,
            favoriteModels = favorites,
            collapsedProviderIds = collapsedProviders,
            collapsedConfigIds = collapsedConfigs,
        )

        val exportJson = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        return exportJson.encodeToString(backup)
    }

    /**
     * 从JSON字符串导入模型配置。
     * 支持旧版纯数组和带版本号的对象格式。
     * 新版采用非破坏性合并。
     * @param jsonContent JSON格式的配置数据
     * @return 导入结果统计 (新增数量, 更新数量, 跳过数量)
     */
    suspend fun importConfigs(jsonContent: String): Triple<Int, Int, Int> {
        val trimmed = jsonContent.trim()

        // 尝试解析为带版本号的对象
        val backupJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        runCatching {
            backupJson.decodeFromString<ModelConfigBackup>(trimmed)
        }.onSuccess { backup ->
            return importVersionedBackup(backup)
        }

        // 尝试解析为旧版纯配置数组
        runCatching {
            backupJson.decodeFromString<List<ModelConfigData>>(trimmed)
        }.onSuccess { configs ->
            return importLegacyConfigs(configs)
        }

        throw Exception(context.getString(R.string.model_config_import_failed, "Unrecognized backup format"))
    }

    private suspend fun importVersionedBackup(backup: ModelConfigBackup): Triple<Int, Int, Int> {
        // 版本检查
        if (backup.version <= 0 || backup.version > BACKUP_VERSION) {
            throw Exception(
                context.getString(
                    R.string.model_config_import_failed,
                    "Unsupported backup version: ${backup.version}"
                )
            )
        }

        var newCount = 0
        var updatedCount = 0
        var skippedCount = 0

        configDataStore.edit { preferences ->
            val currentIds = readConfigListFromPrefs(preferences)
            val currentIdSet = currentIds.toSet()

            // 1. 备份配置：去重保留首次，重复计入 skipped
            val seenBackupIds = mutableSetOf<String>()
            val dedupedBackupConfigs = backup.configs.filter { config ->
                if (config.id.isEmpty() || config.name.isEmpty()) {
                    skippedCount++
                    return@filter false
                }
                if (!seenBackupIds.add(config.id)) {
                    skippedCount++
                    return@filter false
                }
                true
            }

            // 保存/更新配置
            for (config in dedupedBackupConfigs) {
                pinBindingsBeforeImportedConfig(preferences, config)
                preferences[stringPreferencesKey("config_${config.id}")] =
                    json.encodeToString(config)
                if (config.id in currentIdSet) {
                    updatedCount++
                } else {
                    newCount++
                }
            }

            // 2. 构建合并后的配置列表：
            //    - 备份配置按原顺序在前
            //    - 本地独有配置按原相对顺序追加在后
            val backupOrderedIds = dedupedBackupConfigs.map { it.id }
            // 备份中已经存在的本地 ID 已在上方置前，剩下的本地 ID 追加
            val backupIdSet = backupOrderedIds.toSet()
            val mergedIds = backupOrderedIds +
                    currentIds.filter { it !in backupIdSet }
            preferences[CONFIG_LIST_KEY] = json.encodeToString(mergedIds)

            // 3. 收藏合并：本地优先 + 备份新项追加，去重，过滤无效
            val localFavorites = readFavoriteModelsFromPrefs(preferences)
            val seenFavoriteKeys = localFavorites.map { it.configId to it.modelName }.toMutableSet()
            val mergedFavorites = localFavorites.toMutableList()

            // 需要合并后的配置列表来判断有效性
            val mergedConfigs = buildList {
                for (id in mergedIds) {
                    val configKey = stringPreferencesKey("config_${id}")
                    val configJson = preferences[configKey]
                    if (configJson != null) {
                        try {
                            add(json.decodeFromString<ModelConfigData>(configJson))
                        } catch (_: Exception) { }
                    }
                }
            }
            val configSummaries = mergedConfigs.map { config ->
                ModelConfigSummary(
                    id = config.id,
                    name = config.name,
                    modelName = config.modelName,
                    apiEndpoint = config.apiEndpoint,
                    apiProviderType = config.apiProviderType,
                    apiProviderTypeId = config.apiProviderTypeId,
                    modelProtocolSettings = config.modelProtocolSettings,
                )
            }

            for (fav in backup.favoriteModels) {
                val key = fav.configId to fav.modelName
                if (seenFavoriteKeys.add(key)) {
                    // 检查是否在合并后的配置中有效
                    val validIdx =
                        com.ai.assistance.operit.data.model.resolveFavoriteModelIndex(configSummaries, fav)
                    if (validIdx != null) {
                        mergedFavorites.add(fav)
                    }
                }
            }
            preferences[FAVORITE_MODELS_KEY] = json.encodeToString(mergedFavorites)

            // 4. 提供商折叠合并：本地与备份取并集（规范化后）
            val localProviderCollapsed = readCollapsedProviderIdsFromPrefs(preferences)
            val backupProviderCollapsed = backup.collapsedProviderIds
                .map { normalizeProviderId(it) }
                .filter { it.isNotEmpty() }
                .toSet()
            val mergedProviderCollapsed = localProviderCollapsed + backupProviderCollapsed
            preferences[COLLAPSED_PROVIDER_IDS_KEY] =
                json.encodeToString(mergedProviderCollapsed.toList())

            // 5. 配置折叠合并
            //    v1 备份缺少 collapsedConfigIds，只保留本地有效项
            //    v2 备份合并本地与备份的并集
            val localConfigCollapsed = readCollapsedConfigIdsFromPrefs(preferences)
            val backupConfigCollapsed: Collection<String> =
                if (backup.version >= 2) backup.collapsedConfigIds else emptyList()
            val mergedConfigCollapsed = mergeCollapsedConfigIds(
                localIds = localConfigCollapsed,
                backupIds = backupConfigCollapsed,
                mergedConfigIds = mergedIds,
            )
            preferences[COLLAPSED_CONFIG_IDS_KEY] =
                json.encodeToString(mergedConfigCollapsed.toList())
        }

        if (newCount + updatedCount > 0) LearningPromptSnapshotRepository.markChanged(context, "settings")
        return Triple(newCount, updatedCount, skippedCount)
    }

    private suspend fun importLegacyConfigs(configs: List<ModelConfigData>): Triple<Int, Int, Int> {
        var newCount = 0
        var updatedCount = 0
        var skippedCount = 0

        configDataStore.edit { preferences ->
            val currentIds = readConfigListFromPrefs(preferences).toMutableList()
            val currentIdSet = currentIds.toSet()

            for (config in configs) {
                if (config.id.isEmpty() || config.name.isEmpty()) {
                    skippedCount++
                    continue
                }

                pinBindingsBeforeImportedConfig(preferences, config)
                preferences[stringPreferencesKey("config_${config.id}")] =
                    json.encodeToString(config)

                if (config.id in currentIdSet) {
                    updatedCount++
                } else {
                    newCount++
                    currentIds.add(config.id)
                }
            }

            if (newCount > 0) {
                preferences[CONFIG_LIST_KEY] = json.encodeToString(currentIds)
            }
            // 旧版导入不修改本地收藏与折叠
        }

        if (newCount + updatedCount > 0) LearningPromptSnapshotRepository.markChanged(context, "settings")
        return Triple(newCount, updatedCount, skippedCount)
    }

    private suspend fun pinBindingsBeforeImportedConfig(
        preferences: Preferences,
        config: ModelConfigData,
    ) {
        val raw = preferences[stringPreferencesKey("config_${config.id}")] ?: return
        val old = json.decodeFromString<ModelConfigData>(raw)
        if (old.modelName != config.modelName) {
            FunctionalConfigManager(context).pinModelBindingsBeforeConfigChange(config.id, old.modelName)
        }
    }
}

// 扩展函数，用于将ModelParameter转换为CustomParameterData
private fun ModelParameter<*>.toCustomParameterData(): com.ai.assistance.operit.data.model.CustomParameterData {
    return com.ai.assistance.operit.data.model.CustomParameterData(
        id = this.id,
        name = this.name,
        apiName = this.apiName,
        description = this.description,
        defaultValue = this.defaultValue.toString(),
        currentValue = this.currentValue.toString(),
        isEnabled = this.isEnabled,
        valueType = this.valueType.name,
        minValue = this.minValue?.toString(),
        maxValue = this.maxValue?.toString(),
        category = this.category.name
    )
}
