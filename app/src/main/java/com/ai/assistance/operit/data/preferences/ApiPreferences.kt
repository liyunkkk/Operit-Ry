package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.stats.LegacyProviderModelKeyDecoder
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Define the DataStore at the module level
private val Context.apiDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "api_settings")

internal fun usdToCnyStorageValue(rate: Double): Float? =
    rate.toFloat().takeIf { it.isFinite() && it > 0f }

internal fun validStoredUsdToCnyRate(stored: Float?): Double? =
    stored?.takeIf { it.isFinite() && it > 0f }?.toDouble()

internal fun resolveUsdToCnyExchangeRate(stored: Float?): Double =
    validStoredUsdToCnyRate(stored) ?: 7.2

internal fun resolveUsdToCnyRateWithEstimate(stored: Float?): Pair<Double, Boolean> =
    validStoredUsdToCnyRate(stored)?.let { it to false } ?: (7.0 to true)

class ApiPreferences private constructor(private val context: Context) {

    // Define our preferences keys
    companion object {
        @Volatile
        private var INSTANCE: ApiPreferences? = null

        /** JVM tests can avoid initializing the application-scoped ToolPkg runtime. */
        internal var toolPkgProviderNamesProvider: (() -> List<String>)? = null

        fun getInstance(context: Context): ApiPreferences {
            return INSTANCE ?: synchronized(this) {
                val instance = ApiPreferences(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
        @JvmStatic
        fun getFeatureToggleBlocking(
            context: Context,
            featureKey: String,
            defaultValue: Boolean = false
        ): Boolean {
            val normalized = featureKey.trim()
            if (normalized.isEmpty()) {
                return defaultValue
            }
            return runBlocking {
                getInstance(context).featureToggleFlow(normalized, defaultValue).first()
            }
        }

        @JvmStatic
        fun setFeatureToggleBlocking(
            context: Context,
            featureKey: String,
            enabled: Boolean
        ) {
            val normalized = featureKey.trim()
            if (normalized.isEmpty()) {
                return
            }
            runBlocking {
                getInstance(context).saveFeatureToggle(normalized, enabled)
            }
        }

        // 动态生成供应商:模型的Token键
        fun getTokenInputKey(providerModel: String) =
                longPreferencesKey("token_input_${providerModel.replace(":", "_")}")

        fun getTokenCachedInputKey(providerModel: String) =
                longPreferencesKey("token_cached_input_${providerModel.replace(":", "_")}")

        fun getTokenOutputKey(providerModel: String) =
                longPreferencesKey("token_output_${providerModel.replace(":", "_")}")

        // 模型定价键
        fun getModelInputPriceKey(providerModel: String) =
                floatPreferencesKey("model_input_price_${providerModel.replace(":", "_")}")

        fun getModelCachedInputPriceKey(providerModel: String) =
                floatPreferencesKey("model_cached_input_price_${providerModel.replace(":", "_")}")

        fun getModelOutputPriceKey(providerModel: String) =
                floatPreferencesKey("model_output_price_${providerModel.replace(":", "_")}")

        // 请求次数统计键
        fun getRequestCountKey(providerModel: String) =
                intPreferencesKey("request_count_${providerModel.replace(":", "_")}")

        // 计费方式键
        fun getBillingModeKey(providerModel: String) =
                stringPreferencesKey("billing_mode_${providerModel.replace(":", "_")}")

        // 按次计费价格键
        fun getPricePerRequestKey(providerModel: String) =
                floatPreferencesKey("price_per_request_${providerModel.replace(":", "_")}")

        /** 旧系统价格/计费方式键前缀（与 [legacyPriceSettingsFrom] 的键构造对应）。 */
        val LEGACY_PRICE_KEY_PREFIXES =
                listOf("model_input_price_", "model_cached_input_price_", "model_output_price_",
                        "billing_mode_", "price_per_request_")

        val USD_TO_CNY_EXCHANGE_RATE = floatPreferencesKey("usd_to_cny_exchange_rate")

        private val STATS_TARGET_CURRENCY = stringPreferencesKey("stats_target_currency")
        private val STATS_COST_MODE = stringPreferencesKey("stats_cost_mode")
        private val STATS_INCLUDE_LEGACY = booleanPreferencesKey("stats_include_legacy")
        private val STATS_TIME_PRESET = stringPreferencesKey("stats_time_preset")
        private val STATS_TIME_CUSTOM_START = longPreferencesKey("stats_time_custom_start")
        private val STATS_TIME_CUSTOM_END = longPreferencesKey("stats_time_custom_end")
        private val STATS_TIME_MANUAL = booleanPreferencesKey("stats_time_manual")

        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val FEATURE_TOGGLES_JSON = stringPreferencesKey("feature_toggles_json")
        // Default values
        const val DEFAULT_FEATURE_TOGGLE_STATE = false
        const val DEFAULT_KEEP_SCREEN_ON = true
        // Keys for Thinking Mode and Thinking Guidance
        val ENABLE_THINKING_MODE = booleanPreferencesKey("enable_thinking_mode")
        val THINKING_QUALITY_LEVEL = intPreferencesKey("thinking_quality_level")

        // Key for Memory Auto Update
        val ENABLE_MEMORY_AUTO_UPDATE = booleanPreferencesKey("enable_memory_auto_update")
        val ENABLE_LEGACY_MEMORY_EXTRACTION = booleanPreferencesKey("enable_legacy_memory_extraction")

        // Key for Auto Read
        val ENABLE_AUTO_READ = booleanPreferencesKey("enable_auto_read")

        // Key for Tools Enable/Disable
        val ENABLE_TOOLS = booleanPreferencesKey("enable_tools")

        // Key for per-tool prompt visibility
        val TOOL_PROMPT_VISIBILITY_JSON = stringPreferencesKey("tool_prompt_visibility_json")

        // Key for per-tool prompt order (list of tool names)
        val TOOL_PROMPT_ORDER_JSON = stringPreferencesKey("tool_prompt_order_json")

        // Key for plugin order (list of package names)
        val PLUGIN_ORDER_JSON = stringPreferencesKey("plugin_order_json")

        // Key for skill order (list of skill names)
        val SKILL_ORDER_JSON = stringPreferencesKey("skill_order_json")

        // Key for Disable Stream Output
        val DISABLE_STREAM_OUTPUT = booleanPreferencesKey("disable_stream_output")

        // Key for Disable User Preference Description
        val DISABLE_USER_PREFERENCE_DESCRIPTION = booleanPreferencesKey("disable_user_preference_description")

        // Custom System Prompt Template (Advanced Configuration)
        val CUSTOM_SYSTEM_PROMPT_TEMPLATE = stringPreferencesKey("custom_system_prompt_template")


        // Default values for Thinking Mode
        const val DEFAULT_ENABLE_THINKING_MODE = false
        const val MIN_THINKING_QUALITY_LEVEL = 1
        const val MAX_THINKING_QUALITY_LEVEL = 5
        const val DEFAULT_THINKING_QUALITY_LEVEL = 2

        // 思考程度档位对应的默认 reasoning_effort 值（唯一事实来源）。
        // 滑块标签由此派生；直接发送 effort 的 provider 也使用该映射。菜单摘要还会通过
        // ThinkingRequestSemantics 处理 provider 归一化、token budget 和自定义参数覆盖。
        val THINKING_QUALITY_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        fun thinkingQualityEffort(level: Int): String {
            val index = level.coerceIn(MIN_THINKING_QUALITY_LEVEL, MAX_THINKING_QUALITY_LEVEL) -
                MIN_THINKING_QUALITY_LEVEL
            return THINKING_QUALITY_EFFORTS[index]
        }

        // Default value for Memory Auto Update
        const val DEFAULT_ENABLE_MEMORY_AUTO_UPDATE = true

        // Default value for Auto Read
        const val DEFAULT_ENABLE_AUTO_READ = false

        // Default value for Tools Enable/Disable
        const val DEFAULT_ENABLE_TOOLS = true

        // Default value for Disable Stream Output (default false, meaning stream is enabled by default)
        const val DEFAULT_DISABLE_STREAM_OUTPUT = false

        // Default value for Disable User Preference Description
        const val DEFAULT_DISABLE_USER_PREFERENCE_DESCRIPTION = false

        // Default system prompt template (empty means use built-in template)
        const val DEFAULT_SYSTEM_PROMPT_TEMPLATE = ""


        // 自定义参数存储键
        val CUSTOM_PARAMETERS = stringPreferencesKey("custom_parameters")

        private val SAF_BOOKMARKS_JSON = stringPreferencesKey("saf_bookmarks_json")

        // 默认空的自定义参数列表
        const val DEFAULT_CUSTOM_PARAMETERS = "[]"
        const val DEFAULT_TOOL_PROMPT_VISIBILITY_JSON = "{}"
        const val DEFAULT_TOOL_PROMPT_ORDER_JSON = "[]"
        const val DEFAULT_FEATURE_TOGGLES_JSON = "{}"

        // API 配置默认值
        const val DEFAULT_API_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        const val DEFAULT_MODEL_NAME = "deepseek-v4-flash"

        // legacy cleanup applied marker 键前缀（P1 闭环）：marker 与累计键清理在
        // 同一次 DataStore.edit 内原子完成；marker 已存在时同 operation 重试为
        // 幂等 no-op。marker ID 集合与 baseline 快照同一次读取，供导入 fence 校验
        // （见 legacyStatsSnapshotWithMarkers）。
        val LEGACY_CLEANUP_MARKER_PREFIX = "legacy_cleanup_applied_"

        fun legacyCleanupMarkerKey(operationId: String): Preferences.Key<Boolean> =
            booleanPreferencesKey("$LEGACY_CLEANUP_MARKER_PREFIX$operationId")

        /** 移除指定键名的累计计数键（实例方法与 outbox 纯变更函数共用）。 */
        internal fun removeTokenCountKeysForMutation(
            preferences: MutablePreferences,
            vararg keyNames: String,
        ) {
            val names = keyNames.toSet()
            preferences.asMap().keys
                    .filter { it.name in names }
                    .forEach { preferences.remove(it) }
        }

        private const val TAG = "ApiPreferences"
    }

    @Serializable
    data class SafBookmark(
        val uri: String,
        val name: String
    )

    val safBookmarksFlow: Flow<List<SafBookmark>> =
        context.apiDataStore.data.map { preferences ->
            val json = preferences[SAF_BOOKMARKS_JSON] ?: "[]"
            runCatching { Json.decodeFromString<List<SafBookmark>>(json) }.getOrElse { emptyList() }
        }

    suspend fun addSafBookmark(uri: String, name: String) {
        context.apiDataStore.edit { preferences ->
            val existing =
                runCatching {
                    val json = preferences[SAF_BOOKMARKS_JSON] ?: "[]"
                    Json.decodeFromString<List<SafBookmark>>(json)
                }.getOrElse { emptyList() }

            val updated = (existing.filterNot { it.uri == uri } + SafBookmark(uri = uri, name = name))
                .sortedBy { it.name.lowercase() }
            preferences[SAF_BOOKMARKS_JSON] = Json.encodeToString(updated)
            if (existing.map { it.name }.toSet() != updated.map { it.name }.toSet())
                LearningPromptSnapshotRepository.markChanged(context, "settings")
        }
    }

    suspend fun removeSafBookmark(uri: String) {
        context.apiDataStore.edit { preferences ->
            val existing =
                runCatching {
                    val json = preferences[SAF_BOOKMARKS_JSON] ?: "[]"
                    Json.decodeFromString<List<SafBookmark>>(json)
                }.getOrElse { emptyList() }
            val updated = existing.filterNot { it.uri == uri }
            preferences[SAF_BOOKMARKS_JSON] = Json.encodeToString(updated)
            if (existing.map { it.name }.toSet() != updated.map { it.name }.toSet())
                LearningPromptSnapshotRepository.markChanged(context, "settings")
        }
    }

    val featureTogglesFlow: Flow<Map<String, Boolean>> =
        context.apiDataStore.data.map { preferences ->
            val json = preferences[FEATURE_TOGGLES_JSON] ?: DEFAULT_FEATURE_TOGGLES_JSON
            runCatching {
                Json.decodeFromString<Map<String, Boolean>>(json)
            }.getOrElse { emptyMap() }
        }

    fun featureToggleFlow(featureKey: String, defaultValue: Boolean = false): Flow<Boolean> {
        val normalizedKey = featureKey.trim()
        if (normalizedKey.isEmpty()) {
            return featureTogglesFlow.map { defaultValue }
        }
        return featureTogglesFlow.map { toggles ->
            toggles[normalizedKey] ?: defaultValue
        }
    }

    // Get Keep Screen On setting as Flow
    val keepScreenOnFlow: Flow<Boolean> =
            context.apiDataStore.data.map { preferences ->
                preferences[KEEP_SCREEN_ON] ?: DEFAULT_KEEP_SCREEN_ON
            }

    // Flow for Thinking Mode
    val enableThinkingModeFlow: Flow<Boolean> =
        context.apiDataStore.data.map { preferences ->
            preferences[ENABLE_THINKING_MODE] ?: DEFAULT_ENABLE_THINKING_MODE
        }

    val thinkingQualityLevelFlow: Flow<Int> =
        context.apiDataStore.data.map { preferences ->
            (preferences[THINKING_QUALITY_LEVEL] ?: DEFAULT_THINKING_QUALITY_LEVEL).coerceIn(
                MIN_THINKING_QUALITY_LEVEL,
                MAX_THINKING_QUALITY_LEVEL
            )
        }

    // Flow for Memory Auto Update
    val enableMemoryAutoUpdateFlow: Flow<Boolean> =
        context.apiDataStore.data.map { preferences ->
            preferences[ENABLE_MEMORY_AUTO_UPDATE] ?: DEFAULT_ENABLE_MEMORY_AUTO_UPDATE
        }
    val enableLegacyMemoryExtractionFlow: Flow<Boolean> =
        context.apiDataStore.data.map { it[ENABLE_LEGACY_MEMORY_EXTRACTION] ?: false }

    suspend fun saveEnableLegacyMemoryExtraction(enabled: Boolean) {
        context.apiDataStore.edit { it[ENABLE_LEGACY_MEMORY_EXTRACTION] = enabled }
        if (!enabled) com.ai.assistance.operit.api.chat.library.MemoryAutoSaveScheduler.getInstance()?.cancelAutomaticExtraction()
    }

    // Flow for Auto Read
    val enableAutoReadFlow: Flow<Boolean> =
        context.apiDataStore.data.map { preferences ->
            preferences[ENABLE_AUTO_READ] ?: DEFAULT_ENABLE_AUTO_READ
        }

    // Flow for Tools Enable/Disable
    val enableToolsFlow: Flow<Boolean> =
        context.apiDataStore.data.map { preferences ->
            preferences[ENABLE_TOOLS] ?: DEFAULT_ENABLE_TOOLS
        }

    // Flow for per-tool prompt visibility
    val toolPromptVisibilityFlow: Flow<Map<String, Boolean>> =
        context.apiDataStore.data.map { preferences ->
            val json = preferences[TOOL_PROMPT_VISIBILITY_JSON] ?: DEFAULT_TOOL_PROMPT_VISIBILITY_JSON
            runCatching {
                Json.decodeFromString<Map<String, Boolean>>(json)
            }.getOrElse { emptyMap() }
        }

    // Flow for per-tool prompt order
    val toolPromptOrderFlow: Flow<List<String>> =
        context.apiDataStore.data.map { preferences ->
            val json = preferences[TOOL_PROMPT_ORDER_JSON] ?: DEFAULT_TOOL_PROMPT_ORDER_JSON
            runCatching {
                Json.decodeFromString<List<String>>(json)
            }.getOrElse { emptyList() }
        }

    // Flow for plugin order
    val pluginOrderFlow: Flow<List<String>> =
        context.apiDataStore.data.map { preferences ->
            val json = preferences[PLUGIN_ORDER_JSON] ?: DEFAULT_TOOL_PROMPT_ORDER_JSON
            runCatching {
                Json.decodeFromString<List<String>>(json)
            }.getOrElse { emptyList() }
        }

    // Flow for skill order
    val skillOrderFlow: Flow<List<String>> =
        context.apiDataStore.data.map { preferences ->
            val json = preferences[SKILL_ORDER_JSON] ?: DEFAULT_TOOL_PROMPT_ORDER_JSON
            runCatching {
                Json.decodeFromString<List<String>>(json)
            }.getOrElse { emptyList() }
        }

    // Flow for Disable Stream Output
    val disableStreamOutputFlow: Flow<Boolean> =
        context.apiDataStore.data.map { preferences ->
            preferences[DISABLE_STREAM_OUTPUT] ?: DEFAULT_DISABLE_STREAM_OUTPUT
        }

    // Flow for Disable User Preference Description
    val disableUserPreferenceDescriptionFlow: Flow<Boolean> =
        context.apiDataStore.data.map { preferences ->
            preferences[DISABLE_USER_PREFERENCE_DESCRIPTION] ?: DEFAULT_DISABLE_USER_PREFERENCE_DESCRIPTION
        }

    // Custom System Prompt Template Flow
    val customSystemPromptTemplateFlow: Flow<String> =
            context.apiDataStore.data.map { preferences ->
                preferences[CUSTOM_SYSTEM_PROMPT_TEMPLATE] ?: DEFAULT_SYSTEM_PROMPT_TEMPLATE
            }


    suspend fun saveFeatureToggle(featureKey: String, isEnabled: Boolean) {
        val normalizedKey = featureKey.trim()
        if (normalizedKey.isEmpty()) return

        context.apiDataStore.edit { preferences ->
            val currentMap =
                runCatching {
                    val json = preferences[FEATURE_TOGGLES_JSON] ?: DEFAULT_FEATURE_TOGGLES_JSON
                    Json.decodeFromString<Map<String, Boolean>>(json)
                }.getOrElse { emptyMap() }

            preferences[FEATURE_TOGGLES_JSON] =
                Json.encodeToString(currentMap + (normalizedKey to isEnabled))
        }
    }

    // Save Keep Screen On setting
    suspend fun saveKeepScreenOn(isEnabled: Boolean) {
        context.apiDataStore.edit { preferences -> preferences[KEEP_SCREEN_ON] = isEnabled }
    }

    // Save Thinking Mode setting
    suspend fun saveEnableThinkingMode(isEnabled: Boolean) {
        context.apiDataStore.edit { preferences -> preferences[ENABLE_THINKING_MODE] = isEnabled }
    }

    suspend fun saveThinkingQualityLevel(level: Int) {
        context.apiDataStore.edit { preferences ->
            preferences[THINKING_QUALITY_LEVEL] = level.coerceIn(
                MIN_THINKING_QUALITY_LEVEL,
                MAX_THINKING_QUALITY_LEVEL
            )
        }
    }

    suspend fun updateThinkingSettings(
        enableThinkingMode: Boolean? = null,
        thinkingQualityLevel: Int? = null
    ) {
        context.apiDataStore.edit { preferences ->
            enableThinkingMode?.let { preferences[ENABLE_THINKING_MODE] = it }

            thinkingQualityLevel?.let {
                preferences[THINKING_QUALITY_LEVEL] = it.coerceIn(
                    MIN_THINKING_QUALITY_LEVEL,
                    MAX_THINKING_QUALITY_LEVEL
                )
            }
        }
    }

    // Save Memory Auto Update setting
    suspend fun saveEnableMemoryAutoUpdate(isEnabled: Boolean) {
        context.apiDataStore.edit { preferences -> preferences[ENABLE_MEMORY_AUTO_UPDATE] = isEnabled }
        if (!isEnabled) {
            com.ai.assistance.operit.api.chat.library.MemoryLearningCoordinator.cancelAutomaticReviews()
            com.ai.assistance.operit.api.chat.library.MemoryAutoSaveScheduler.getInstance()?.cancelAutomaticExtraction()
        }
    }

    // Save Auto Read setting
    suspend fun saveEnableAutoRead(isEnabled: Boolean) {
        context.apiDataStore.edit { preferences -> preferences[ENABLE_AUTO_READ] = isEnabled }
    }

    // Save Tools Enable/Disable setting
    suspend fun saveEnableTools(isEnabled: Boolean) {
        context.apiDataStore.edit { preferences -> preferences[ENABLE_TOOLS] = isEnabled }
        LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    // Save prompt visibility for a single tool
    suspend fun saveToolPromptVisibility(toolName: String, isVisible: Boolean) {
        context.apiDataStore.edit { preferences ->
            val currentMap = runCatching {
                val json = preferences[TOOL_PROMPT_VISIBILITY_JSON] ?: DEFAULT_TOOL_PROMPT_VISIBILITY_JSON
                Json.decodeFromString<Map<String, Boolean>>(json)
            }.getOrElse { emptyMap() }
            preferences[TOOL_PROMPT_VISIBILITY_JSON] = Json.encodeToString(currentMap + (toolName to isVisible))
        }
        LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    // Save prompt visibility map for all tools
    suspend fun saveToolPromptVisibilityMap(visibilityMap: Map<String, Boolean>) {
        context.apiDataStore.edit { preferences ->
            preferences[TOOL_PROMPT_VISIBILITY_JSON] = Json.encodeToString(visibilityMap)
        }
        LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    suspend fun getToolPromptVisibilityMap(): Map<String, Boolean> {
        val preferences = context.apiDataStore.data.first()
        val json = preferences[TOOL_PROMPT_VISIBILITY_JSON] ?: DEFAULT_TOOL_PROMPT_VISIBILITY_JSON
        return runCatching {
            Json.decodeFromString<Map<String, Boolean>>(json)
        }.getOrElse { emptyMap() }
    }

    // Save tool prompt order (list of tool names)
    suspend fun saveToolPromptOrder(order: List<String>) {
        context.apiDataStore.edit { preferences ->
            preferences[TOOL_PROMPT_ORDER_JSON] = Json.encodeToString(order)
        }
        LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    suspend fun getToolPromptOrder(): List<String> {
        val preferences = context.apiDataStore.data.first()
        val json = preferences[TOOL_PROMPT_ORDER_JSON] ?: DEFAULT_TOOL_PROMPT_ORDER_JSON
        return runCatching {
            Json.decodeFromString<List<String>>(json)
        }.getOrElse { emptyList() }
    }

    // Save plugin order (list of package names)
    suspend fun savePluginOrder(order: List<String>) {
        context.apiDataStore.edit { preferences ->
            preferences[PLUGIN_ORDER_JSON] = Json.encodeToString(order)
        }
        LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    suspend fun getPluginOrder(): List<String> {
        val preferences = context.apiDataStore.data.first()
        val json = preferences[PLUGIN_ORDER_JSON] ?: DEFAULT_TOOL_PROMPT_ORDER_JSON
        return runCatching {
            Json.decodeFromString<List<String>>(json)
        }.getOrElse { emptyList() }
    }

    // Save skill order (list of skill names)
    suspend fun saveSkillOrder(order: List<String>) {
        context.apiDataStore.edit { preferences ->
            preferences[SKILL_ORDER_JSON] = Json.encodeToString(order)
        }
        LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    suspend fun getSkillOrder(): List<String> {
        val preferences = context.apiDataStore.data.first()
        val json = preferences[SKILL_ORDER_JSON] ?: DEFAULT_TOOL_PROMPT_ORDER_JSON
        return runCatching {
            Json.decodeFromString<List<String>>(json)
        }.getOrElse { emptyList() }
    }

    // Save Disable Stream Output setting
    suspend fun saveDisableStreamOutput(isDisabled: Boolean) {
        context.apiDataStore.edit { preferences -> preferences[DISABLE_STREAM_OUTPUT] = isDisabled }
    }

    // Save Disable User Preference Description setting
    suspend fun saveDisableUserPreferenceDescription(isDisabled: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[DISABLE_USER_PREFERENCE_DESCRIPTION] = isDisabled
        }
        LearningPromptSnapshotRepository.markChanged(context, "settings")
    }

    // Save Disable Status Tags setting
    /**
     * 更新指定供应商:模型的token计数
     * @param providerModel 供应商:模型标识符，格式如"DEEPSEEK:deepseek-chat"
     * @param inputTokens 新增的输入token
     * @param outputTokens 新增的输出token
     * @param cachedInputTokens 新增的缓存命中token
     */
    suspend fun updateTokensForProviderModel(
            providerModel: String,
            inputTokens: Int,
            outputTokens: Int,
            cachedInputTokens: Int = 0
    ) {
        context.apiDataStore.edit { preferences ->
            val inputKey = getTokenInputKey(providerModel)
            val cachedInputKey = getTokenCachedInputKey(providerModel)
            val outputKey = getTokenOutputKey(providerModel)

            val currentInputTokens = readTokenCount(preferences, inputKey.name)
            val currentCachedInputTokens = readTokenCount(preferences, cachedInputKey.name)
            val currentOutputTokens = readTokenCount(preferences, outputKey.name)

            removeTokenCountKeys(
                    preferences,
                    inputKey.name,
                    cachedInputKey.name,
                    outputKey.name
            )
            preferences[inputKey] = currentInputTokens + inputTokens.toLong()
            preferences[cachedInputKey] = currentCachedInputTokens + cachedInputTokens.toLong()
            preferences[outputKey] = currentOutputTokens + outputTokens.toLong()
        }
    }

    /**
     * 获取指定供应商:模型的输入token数量
     */
    suspend fun getInputTokensForProviderModel(providerModel: String): Long {
        val preferences = context.apiDataStore.data.first()
        return readTokenCount(preferences, getTokenInputKey(providerModel).name)
    }

    /**
     * 获取指定供应商:模型的缓存输入token数量
     */
    suspend fun getCachedInputTokensForProviderModel(providerModel: String): Long {
        val preferences = context.apiDataStore.data.first()
        return readTokenCount(preferences, getTokenCachedInputKey(providerModel).name)
    }

    /**
     * 获取指定供应商:模型的输出token数量
     */
    suspend fun getOutputTokensForProviderModel(providerModel: String): Long {
        val preferences = context.apiDataStore.data.first()
        return readTokenCount(preferences, getTokenOutputKey(providerModel).name)
    }

    /**
     * 获取所有供应商:模型的token统计
     * @return Map<供应商:模型, Triple<输入tokens, 输出tokens, 缓存tokens>>
     */
    suspend fun getAllProviderModelTokens(): Map<String, Triple<Long, Long, Long>> {
        val preferences = context.apiDataStore.data.first()
        val result = mutableMapOf<String, Triple<Long, Long, Long>>()
        val providerNames = registeredToolPkgProviderNames()
        
        // 遍历所有preferences，查找token相关的key
        preferences.asMap().forEach { (key, value) ->
            val keyName = key.name
            if (keyName.startsWith("token_input_")) {
                val providerModel =
                        LegacyProviderModelKeyDecoder.decode(
                                keyName.removePrefix("token_input_"), providerNames)
                val inputTokens = readTokenCountValue(value)
                val outputTokens = readTokenCount(preferences, getTokenOutputKey(providerModel).name)
                val cachedInputTokens =
                        readTokenCount(preferences, getTokenCachedInputKey(providerModel).name)
                if (inputTokens > 0L || outputTokens > 0L || cachedInputTokens > 0L) {
                    result[providerModel] = Triple(inputTokens, outputTokens, cachedInputTokens)
                }
            }
        }
        
        return result
    }

    /**
     * 获取所有供应商:模型的token统计的Flow
     * @return Flow<Map<供应商:模型, Triple<输入tokens, 输出tokens, 缓存tokens>>>
     */
    val allProviderModelTokensFlow: Flow<Map<String, Triple<Long, Long, Long>>> =
        context.apiDataStore.data.map { preferences ->
            val result = mutableMapOf<String, Triple<Long, Long, Long>>()
            val providerNames = registeredToolPkgProviderNames()
            
            // 遍历所有preferences，查找token相关的key
            preferences.asMap().forEach { (key, value) ->
                val keyName = key.name
                if (keyName.startsWith("token_input_")) {
                    val providerModel =
                            LegacyProviderModelKeyDecoder.decode(
                                    keyName.removePrefix("token_input_"), providerNames)
                    val inputTokens = readTokenCountValue(value)
                    val outputTokens = readTokenCount(preferences, getTokenOutputKey(providerModel).name)
                    val cachedInputTokens =
                            readTokenCount(preferences, getTokenCachedInputKey(providerModel).name)
                    if (inputTokens > 0L || outputTokens > 0L || cachedInputTokens > 0L) {
                        result[providerModel] = Triple(inputTokens, outputTokens, cachedInputTokens)
                    }
                }
            }
            
            result
        }

    // Save custom system prompt template
    suspend fun saveCustomSystemPromptTemplate(template: String) {
        context.apiDataStore.edit { preferences ->
            preferences[CUSTOM_SYSTEM_PROMPT_TEMPLATE] = template
        }
        LearningPromptSnapshotRepository.markChanged(context, "template")
    }

    // Reset custom system prompt template to default
    suspend fun resetCustomSystemPromptTemplate() {
        context.apiDataStore.edit { preferences ->
            preferences[CUSTOM_SYSTEM_PROMPT_TEMPLATE] = DEFAULT_SYSTEM_PROMPT_TEMPLATE
        }
        LearningPromptSnapshotRepository.markChanged(context, "template")
    }

    /**
     * legacy cleanup applied marker 键前缀（P1 闭环）：marker 与累计键清理在
     * **同一次** DataStore.edit 内原子完成；marker 已存在时同 operation 重试
     * 为幂等 no-op。marker ID 集合与 baseline 快照同一次读取，供导入 fence 校验
     * （见 [legacyStatsSnapshotWithMarkers]）。
     */
    val LEGACY_CLEANUP_MARKER_PREFIX = "legacy_cleanup_applied_"

    fun legacyCleanupMarkerKey(operationId: String): Preferences.Key<Boolean> =
        booleanPreferencesKey("$LEGACY_CLEANUP_MARKER_PREFIX$operationId")

    /** 读取全部已应用的 legacy cleanup marker operationId 集合（导入 fence 用）。 */
    suspend fun appliedLegacyCleanupMarkerIds(): Set<String> {
        val preferences = context.apiDataStore.data.first()
        return appliedMarkerIdsFrom(preferences)
    }

    /**
     * 应用一次 legacy cleanup（P1 闭环 drain 的 DataStore 侧）：
     * 单次 DataStore.edit 内，若该 operation 的 applied marker 不存在，则精准清除
     * 累计键并写入 marker；marker 已存在则幂等 no-op（崩溃后重放不二次清键）。
     * [providerModels] 为 null 表示 ALL kind：清除全部旧累计键
     * （token_input_ / token_cached_input_ / token_output_ / request_count_ 前缀），
     * **绝不触碰价格/计费方式等配置键**与 marker 键。取消向上传播。
     */
    suspend fun applyLegacyCleanup(operationId: String, providerModels: List<String>?) {
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        context.apiDataStore.edit { preferences ->
            applyLegacyCleanupMutation(preferences, operationId, providerModels)
        }
    }

    private fun appliedMarkerIdsFrom(preferences: Preferences): Set<String> =
        preferences.asMap().keys.asSequence()
            .map { it.name }
            .filter { it.startsWith(LEGACY_CLEANUP_MARKER_PREFIX) }
            .map { it.removePrefix(LEGACY_CLEANUP_MARKER_PREFIX) }
            .toSet()

    /**
     * 旧累计统计快照 + **同一次读取**的 applied marker ID 集合（P1 闭环导入 fence）：
     * baseline 快照与 marker 集合来自同一个 DataStore 读取，Room 事务内校验全部
     * cleanup operation ID 均包含在该 marker 集合（且无 PENDING）后才允许导入，
     * 杜绝“先读旧快照 → cleanup 完成 → 旧快照写回”复活已删除的 baseline。
     */
    suspend fun legacyStatsSnapshotWithMarkers(): LegacyStatsSnapshotRead {
        val preferences = context.apiDataStore.data.first()
        return LegacyStatsSnapshotRead(
            snapshot =
                com.ai.assistance.operit.data.stats.LegacyTokenStatsSnapshot.parse(
                    preferences.asMap().mapKeys { it.key.name },
                    registeredToolPkgProviderNames(),
                ),
            cleanupMarkerIds = appliedMarkerIdsFrom(preferences),
        )
    }

    /**
     * 重置所有供应商:模型的token计数，并同步清空新统计账本（事件 + baseline）。
     * P1 闭环：顺序改为 **Room 先删（同一事务写 FULL tombstone + 删除 + 创建
     * ALL cleanup operation）→ 排空 DataStore 累计键（marker 幂等）**，消除
     * 旧的“先清 DataStore 再删新账本”跨存储窗口（新账本删除失败时旧计数不会被
     * 静默清掉；排空失败时 operation 保持 PENDING 由下次启动重试）。
     * @return true = 旧计数与新账本均清零成功；false = 任一步失败
     * （已记录错误日志，调用方可据此提示用户重试，不假装成功）。
     * 协程取消（CancellationException）不在此吞掉，向上传播。
     */
    suspend fun resetAllProviderModelTokenCounts(): Boolean {
        return try {
            com.ai.assistance.operit.data.stats.TokenStatsResetCoordinator
                .resetAllStatistics(context)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "重置全部统计：新账本清理失败", e)
            false
        }
    }

    /**
     * 重置指定供应商:模型的token计数，并同步清空该模型在新账本中的事件与 baseline
     * （所有配置实例身份，见 TokenStatsResetCoordinator）。P1 闭环：顺序与
     * [resetAllProviderModelTokenCounts] 一致（Room 先删 + 创建精确 items 的
     * cleanup operation → 排空 DataStore 累计键）。
     * @return true = 旧计数与新账本均清零成功；false = 任一步失败
     * （已记录错误日志，调用方可据此提示用户重试，不假装成功）。
     * 协程取消（CancellationException）不在此吞掉，向上传播。
     */
    suspend fun resetProviderModelTokenCounts(providerModel: String): Boolean {
        return try {
            com.ai.assistance.operit.data.stats.TokenStatsResetCoordinator
                .resetStatisticsForProviderModel(context, providerModel)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "重置模型统计：新账本清理失败", e)
            false
        }
    }

    /**
     * 读取指定 provider:model 的旧系统用户价格设置（阶段 2 事件记录用）。
     * 旧约定：价格键缺失或为 0 视为未设置（0 与“未设置”不可区分），
     * 只有 > 0 的值才算用户设置；无任何设置时返回 null。
     */
    suspend fun legacyPriceSettingsFor(
        providerModel: String
    ): com.ai.assistance.operit.data.stats.LegacyPriceSettings? {
        val preferences = context.apiDataStore.data.first()
        return legacyPriceSettingsFrom(preferences, providerModel)
    }

    /**
     * 旧系统**全部** provider:model 用户价格设置的一次快照读取（阶段 3 统计查询
     * 重估口径用）：整个偏好文件只读一次（P1-2，杜绝按 identity 逐条读取 DataStore
     * 的多次挂起）。键约定与 [legacyPriceSettingsFor] 完全一致：价格键缺失或为 0
     * 视为未设置，只有 > 0 的值才算用户设置；无任何设置的模型不出现。
     */
    suspend fun allLegacyPriceSettings(): Map<String, com.ai.assistance.operit.data.stats.LegacyPriceSettings?> {
        val preferences = context.apiDataStore.data.first()
        val candidates = linkedSetOf<String>()
        val providerNames = registeredToolPkgProviderNames()
        preferences.asMap().keys.forEach { key ->
            val name = key.name
            for (prefix in LEGACY_PRICE_KEY_PREFIXES) {
                if (name.startsWith(prefix) && name.length > prefix.length) {
                    candidates += LegacyProviderModelKeyDecoder.decode(
                            name.substring(prefix.length), providerNames)
                    break
                }
            }
        }
        return candidates.associateWith { providerModel ->
            legacyPriceSettingsFrom(preferences, providerModel)
        }
    }

    /** 恢复内置定价时清除旧系统遗留的 provider:model 价格层。 */
    suspend fun clearLegacyPriceSettings(providerModel: String) {
        context.apiDataStore.edit { preferences ->
            preferences.remove(getModelInputPriceKey(providerModel))
            preferences.remove(getModelCachedInputPriceKey(providerModel))
            preferences.remove(getModelOutputPriceKey(providerModel))
            preferences.remove(getBillingModeKey(providerModel))
            preferences.remove(getPricePerRequestKey(providerModel))
        }
    }

    private fun legacyPriceSettingsFrom(
        preferences: Preferences,
        providerModel: String
    ): com.ai.assistance.operit.data.stats.LegacyPriceSettings? {
        val billingRaw = preferences[getBillingModeKey(providerModel)]
        val settings =
            com.ai.assistance.operit.data.stats.LegacyPriceSettings(
                billingMode = billingRaw?.let { com.ai.assistance.operit.data.model.BillingMode.fromString(it) },
                inputPricePerMillion =
                    preferences[getModelInputPriceKey(providerModel)]?.toDouble()?.takeIf { it > 0.0 },
                cachedInputPricePerMillion =
                    preferences[getModelCachedInputPriceKey(providerModel)]?.toDouble()?.takeIf { it > 0.0 },
                outputPricePerMillion =
                    preferences[getModelOutputPriceKey(providerModel)]?.toDouble()?.takeIf { it > 0.0 },
                pricePerRequest =
                    preferences[getPricePerRequestKey(providerModel)]?.toDouble()?.takeIf { it > 0.0 },
            )
        return settings.takeIf { it.hasAnyUserSetting() }
    }

    private fun removeTokenCountKeys(preferences: MutablePreferences, vararg keyNames: String) =
        removeTokenCountKeysForMutation(preferences, *keyNames)

    private fun readTokenCount(preferences: Preferences, keyName: String): Long {
        val values = preferences.asMap().entries
                .filter { it.key.name == keyName }
                .map { it.value }
        val value = values.firstOrNull { it is Long } ?: values.firstOrNull()
        return readTokenCountValue(value)
    }

    private fun readTokenCountValue(value: Any?): Long {
        return when (value) {
            is Long -> value
            is Int -> if (value < 0) value.toLong() and 0xFFFF_FFFFL else value.toLong()
            else -> 0L
        }
    }

    // 获取模型输入价格（每百万tokens的美元价格）
    suspend fun getModelInputPrice(providerModel: String): Double {
        val preferences = context.apiDataStore.data.first()
        return preferences[getModelInputPriceKey(providerModel)]?.toDouble() ?: 0.0
    }

    // 获取模型缓存输入价格（每百万tokens的美元价格）
    suspend fun getModelCachedInputPrice(providerModel: String): Double {
        val preferences = context.apiDataStore.data.first()
        return preferences[getModelCachedInputPriceKey(providerModel)]?.toDouble() ?: 0.0
    }

    // 获取模型输出价格（每百万tokens的美元价格）
    suspend fun getModelOutputPrice(providerModel: String): Double {
        val preferences = context.apiDataStore.data.first()
        return preferences[getModelOutputPriceKey(providerModel)]?.toDouble() ?: 0.0
    }

    // 设置模型输入价格（每百万tokens的美元价格）
    suspend fun setModelInputPrice(providerModel: String, price: Double) {
        context.apiDataStore.edit { preferences ->
            preferences[getModelInputPriceKey(providerModel)] = price.toFloat()
        }
    }

    // 设置模型缓存输入价格（每百万tokens的美元价格）
    suspend fun setModelCachedInputPrice(providerModel: String, price: Double) {
        context.apiDataStore.edit { preferences ->
            preferences[getModelCachedInputPriceKey(providerModel)] = price.toFloat()
        }
    }

    // 设置模型输出价格（每百万tokens的美元价格）
    suspend fun setModelOutputPrice(providerModel: String, price: Double) {
        context.apiDataStore.edit { preferences ->
            preferences[getModelOutputPriceKey(providerModel)] = price.toFloat()
        }
    }

    // ===== Request Count Statistics 请求次数统计相关方法 =====

    /**
     * 增加指定供应商:模型的请求次数
     * @param providerModel 供应商:模型标识符，格式如"DEEPSEEK:deepseek-chat"
     */
    suspend fun incrementRequestCountForProviderModel(providerModel: String) {
        context.apiDataStore.edit { preferences ->
            val countKey = getRequestCountKey(providerModel)
            val currentCount = preferences[countKey] ?: 0
            preferences[countKey] = currentCount + 1
        }
    }

    /**
     * 获取指定供应商:模型的请求次数
     * @param providerModel 供应商:模型标识符
     * @return 请求次数
     */
    suspend fun getRequestCountForProviderModel(providerModel: String): Int {
        val preferences = context.apiDataStore.data.first()
        return preferences[getRequestCountKey(providerModel)] ?: 0
    }

    /**
     * 获取所有供应商:模型的请求次数统计
     * @return Map<供应商:模型, 请求次数>
     */
    suspend fun getAllProviderModelRequestCounts(): Map<String, Int> {
        val preferences = context.apiDataStore.data.first()
        val result = mutableMapOf<String, Int>()
        val providerNames = registeredToolPkgProviderNames()
        
        // 遍历所有preferences，查找请求次数相关的key
        preferences.asMap().forEach { (key, value) ->
            val keyName = key.name
            if (keyName.startsWith("request_count_")) {
                val providerModel =
                        LegacyProviderModelKeyDecoder.decode(
                                keyName.removePrefix("request_count_"), providerNames)
                val count = value as? Int ?: 0
                if (count > 0) {
                    result[providerModel] = count
                }
            }
        }
        
        return result
    }

    /**
     * 重置指定供应商:模型的请求次数
     * @param providerModel 供应商:模型标识符
     */
    suspend fun resetProviderModelRequestCount(providerModel: String) {
        context.apiDataStore.edit { preferences ->
            preferences[getRequestCountKey(providerModel)] = 0
        }
    }

    // ===== Billing Mode 计费方式相关方法 =====

    /**
     * 获取指定供应商:模型的计费方式
     * @param providerModel 供应商:模型标识符
     * @return 计费方式，默认为TOKEN
     */
    suspend fun getBillingModeForProviderModel(providerModel: String): com.ai.assistance.operit.data.model.BillingMode {
        val preferences = context.apiDataStore.data.first()
        val modeString = preferences[getBillingModeKey(providerModel)]
        return com.ai.assistance.operit.data.model.BillingMode.fromString(modeString)
    }

    /**
     * 设置指定供应商:模型的计费方式
     * @param providerModel 供应商:模型标识符
     * @param mode 计费方式
     */
    suspend fun setBillingModeForProviderModel(providerModel: String, mode: com.ai.assistance.operit.data.model.BillingMode) {
        context.apiDataStore.edit { preferences ->
            preferences[getBillingModeKey(providerModel)] = mode.name
        }
    }

    // ===== Price Per Request 按次计费价格相关方法 =====

    /**
     * 获取指定供应商:模型的按次计费价格
     * @param providerModel 供应商:模型标识符
     * @return 每次请求的价格，未设置时返回0.0
     */
    suspend fun getPricePerRequestForProviderModel(providerModel: String): Double {
        val preferences = context.apiDataStore.data.first()
        return preferences[getPricePerRequestKey(providerModel)]?.toDouble() ?: 0.0
    }

    /**
     * 设置指定供应商:模型的按次计费价格（人民币）
     * @param providerModel 供应商:模型标识符
     * @param price 每次请求的价格
     */
    suspend fun setPricePerRequestForProviderModel(providerModel: String, price: Double) {
        context.apiDataStore.edit { preferences ->
            preferences[getPricePerRequestKey(providerModel)] = price.toFloat()
        }
    }

    suspend fun getUsdToCnyExchangeRate(): Double {
        val preferences = context.apiDataStore.data.first()
        return resolveUsdToCnyExchangeRate(preferences[USD_TO_CNY_EXCHANGE_RATE])
    }

    /**
     * 统计页汇率读取（阶段 4）：区分“用户手动设置”与“未设置”。
     * 未设置时返回默认估算 7.0（[com.ai.assistance.operit.data.stats.TokenCostCurrency]
     * 契约）并标记 estimated = true，界面必须显示估算提示；不联网获取汇率。
     */
    suspend fun usdToCnyRateWithEstimate(): Pair<Double, Boolean> {
        val preferences = context.apiDataStore.data.first()
        return resolveUsdToCnyRateWithEstimate(preferences[USD_TO_CNY_EXCHANGE_RATE])
    }

    suspend fun setUsdToCnyExchangeRate(rate: Double) {
        val stored = requireNotNull(usdToCnyStorageValue(rate)) {
            "USD to CNY exchange rate must remain finite and positive as Float"
        }
        context.apiDataStore.edit { preferences ->
            preferences[USD_TO_CNY_EXCHANGE_RATE] = stored
        }
    }

    // ===== 统计页偏好（阶段 4；与汇率共用 api_settings 文件，备份自动覆盖） =====

    suspend fun getStatsTargetCurrency(): com.ai.assistance.operit.data.collects.PricingCurrency {
        val preferences = context.apiDataStore.data.first()
        val raw = preferences[STATS_TARGET_CURRENCY]
        return if (raw.equals(com.ai.assistance.operit.data.collects.PricingCurrency.USD.name, ignoreCase = true)) {
            com.ai.assistance.operit.data.collects.PricingCurrency.USD
        } else {
            com.ai.assistance.operit.data.collects.PricingCurrency.CNY
        }
    }

    suspend fun setStatsTargetCurrency(
        currency: com.ai.assistance.operit.data.collects.PricingCurrency
    ) {
        context.apiDataStore.edit { preferences ->
            preferences[STATS_TARGET_CURRENCY] = currency.name
        }
    }

    suspend fun getStatsCostMode(): com.ai.assistance.operit.data.stats.TokenStatsCostMode {
        val preferences = context.apiDataStore.data.first()
        val raw = preferences[STATS_COST_MODE]
        return com.ai.assistance.operit.data.stats.TokenStatsCostMode.entries
            .firstOrNull { it.name == raw }
            ?: com.ai.assistance.operit.data.stats.TokenStatsCostMode.HISTORICAL
    }

    suspend fun setStatsCostMode(mode: com.ai.assistance.operit.data.stats.TokenStatsCostMode) {
        context.apiDataStore.edit { preferences ->
            preferences[STATS_COST_MODE] = mode.name
        }
    }

    private fun registeredToolPkgProviderNames(): List<String> =
            toolPkgProviderNamesProvider?.invoke()
                ?: ToolPkgAiProviderRegistry.list().map { it.displayName }

    /** 旧版累计 baseline 是否加入生命周期累计；缺省开启以保持升级前后的总计连续。 */
    suspend fun getStatsIncludeLegacy(): Boolean {
        val preferences = context.apiDataStore.data.first()
        return preferences[STATS_INCLUDE_LEGACY] ?: true
    }

    suspend fun setStatsIncludeLegacy(include: Boolean) {
        context.apiDataStore.edit { preferences ->
            preferences[STATS_INCLUDE_LEGACY] = include
        }
    }

    /**
     * 统计页时间选择（阶段 4）：null = 从未有任何选择（首次进入，允许自动回退）。
     * CUSTOM 预设必须同时存在合法自定义边界，否则视为未选择（防御损坏状态）。
     */
    suspend fun getStatsTimeSelection(): com.ai.assistance.operit.data.stats.TokenStatsTimeSelection? {
        val preferences = context.apiDataStore.data.first()
        val presetRaw = preferences[STATS_TIME_PRESET] ?: return null
        val preset = com.ai.assistance.operit.data.stats.TokenStatsPreset.entries
            .firstOrNull { it.name == presetRaw }
            ?: return null
        if (preset != com.ai.assistance.operit.data.stats.TokenStatsPreset.CUSTOM) {
            return com.ai.assistance.operit.data.stats.TokenStatsTimeSelection(preset)
        }
        val start = preferences[STATS_TIME_CUSTOM_START] ?: return null
        val end = preferences[STATS_TIME_CUSTOM_END] ?: return null
        if (end <= start) return null
        return com.ai.assistance.operit.data.stats.TokenStatsTimeSelection(preset, start, end)
    }

    /**
     * 统计页时间选择是否由用户手动做出（阶段 4）。
     * false = 首次自动回退结果；旧版本持久化的选择没有该键，按 false 处理
     * （选择本身仍被复用，只是不再区分来源，迁移合理）。
     */
    suspend fun getStatsSelectionWasManual(): Boolean {
        val preferences = context.apiDataStore.data.first()
        return preferences[STATS_TIME_MANUAL] ?: false
    }

    /**
     * 统计页时间选择保存（阶段 4）：[manual] = 用户手动选择（true）或首次
     * 自动回退（false）。清除时（[selection] = null）一并移除 manual 键，
     * 回到“从未选择”的首次回退语义。
     */
    suspend fun setStatsTimeSelection(
        selection: com.ai.assistance.operit.data.stats.TokenStatsTimeSelection?,
        manual: Boolean,
    ) {
        context.apiDataStore.edit { preferences ->
            if (selection == null) {
                preferences.remove(STATS_TIME_PRESET)
                preferences.remove(STATS_TIME_CUSTOM_START)
                preferences.remove(STATS_TIME_CUSTOM_END)
                preferences.remove(STATS_TIME_MANUAL)
                return@edit
            }
            preferences[STATS_TIME_PRESET] = selection.preset.name
            preferences[STATS_TIME_MANUAL] = manual
            if (selection.preset == com.ai.assistance.operit.data.stats.TokenStatsPreset.CUSTOM) {
                preferences[STATS_TIME_CUSTOM_START] = selection.customStartMs ?: 0L
                preferences[STATS_TIME_CUSTOM_END] = selection.customEndMs ?: 0L
            } else {
                preferences.remove(STATS_TIME_CUSTOM_START)
                preferences.remove(STATS_TIME_CUSTOM_END)
            }
        }
    }


}

/**
 * 单次 DataStore.edit 内的 legacy cleanup 变更（P1 闭环，纯函数）：
 * - marker 已存在 → 严格 no-op（崩溃后重放不二次清键，也不写任何值）；
 * - [providerModels] == null（ALL kind）→ 清除全部旧累计键
 *   （token_input_/token_cached_input_/token_output_/request_count_ 前缀），
 *   价格/计费方式等配置键与 marker 键一律保留；
 * - 否则只清除这些 provider:model 的累计键与 request_count；
 * 之后写入 operation marker（与清理同一次 edit 原子提交）。
 * 独立为纯函数以便 Windows JVM 测试直接验证键级语义（DataStore.edit 只是薄壳）。
 */
internal fun applyLegacyCleanupMutation(
    preferences: MutablePreferences,
    operationId: String,
    providerModels: List<String>?,
) {
    require(operationId.isNotBlank()) { "operationId must not be blank" }
    val markerKey = ApiPreferences.legacyCleanupMarkerKey(operationId)
    if (preferences[markerKey] == true) return
    if (providerModels == null) {
        val keysToRemove =
            preferences.asMap().keys.filter { key ->
                key.name.startsWith("token_input_") ||
                    key.name.startsWith("token_cached_input_") ||
                    key.name.startsWith("token_output_") ||
                    key.name.startsWith("request_count_")
            }
        keysToRemove.forEach { preferences.remove(it) }
    } else {
        providerModels.distinct().forEach { providerModel ->
            ApiPreferences.removeTokenCountKeysForMutation(
                preferences,
                ApiPreferences.getTokenInputKey(providerModel).name,
                ApiPreferences.getTokenCachedInputKey(providerModel).name,
                ApiPreferences.getTokenOutputKey(providerModel).name,
            )
            preferences.remove(ApiPreferences.getRequestCountKey(providerModel))
        }
    }
    preferences[markerKey] = true
}

/**
 * baseline 快照 + 同一次 DataStore 读取的 applied marker ID 集合（导入 fence 用）。
 */
data class LegacyStatsSnapshotRead(
    val snapshot: com.ai.assistance.operit.data.stats.LegacyTokenStatsSnapshot,
    val cleanupMarkerIds: Set<String>,
)
