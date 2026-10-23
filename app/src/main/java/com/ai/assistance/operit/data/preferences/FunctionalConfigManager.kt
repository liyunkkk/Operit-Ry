package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigSummary
import com.ai.assistance.operit.data.model.getModelList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 为功能配置创建专用的DataStore
private val Context.functionalConfigDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "functional_configs")

/** 功能配置映射数据，包含配置ID、模型索引和功能请求专属的思考程度。 */
@Serializable
data class FunctionConfigMapping(
    val configId: String = FunctionalConfigManager.DEFAULT_CONFIG_ID,
    val modelIndex: Int = 0,
    val thinkingQualityLevel: Int = FunctionalConfigManager.DEFAULT_THINKING_QUALITY_LEVEL,
    // Null is a legacy index-only binding; an empty name records an unavailable selection.
    val modelName: String? = null,
)

/** Removed function keys must not reset the other saved model bindings. */
internal fun <T> Map<String, T>.knownFunctionMappings(): Map<FunctionType, T> =
    entries.mapNotNull { (name, value) ->
        FunctionType.entries.find { it.name == name }?.let { it to value }
    }.toMap()

internal fun FunctionConfigMapping.bindModelName(models: String): FunctionConfigMapping =
    if (modelName != null || (getModelList(models).isEmpty() && modelIndex == 0)) this
    else copy(modelName = getModelList(models).getOrNull(modelIndex).orEmpty())

internal fun FunctionConfigMapping.resolveModelIndex(models: String?): FunctionConfigMapping {
    if (models == null) return copy(modelIndex = -1)
    val bound = bindModelName(models)
    // Some plugin providers choose their own model and have never had a named selection.
    if (bound.modelName == null && getModelList(models).isEmpty()) return bound
    return bound.copy(modelIndex = getModelList(models).indexOf(bound.modelName))
}

internal fun unavailableFunctionModels(
    mapping: Map<FunctionType, FunctionConfigMapping>,
    configs: List<ModelConfigSummary>,
    isPluginProvider: (String) -> Boolean,
): List<FunctionType> = FunctionType.values().filter { function ->
    if (function == FunctionType.UI_CONTROLLER) return@filter false
    val configured = mapping[function] ?: FunctionConfigMapping()
    val followed = FunctionalConfigManager.FUNCTION_CONFIG_FOLLOWERS[function]
    val binding = if (followed != null && configured.configId == FunctionalConfigManager.DEFAULT_CONFIG_ID) {
        mapping[followed] ?: FunctionConfigMapping()
    } else configured
    val config = configs.find { it.id == binding.configId } ?: return@filter true
    val resolved = binding.resolveModelIndex(config.modelName)
    val unnamedPlugin = resolved.modelIndex == 0 && resolved.modelName == null &&
        getModelList(config.modelName).isEmpty() && isPluginProvider(config.apiProviderTypeId)
    !unnamedPlugin && resolved.modelIndex !in getModelList(config.modelName).indices
}

/** 管理不同功能使用的模型配置 这个类用于将FunctionType映射到对应的ModelConfigID */
class FunctionalConfigManager(private val context: Context) {

    // 定义key
    companion object {
        // 功能配置映射key
        val FUNCTION_CONFIG_MAPPING = stringPreferencesKey("function_config_mapping")

        // 默认映射值
        const val DEFAULT_CONFIG_ID = "default"

        /**
         * 跟随另一个功能的模型选择的功能。
         *
         * “自动审核”的异步风险分类器是审批的子功能：用户没有单独指定它时，它使用审批代理的
         * 配置，这样只设置一次“权限审批”就能同时生效。
         */
        val FUNCTION_CONFIG_FOLLOWERS: Map<FunctionType, FunctionType> =
            mapOf(FunctionType.PERMISSION_RISK_SCORER to FunctionType.PERMISSION_REVIEWER)

        /** 功能请求固定使用与聊天设置一致的五档推理强度。 */
        const val DEFAULT_THINKING_QUALITY_LEVEL =
            ApiPreferences.DEFAULT_THINKING_QUALITY_LEVEL
    }

    // Json解析器
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 获取ModelConfigManager实例用于配置查询
    private val modelConfigManager = ModelConfigManager(context)

    // 获取功能配置映射（保持向后兼容）
    val functionConfigMappingFlow: Flow<Map<FunctionType, String>> =
            context.functionalConfigDataStore.data.map { preferences ->
                val mappingJson = preferences[FUNCTION_CONFIG_MAPPING] ?: "{}"
                if (mappingJson == "{}") {
                    FunctionType.values().associateWith { DEFAULT_CONFIG_ID }
                } else {
                    try {
                        // 尝试新格式（包含modelIndex）
                        val rawMap = json.decodeFromString<Map<String, FunctionConfigMapping>>(mappingJson)
                        rawMap.knownFunctionMappings().mapValues { it.value.configId }
                    } catch (e: Exception) {
                        try {
                            // 回退到旧格式（只有configId）
                            val rawMap = json.decodeFromString<Map<String, String>>(mappingJson)
                            rawMap.knownFunctionMappings()
                        } catch (e2: Exception) {
                            FunctionType.values().associateWith { DEFAULT_CONFIG_ID }
                        }
                    }
                }
            }

    // 获取完整的功能配置映射（包含modelIndex）
    private val storedMappingFlow: Flow<Map<FunctionType, FunctionConfigMapping>> =
            context.functionalConfigDataStore.data.map { preferences ->
                val mappingJson = preferences[FUNCTION_CONFIG_MAPPING] ?: "{}"
                if (mappingJson == "{}") {
                    FunctionType.values().associateWith { FunctionConfigMapping(DEFAULT_CONFIG_ID, 0) }
                } else {
                    try {
                        val rawMap = json.decodeFromString<Map<String, FunctionConfigMapping>>(mappingJson)
                        rawMap.knownFunctionMappings()
                    } catch (e: Exception) {
                        try {
                            // 从旧格式迁移
                            val rawMap = json.decodeFromString<Map<String, String>>(mappingJson)
                            rawMap.knownFunctionMappings().mapValues {
                                FunctionConfigMapping(it.value, 0)
                            }
                        } catch (e2: Exception) {
                            FunctionType.values().associateWith { FunctionConfigMapping(DEFAULT_CONFIG_ID, 0) }
                        }
                    }
                }
            }

    val functionConfigMappingWithIndexFlow: Flow<Map<FunctionType, FunctionConfigMapping>> =
        combine(storedMappingFlow, modelConfigManager.configSummariesFlow) { mapping, configs ->
            val models = configs.associate { it.id to it.modelName }
            (FunctionType.values().associateWith { FunctionConfigMapping() } + mapping)
                .mapValues { (_, value) -> value.resolveModelIndex(models[value.configId]) }
        }

    val unavailableFunctionModelsFlow: Flow<List<FunctionType>> by lazy {
        combine(
            storedMappingFlow,
            modelConfigManager.configSummariesFlow,
            com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry.registeredProviderIdsFlow,
        ) { mapping, configs, providerIds ->
            unavailableFunctionModels(mapping, configs) { providerId ->
                providerId.trim().lowercase() in providerIds
            }
        }.distinctUntilChanged()
    }

    /** Pin legacy selections before a model list changes, while its old indices still have meaning. */
    suspend fun pinModelBindingsBeforeConfigChange(configId: String, oldModels: String) {
        // An empty setup has no previous model to preserve.
        if (getModelList(oldModels).isEmpty()) return
        // Only read this DataStore here: callers may hold the model-config DataStore edit lock.
        context.functionalConfigDataStore.edit { preferences ->
            val raw = preferences[FUNCTION_CONFIG_MAPPING] ?: "{}"
            val mapping = FunctionType.values().associateWith { FunctionConfigMapping() } +
                decodeStoredMapping(raw)
            val pinned = mapping.mapValues { (_, value) ->
                if (value.configId == configId) value.bindModelName(oldModels) else value
            }
            preferences[FUNCTION_CONFIG_MAPPING] =
                json.encodeToString(pinned.mapKeys { it.key.name })
        }
    }

    private fun decodeStoredMapping(raw: String): Map<FunctionType, FunctionConfigMapping> {
        if (raw == "{}") return FunctionType.values().associateWith { FunctionConfigMapping() }
        return try {
            json.decodeFromString<Map<String, FunctionConfigMapping>>(raw)
                .knownFunctionMappings()
        } catch (_: Exception) {
            // Preserve old config-id-only settings when upgrading.
            json.decodeFromString<Map<String, String>>(raw)
                .knownFunctionMappings()
                .mapValues { FunctionConfigMapping(it.value) }
        }
    }

    // 初始化，确保有默认映射
    suspend fun initializeIfNeeded() {
        val mapping = functionConfigMappingWithIndexFlow.first()

        // 只在映射真正为空时才创建默认映射，避免覆盖用户已保存的modelIndex
        if (mapping.isEmpty()) {
            val defaultMapping = FunctionType.values().associateWith { FunctionConfigMapping(DEFAULT_CONFIG_ID, 0) }
            saveFunctionConfigMappingWithIndex(defaultMapping)
        }

        // 确保ModelConfigManager也已初始化
        modelConfigManager.initializeIfNeeded()
    }

    // 保存功能配置映射（保持向后兼容）
    suspend fun saveFunctionConfigMapping(mapping: Map<FunctionType, String>) {
        val mappingWithIndex = mapping.entries.associate { 
            it.key to FunctionConfigMapping(it.value, 0) 
        }
        saveFunctionConfigMappingWithIndex(mappingWithIndex)
    }

    // 保存功能配置映射（包含modelIndex）
    suspend fun saveFunctionConfigMappingWithIndex(mapping: Map<FunctionType, FunctionConfigMapping>) {
        fun projection(values: Map<FunctionType, FunctionConfigMapping>) = values
            .filterKeys { it.name in setOf("CHAT", "VOICE", "IMAGE_RECOGNITION", "AUDIO_RECOGNITION", "VIDEO_RECOGNITION") }
            .mapValues { it.value.configId to it.value.modelIndex }
        val changed = projection(functionConfigMappingWithIndexFlow.first()) != projection(mapping)
        val configs = modelConfigManager.configSummariesFlow.first().associate { it.id to it.modelName }
        val stringMapping = mapping.entries.associate {
            it.key.name to it.value.bindModelName(configs[it.value.configId].orEmpty())
        }
        context.functionalConfigDataStore.edit { preferences ->
            preferences[FUNCTION_CONFIG_MAPPING] = json.encodeToString(stringMapping)
        }
        if (changed) LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    // 获取指定功能的配置ID
    suspend fun getConfigIdForFunction(functionType: FunctionType): String {
        val mapping = functionConfigMappingFlow.first()
        return mapping[functionType] ?: DEFAULT_CONFIG_ID
    }

    // 获取指定功能的完整配置（包含modelIndex）
    suspend fun getConfigMappingForFunction(functionType: FunctionType): FunctionConfigMapping {
        val mapping = functionConfigMappingWithIndexFlow.first()
        return mapping[functionType] ?: FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)
    }

    /**
     * 功能实际生效的配置。
     *
     * 只有 [FUNCTION_CONFIG_FOLLOWERS] 中的功能会跟随：当它自己的配置仍是应用默认配置
     * （用户没有单独选择）时，返回被跟随功能的配置。
     */
    suspend fun getEffectiveConfigMappingForFunction(functionType: FunctionType): FunctionConfigMapping {
        val configured = getConfigMappingForFunction(functionType)
        val followed = FUNCTION_CONFIG_FOLLOWERS[functionType] ?: return configured
        if (configured.configId != DEFAULT_CONFIG_ID) return configured
        return getConfigMappingForFunction(followed)
    }

    // 设置指定功能的配置ID
    suspend fun setConfigForFunction(functionType: FunctionType, configId: String) {
        setConfigForFunction(functionType, configId, 0)
    }

    // 设置指定功能的配置ID和模型索引
    suspend fun setConfigForFunction(functionType: FunctionType, configId: String, modelIndex: Int) {
        val config = modelConfigManager.configSummariesFlow.first().find { it.id == configId }
        val unnamedPlugin = config != null && config.modelName.isBlank() && modelIndex == 0 &&
            com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry.get(config.apiProviderTypeId) != null
        require(config != null && (modelIndex in getModelList(config.modelName).indices || unnamedPlugin)) {
            context.getString(com.ai.assistance.operit.R.string.functional_model_selection_unavailable)
        }
        val mapping = functionConfigMappingWithIndexFlow.first().toMutableMap()
        val thinkingQualityLevel =
            mapping[functionType]?.thinkingQualityLevel ?: DEFAULT_THINKING_QUALITY_LEVEL
        mapping[functionType] =
            FunctionConfigMapping(configId, modelIndex, thinkingQualityLevel)
                .bindModelName(config.modelName)
        saveFunctionConfigMappingWithIndex(mapping)
    }

    /** 设置指定功能请求的思考程度，同时保留当前模型配置与模型索引。 */
    suspend fun setThinkingQualityForFunction(functionType: FunctionType, qualityLevel: Int) {
        val mapping = functionConfigMappingWithIndexFlow.first().toMutableMap()
        val current =
            mapping[functionType] ?: FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)
        mapping[functionType] =
            current.copy(thinkingQualityLevel = normalizeThinkingQualityLevel(qualityLevel))
        saveFunctionConfigMappingWithIndex(mapping)
    }

    // 重置指定功能的配置为默认
    suspend fun resetFunctionConfig(functionType: FunctionType) {
        val mapping = functionConfigMappingWithIndexFlow.first().toMutableMap()
        mapping[functionType] = FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)
        saveFunctionConfigMappingWithIndex(mapping)
    }

    // 重置所有功能配置为默认
    suspend fun resetAllFunctionConfigs() {
        val defaultMapping = FunctionType.values().associateWith { FunctionConfigMapping(DEFAULT_CONFIG_ID, 0) }
        saveFunctionConfigMappingWithIndex(defaultMapping)
    }

    private fun normalizeThinkingQualityLevel(qualityLevel: Int): Int {
        return qualityLevel.coerceIn(
            ApiPreferences.MIN_THINKING_QUALITY_LEVEL,
            ApiPreferences.MAX_THINKING_QUALITY_LEVEL,
        )
    }
}
