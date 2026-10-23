package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.api.chat.llmprovider.AIServiceFactory
import com.ai.assistance.operit.api.chat.llmprovider.RateLimitedAIService
import com.ai.assistance.operit.api.chat.llmprovider.RateLimiterRegistry
import com.ai.assistance.operit.api.chat.llmprovider.RequestConcurrencyRegistry
import com.ai.assistance.operit.api.chat.llmprovider.withFunctionalReasoning
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.forSelectedModel
import com.ai.assistance.operit.data.model.getModelByIndex
import com.ai.assistance.operit.data.model.getValidModelIndex
import com.ai.assistance.operit.data.model.getModelList
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.resolveModelIndex
import com.ai.assistance.operit.data.model.multimodalCapabilitiesForModel
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** 管理多个AIService实例，根据功能类型提供不同的服务配置 */
class MultiServiceManager(private val context: Context) {
    companion object {
        private const val TAG = "MultiServiceManager"
    }

    class ServiceLease internal constructor(
        private val closeAction: suspend () -> Unit,
        val service: AIService,
        val modelConfig: ModelConfigData,
        val modelIndex: Int,
        val modelParameters: List<ModelParameter<*>>
    ) {
        private val closed = AtomicBoolean(false)

        suspend fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    withContext(NonCancellable) {
                        closeAction()
                    }
                } catch (error: Throwable) {
                    closed.set(false)
                    throw error
                }
            }
        }
    }

    private class ManagedService(
        val service: AIService,
        val modelConfig: ModelConfigData,
        val modelIndex: Int,
        val modelParameters: List<ModelParameter<*>>,
        var activeLeases: Int = 0,
        var retired: Boolean = false,
        var released: Boolean = false
    )

    // 配置管理器
    private val functionalConfigManager = FunctionalConfigManager(context)
    private val modelConfigManager = ModelConfigManager(context)

    // 服务实例缓存
    private val serviceInstances = mutableMapOf<FunctionType, ManagedService>()
    private val customServiceInstances = mutableMapOf<String, ManagedService>()
    private val retiredServices = mutableSetOf<ManagedService>()
    private val serviceMutex = Mutex()

    private val initMutex = Mutex()
    @Volatile private var isInitialized = false

    // 默认AIService，用于兼容现有代码
    private var defaultService: ManagedService? = null

    /** 初始化服务管理器，确保配置已经准备好 */
    suspend fun initialize() {
        ensureInitialized()
    }

    private suspend fun ensureInitialized() {
        if (isInitialized) return
        initMutex.withLock {
            if (isInitialized) return
            functionalConfigManager.initializeIfNeeded()
            isInitialized = true
        }
    }

    /** 获取指定功能类型的AIService */
    suspend fun getServiceForFunction(functionType: FunctionType): AIService {
        ensureInitialized()
        return serviceMutex.withLock {
            getOrCreateServiceForFunctionLocked(functionType).service
        }
    }

    /** 根据配置ID和模型索引获取AIService（不会修改功能映射） */
    suspend fun getServiceForConfig(configId: String, modelIndex: Int): AIService {
        ensureInitialized()
        return serviceMutex.withLock {
            getOrCreateServiceForConfigLocked(configId, modelIndex).service
        }
    }

    suspend fun acquireServiceForFunction(functionType: FunctionType): ServiceLease {
        ensureInitialized()
        return serviceMutex.withLock {
            val managedService =
                getOrCreateServiceForFunctionLocked(functionType).also { it.activeLeases += 1 }
            ServiceLease(
                closeAction = { releaseLease(managedService) },
                service = managedService.service,
                modelConfig = managedService.modelConfig,
                modelIndex = managedService.modelIndex,
                modelParameters = managedService.modelParameters,
            )
        }
    }

    suspend fun acquireServiceForConfig(configId: String, modelIndex: Int): ServiceLease {
        ensureInitialized()
        return serviceMutex.withLock {
            val managedService =
                getOrCreateServiceForConfigLocked(configId, modelIndex).also {
                    it.activeLeases += 1
                }
            ServiceLease(
                closeAction = { releaseLease(managedService) },
                service = managedService.service,
                modelConfig = managedService.modelConfig,
                modelIndex = managedService.modelIndex,
                modelParameters = managedService.modelParameters,
            )
        }
    }

    /** A fresh provider, never entered into foreground caches or shared cancellation state.
     * Registry-backed throttles and the factory's usage ledger still apply.
     * The caller owns cancellation and must close this lease in finally.
     */
    suspend fun acquireIsolatedService(
        modelConfig: ModelConfigData,
        modelParameters: List<ModelParameter<*>>,
    ): ServiceLease {
        val service = createServiceFromConfig(modelConfig, 0, allowPluginProviders = false)
        return ServiceLease(
            closeAction = { service.release() },
            service = service,
            modelConfig = modelConfig,
            modelIndex = 0,
            modelParameters = modelParameters.toList(),
        )
    }

    private suspend fun getOrCreateServiceForFunctionLocked(functionType: FunctionType): ManagedService {
        val configMapping = functionalConfigManager.getEffectiveConfigMappingForFunction(functionType)
        val config = modelConfigManager.getModelConfigFlow(configMapping.configId).first()
        val actualModelIndex = configMapping.resolveModelIndex(config.modelName).modelIndex
        requireAvailableSelection(config, actualModelIndex)
        val selectedModelConfig = config.forSelectedModel(actualModelIndex)
        serviceInstances[functionType]?.let {
            if (it.modelConfig == selectedModelConfig && it.modelIndex == actualModelIndex) return it
            serviceInstances.remove(functionType)
            retireManagedServiceLocked(it)
        }
        val baseService = createServiceFromConfig(config, actualModelIndex)
        val service =
            if (functionType == FunctionType.CHAT) {
                baseService
            } else {
                baseService.withFunctionalReasoning(
                    config = selectedModelConfig,
                    thinkingQualityLevel = configMapping.thinkingQualityLevel,
                )
            }
        val managedService = ManagedService(
            service = service,
            modelConfig = selectedModelConfig,
            modelIndex = actualModelIndex,
            modelParameters =
                modelConfigManager.getModelParametersForConfigSnapshot(selectedModelConfig),
        )
        serviceInstances[functionType] = managedService

        if (functionType == FunctionType.CHAT) {
            defaultService = managedService
        }

        AppLogger.d(
            TAG,
            "已为功能${functionType}创建服务实例，使用配置${config.name}，模型索引${configMapping.modelIndex}，思考程度${configMapping.thinkingQualityLevel}",
        )
        return managedService
    }

    private suspend fun getOrCreateServiceForConfigLocked(configId: String, modelIndex: Int): ManagedService {
        val normalizedIndex = modelIndex
        val cacheKey = "$configId#$normalizedIndex"
        val config = modelConfigManager.getModelConfigFlow(configId).first()
        requireAvailableSelection(config, normalizedIndex)
        val actualModelIndex = normalizedIndex
        customServiceInstances[cacheKey]?.let {
            if (it.modelConfig == config.forSelectedModel(actualModelIndex)) return it
            customServiceInstances.remove(cacheKey)
            retireManagedServiceLocked(it)
        }
        val service = createServiceFromConfig(config, actualModelIndex)
        val managedService = ManagedService(
            service = service,
            modelConfig = config.forSelectedModel(actualModelIndex),
            modelIndex = actualModelIndex,
            modelParameters =
                modelConfigManager.getModelParametersForConfigSnapshot(
                    config.forSelectedModel(actualModelIndex),
                ),
        )
        customServiceInstances[cacheKey] = managedService

        AppLogger.d(TAG, "已为自定义配置创建服务实例，配置=$configId，模型索引=$normalizedIndex")
        return managedService
    }

    /** 获取默认服务（通常是CHAT功能的服务） */
    suspend fun getDefaultService(): AIService {
        ensureInitialized()
        return serviceMutex.withLock {
            getOrCreateServiceForFunctionLocked(FunctionType.CHAT).service
        }
    }

    suspend fun cancelAllStreaming() {
        serviceMutex.withLock {
            val services = mutableSetOf<AIService>()
            services.addAll(serviceInstances.values.map { it.service })
            services.addAll(customServiceInstances.values.map { it.service })
            services.addAll(retiredServices.map { it.service })
            defaultService?.let { services.add(it.service) }

            services.forEach { service ->
                try {
                    service.cancelStreaming()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "取消服务流式传输时出错", e)
                }
            }
        }
    }

    suspend fun resetAllTokenCounters() {
        serviceMutex.withLock {
            val services = mutableSetOf<AIService>()
            services.addAll(serviceInstances.values.map { it.service })
            services.addAll(customServiceInstances.values.map { it.service })
            services.addAll(retiredServices.map { it.service })
            defaultService?.let { services.add(it.service) }

            services.forEach { service ->
                try {
                    service.resetTokenCounts()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "重置服务token计数器时出错", e)
                }
            }
        }
    }

    suspend fun resetTokenCountersForFunction(functionType: FunctionType) {
        val service = getServiceForFunction(functionType)
        try {
            service.resetTokenCounts()
        } catch (e: Exception) {
            AppLogger.e(TAG, "重置功能${functionType}的token计数器时出错", e)
        }
    }

    /** 刷新指定功能类型的服务实例 当配置更改时调用此方法 */
    suspend fun refreshServiceForFunction(functionType: FunctionType) {
        ensureInitialized()
        serviceMutex.withLock {
            serviceInstances.remove(functionType)?.let { retireManagedServiceLocked(it) }

            // A follower borrows this function's configuration, so its cached service may have been
            // built from the previous one.
            FunctionalConfigManager.FUNCTION_CONFIG_FOLLOWERS
                .filterValues { followed -> followed == functionType }
                .keys
                .forEach { follower ->
                    serviceInstances.remove(follower)?.let { retireManagedServiceLocked(it) }
                }

            if (functionType == FunctionType.CHAT) {
                defaultService = null
                val customServices = customServiceInstances.values.toList()
                customServiceInstances.clear()
                customServices.forEach { service ->
                    retireManagedServiceLocked(service)
                }
            }

            AppLogger.d(TAG, "已移除功能${functionType}的服务实例缓存")
        }
    }

    /** 刷新所有服务实例 当全局设置更改时调用此方法 */
    suspend fun refreshAllServices() {
        ensureInitialized()
        serviceMutex.withLock {
            val services = mutableSetOf<ManagedService>()
            services.addAll(serviceInstances.values)
            services.addAll(customServiceInstances.values)
            services.addAll(retiredServices)
            defaultService?.let { services.add(it) }

            serviceInstances.clear()
            customServiceInstances.clear()
            defaultService = null
            services.forEach { service ->
                retireManagedServiceLocked(service)
            }
            AppLogger.d(TAG, "已清除所有服务实例缓存；活跃租约结束后释放资源")
        }
    }

    private suspend fun releaseLease(managedService: ManagedService) {
        serviceMutex.withLock {
            managedService.activeLeases = (managedService.activeLeases - 1).coerceAtLeast(0)
            closeRetiredServiceLocked(managedService)
        }
    }

    private fun retireManagedServiceLocked(managedService: ManagedService) {
        managedService.retired = true
        retiredServices.add(managedService)
        closeRetiredServiceLocked(managedService)
    }

    private fun closeRetiredServiceLocked(managedService: ManagedService) {
        if (managedService.retired && managedService.activeLeases == 0) {
            closeManagedServiceLocked(managedService, cancelStreaming = false)
        }
    }

    private fun closeManagedServiceLocked(managedService: ManagedService, cancelStreaming: Boolean) {
        if (managedService.released) {
            return
        }
        managedService.released = true
        retiredServices.remove(managedService)
        try {
            if (cancelStreaming) {
                managedService.service.cancelStreaming()
            }
            managedService.service.release()
            AppLogger.d(TAG, "已释放服务资源: providerModel=${managedService.service.providerModel}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "释放服务资源时出错", e)
        }
    }

    /** 根据配置创建AIService实例 */
    private suspend fun createServiceFromConfig(
        config: ModelConfigData,
        modelIndex: Int,
        allowPluginProviders: Boolean = true,
    ): AIService {
        // 使用公共函数计算有效索引
        val actualIndex = getValidModelIndex(config.modelName, modelIndex)
        
        // 记录越界警告
        if (actualIndex != modelIndex && modelIndex != 0) {
            val modelList = config.modelName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            AppLogger.w(TAG, "模型索引 $modelIndex 超出范围(0-${modelList.size - 1})，自动使用第一个模型")
        }
        
        // 根据实际索引选择具体模型
        val selectedModelName = getModelByIndex(config.modelName, actualIndex)
        
        // 创建一个临时配置，使用选中的模型名称
        val configWithSelectedModel = config.forSelectedModel(actualIndex)
        
        AppLogger.d(TAG, "创建服务: 原始模型='${config.modelName}', 选中模型='$selectedModelName' (请求索引=$modelIndex, 实际索引=$actualIndex)")

        val rawService = AIServiceFactory.createService(
            config = configWithSelectedModel,
            modelConfigManager = modelConfigManager,
            context = context,
            allowPluginProviders = allowPluginProviders,
            useSnapshotApiKeys = !allowPluginProviders,
        )

        val requestLimitPerMinute = config.requestLimitPerMinute.coerceAtLeast(0)
        val maxConcurrentRequests = config.maxConcurrentRequests.coerceAtLeast(0)

        if (requestLimitPerMinute == 0 && maxConcurrentRequests == 0) {
            return rawService
        }

        val limiter =
            if (requestLimitPerMinute > 0) {
                RateLimiterRegistry.getOrCreate(
                    key = config.id,
                    maxRequestsPerMinute = requestLimitPerMinute
                )
            } else {
                null
            }

        val concurrencySemaphore =
            if (maxConcurrentRequests > 0) {
                RequestConcurrencyRegistry.getOrCreate(
                    key = config.id,
                    maxConcurrentRequests = maxConcurrentRequests
                )
            } else {
                null
            }

        return RateLimitedAIService(
            delegate = rawService,
            rateLimiter = limiter,
            concurrencySemaphore = concurrencySemaphore
        )
    }

    /**
     * 获取指定功能类型的模型参数列表
     * @param functionType 功能类型
     * @return 模型参数列表
     */
    suspend fun getModelParametersForFunction(
            functionType: FunctionType
    ): List<com.ai.assistance.operit.data.model.ModelParameter<*>> {
        ensureInitialized()
        val configMapping = functionalConfigManager.getEffectiveConfigMappingForFunction(functionType)
        return modelConfigManager.getModelParametersForConfig(configMapping.configId)
    }

    /**
     * 获取指定功能类型的模型配置
     * @param functionType 功能类型
     * @return 模型配置数据
     */
    suspend fun getModelConfigForFunction(functionType: FunctionType): ModelConfigData {
        ensureInitialized()
        val mapping = functionalConfigManager.getEffectiveConfigMappingForFunction(functionType)
        val config = modelConfigManager.getModelConfigFlow(mapping.configId).first()
        val index = mapping.resolveModelIndex(config.modelName).modelIndex
        requireAvailableSelection(config, index)
        return config.forSelectedModel(index)
    }

    private suspend fun requireAvailableSelection(config: ModelConfigData, modelIndex: Int) {
        val unnamedPlugin = config.modelName.isBlank() && modelIndex == 0 &&
            com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry.get(config.apiProviderTypeId) != null
        check(config.id in modelConfigManager.configListFlow.first() &&
            (modelIndex in getModelList(config.modelName).indices || unnamedPlugin)) {
            context.getString(R.string.functional_model_selection_unavailable)
        }
    }

    suspend fun getSelectedModelConfigForConfig(configId: String, modelIndex: Int): ModelConfigData {
        ensureInitialized()
        val config = modelConfigManager.getModelConfigFlow(configId).first()
        requireAvailableSelection(config, modelIndex)
        return config.forSelectedModel(modelIndex)
    }

    /** 获取指定配置ID的模型配置 */
    suspend fun getModelConfigForConfig(configId: String): ModelConfigData {
        ensureInitialized()
        return modelConfigManager.getModelConfigFlow(configId).first()
    }

    /** 获取指定配置ID的模型参数 */
    suspend fun getModelParametersForConfig(
        configId: String
    ): List<com.ai.assistance.operit.data.model.ModelParameter<*>> {
        ensureInitialized()
        return modelConfigManager.getModelParametersForConfig(configId)
    }

    /**
     * 检查识图功能是否已配置
     * @return 如果识图功能配置启用了直接图片处理则返回true
     */
    suspend fun hasImageRecognitionConfigured(): Boolean {
        ensureInitialized()
        val configMapping = functionalConfigManager.getConfigMappingForFunction(FunctionType.IMAGE_RECOGNITION)
        val config = modelConfigManager.getModelConfigFlow(configMapping.configId).first()
        
        // 检查模型配置是否启用了直接图片处理
        return config.multimodalCapabilitiesForModel(configMapping.modelIndex).image
    }

    suspend fun hasAudioRecognitionConfigured(): Boolean {
        ensureInitialized()
        val configMapping = functionalConfigManager.getConfigMappingForFunction(FunctionType.AUDIO_RECOGNITION)
        val config = modelConfigManager.getModelConfigFlow(configMapping.configId).first()
        return config.multimodalCapabilitiesForModel(configMapping.modelIndex).audio
    }

    suspend fun hasVideoRecognitionConfigured(): Boolean {
        ensureInitialized()
        val configMapping = functionalConfigManager.getConfigMappingForFunction(FunctionType.VIDEO_RECOGNITION)
        val config = modelConfigManager.getModelConfigFlow(configMapping.configId).first()
        return config.multimodalCapabilitiesForModel(configMapping.modelIndex).video
    }

}
