package com.ai.assistance.operit.api.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.operit.api.chat.protocol.ExecutableToolProtocolParser
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.ui.features.chat.components.part.permissionDenialDisplayText
import com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager
import com.ai.assistance.operit.api.chat.enhance.ConversationRoundManager
import com.ai.assistance.operit.api.chat.enhance.ConversationService
import com.ai.assistance.operit.api.chat.enhance.FileBindingService
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.api.chat.enhance.ToolTurnSignal
import com.ai.assistance.operit.api.chat.enhance.resolveToolTurnSignal
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.core.chat.logMessageTiming
import com.ai.assistance.operit.core.chat.messageTimingNow
import com.ai.assistance.operit.core.chat.TurnInputInbox
import com.ai.assistance.operit.core.chat.AssistantToolSequence
import com.ai.assistance.operit.core.chat.hooks.PromptHookContext
import com.ai.assistance.operit.core.chat.hooks.PromptHookRegistry
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.chat.hooks.appendUserTurnIfMissing
import com.ai.assistance.operit.core.chat.hooks.buildActivePromptHookMetadata
import com.ai.assistance.operit.core.chat.hooks.mergeAdjacentTurns
import com.ai.assistance.operit.core.chat.hooks.toPromptTurns
import com.ai.assistance.operit.core.chat.hooks.toRoleContentPairs
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.core.tools.climode.CliToolModeSupport
import com.ai.assistance.operit.core.tools.climode.ToolExposureMode
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.forSelectedModel
import com.ai.assistance.operit.data.model.protocolSettingsForModel
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ConversationSummaryConfig
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.ExternalHttpApiPreferences
import com.ai.assistance.operit.data.preferences.WakeWordPreferences
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.stream.MutableSharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TextStreamEventType
import com.ai.assistance.operit.util.stream.TextStreamRevisionTracker
import com.ai.assistance.operit.util.stream.stream
import com.ai.assistance.operit.util.stream.withEventChannel
import com.ai.assistance.operit.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.ai.assistance.operit.data.repository.CustomEmojiRepository
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.data.repository.SubagentRunRepository
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.data.repository.MemoryAutoSaveCandidateRepository
import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.LocaleUtils

@Suppress("UNUSED_PARAMETER")
internal fun resolveImageRecognitionAvailability(
    isSubTask: Boolean,
    hasConfiguredBackend: Boolean,
): Boolean {
    // IMAGE_RECOGNITION is a bounded function-model request, not a nested agent turn. Subagents
    // therefore use the same fallback as the root chat when their own model cannot see images.
    return hasConfiguredBackend
}

/**
 * Enhanced AI service that provides advanced conversational capabilities by integrating various
 * components like tool execution, conversation management, user preferences, and problem library.
 */
class EnhancedAIService private constructor(
    private val context: Context,
    private val providerSessionId: String = java.util.UUID.randomUUID().toString(),
) {
    data class TurnTokenSnapshot(
        val inputTokens: Int,
        val outputTokens: Int,
        val cachedInputTokens: Int,
    )

    companion object {
        private const val TAG = "EnhancedAIService"

        @Volatile private var INSTANCE: EnhancedAIService? = null

        private val CHAT_INSTANCES = ConcurrentHashMap<String, EnhancedAIService>()

        private val FOREGROUND_REF_COUNT = AtomicInteger(0)

        /**
         * 获取EnhancedAIService实例
         * @param context 应用上下文
         * @return EnhancedAIService实.
         */
        fun getInstance(context: Context): EnhancedAIService {
            return INSTANCE
                    ?: synchronized(this) {
                        INSTANCE
                                ?: EnhancedAIService(context.applicationContext).also {
                                    INSTANCE = it
                                }
                    }
        }

        fun getChatInstance(context: Context, chatId: String): EnhancedAIService {
            val appContext = context.applicationContext
            return CHAT_INSTANCES[chatId]
                ?: synchronized(CHAT_INSTANCES) {
                    CHAT_INSTANCES[chatId]
                        ?: EnhancedAIService(appContext, chatId).also { CHAT_INSTANCES[chatId] = it }
                }
        }

        fun releaseChatInstance(chatId: String) {
            val instance = CHAT_INSTANCES.remove(chatId) ?: return
            runCatching {
                instance.cancelConversation()
            }.onFailure { e ->
                AppLogger.e(TAG, "释放chat实例资源失败: chatId=$chatId", e)
            }
        }

        /**
         * 获取指定功能类型的 AIService 实例（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型
         * @return AIService 实例
         */
        suspend fun getAIServiceForFunction(
                context: Context,
                functionType: FunctionType
        ): AIService {
            return getInstance(context).multiServiceManager.getServiceForFunction(functionType)
        }

        suspend fun getModelConfigForFunction(
            context: Context,
            functionType: FunctionType
        ): ModelConfigData {
            return getInstance(context).multiServiceManager.getModelConfigForFunction(functionType)
        }

        /**
         * 刷新指定功能类型的 AIService 实例（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型
         */
        suspend fun refreshServiceForFunction(context: Context, functionType: FunctionType) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach { it.multiServiceManager.refreshServiceForFunction(functionType) }
        }

        /**
         * 刷新所有 AIService 实例（非实例化方式）
         * @param context 应用上下文
         */
        suspend fun refreshAllServices(context: Context) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach { it.multiServiceManager.refreshAllServices() }
        }

        /**
         * 获取指定功能类型的当前输入token计数（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型
         * @return 输入token计数
         */
        suspend fun getCurrentInputTokenCountForFunction(
                context: Context,
                functionType: FunctionType
        ): Int {
            return getInstance(context)
                    .multiServiceManager
                    .getServiceForFunction(functionType)
                    .inputTokenCount
        }

        /**
         * 获取指定功能类型的当前输出token计数（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型
         * @return 输出token计数
         */
        suspend fun getCurrentOutputTokenCountForFunction(
                context: Context,
                functionType: FunctionType
        ): Int {
            return getInstance(context)
                    .multiServiceManager
                    .getServiceForFunction(functionType)
                    .outputTokenCount
        }

        /**
         * 重置指定功能类型或所有功能类型的token计数器（非实例化方式）
         * @param context 应用上下文
         * @param functionType 功能类型，如果为null则重置所有功能类型
         */
        suspend fun resetTokenCountersForFunction(
                context: Context,
                functionType: FunctionType? = null
        ) {
            val allInstances = buildList {
                add(getInstance(context))
                addAll(CHAT_INSTANCES.values)
            }.distinct()
            allInstances.forEach {
                if (functionType == null) {
                    it.multiServiceManager.resetAllTokenCounters()
                } else {
                    it.multiServiceManager.resetTokenCountersForFunction(functionType)
                }
            }
        }

        fun resetTokenCounters(context: Context) {
            val appContext = context.applicationContext
            val allInstances = buildList {
                add(getInstance(appContext))
                addAll(CHAT_INSTANCES.values)
            }.distinct()

            allInstances.forEach { instance ->
                instance.initScope.launch {
                    runCatching {
                        instance.multiServiceManager.resetAllTokenCounters()
                    }.onFailure { e ->
                        AppLogger.e(TAG, "重置token计数器失败", e)
                    }
                }
            }
        }

        /**
         * 处理文件绑定操作（非实例化方式）
         * @param context 应用上下文
         * @param originalContent 原始文件内容
         * @param aiGeneratedCode AI生成的代码（包含"//existing code"标记）
         * @return 混合后的文件内容
         */
        suspend fun applyFileBinding(
                context: Context,
                originalContent: String,
                aiGeneratedCode: String,
                onProgress: ((Float, String) -> Unit)? = null
        ): Pair<String, String> {
            // 获取EnhancedAIService实例
            val instance = getInstance(context)

            // 委托给FileBindingService处理
            return instance.fileBindingService.processFileBinding(
                    originalContent,
                    aiGeneratedCode,
                    onProgress
            )
        }

        suspend fun applyFileBindingOperations(
            context: Context,
            originalContent: String,
            operations: List<FileBindingService.StructuredEditOperation>,
            onProgress: ((Float, String) -> Unit)? = null
        ): Pair<String, String> {
            val instance = getInstance(context)
            return instance.fileBindingService.processFileBindingOperations(
                originalContent = originalContent,
                operations = operations,
                onProgress = onProgress
            )
        }

        /**
         * 自动生成工具包描述（非实例化方式）
         * @param context 应用上下文
         * @param pluginName 工具包名称
         * @param toolDescriptions 工具描述列表
         * @return 生成的工具包描述
         */
        suspend fun generatePackageDescription(
            context: Context,
            pluginName: String,
            toolDescriptions: List<String>
        ): String {
            return getInstance(context).generatePackageDescription(pluginName, toolDescriptions)
        }
    }

    interface SendMessageCallbacks {
        fun onNonFatalError(error: String) {}

        fun onTokenLimitExceeded() {}

        fun onToolInvocation(toolName: String) {}
    }

    data class ToolExecutionBoundarySnapshot(
        val displayContent: CharSequence,
        val replayCharCount: Int,
        val revisionEventCount: Int = 0,
    )

    data class SendMessageOptions(
        var message: String = "",
        var maxTokens: Int = 0,
        var tokenUsageThreshold: Double = 0.0,
        var chatId: String? = null,
        var chatHistory: List<PromptTurn> = emptyList(),
        var workspacePath: String? = null,
        var workspaceEnv: String? = null,
        var functionType: FunctionType = FunctionType.CHAT,
        /**
         * The provider conversation this turn belongs to, when it is not the chat the turn runs in.
         * An internal turn whose conversation outlives its chat pins it here so a provider that caches
         * prompt prefixes keeps reusing them; null means the turn's own chat is the conversation.
         */
        var providerSessionId: String? = null,
        var promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        var enableThinking: Boolean = false,
        var enableMemoryAutoUpdate: Boolean = true,
        var onNonFatalError: suspend (error: String) -> Unit = {},
        var onTokenLimitExceeded: (suspend () -> Unit)? = null,
        var customSystemPromptTemplate: String? = null,
        var additionalSystemPrompt: String? = null,
        var isSubTask: Boolean = false,
        var toolsEnabled: Boolean = true,
        var isolatedToolPrompts: List<ToolPrompt>? = null,
        var terminalToolNames: Set<String> = emptySet(),
        var promptHooksEnabled: Boolean = true,
        var characterName: String? = null,
        var avatarUri: String? = null,
        var roleCardId: String? = null,
        var enableGroupOrchestrationHint: Boolean = false,
        var groupParticipantNamesText: String? = null,
        var proxySenderName: String? = null,
        var callbacks: SendMessageCallbacks? = null,
        var onToolInvocation: (suspend (String) -> Unit)? = null,
        var onToolExecutionBoundary: (suspend (ToolExecutionBoundarySnapshot) -> Unit)? = null,
        var turnInputInbox: TurnInputInbox? = null,
        var onTurnInput: (suspend (List<TurnInputInbox.Input>, ToolExecutionBoundarySnapshot) -> String)? = null,
        var notifyReplyOverride: Boolean? = null,
        var chatModelConfigIdOverride: String? = null,
        var chatModelIndexOverride: Int? = null,
        var memorySpaceIdOverride: String? = null,
        var toolTimingScopeId: String? = null,
        var stream: Boolean = true,
        var disableWarning: Boolean = false,
        var collaborationInput: Boolean = false,
    )

    // MultiServiceManager 管理不同功能的 AIService 实例
    private val multiServiceManager = MultiServiceManager(context)

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()
    @Volatile private var isServiceManagerInitialized = false

    // 添加ConversationService实例
    private val conversationService = ConversationService(context, CustomEmojiRepository.getInstance(context))

    // 添加FileBindingService实例
    private val fileBindingService = FileBindingService(context)

    // Tool handler for executing tools
    private val toolHandler = AIToolHandler.getInstance(context)

    private suspend fun ensureInitialized() {
        if (isServiceManagerInitialized) return
        initMutex.withLock {
            if (isServiceManagerInitialized) return
            withContext(Dispatchers.IO) {
                multiServiceManager.initialize()
            }
            isServiceManagerInitialized = true
        }
    }

    // State flows for UI updates
    private val _inputProcessingState =
            MutableStateFlow<InputProcessingState>(InputProcessingState.Idle)
    val inputProcessingState = _inputProcessingState.asStateFlow()

    /**
     * 设置当前的输入处理状态
     * @param newState 新的状态
     */
    fun setInputProcessingState(newState: com.ai.assistance.operit.data.model.InputProcessingState) {
        _inputProcessingState.value = newState
    }

    // Per-request token counts
    private val _perRequestTokenCounts = MutableStateFlow<Pair<Int, Int>?>(null)
    val perRequestTokenCounts: StateFlow<Pair<Int, Int>?> = _perRequestTokenCounts.asStateFlow()

    // Stable request window estimate for the next model hop.
    private val _requestWindowEstimate = MutableStateFlow<Int?>(null)
    val requestWindowEstimateFlow: StateFlow<Int?> = _requestWindowEstimate.asStateFlow()

    // Conversation management
    // private val streamBuffer = StringBuilder() // Moved to MessageExecutionContext
    // private val roundManager = ConversationRoundManager() // Moved to MessageExecutionContext
    // private val isConversationActive = AtomicBoolean(false) // Moved to MessageExecutionContext

    // Api Preferences for settings
    private val apiPreferences = ApiPreferences.getInstance(context)
    private val characterCardToolAccessResolver = CharacterCardToolAccessResolver.getInstance(context)

    // Execution context for a single sendMessage call to achieve concurrency
    private data class ModelExecutionSnapshot(
        val lease: MultiServiceManager.ServiceLease
    ) {
        val service: AIService
            get() = lease.service
        val config: ModelConfigData
            get() = lease.modelConfig
        val modelParameters: List<ModelParameter<*>>
            get() = lease.modelParameters
    }

    private data class MessageExecutionContext(
        val executionId: Int,
        val streamBuffer: StringBuilder = StringBuilder(),
        val roundManager: ConversationRoundManager = ConversationRoundManager(),
        val isConversationActive: AtomicBoolean = AtomicBoolean(true),
        val conversationHistory: MutableList<PromptTurn>,
        val collaborationInputs: MutableList<PromptTurn> = mutableListOf(),
        val eventChannel: MutableSharedStream<TextStreamEvent>,
        val onToolExecutionBoundary: (suspend (ToolExecutionBoundarySnapshot) -> Unit)? = null,
        val turnInputInbox: TurnInputInbox? = null,
        val onTurnInput: (suspend (List<TurnInputInbox.Input>, ToolExecutionBoundarySnapshot) -> String)? = null,
        val toolTimingScopeId: String? = null,
        val workspacePath: String? = null,
        val workspaceEnv: String? = null,
        /** The conversation identity this turn asks its provider under, resolved by the turn itself. */
        val providerSessionId: String? = null,
        val toolsEnabled: Boolean = true,
        val isolatedToolPrompts: List<ToolPrompt>? = null,
        val terminalToolNames: Set<String> = emptySet(),
        val promptHooksEnabled: Boolean = true,
        val toolSequence: AssistantToolSequence = AssistantToolSequence(toolTimingScopeId),
        val emittedReplayCharCount: AtomicInteger = AtomicInteger(0),
        val learningToolIterations: AtomicInteger = AtomicInteger(0),
        val subagentToolLoopGuard: ToolExecutionManager.SubagentToolLoopGuard =
            ToolExecutionManager.SubagentToolLoopGuard(),
        var modelExecutionSnapshot: ModelExecutionSnapshot? = null
    )

    private val activeExecutionContexts = ConcurrentHashMap<Int, MessageExecutionContext>()
    internal fun collaborationHistorySnapshot(): List<PromptTurn> {
        val active = activeExecutionContexts.values.maxByOrNull { it.executionId } ?: return emptyList()
        val history = active.conversationHistory.toList()
        // The caller is inside the latest tool batch: its assistant call has no results yet.
        // The fork selector subsequently keeps only user inputs and final answers.
        return if (history.lastOrNull()?.kind in setOf(PromptTurnKind.ASSISTANT, PromptTurnKind.TOOL_CALL)) {
            history.dropLast(1)
        } else history
    }
    private val nextExecutionContextId = AtomicInteger(0)

    private fun registerExecutionContext(context: MessageExecutionContext) {
        activeExecutionContexts[context.executionId] = context
        com.ai.assistance.operit.core.tools.phone.PhoneControlTools.registerTurn(context.toolSequence.scopeId)
    }

    private fun unregisterExecutionContext(context: MessageExecutionContext) {
        com.ai.assistance.operit.core.tools.phone.PhoneControlTools.finishTurn(context.toolSequence.scopeId)
        activeExecutionContexts.remove(context.executionId, context)
    }

    private fun invalidateExecutionContext(context: MessageExecutionContext, reason: String) {
        com.ai.assistance.operit.core.tools.phone.PhoneControlTools.finishTurn(context.toolSequence.scopeId)
        context.turnInputInbox?.seal()
        if (context.isConversationActive.compareAndSet(true, false)) {
            AppLogger.d(TAG, "执行上下文已失效: id=${context.executionId}, reason=$reason")
        }
    }

    private fun invalidateAllExecutionContexts(reason: String) {
        activeExecutionContexts.values.forEach { context ->
            invalidateExecutionContext(context, reason)
        }
    }

    private fun isExecutionContextActive(context: MessageExecutionContext): Boolean {
        return context.isConversationActive.get() &&
            activeExecutionContexts[context.executionId] === context
    }

    private suspend fun getModelExecutionSnapshot(
        context: MessageExecutionContext,
        functionType: FunctionType,
        chatModelConfigIdOverride: String?,
        chatModelIndexOverride: Int?
    ): ModelExecutionSnapshot {
        context.modelExecutionSnapshot?.let { return it }
        ensureInitialized()
        val overrideConfigId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
        val lease =
            if (functionType == FunctionType.CHAT && overrideConfigId != null) {
                multiServiceManager.acquireServiceForConfig(
                    configId = overrideConfigId,
                    modelIndex = (chatModelIndexOverride ?: 0).coerceAtLeast(0)
                )
            } else {
                multiServiceManager.acquireServiceForFunction(functionType)
            }
        val snapshot = ModelExecutionSnapshot(lease)
        context.modelExecutionSnapshot = snapshot
        return snapshot
    }

    private suspend fun releaseModelExecutionSnapshot(context: MessageExecutionContext) {
        val snapshot = context.modelExecutionSnapshot ?: return
        context.modelExecutionSnapshot = null
        snapshot.lease.close()
    }

    private suspend fun startAssistantResponseRound(
        context: MessageExecutionContext,
        collector: StreamCollector<String>,
    ) {
        val separator = context.roundManager.startNewRound()
        context.streamBuffer.clear()
        if (separator.isNotEmpty()) {
            collector.emit(separator)
            context.emittedReplayCharCount.addAndGet(separator.length)
        }
    }

    // Coroutine management
    private val toolProcessingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toolExecutionJobs = ConcurrentHashMap<String, Job>()
    // private val conversationHistory = mutableListOf<Pair<String, String>>() // Moved to MessageExecutionContext
    // private val conversationMutex = Mutex() // Moved to MessageExecutionContext

    private var accumulatedInputTokenCount = 0
    private var accumulatedOutputTokenCount = 0
    private var accumulatedCachedInputTokenCount = 0
    private var currentRequestInputTokenCount = 0
    private var currentRequestOutputTokenCount = 0
    private var currentRequestCachedInputTokenCount = 0

    private fun saturatedTokenSum(vararg values: Int): Int {
        val total = values.fold(0L) { acc, value -> acc + value.toLong().coerceAtLeast(0L) }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // Callbacks
    private var currentResponseCallback: ((content: String, thinking: String?) -> Unit)? = null
    private var currentCompleteCallback: (() -> Unit)? = null

    // Package manager for handling tool packages
    private val packageManager = PackageManager.getInstance(context, toolHandler)

    // 存储最后的回复内容，用于通知
    private var lastReplyContent: String? = null

    init {
        com.ai.assistance.operit.api.chat.library.MemoryLibrary.initialize(context)
        initScope.launch {
            runCatching {
                ensureInitialized()
            }.onFailure { e ->
                AppLogger.e(TAG, "MultiServiceManager初始化失败", e)
            }
        }
        initScope.launch {
            runCatching {
                toolHandler.registerDefaultTools()
            }.onFailure { e ->
                AppLogger.e(TAG, "注册默认工具失败", e)
            }
        }
    }

    /**
     * 获取指定功能类型的 AIService 实例
     * @param functionType 功能类型
     * @return AIService 实例
     */
    suspend fun getAIServiceForFunction(functionType: FunctionType): AIService {
        ensureInitialized()
        return getAIServiceForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = null,
            chatModelIndexOverride = null
        )
    }

    suspend fun getAIServiceForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String?,
        chatModelIndexOverride: Int?
    ): AIService {
        ensureInitialized()
        val overrideConfigId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
        return if (functionType == FunctionType.CHAT && overrideConfigId != null) {
            multiServiceManager.getServiceForConfig(
                configId = overrideConfigId,
                modelIndex = (chatModelIndexOverride ?: 0).coerceAtLeast(0)
            )
        } else {
            multiServiceManager.getServiceForFunction(functionType)
        }
    }

    /**
     * Pins the service, selected model configuration, and parameters to one request snapshot.
     *
     * Callers that invoke a provider directly must close the returned lease after the stream
     * finishes. This avoids mixing a refreshed service with parameters resolved from a different
     * configuration.
     */
    suspend fun acquireAIServiceLeaseForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
    ): MultiServiceManager.ServiceLease {
        ensureInitialized()
        val overrideConfigId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
        return if (functionType == FunctionType.CHAT && overrideConfigId != null) {
            multiServiceManager.acquireServiceForConfig(
                configId = overrideConfigId,
                modelIndex = (chatModelIndexOverride ?: 0).coerceAtLeast(0),
            )
        } else {
            multiServiceManager.acquireServiceForFunction(functionType)
        }
    }

    /**
     * 获取指定功能类型的provider和model信息
     * @param functionType 功能类型
     * @return Pair<provider, modelName>，例如 Pair("DEEPSEEK", "deepseek-chat")
     */
    suspend fun getProviderAndModelForFunction(functionType: FunctionType): Pair<String, String> {
        return getProviderAndModelForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = null,
            chatModelIndexOverride = null
        )
    }

    suspend fun getProviderAndModelForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String?,
        chatModelIndexOverride: Int?
    ): Pair<String, String> {
        val service = getAIServiceForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )
        val providerModel = service.providerModel
        // providerModel格式为"PROVIDER:modelName"，使用第一个冒号分割
        val colonIndex = providerModel.indexOf(":")
        return if (colonIndex > 0) {
            val provider = providerModel.substring(0, colonIndex)
            val modelName = providerModel.substring(colonIndex + 1)
            Pair(provider, modelName)
        } else {
            // 如果没有冒号，整个字符串作为provider，modelName为空
            Pair(providerModel, "")
        }
    }

    suspend fun getDisplayProviderAndModelForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String?,
        chatModelIndexOverride: Int?
    ): Pair<String, String> {
        val (provider, modelName) = getProviderAndModelForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )
        val config = getModelConfigForFunction(
            functionType = functionType,
            chatModelConfigIdOverride = chatModelConfigIdOverride,
            chatModelIndexOverride = chatModelIndexOverride
        )
        // The service reports its wire adapter (e.g. DEEPSEEK), not necessarily the account
        // supplying the model. Keep the account identity in the persisted message label.
        val account = multiServiceManager.getModelConfigForConfig(config.id)
        val displayProvider =
            ApiProviderType.fromProviderTypeId(account.apiProviderTypeId)?.name ?: provider
        return Pair("$displayProvider/${account.name}", modelName)
    }

    suspend fun getModelConfigForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null
    ): ModelConfigData {
        ensureInitialized()
        val overrideConfigId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
        return if (functionType == FunctionType.CHAT && overrideConfigId != null) {
            multiServiceManager
                .getModelConfigForConfig(overrideConfigId)
                .forSelectedModel((chatModelIndexOverride ?: 0).coerceAtLeast(0))
        } else {
            multiServiceManager.getModelConfigForFunction(functionType)
        }
    }

    /**
     * 刷新指定功能类型的 AIService 实例 当配置发生更改时调用
     * @param functionType 功能类型
     */
    suspend fun refreshServiceForFunction(functionType: FunctionType) {
        ensureInitialized()
        multiServiceManager.refreshServiceForFunction(functionType)
    }

    /** 刷新所有 AIService 实例 当全局配置发生更改时调用 */
    suspend fun refreshAllServices() {
        ensureInitialized()
        multiServiceManager.refreshAllServices()
    }

    /**
     * 供独立功能模块复用同一批功能服务实例（例如“自动审核”的异步风险分类器）。
     *
     * 复用这里的实例而不是各自新建，配置变更时的刷新才会同时作用到这些功能。
     */
    internal fun getFunctionalServiceManager(): MultiServiceManager = multiServiceManager

    suspend fun callFunctionModel(
        functionType: FunctionType,
        turns: List<PromptTurn>,
        enableThinking: Boolean = false,
        recordTokenUsage: Boolean = true,
    ): String {
        require(recordTokenUsage) { "Operit Ry records all model calls; recordTokenUsage=false is unsupported" }
        val lease = acquireAIServiceLeaseForFunction(functionType)
        try {
            val output = StringBuilder()
            lease.service.sendMessage(
                context = context,
                chatHistory = turns,
                modelParameters = lease.modelParameters,
                enableThinking = enableThinking,
                stream = false,
                availableTools = emptyList(),
                preserveThinkInHistory = true,
                statsCategory = com.ai.assistance.operit.data.stats.TokenStatCategory.OTHER,
            ).collect { output.append(it) }
            return output.toString()
        } finally {
            lease.close()
        }
    }

    private suspend fun getModelParametersForFunction(
        functionType: FunctionType,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null
    ): List<com.ai.assistance.operit.data.model.ModelParameter<*>> {
        ensureInitialized()
        val overrideConfigId = chatModelConfigIdOverride?.takeIf { it.isNotBlank() }
        return if (functionType == FunctionType.CHAT && overrideConfigId != null) {
            multiServiceManager.getModelParametersForConfig(overrideConfigId)
        } else {
            multiServiceManager.getModelParametersForFunction(functionType)
        }
    }

    private fun publishRequestWindowEstimate(windowSize: Int) {
        _requestWindowEstimate.value = windowSize
    }

    private val requestInputUsages =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<AIService, RequestInputUsage>())

    private fun beginInputUsage(
        service: AIService, history: List<PromptTurn>, tools: List<ToolPrompt>?
    ): RequestInputUsage = RequestInputUsage(history.toList(), tools?.toList()).also {
        requestInputUsages[service] = it
    }

    private fun reportInputUsage(
        tracker: RequestInputUsage,
        usage: com.ai.assistance.operit.data.stats.ProviderUsageSnapshot,
        attempt: Int,
    ) {
        tracker.report(usage, attempt)
        tracker.actualInput()?.let {
            // Both the header indicator and compaction consume this window flow.
            publishRequestWindowEstimate(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private suspend fun estimatePreparedRequestWindow(
        serviceForFunction: AIService,
        preparedHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?,
        publishEstimate: Boolean
    ): Int {
        val baseline = requestInputUsages[serviceForFunction]?.baseline(preparedHistory, availableTools)
        val windowSize = if (baseline != null) {
            val addedHistory = preparedHistory.drop(baseline.second)
            val addedTokens = if (addedHistory.isEmpty()) 0 else
                serviceForFunction.calculateInputTokens(addedHistory, null)
            (baseline.first + addedTokens.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } else {
            serviceForFunction.calculateInputTokens(
                chatHistory = preparedHistory,
                availableTools = availableTools
            )
        }
        AppLogger.d(TAG, "Context window: source=${if (baseline != null) "server_usage+new_content" else "estimate"}, tokens=$windowSize")
        if (publishEstimate) {
            publishRequestWindowEstimate(windowSize)
        }
        return windowSize
    }

    /**
     * Compact inside the same v2 session: no generic auto-continuation may take ownership of
     * the child. Split the persisted assistant at this boundary before saving the checkpoint,
     * so later turns can append only transcript rows after its cutoff.
     */
    private suspend fun compactCollaborationHistory(
        execution: MessageExecutionContext,
        chatId: String,
        history: List<PromptTurn>,
        service: AIService,
        tools: List<ToolPrompt>?,
        maxTokens: Int,
    ): List<PromptTurn> {
        val summary = generateSummaryFromPromptTurns(
            com.ai.assistance.operit.core.agent.collaboration.CollaborationCheckpoint.summaryInput(history),
            previousSummary = null,
            summaryConfig = ConversationSummaryConfig(globalRules = "Preserve the assigned task, constraints, all agent paths and pending work, " +
                "key findings and unresolved messages from the quoted JSON. This is a checkpoint " +
                "for the same continuing agent. Your own summarization instructions are not part " +
                "of its task. Never mark unfinished work complete just because you summarized it."),
        )
        check(summary.isNotBlank()) { "Agent context compaction returned an empty checkpoint" }
        val durable = com.ai.assistance.operit.core.agent.collaboration.CollaborationCheckpoint
            .durableHistory(summary, execution.collaborationInputs.toList())
        val compacted = com.ai.assistance.operit.core.agent.collaboration.CollaborationCheckpoint
            .resumeHistory(history.filter { it.kind == PromptTurnKind.SYSTEM }, durable)
        val compactedTokens = estimatePreparedRequestWindow(service, compacted, tools, true)
        check(compactedTokens < maxTokens) {
            "Agent context remains larger than the configured capacity after compaction"
        }

        withContext(NonCancellable) {
            val scopeId = requireNotNull(execution.onTurnInput).invoke(
                emptyList(), ToolExecutionBoundarySnapshot(
                    execution.roundManager.getDisplaySnapshot(), execution.emittedReplayCharCount.get(),
                    execution.eventChannel.replayCache.size,
                ),
            )
            com.ai.assistance.operit.core.tools.phone.PhoneControlTools.finishTurn(execution.toolSequence.scopeId)
            execution.toolSequence.startMessage(scopeId)
            com.ai.assistance.operit.core.tools.phone.PhoneControlTools.registerTurn(scopeId)
            // The split allocates this next segment ID under transcriptMutex. A concurrent
            // stream persistence may already insert it, so a later database MAX is unsafe.
            val cutoff = scopeId.toLong() - 1
            com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator
                .getInstance(context).checkpoint(chatId, durable, cutoff)
        }
        return compacted
    }

    private fun applyPromptFinalizeHooks(
        initialContext: PromptHookContext,
        dispatchHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchPromptFinalizeHooks
    ): PromptHookContext {
        return dispatchHooks(initialContext)
    }

    private fun bypassPromptHooks(context: PromptHookContext): PromptHookContext = context

    private suspend fun buildPromptFinalizeMetadata(
        chatId: String?,
        roleCardId: String?,
        workspacePath: String?,
        workspaceEnv: String?,
        enableThinking: Boolean,
        stream: Boolean,
        isSubTask: Boolean
    ): Map<String, Any?> {
        return mapOf(
            "workspacePath" to workspacePath,
            "workspaceEnv" to workspaceEnv,
            "enableThinking" to enableThinking,
            "stream" to stream,
            "isSubTask" to isSubTask
        ) +
            buildActivePromptHookMetadata(
                context,
                chatId,
                roleCardId,
                includeActivePrompt = !isSubTask,
            )
    }

    private fun applyFinalizedCurrentUserTurn(
        preparedHistory: List<PromptTurn>,
        originalCurrentMessage: String,
        finalizedCurrentMessage: String
    ): List<PromptTurn> {
        if (finalizedCurrentMessage.isBlank()) {
            return preparedHistory
        }

        val lastTurn = preparedHistory.lastOrNull()
        return when {
            lastTurn?.kind == PromptTurnKind.USER &&
                lastTurn.content == finalizedCurrentMessage -> {
                preparedHistory
            }
            lastTurn?.kind == PromptTurnKind.USER &&
                lastTurn.content == originalCurrentMessage -> {
                preparedHistory.dropLast(1) + lastTurn.copy(content = finalizedCurrentMessage)
            }
            else -> {
                preparedHistory.appendUserTurnIfMissing(finalizedCurrentMessage)
            }
        }
    }

    suspend fun estimateRequestWindowFromMemory(
        message: String,
        chatHistory: List<PromptTurn>,
        chatId: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        functionType: FunctionType = FunctionType.CHAT,
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        enableThinking: Boolean = false,
        customSystemPromptTemplate: String? = null,
        roleCardId: String? = null,
        enableGroupOrchestrationHint: Boolean = false,
        groupParticipantNamesText: String? = null,
        proxySenderName: String? = null,
        isSubTask: Boolean = false,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        memorySpaceIdOverride: String? = null,
        stream: Boolean = true,
        publishEstimate: Boolean = true
    ): Int {
        val modelConfig =
            getModelConfigForFunction(
                functionType = functionType,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride
            )
        val preparedHistory =
            prepareConversationHistory(
                chatHistory = chatHistory,
                processedInput = message,
                chatId = chatId,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                promptFunctionType = promptFunctionType,
                customSystemPromptTemplate = customSystemPromptTemplate,
                roleCardId = roleCardId,
                enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                groupParticipantNamesText = groupParticipantNamesText,
                proxySenderName = proxySenderName,
                isSubTask = isSubTask,
                functionType = functionType,
                modelConfig = modelConfig,
                memorySpaceIdOverride = memorySpaceIdOverride,
                dispatchHistoryHooks = PromptHookRegistry::dispatchPromptEstimateHistoryHooks,
                dispatchSystemPromptComposeHooks = ::bypassPromptHooks,
                dispatchToolPromptComposeHooks = ::bypassPromptHooks
            )

        val modelParameters =
            getModelParametersForFunction(
                functionType = functionType,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride
            )
        val serviceForFunction =
            getAIServiceForFunction(
                functionType = functionType,
                chatModelConfigIdOverride = chatModelConfigIdOverride,
                chatModelIndexOverride = chatModelIndexOverride
            )
        val availableTools =
            getAvailableToolsForFunction(
                functionType = functionType,
                chatId = chatId,
                promptFunctionType = promptFunctionType,
                roleCardId = roleCardId,
                modelConfig = modelConfig,
                isSubTask = isSubTask
            )

        var finalProcessedInput = message
        var finalPreparedHistory = preparedHistory
        val beforeFinalizeContext =
            applyPromptFinalizeHooks(
                PromptHookContext(
                    stage = "before_finalize_prompt",
                    chatId = chatId,
                    functionType = functionType.name,
                    promptFunctionType = promptFunctionType.name,
                    rawInput = message,
                    processedInput = finalProcessedInput,
                    preparedHistory = finalPreparedHistory,
                    modelParameters = serializePromptHookModelParameters(modelParameters),
                    availableTools = serializePromptHookToolPrompts(availableTools),
                    metadata = buildPromptFinalizeMetadata(
                        chatId = chatId,
                        roleCardId = roleCardId,
                        workspacePath = workspacePath,
                        workspaceEnv = workspaceEnv,
                        enableThinking = enableThinking,
                        stream = stream,
                        isSubTask = isSubTask
                    )
                ),
                dispatchHooks = PromptHookRegistry::dispatchPromptEstimateFinalizeHooks
            )
        finalProcessedInput = beforeFinalizeContext.processedInput ?: finalProcessedInput
        finalPreparedHistory = beforeFinalizeContext.preparedHistory
        val beforeSendContext =
            applyPromptFinalizeHooks(
                beforeFinalizeContext.copy(
                    stage = "before_send_to_model",
                    processedInput = finalProcessedInput,
                    preparedHistory = finalPreparedHistory
                ),
                dispatchHooks = PromptHookRegistry::dispatchPromptEstimateFinalizeHooks
            )
        finalProcessedInput = beforeSendContext.processedInput ?: finalProcessedInput
        finalPreparedHistory = beforeSendContext.preparedHistory
        if (!ChatUtils.isGeminiProviderModel(serviceForFunction.providerModel)) {
            finalProcessedInput = ChatUtils.stripGeminiThoughtSignatureMeta(finalProcessedInput)
            finalPreparedHistory = ChatUtils.stripGeminiThoughtSignatureMetaTurns(finalPreparedHistory)
        }
        if (!ChatUtils.isOpenAIResponsesProviderModel(serviceForFunction.providerModel)) {
            finalProcessedInput = ChatUtils.stripOpenAiResponsesReasoningMeta(finalProcessedInput)
            finalPreparedHistory = ChatUtils.stripOpenAiResponsesReasoningMetaTurns(finalPreparedHistory)
        }

        val requestHistory =
            applyFinalizedCurrentUserTurn(
                preparedHistory = finalPreparedHistory,
                originalCurrentMessage = message,
                finalizedCurrentMessage = finalProcessedInput
            ).mergeAdjacentTurns { previous, current ->
                com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory.canMergeUserTurns(previous, current)
            }

        return estimatePreparedRequestWindow(
            serviceForFunction = serviceForFunction,
            preparedHistory = requestHistory,
            availableTools = availableTools,
            publishEstimate = publishEstimate
        )
    }

    /** Send a message to the AI service */
    suspend fun sendMessage(
        options: SendMessageOptions
    ): Stream<String> {
        val message = options.message
        val chatId = options.chatId
        val chatHistory = options.chatHistory
        val workspacePath = options.workspacePath
        val workspaceEnv = options.workspaceEnv
        val functionType = options.functionType
        val promptFunctionType = options.promptFunctionType
        val enableThinking = options.enableThinking
        val enableMemoryAutoUpdate = options.enableMemoryAutoUpdate
        val maxTokens = options.maxTokens
        val tokenUsageThreshold = options.tokenUsageThreshold
        val customSystemPromptTemplate = options.customSystemPromptTemplate
        val additionalSystemPrompt = options.additionalSystemPrompt
        val isSubTask = options.isSubTask
        if (!isSubTask) com.ai.assistance.operit.api.chat.library.MemoryLearningCoordinator.foregroundStarted(chatId)
        val toolsEnabled = options.toolsEnabled
        val isolatedToolPrompts = options.isolatedToolPrompts
        val terminalToolNames = options.terminalToolNames
        val promptHooksEnabled = options.promptHooksEnabled
        val characterName = options.characterName
        val avatarUri = options.avatarUri
        val roleCardId = options.roleCardId
        val enableGroupOrchestrationHint = options.enableGroupOrchestrationHint
        val groupParticipantNamesText = options.groupParticipantNamesText
        val proxySenderName = options.proxySenderName
        val callbacks = options.callbacks
        val notifyReplyOverride = options.notifyReplyOverride
        val chatModelConfigIdOverride = options.chatModelConfigIdOverride
        val chatModelIndexOverride = options.chatModelIndexOverride
        // Resolve once for both CHAT and VOICE so prompt, tools and post-turn learning use
        // the same space even if the user switches the global selection during generation.
        val memorySpaceIdOverride = options.memorySpaceIdOverride?.takeIf { it.isNotBlank() }
            ?: if (isSubTask) null else {
                val card = roleCardId?.takeIf { it.isNotBlank() }?.let {
                    com.ai.assistance.operit.data.preferences.CharacterCardManager.getInstance(context)
                        .getCharacterCardFlow(it).first()
                }
                card?.takeIf {
                    com.ai.assistance.operit.data.model.CharacterCardMemoryProfileBindingMode.normalize(it.memoryProfileBindingMode) ==
                        com.ai.assistance.operit.data.model.CharacterCardMemoryProfileBindingMode.FIXED_PROFILE
                }?.memoryProfileId?.takeIf { it.isNotBlank() } ?: preferencesManager.activeMemorySpaceIdFlow.first()
            }
        val toolTimingScopeId = options.toolTimingScopeId
        val stream = options.stream
        val disableWarning = options.disableWarning
        val onNonFatalError: suspend (error: String) -> Unit = { error ->
            options.onNonFatalError(error)
            callbacks?.onNonFatalError(error)
        }
        val onTokenLimitExceeded: (suspend () -> Unit)? =
            if (options.onTokenLimitExceeded != null || callbacks != null) {
                suspend {
                    options.onTokenLimitExceeded?.invoke()
                    callbacks?.onTokenLimitExceeded()
                }
            } else {
                null
            }
        val onToolInvocation: (suspend (String) -> Unit)? =
            if (options.onToolInvocation != null || callbacks != null) {
                { toolName ->
                    options.onToolInvocation?.invoke(toolName)
                    callbacks?.onToolInvocation(toolName)
                }
            } else {
                null
            }

        AppLogger.d(TAG, "sendMessage调用开始: 功能类型=$functionType, 提示词类型=$promptFunctionType")
        accumulatedInputTokenCount = 0
        accumulatedOutputTokenCount = 0
        accumulatedCachedInputTokenCount = 0
        currentRequestInputTokenCount = 0
        currentRequestOutputTokenCount = 0
        currentRequestCachedInputTokenCount = 0

        // A turn that belongs to a conversation of its own asks under that identity everywhere it
        // reaches a provider, including the tool continuations it starts: a provider only reuses the
        // prompt prefix a sibling turn warmed while the identity stays the same.
        val providerConversationId =
            options.providerSessionId?.takeIf { it.isNotBlank() }
                ?: chatId?.takeIf { it.isNotBlank() }
                ?: providerSessionId

        val eventChannel = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val wrappedStream = stream {
            val responseCollector = this
            val execContext =
                MessageExecutionContext(
                    executionId = nextExecutionContextId.incrementAndGet(),
                    conversationHistory = chatHistory.toMutableList(),
                    eventChannel = eventChannel,
                    onToolExecutionBoundary = options.onToolExecutionBoundary,
                    turnInputInbox = options.turnInputInbox,
                    onTurnInput = options.onTurnInput,
                    toolTimingScopeId = toolTimingScopeId,
                    workspacePath = workspacePath,
                    workspaceEnv = workspaceEnv,
                    providerSessionId = providerConversationId,
                    toolsEnabled = toolsEnabled,
                    isolatedToolPrompts = isolatedToolPrompts,
                    terminalToolNames = terminalToolNames,
                    promptHooksEnabled = promptHooksEnabled,
                )
            registerExecutionContext(execContext)
            var hadFatalError = false
            try {
                // 确保所有操作都在IO线程上执行
                withContext(Dispatchers.IO) {
                    // 仅当会话首次启动时开启服务，并更新前台通知为“运行中”
                    if (!isSubTask) {
                        startAiService(characterName, avatarUri)
                    }

                    // Update state to show we're processing
                    if (!isSubTask) {
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value = InputProcessingState.Processing(context.getString(R.string.enhanced_processing_message))
                        }
                    }

                    val startTime = messageTimingNow()
                    val modelSnapshot =
                        getModelExecutionSnapshot(
                            execContext,
                            functionType,
                            chatModelConfigIdOverride,
                            chatModelIndexOverride
                        )

                    // Prepare conversation history with system prompt
                    val preparedHistory =
                            prepareConversationHistory(
                                    execContext.conversationHistory, // 始终使用内部历史记录
                                    message,
                                    chatId,
                                    workspacePath,
                                    workspaceEnv,
                                    promptFunctionType,
                                    customSystemPromptTemplate,
                                    roleCardId,
                                    enableGroupOrchestrationHint,
                                    groupParticipantNamesText,
                                    proxySenderName,
                                    isSubTask,
                                    functionType,
                                    modelSnapshot.config,
                                    memorySpaceIdOverride,
                                    additionalSystemPrompt,
                                    dispatchHistoryHooks =
                                        if (promptHooksEnabled) {
                                            PromptHookRegistry::dispatchPromptHistoryHooks
                                        } else {
                                            ::bypassPromptHooks
                                        },
                                    dispatchSystemPromptComposeHooks =
                                        if (promptHooksEnabled) {
                                            PromptHookRegistry::dispatchSystemPromptComposeHooks
                                        } else {
                                            ::bypassPromptHooks
                                        },
                                    dispatchToolPromptComposeHooks =
                                        if (promptHooksEnabled) {
                                            PromptHookRegistry::dispatchToolPromptComposeHooks
                                        } else {
                                            ::bypassPromptHooks
                                        },
                            )
                    val tAfterPrepareHistory = messageTimingNow()
                    AppLogger.d(TAG, "sendMessage本地耗时: prepareConversationHistory=${tAfterPrepareHistory - startTime}ms")
                    
                    // 关键修复：用准备好的历史记录（包含了系统提示）去同步更新内部的 conversationHistory 状态
                    execContext.conversationHistory.clear()
                    execContext.conversationHistory.addAll(preparedHistory)

                    // Update UI state to connecting
                    if (!isSubTask) {
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value = InputProcessingState.Connecting(context.getString(R.string.enhanced_connecting_service))
                        }
                    }

                    // Get all model parameters from preferences (with enabled state)
                    val modelParameters = com.ai.assistance.operit.core.agent.collaboration.CollaborationModelParameters.apply(
                        modelSnapshot.modelParameters,
                        com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(context).reasoningEffort(chatId),
                        modelSnapshot.config
                            .protocolSettingsForModel(modelSnapshot.config.modelName)
                            .reasoningEfforts,
                    )
                    val tAfterModelParams = messageTimingNow()
                    AppLogger.d(TAG, "sendMessage本地耗时: getModelParametersForFunction=${tAfterModelParams - tAfterPrepareHistory}ms")

                    // 获取对应功能类型的AIService实例
                    val serviceForFunction = modelSnapshot.service
                    val tAfterGetService = messageTimingNow()
                    AppLogger.d(TAG, "sendMessage本地耗时: getAIServiceForFunction=${tAfterGetService - tAfterModelParams}ms")

                    // 清空之前的单次请求token计数
                    _perRequestTokenCounts.value = null
                    currentRequestInputTokenCount = 0
                    currentRequestOutputTokenCount = 0
                    currentRequestCachedInputTokenCount = 0

                    // 获取工具列表（如果启用Tool Call）
                    val availableTools = getAvailableToolsForFunction(
                        functionType = functionType,
                        chatId = chatId,
                        promptFunctionType = promptFunctionType,
                        roleCardId = roleCardId,
                        modelConfig = modelSnapshot.config,
                        isSubTask = isSubTask,
                        toolsEnabled = toolsEnabled,
                        isolatedToolPrompts = isolatedToolPrompts,
                    )
                    val tAfterGetTools = messageTimingNow()
                    AppLogger.d(TAG, "sendMessage本地耗时: getAvailableToolsForFunction=${tAfterGetTools - tAfterGetService}ms")

                    var finalProcessedInput = message
                    var finalPreparedHistory = preparedHistory
                    val beforeFinalizeContext =
                        applyPromptFinalizeHooks(
                            PromptHookContext(
                                stage = "before_finalize_prompt",
                                chatId = chatId,
                                functionType = functionType.name,
                                promptFunctionType = promptFunctionType.name,
                                rawInput = message,
                                processedInput = finalProcessedInput,
                                preparedHistory = finalPreparedHistory,
                                modelParameters = serializePromptHookModelParameters(modelParameters),
                                availableTools = serializePromptHookToolPrompts(availableTools),
                                metadata = buildPromptFinalizeMetadata(
                                    chatId = chatId,
                                    roleCardId = roleCardId,
                                    workspacePath = workspacePath,
                                    workspaceEnv = workspaceEnv,
                                    enableThinking = enableThinking,
                                    stream = stream,
                                    isSubTask = isSubTask
                                )
                            ),
                        dispatchHooks =
                            if (promptHooksEnabled) {
                                PromptHookRegistry::dispatchPromptFinalizeHooks
                            } else {
                                ::bypassPromptHooks
                            },
                    )
                    finalProcessedInput = beforeFinalizeContext.processedInput ?: finalProcessedInput
                    finalPreparedHistory = beforeFinalizeContext.preparedHistory
                    val beforeSendContext =
                        applyPromptFinalizeHooks(
                            beforeFinalizeContext.copy(
                                stage = "before_send_to_model",
                                processedInput = finalProcessedInput,
                                preparedHistory = finalPreparedHistory
                            ),
                        dispatchHooks =
                            if (promptHooksEnabled) {
                                PromptHookRegistry::dispatchPromptFinalizeHooks
                            } else {
                                ::bypassPromptHooks
                            },
                    )
                    finalProcessedInput = beforeSendContext.processedInput ?: finalProcessedInput
                    finalPreparedHistory = beforeSendContext.preparedHistory
                    if (!ChatUtils.isGeminiProviderModel(serviceForFunction.providerModel)) {
                        finalProcessedInput = ChatUtils.stripGeminiThoughtSignatureMeta(finalProcessedInput)
                        finalPreparedHistory = ChatUtils.stripGeminiThoughtSignatureMetaTurns(finalPreparedHistory)
                    }
                    if (!ChatUtils.isOpenAIResponsesProviderModel(serviceForFunction.providerModel)) {
                        finalProcessedInput = ChatUtils.stripOpenAiResponsesReasoningMeta(finalProcessedInput)
                        finalPreparedHistory = ChatUtils.stripOpenAiResponsesReasoningMetaTurns(finalPreparedHistory)
                    }
                    var requestHistory =
                        applyFinalizedCurrentUserTurn(
                            preparedHistory = finalPreparedHistory,
                            originalCurrentMessage = message,
                            finalizedCurrentMessage = finalProcessedInput
                        ).mergeAdjacentTurns { previous, current ->
                            com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory.canMergeUserTurns(previous, current)
                        }
                    if (options.collaborationInput && requestHistory.lastOrNull()?.kind == PromptTurnKind.USER) {
                        val last = requestHistory.last()
                        requestHistory = requestHistory.dropLast(1) + last.copy(metadata = last.metadata + mapOf(
                            com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory.EVENT_METADATA to true,
                            com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory.TASK_METADATA to true,
                        ))
                    }
                    if (com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator
                            .getInstance(context).isAgent(chatId)) {
                        // A user may also resume an agent directly from its conversation UI.
                        // Preserve that real user input without relabeling it as an agent event.
                        execContext.collaborationInputs.add(requireNotNull(requestHistory.lastOrNull {
                            it.kind == PromptTurnKind.USER
                        }) { "Agent request has no current input" })
                    }
                    execContext.conversationHistory.clear()
                    execContext.conversationHistory.addAll(requestHistory)
                    val initialWindow = estimatePreparedRequestWindow(
                        serviceForFunction = serviceForFunction,
                        preparedHistory = requestHistory,
                        availableTools = availableTools,
                        publishEstimate = true
                    )
                    if (maxTokens > 0 && initialWindow.toDouble() / maxTokens >= tokenUsageThreshold &&
                        com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(context).isAgent(chatId)
                    ) {
                        requestHistory = compactCollaborationHistory(
                            execContext, requireNotNull(chatId), requestHistory, serviceForFunction, availableTools, maxTokens,
                        )
                        execContext.conversationHistory.clear()
                        execContext.conversationHistory.addAll(requestHistory)
                    }
                    
                    // 使用新的Stream API
                    AppLogger.d(TAG, "sendMessage请求前准备耗时: ${tAfterGetTools - startTime}ms, 流式输出: $stream")
                    val requestStartTime = messageTimingNow()
                    notifyModelRequestStarted(chatId, isSubTask, modelSnapshot)
                    val inputUsage = beginInputUsage(serviceForFunction, requestHistory, availableTools)
                    val responseStream =
                            serviceForFunction.sendMessage(
                                    context = this@EnhancedAIService.context,
                                    chatHistory = requestHistory,
                                    modelParameters = modelParameters,
                                    enableThinking = enableThinking,
                                    stream = stream,
                                    availableTools = availableTools,
                                    onUsageReported = { usage, attempt -> reportInputUsage(inputUsage, usage, attempt) },
                                    onTokensUpdated = { input, cachedInput, output ->
                                        currentRequestInputTokenCount = input.coerceAtLeast(0)
                                        currentRequestOutputTokenCount = output.coerceAtLeast(0)
                                        currentRequestCachedInputTokenCount = cachedInput.coerceAtLeast(0)
                                        _perRequestTokenCounts.value = Pair(input, output)
                                    },
                                    onNonFatalError = onNonFatalError,
                                    statsCategory =
                                        tokenStatsCategoryFor(
                                            functionType = options.functionType,
                                            isSubTask = isSubTask,
                                        )
                            )
                    val revisableStream = responseStream as? TextStreamEventCarrier

                    // 收到第一个响应，更新状态
                    var isFirstChunk = true

                    // 创建一个新的轮次来管理内容
                    startAssistantResponseRound(execContext, responseCollector)
                    val revisionTracker = TextStreamRevisionTracker()
                    val replayRoundStart = execContext.emittedReplayCharCount.get()
                    var processedRevisionEventCount = 0

                    suspend fun drainRevisionEvents() {
                        val events = revisableStream?.eventChannel?.replayCache.orEmpty()
                        while (processedRevisionEventCount < events.size) {
                            val event = events[processedRevisionEventCount++]
                            execContext.eventChannel.emit(event)
                            when (event.eventType) {
                                TextStreamEventType.SAVEPOINT -> revisionTracker.savepoint(event.id)
                                TextStreamEventType.ROLLBACK -> {
                                    val snapshot = revisionTracker.rollback(event.id)?.toString()
                                        ?: continue
                                    execContext.streamBuffer.clear()
                                    execContext.streamBuffer.append(snapshot)
                                    execContext.roundManager.updateContent(snapshot)
                                    execContext.emittedReplayCharCount.set(
                                        replayRoundStart + snapshot.length
                                    )
                                }
                            }
                        }
                    }

                    // 从原始stream收集内容并处理
                    var chunkCount = 0
                    var totalChars = 0
                    var lastLogTime = messageTimingNow()

                    try {
                    responseStream.collect { content ->
                                // Providers publish revision events before the text they govern.
                                // Drain that replay log in this collector so rollback and content
                                // cannot overtake one another in separate coroutines.
                                drainRevisionEvents()
                                // 第一次收到响应，更新状态
                                if (isFirstChunk) {
                                    if (!isSubTask) {
                                    withContext(Dispatchers.Main) {
                                        _inputProcessingState.value =
                                                InputProcessingState.Receiving(context.getString(R.string.enhanced_receiving_response))
                                        }
                                    }
                                    isFirstChunk = false
                                    logMessageTiming(
                                        stage = "enhanced.sendMessage.firstResponseChunk",
                                        startTimeMs = requestStartTime,
                                        details = "functionType=$functionType, stream=$stream"
                                    )
                                }

                                // 累计统计
                                chunkCount++
                                totalChars += content.length

                                // 周期性日志
                                val currentTime = messageTimingNow()
                                if (currentTime - lastLogTime > 5000) { // 每5秒记录一次
                                    AppLogger.d(TAG, "已接收 $chunkCount 个内容块，总计 $totalChars 个字符")
                                    lastLogTime = currentTime
                                }

                                revisionTracker.append(content)

                                // Keep both mutable accumulators synchronized without rebuilding
                                // the complete response for every streamed chunk.
                                execContext.streamBuffer.append(content)
                                execContext.roundManager.appendChunk(content)

                                // 发射当前内容片段
                                emit(content)
                                execContext.emittedReplayCharCount.addAndGet(content.length)
                    }
                    } finally {
                        drainRevisionEvents()
                    }

                    // Update accumulated token counts and persist them
                    val inputTokens = serviceForFunction.inputTokenCount
                    val cachedInputTokens = serviceForFunction.cachedInputTokenCount
                    val outputTokens = serviceForFunction.outputTokenCount
                    accumulatedInputTokenCount = saturatedTokenSum(accumulatedInputTokenCount, inputTokens)
                    accumulatedOutputTokenCount = saturatedTokenSum(accumulatedOutputTokenCount, outputTokens)
                    accumulatedCachedInputTokenCount =
                        saturatedTokenSum(accumulatedCachedInputTokenCount, cachedInputTokens)
                    currentRequestInputTokenCount = 0
                    currentRequestOutputTokenCount = 0
                    currentRequestCachedInputTokenCount = 0

                    AppLogger.d(
                            TAG,
                            "Token count updated for $functionType. Input: $inputTokens, Output: $outputTokens, CachedInput: $cachedInputTokens. Turn Accumulated: $accumulatedInputTokenCount, $accumulatedOutputTokenCount, $accumulatedCachedInputTokenCount"
                    )
                    logMessageTiming(
                        stage = "enhanced.sendMessage.streamComplete",
                        startTimeMs = requestStartTime,
                        details = "functionType=$functionType, totalChars=$totalChars, stream=$stream"
                    )
                }
            } catch (e: CancellationException) {
                invalidateExecutionContext(execContext, "sendMessage.collect.cancelled")
                AppLogger.d(TAG, "sendMessage流被取消")
                throw e
            } catch (e: Exception) {
                // 用户取消导致的 Socket closed 是预期行为，不应作为错误处理
                if (e.message?.contains("Socket closed", ignoreCase = true) == true) {
                    if (isExecutionContextActive(execContext)) {
                        AppLogger.d(TAG, "Stream was cancelled by the user (Socket closed).")
                    } else {
                        AppLogger.d(TAG, "Stream closed after execution context was invalidated.")
                    }
                } else {
                    hadFatalError = true
                    // Handle any exceptions
                    AppLogger.e(TAG, "发送消息时发生错误: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value =
                            InputProcessingState.Error(
                                // A turn the automatic review stopped fails with the instruction
                                // written for the model. This state reaches the error dialog, the
                                // floating window and the web state endpoint, so it reports the
                                // outcome here rather than leaving the instruction to be replaced
                                // later by each reader.
                                message =
                                    context.getString(
                                        R.string.enhanced_error_with_message,
                                        e.message?.let { permissionDenialDisplayText(context, it) }
                                            .orEmpty(),
                                    )
                            )
                    }
                }

                // 发生无法处理的错误时，也应停止服务，但用户取消除外
                if (e.message?.contains("Socket closed", ignoreCase = true) != true) {
                    if (!isSubTask) stopAiService()
                }
            } finally {
                try {
                    // 确保流处理完成后调用；如果本轮已被取消，则不能再继续跑完成逻辑。
                    if (!hadFatalError && isExecutionContextActive(execContext)) {
                        val collector = this
                        withContext(Dispatchers.IO) {
                            processStreamCompletion(
                                execContext,
                                functionType,
                                promptFunctionType,
                                collector,
                                enableThinking,
                                enableMemoryAutoUpdate,
                                onNonFatalError,
                                onTokenLimitExceeded,
                                maxTokens,
                                tokenUsageThreshold,
                                isSubTask,
                                characterName,
                                avatarUri,
                                roleCardId,
                                chatId,
                                onToolInvocation,
                                notifyReplyOverride,
                                chatModelConfigIdOverride,
                                chatModelIndexOverride,
                                memorySpaceIdOverride,
                                stream,
                                enableGroupOrchestrationHint,
                                disableWarning
                            )
                        }
                    } else if (!hadFatalError) {
                        AppLogger.d(
                            TAG,
                            "跳过流完成处理：执行上下文已失效, id=${execContext.executionId}"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Tool continuations recurse, so release foreground ownership only here,
                    // once at the outer turn boundary rather than in each recursive handler.
                    invalidateExecutionContext(execContext, "sendMessage.completion.failed")
                    withContext(Dispatchers.Main) {
                        _inputProcessingState.value = InputProcessingState.Error(
                            // Same reason as the stream failure above: this state is shown, and a
                            // denied turn's own message is written for the model.
                            context.getString(
                                R.string.enhanced_error_with_message,
                                e.message?.let { permissionDenialDisplayText(context, it) }.orEmpty(),
                            )
                        )
                    }
                    if (!isSubTask) stopAiService(characterName, avatarUri)
                    throw e
                } finally {
                    unregisterExecutionContext(execContext)
                    withContext(NonCancellable) {
                        releaseModelExecutionSnapshot(execContext)
                    }
                }
            }
        }
        val sessionContext = com.ai.assistance.operit.api.chat.llmprovider.OpenCodeSessionContext(
            providerConversationId,
            workspacePath,
        )
        val sessionStream = object : Stream<String> by wrappedStream {
            override suspend fun collect(collector: StreamCollector<String>) {
                withContext(sessionContext) { wrappedStream.collect(collector) }
            }
        }
        return sessionStream.withEventChannel(eventChannel)
    }

    private data class TruncatedToolRoundRecovery(
        val repairedContent: String,
        val appendedSuffix: String,
        val invalidatedToolNames: List<String>,
        val invalidatedInvocationCount: Int,
    )

    private suspend fun detectAndRepairTruncatedToolRound(content: String): TruncatedToolRoundRecovery? {
        val inspection = ExecutableToolProtocolParser.inspectTruncation(content)
        val candidate = inspection.truncatedTool ?: return null
        val fragment = candidate.fragment
        val tagName = candidate.tagName

        val appendedSuffix =
            ExecutableToolProtocolParser.buildTruncatedToolRepairSuffix(
                fragment = fragment,
                fallbackTagName = tagName,
            )
        if (appendedSuffix.isEmpty()) {
            return null
        }
        val repairedContent = content + appendedSuffix
        val invalidatedToolNames =
            buildList {
                inspection.completeToolNames.forEach { add(it) }
                ExecutableToolProtocolParser.extractAttributeValue(fragment, "name")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(it) }
            }
                .distinct()
                .toList()

        return TruncatedToolRoundRecovery(
            repairedContent = repairedContent,
            appendedSuffix = appendedSuffix,
            invalidatedToolNames = invalidatedToolNames,
            invalidatedInvocationCount =
                ToolExecutionManager.countDisplayedToolInvocations(repairedContent),
        )
    }

    /** 在处理完流后调用，使用增强的工具检测功能 */
    private suspend fun processStreamCompletion(
            context: MessageExecutionContext,
            functionType: FunctionType = FunctionType.CHAT,
            promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
            collector: StreamCollector<String>,
            enableThinking: Boolean = false,
            enableMemoryAutoUpdate: Boolean = true,
            onNonFatalError: suspend (error: String) -> Unit,
            onTokenLimitExceeded: (suspend () -> Unit)? = null,
            maxTokens: Int,
            tokenUsageThreshold: Double,
            isSubTask: Boolean,
            characterName: String? = null,
            avatarUri: String? = null,
            roleCardId: String? = null,
            chatId: String? = null,
            onToolInvocation: (suspend (String) -> Unit)? = null,
            notifyReplyOverride: Boolean? = null,
            chatModelConfigIdOverride: String? = null,
            chatModelIndexOverride: Int? = null,
            memorySpaceIdOverride: String? = null,
            stream: Boolean = true,
            enableGroupOrchestrationHint: Boolean = false,
            disableWarning: Boolean = false
    ) {
        suspend fun finishTurn() {
            // Finish and input acceptance share one lock, so input arriving at EOF either
            // continues this turn or is rejected for the caller to keep queued.
            if (context.turnInputInbox?.finishIfEmpty() == false) {
                processToolResults(
                    results = emptyList(), context = context, functionType = functionType,
                    promptFunctionType = promptFunctionType, collector = collector,
                    enableThinking = enableThinking, enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                    onNonFatalError = onNonFatalError, onTokenLimitExceeded = onTokenLimitExceeded,
                    maxTokens = maxTokens, tokenUsageThreshold = tokenUsageThreshold,
                    isSubTask = isSubTask, characterName = characterName, avatarUri = avatarUri,
                    roleCardId = roleCardId, chatId = chatId, onToolInvocation = onToolInvocation,
                    notifyReplyOverride = notifyReplyOverride,
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride,
                    memorySpaceIdOverride = memorySpaceIdOverride, stream = stream,
                    enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                    disableWarning = disableWarning, inputOnly = true,
                )
                return
            }
            finalizeAssistantResponse(
                context = context, content = context.roundManager.getDisplayContent(),
                enableMemoryAutoUpdate = enableMemoryAutoUpdate, onNonFatalError = onNonFatalError,
                isSubTask = isSubTask, chatId = chatId, characterName = characterName,
                avatarUri = avatarUri, notifyReplyOverride = notifyReplyOverride,
                memorySpaceIdOverride = memorySpaceIdOverride,
            )
        }
        try {
            val startTime = messageTimingNow()
            // If conversation is no longer active, return immediately
            if (!context.isConversationActive.get()) {
                return
            }

            // Get response content
            val content = context.streamBuffer.toString().trim()

            // If content is empty, it means an error likely occurred or the model returned nothing.
            // We must still finalize the conversation to reset the state correctly.
            if (content.isEmpty()) {
                AppLogger.d(TAG, "Stream content is empty. Finalizing conversation state.")
                finishTurn()
                return
            }

            // If content is empty, finish immediately
            if (content.isEmpty()) {
                return
            }

            // 禁止“纯思考输出”：移除 thinking 后正文为空时，发出专用告警并回传给 AI 继续生成
            // Only visibility is needed here; do not copy a long response merely to test emptiness.
            val contentWithoutThinking = ChatUtils.removeThinkingContentWindow(content, 0)
            if (contentWithoutThinking.length == 0) {
                if (disableWarning) {
                    AppLogger.w(TAG, "检测到纯思考输出，disableWarning=true，直接结束本轮而不注入警告")
                    finishTurn()
                    return
                }
                val pureThinkingWarning =
                        ConversationMarkupManager.createWarningStatus(
                                this@EnhancedAIService.context.getString(
                                        R.string.enhanced_pure_thinking_only_warning
                                )
                        )
                val warningDisplayContent = "\n$pureThinkingWarning"
                context.roundManager.appendChunk(warningDisplayContent)
                collector.emit(warningDisplayContent)
                context.emittedReplayCharCount.addAndGet(warningDisplayContent.length)
                try {
                    context.conversationHistory.add(
                        PromptTurn(kind = PromptTurnKind.TOOL_RESULT, content = pureThinkingWarning)
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "添加纯思考告警到历史记录失败", e)
                    return
                }
                AppLogger.w(TAG, "检测到纯思考输出（removeThinking后正文为空），已回传告警给AI继续生成")
                handleToolInvocation(
                        toolInvocations = emptyList(),
                        context = context,
                        functionType = functionType,
                        promptFunctionType = promptFunctionType,
                        collector = collector,
                        enableThinking = enableThinking,
                        enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                        onNonFatalError = onNonFatalError,
                        onTokenLimitExceeded = onTokenLimitExceeded,
                        maxTokens = maxTokens,
                        tokenUsageThreshold = tokenUsageThreshold,
                        isSubTask = isSubTask,
                        characterName = characterName,
                        avatarUri = avatarUri,
                        roleCardId = roleCardId,
                        chatId = chatId,
                        onToolInvocation = onToolInvocation,
                        notifyReplyOverride = notifyReplyOverride,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride,
                        memorySpaceIdOverride = memorySpaceIdOverride,
                        stream = stream,
                        enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                        toolResultOverrideMessage = pureThinkingWarning,
                        disableWarning = disableWarning
                )
                return
            }

            val truncatedToolRecovery = detectAndRepairTruncatedToolRound(content)
            val finalContent = truncatedToolRecovery?.repairedContent ?: content

            // 截断修复仅追加缺失后缀，不撤回已经发出的内容
            if (truncatedToolRecovery != null) {
                val appendedSuffix = truncatedToolRecovery.appendedSuffix
                if (appendedSuffix.isNotEmpty()) {
                    context.streamBuffer.append(appendedSuffix)
                    context.roundManager.updateContent(context.streamBuffer.toString())
                    collector.emit(appendedSuffix)
                    context.emittedReplayCharCount.addAndGet(appendedSuffix.length)
                }
            } else if (finalContent != content) {
                context.streamBuffer.setLength(0)
                context.streamBuffer.append(finalContent)
                context.roundManager.updateContent(finalContent)
            }

            // 预先提取工具调用信息，避免重复解析
            // Thinking/search blocks are display-only reasoning. Never execute tool-shaped text
            // from those blocks, even when visible content follows in the same response.
            val extractedToolInvocations =
                    if (!context.toolsEnabled) {
                        emptyList()
                    } else if (truncatedToolRecovery == null) {
                        ToolExecutionManager.extractExecutableToolInvocations(finalContent).map { invocation ->
                            invocation.copy(
                                callId = java.util.UUID.randomUUID().toString(),
                                invocationIndex = context.toolSequence.nextIndex.getAndIncrement(),
                            )
                        }
                    } else {
                        ToolExecutionManager.reserveToolInvocationIndices(
                            context.toolSequence.nextIndex,
                            truncatedToolRecovery.invalidatedInvocationCount,
                        )
                        emptyList()
                    }

            // Check again if conversation is active
            if (!context.isConversationActive.get()) {
                return
            }

            // Add current assistant message to conversation history
            try {
                context.conversationHistory.add(
                    PromptTurn(
                        kind = PromptTurnKind.ASSISTANT,
                        content = context.roundManager.getCurrentRoundContent(),
                        metadata = mapOf(
                            com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory.INTERMEDIATE_METADATA to
                                (extractedToolInvocations.isNotEmpty() || truncatedToolRecovery != null),
                        )
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "添加助手消息到历史记录失败", e)
                return
            }

            // Check again if conversation is active
            if (!context.isConversationActive.get()) {
                return
            }

            if (truncatedToolRecovery != null) {
                if (disableWarning) {
                    AppLogger.w(
                        TAG,
                        "检测到未闭合工具调用，disableWarning=true，直接结束本轮而不注入警告。invalidated=${truncatedToolRecovery.invalidatedToolNames}"
                    )
                    finishTurn()
                    return
                }
                val warningStatus =
                        ConversationMarkupManager.createWarningStatus(
                                this@EnhancedAIService.context.getString(
                                        R.string.enhanced_truncated_tool_call_warning
                                )
                        )
                val warningDisplayContent = "\n$warningStatus"
                context.roundManager.appendContent(warningDisplayContent)
                collector.emit(warningDisplayContent)
                context.emittedReplayCharCount.addAndGet(warningDisplayContent.length)
                AppLogger.w(
                        TAG,
                        "检测到未闭合工具调用，本轮工具全部作废。invalidated=${truncatedToolRecovery.invalidatedToolNames}"
                )
                handleToolInvocation(
                        toolInvocations = emptyList(),
                        context = context,
                        functionType = functionType,
                        promptFunctionType = promptFunctionType,
                        collector = collector,
                        enableThinking = enableThinking,
                        enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                        onNonFatalError = onNonFatalError,
                        onTokenLimitExceeded = onTokenLimitExceeded,
                        maxTokens = maxTokens,
                        tokenUsageThreshold = tokenUsageThreshold,
                        isSubTask = isSubTask,
                        characterName = characterName,
                        avatarUri = avatarUri,
                        roleCardId = roleCardId,
                        chatId = chatId,
                        onToolInvocation = onToolInvocation,
                        notifyReplyOverride = notifyReplyOverride,
                        chatModelConfigIdOverride = chatModelConfigIdOverride,
                        chatModelIndexOverride = chatModelIndexOverride,
                        memorySpaceIdOverride = memorySpaceIdOverride,
                        stream = stream,
                        enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                        toolResultOverrideMessage = warningStatus,
                        disableWarning = disableWarning
                )
                return
            }

            // Main flow: Detect and process tool invocations
            if (extractedToolInvocations.isNotEmpty()) {
                logMessageTiming(
                    stage = "enhanced.processStreamCompletion.detectToolInvocations",
                    startTimeMs = startTime,
                    details = "count=${extractedToolInvocations.size}"
                )
                handleToolInvocation(
                        extractedToolInvocations,
                        context,
                        functionType,
                        promptFunctionType,
                        collector,
                        enableThinking,
                        enableMemoryAutoUpdate,
                        onNonFatalError,
                        onTokenLimitExceeded,
                        maxTokens,
                        tokenUsageThreshold,
                        isSubTask,
                        characterName,
                        avatarUri,
                        roleCardId,
                        chatId,
                        onToolInvocation,
                        notifyReplyOverride,
                        chatModelConfigIdOverride,
                        chatModelIndexOverride,
                        memorySpaceIdOverride,
                        stream = stream,
                        enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                        disableWarning = disableWarning
                )
                return
            }

            finishTurn()
            logMessageTiming(
                stage = "enhanced.processStreamCompletion.complete",
                startTimeMs = startTime
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理流完成时发生错误", e)
            // Let the turn dispatcher persist the partial response and report failure. Returning
            // normally here would mark an interrupted/failed subagent as successfully completed.
            throw e
        }
    }

    /**
     * Announces a model request and keeps the Subagent run's round counter in step, so the card can
     * report model rounds. A chat with its own run observer (the reading companion) already
     * persists its rounds, so it is skipped here.
     */
    private suspend fun notifyModelRequestStarted(chatId: String?, isSubTask: Boolean, snapshot: ModelExecutionSnapshot) {
        val identity = com.ai.assistance.operit.api.chat.llmprovider.resolveTokenStatIdentity(snapshot.service)
        com.ai.assistance.operit.core.agent.AgentRunObservers.forChat(chatId)?.onModelRequest(
            com.ai.assistance.operit.core.agent.AgentModelIdentity(
                snapshot.config.id, snapshot.config.name, snapshot.lease.modelIndex,
                identity.first, identity.second,
            )
        )
        val childChatId = chatId?.takeIf { it.isNotBlank() }
        if (!isSubTask || childChatId == null) return
        if (com.ai.assistance.operit.core.agent.AgentRunObservers.hasObserver(childChatId)) return
        runCatching {
            SubagentRunRepository.getInstance(context)
                .incrementModelRoundCountByChildChatId(childChatId)
        }.onFailure { error ->
            AppLogger.e(
                TAG,
                "Failed to persist Subagent model round count: chatId=$childChatId",
                error,
            )
        }
    }

    /** Finalize an assistant response without relying on status-tag control flow. */
    private suspend fun finalizeAssistantResponse(
        context: MessageExecutionContext,
        content: String,
        enableMemoryAutoUpdate: Boolean,
        onNonFatalError: suspend (error: String) -> Unit,
        isSubTask: Boolean,
        chatId: String? = null,
        characterName: String? = null,
        avatarUri: String? = null,
        notifyReplyOverride: Boolean? = null,
        memorySpaceIdOverride: String? = null
    ) {
        // Mark conversation as complete
        context.turnInputInbox?.seal()
        context.isConversationActive.set(false)

        // 清除内容池
        // roundManager.clearContent()
        
        // 保存最后的回复内容用于通知
        lastReplyContent = content

        // Ensure input processing state is updated to completed
        if (!isSubTask) {
            withContext(Dispatchers.Main) {
                _inputProcessingState.value = InputProcessingState.Completed
            }
        }

        if (!isSubTask && content.isNotBlank()) {
            runCatching {
                val currentChatId = chatId?.takeIf { it.isNotBlank() }
                val profileId =
                    memorySpaceIdOverride?.takeIf { it.isNotBlank() }
                        ?: preferencesManager.activeMemorySpaceIdFlow.first()
                if (currentChatId.isNullOrBlank()) {
                    AppLogger.w(TAG, "自动保存长期记忆入队跳过：chatId为空")
                } else {
                    com.ai.assistance.operit.api.chat.library.MemoryLearningCoordinator.prepareReview(
                        this@EnhancedAIService.context,profileId,currentChatId,
                        turnKey = context.toolTimingScopeId,
                        toolIterations = context.learningToolIterations.get()
                    )
                    val memoryPreferences = com.ai.assistance.operit.data.preferences.ApiPreferences.getInstance(this@EnhancedAIService.context)
                    if (enableMemoryAutoUpdate && memoryPreferences.enableMemoryAutoUpdateFlow.first() &&
                        memoryPreferences.enableLegacyMemoryExtractionFlow.first()) {
                    MemoryAutoSaveCandidateRepository(this@EnhancedAIService.context, profileId)
                        .enqueue(
                            chatId = currentChatId,
                            triggerMessageTimestamp = System.currentTimeMillis()
                        )
                    }
                }
            }.onFailure { e ->
                AppLogger.e(TAG, "自动保存长期记忆候选入队失败", e)
                onNonFatalError(
                    this@EnhancedAIService.context.getString(
                        R.string.chat_auto_update_memory_failed,
                        e.message ?: ""
                    )
                )
            }
        }

        if (!isSubTask) {
            notifyReplyCompleted(chatId, characterName, avatarUri, notifyReplyOverride)
            stopAiService(characterName, avatarUri)
        }
    }

    /** Handle tool invocation processing - simplified version without callbacks */
    private suspend fun handleToolInvocation(
        toolInvocations: List<ToolInvocation>,
        context: MessageExecutionContext,
        functionType: FunctionType = FunctionType.CHAT,
        promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
        collector: StreamCollector<String>,
        enableThinking: Boolean = false,
        enableMemoryAutoUpdate: Boolean = true,
        onNonFatalError: suspend (error: String) -> Unit,
        onTokenLimitExceeded: (suspend () -> Unit)? = null,
        maxTokens: Int,
        tokenUsageThreshold: Double,
        isSubTask: Boolean,
        characterName: String? = null,
        avatarUri: String? = null,
        roleCardId: String? = null,
        chatId: String? = null,
        onToolInvocation: (suspend (String) -> Unit)? = null,
        notifyReplyOverride: Boolean? = null,
        chatModelConfigIdOverride: String? = null,
        chatModelIndexOverride: Int? = null,
        memorySpaceIdOverride: String? = null,
        stream: Boolean = true,
        enableGroupOrchestrationHint: Boolean = false,
        toolResultOverrideMessage: String? = null,
        disableWarning: Boolean = false
    ) {
        val startTime = messageTimingNow()

        val isolatedToolNames = context.isolatedToolPrompts?.mapTo(linkedSetOf()) { it.name }
        if (isolatedToolNames != null && toolInvocations.any { it.tool.name !in isolatedToolNames }) {
            AppLogger.w(
                TAG,
                "Isolated turn produced an unexposed tool call; refusing execution: " +
                    toolInvocations.map { it.tool.name },
            )
            finalizeAssistantResponse(
                context = context,
                content = context.roundManager.getDisplayContent(),
                enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                onNonFatalError = onNonFatalError,
                isSubTask = isSubTask,
                chatId = chatId,
                characterName = characterName,
                avatarUri = avatarUri,
                notifyReplyOverride = notifyReplyOverride,
                memorySpaceIdOverride = memorySpaceIdOverride,
            )
            return
        }

        val terminalInvocations =
            toolInvocations.filter { invocation ->
                com.ai.assistance.operit.core.tools.ToolCallRepairRouter.terminalToolName(invocation) in context.terminalToolNames
            }
        if (terminalInvocations.isNotEmpty() && toolInvocations.size != 1) {
            AppLogger.w(TAG, "Terminal result turn must contain exactly one tool call")
            finalizeAssistantResponse(
                context = context,
                content = context.roundManager.getDisplayContent(),
                enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                onNonFatalError = onNonFatalError,
                isSubTask = isSubTask,
                chatId = chatId,
                characterName = characterName,
                avatarUri = avatarUri,
                notifyReplyOverride = notifyReplyOverride,
                memorySpaceIdOverride = memorySpaceIdOverride,
            )
            return
        }

        val liveAssistantContent = context.roundManager.getDisplaySnapshot()
        context.onToolExecutionBoundary?.invoke(
            ToolExecutionBoundarySnapshot(
                displayContent = liveAssistantContent,
                replayCharCount = context.emittedReplayCharCount.get(),
                revisionEventCount = context.eventChannel.replayCache.size,
            )
        )

        toolInvocations.forEach { invocation ->
            onToolInvocation?.invoke(invocation.tool.name)
        }

        if (!isSubTask && toolInvocations.isNotEmpty()) {
            // One tool batch is one iteration, regardless of the number of parallel calls.
            context.learningToolIterations.incrementAndGet()
            withContext(Dispatchers.Main) {
                val toolNames = toolInvocations.joinToString(", ") { resolveToolDisplayName(it.tool) }
                _inputProcessingState.value = InputProcessingState.ExecutingTool(toolNames)
            }
        }

        // This independent scope must retain the conversation identity for tool continuations.
        val processToolJob = toolProcessingScope.async(
            context = com.ai.assistance.operit.api.chat.llmprovider.OpenCodeSessionContext(
                context.providerSessionId
                    ?: chatId?.takeIf { it.isNotBlank() }
                    ?: providerSessionId,
                context.workspacePath,
            ),
            start = CoroutineStart.LAZY,
        ) {
            val chatHistoryManager =
                ChatHistoryManager.getInstance(this@EnhancedAIService.context)
            val childChatTitle =
                chatId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { chatHistoryManager.getChatTitle(it) }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val conversationLabel =
                if (isSubTask && !chatId.isNullOrBlank()) {
                    SubagentRunRepository.getInstance(this@EnhancedAIService.context)
                        .getByChildChatId(chatId)
                        ?.let { run ->
                            val parentTitle =
                                chatHistoryManager.getChatTitle(run.parentChatId)
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: run.parentChatId
                            this@EnhancedAIService.context.getString(
                                R.string.subagent_permission_source,
                                run.agentProfileId,
                                run.title,
                                parentTitle,
                                childChatTitle ?: chatId,
                            )
                        }
                        ?: childChatTitle
                } else {
                    childChatTitle ?: characterName?.trim()?.takeIf { it.isNotEmpty() }
                }
            val modelSnapshot = getModelExecutionSnapshot(
                context,
                functionType,
                chatModelConfigIdOverride,
                chatModelIndexOverride
            )
            val config = modelSnapshot.config
            val imageRecognitionModelAvailable =
                resolveImageRecognitionAvailability(
                    isSubTask = isSubTask,
                    hasConfiguredBackend = multiServiceManager.hasImageRecognitionConfigured(),
                )
            val toolOutputCountingCollector =
                object : StreamCollector<String> {
                    override suspend fun emit(value: String) {
                        // Tool results are part of the displayed transcript. Keep the canonical
                        // display ledger aligned with emittedReplayCharCount so a later tool
                        // boundary cannot replace a prefix that contains earlier results with a
                        // round-manager snapshot that omitted them.
                        context.roundManager.appendChunk(value)
                        collector.emit(value)
                        context.emittedReplayCharCount.addAndGet(value.length)
                    }
                }
            val allToolResults = ToolExecutionManager.executeInvocations(
                invocations = toolInvocations,
                context = this@EnhancedAIService.context,
                toolHandler = toolHandler,
                packageManager = packageManager,
                collector = toolOutputCountingCollector,
                timingScopeId = context.toolSequence.scopeId,
                toolExposureMode = ToolExposureMode.resolve(config.apiProviderType),
                callerName = characterName,
                callerChatId = chatId,
                callerCardId = roleCardId,
                resolvedMemorySpaceId = memorySpaceIdOverride,
                conversationLabel = conversationLabel,
                parentModelConfigId = modelSnapshot.config.id,
                parentModelIndex = modelSnapshot.lease.modelIndex,
                parentModelSupportsVision = config.enableDirectImageProcessing,
                imageRecognitionModelAvailable = imageRecognitionModelAvailable,
                liveAssistantContent = liveAssistantContent,
                workspacePath = context.workspacePath,
                workspaceEnv = context.workspaceEnv,
                isSubagent = isSubTask,
                subagentToolLoopGuard =
                    context.subagentToolLoopGuard.takeIf {
                        isSubTask && !com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator
                            .getInstance(this@EnhancedAIService.context).isAgent(chatId)
                    },
            )

            if (allToolResults.isNotEmpty()) {
                AppLogger.d(TAG, "所有工具结果收集完毕，准备最终处理。")
                val turnSignal = resolveToolTurnSignal(allToolResults)
                if (turnSignal == ToolTurnSignal.INTERRUPTED) {
                    val interrupted = allToolResults.first { it.interruptTurn && !it.success }
                    throw IllegalStateException(
                        interrupted.error
                            ?: "Tool execution interrupted this turn: ${interrupted.toolName}"
                    )
                }
                if (
                    turnSignal == ToolTurnSignal.COMPLETE ||
                    toolInvocations.singleOrNull()?.let {
                        com.ai.assistance.operit.core.tools.ToolCallRepairRouter.terminalToolName(it)
                    } in context.terminalToolNames
                ) {
                    finalizeAssistantResponse(
                        context = context,
                        content = context.roundManager.getDisplayContent(),
                        enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                        onNonFatalError = onNonFatalError,
                        isSubTask = isSubTask,
                        chatId = chatId,
                        characterName = characterName,
                        avatarUri = avatarUri,
                        notifyReplyOverride = notifyReplyOverride,
                        memorySpaceIdOverride = memorySpaceIdOverride,
                    )
                } else {
                    processToolResults(
                        allToolResults, context, functionType, promptFunctionType, collector, enableThinking,
                        enableMemoryAutoUpdate, onNonFatalError, onTokenLimitExceeded, maxTokens, tokenUsageThreshold, isSubTask,
                        characterName, avatarUri, roleCardId, chatId, onToolInvocation, notifyReplyOverride,
                        chatModelConfigIdOverride, chatModelIndexOverride, memorySpaceIdOverride, stream, enableGroupOrchestrationHint,
                        disableWarning = disableWarning
                    )
                }
            } else if (!toolResultOverrideMessage.isNullOrEmpty()) {
                AppLogger.d(TAG, "0工具路由命中，使用覆盖消息继续请求AI。")
                processToolResults(
                    results = emptyList(),
                    context = context,
                    functionType = functionType,
                    promptFunctionType = promptFunctionType,
                    collector = collector,
                    enableThinking = enableThinking,
                    enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                    onNonFatalError = onNonFatalError,
                    onTokenLimitExceeded = onTokenLimitExceeded,
                    maxTokens = maxTokens,
                    tokenUsageThreshold = tokenUsageThreshold,
                    isSubTask = isSubTask,
                    characterName = characterName,
                    avatarUri = avatarUri,
                    roleCardId = roleCardId,
                    chatId = chatId,
                    onToolInvocation = onToolInvocation,
                    notifyReplyOverride = notifyReplyOverride,
                    chatModelConfigIdOverride = chatModelConfigIdOverride,
                    chatModelIndexOverride = chatModelIndexOverride,
                    memorySpaceIdOverride = memorySpaceIdOverride,
                    stream = stream,
                    enableGroupOrchestrationHint = enableGroupOrchestrationHint,
                    toolResultMessageOverride = toolResultOverrideMessage,
                    disableWarning = disableWarning
                )
            }

            logMessageTiming(
                stage = "enhanced.handleToolInvocation.complete",
                startTimeMs = startTime,
                details = "toolCount=${toolInvocations.size}"
            )
        }

        val invocationId = java.util.UUID.randomUUID().toString()
        toolExecutionJobs[invocationId] = processToolJob

        try {
            if (isExecutionContextActive(context)) {
                processToolJob.start()
            } else {
                processToolJob.cancel()
            }
            processToolJob.await()
        } finally {
            toolExecutionJobs.remove(invocationId)
        }
    }


    /** Process tool execution result - simplified version without callbacks */
    private suspend fun processToolResults(
            results: List<ToolResult>,
            context: MessageExecutionContext,
            functionType: FunctionType = FunctionType.CHAT,
            promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
            collector: StreamCollector<String>,
            enableThinking: Boolean = false,
            enableMemoryAutoUpdate: Boolean = true,
            onNonFatalError: suspend (error: String) -> Unit,
            onTokenLimitExceeded: (suspend () -> Unit)? = null,
            maxTokens: Int,
            tokenUsageThreshold: Double,
            isSubTask: Boolean,
            characterName: String? = null,
            avatarUri: String? = null,
            roleCardId: String? = null,
            chatId: String? = null,
            onToolInvocation: (suspend (String) -> Unit)? = null,
            notifyReplyOverride: Boolean? = null,
            chatModelConfigIdOverride: String? = null,
            chatModelIndexOverride: Int? = null,
            memorySpaceIdOverride: String? = null,
            stream: Boolean = true,
            enableGroupOrchestrationHint: Boolean = false,
            toolResultMessageOverride: String? = null,
            disableWarning: Boolean = false,
            inputOnly: Boolean = false,
    ) {
        val startTime = messageTimingNow()
        val toolNames = results.joinToString(", ") { it.toolName }
        val rawToolResultMessage =
            toolResultMessageOverride ?: ConversationMarkupManager.buildBoundedToolResultMessage(results)
        val toolResultMessage =
            if (rawToolResultMessage.length <= ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS) {
                rawToolResultMessage
            } else {
                AppLogger.w(
                    TAG,
                    "工具结果消息超过最终兜底上限，已静默截断。原长度: ${rawToolResultMessage.length}"
                )
                rawToolResultMessage.take(ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS)
            }

        if (toolResultMessage.isBlank() && !inputOnly) {
            AppLogger.w(TAG, "工具结果消息为空，跳过后续AI请求")
            return
        }

        val displayToolNames = if (toolNames.isNotBlank()) toolNames else "warning"
        if (results.isNotEmpty()) {
            AppLogger.d(TAG, "开始处理工具结果: $toolNames, 成功: ${results.all { it.success }}")
        } else {
            AppLogger.d(TAG, "开始处理0工具覆盖消息，长度: ${toolResultMessage.length}")
        }

        // Add transition state
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.ProcessingToolResult(displayToolNames)
            }
        }

        // Check if conversation is still active
        if (!context.isConversationActive.get()) {
            return
        }

        // Add tool result to conversation history
        if (!inputOnly) context.conversationHistory.add(
            PromptTurn(
                kind = PromptTurnKind.TOOL_RESULT,
                content = toolResultMessage,
                toolName = toolNames.ifBlank { null }
            )
        )

        val turnInputs = context.turnInputInbox?.drain().orEmpty()
        if (turnInputs.isNotEmpty()) {
            // Once durable user rows are written, cancellation must not return those same
            // messages to the queue. Complete the history handoff and acknowledgement together.
            withContext(NonCancellable) {
                val nextAssistantScope = context.onTurnInput?.invoke(
                    turnInputs,
                    ToolExecutionBoundarySnapshot(
                        context.roundManager.getDisplaySnapshot(), context.emittedReplayCharCount.get(),
                        context.eventChannel.replayCache.size,
                    ),
                )
                if (nextAssistantScope != null) {
                    // Tool rows are indexed within a displayed assistant message. Steering
                    // starts a new message, so both live timings and result indices move with it.
                    com.ai.assistance.operit.core.tools.phone.PhoneControlTools.finishTurn(context.toolSequence.scopeId)
                    context.toolSequence.startMessage(nextAssistantScope)
                    com.ai.assistance.operit.core.tools.phone.PhoneControlTools.registerTurn(nextAssistantScope)
                }
                turnInputs.forEach { input ->
                    val inputTurn = PromptTurn(
                        kind = PromptTurnKind.USER, content = input.text,
                        metadata = if (input.agentPath != null) mapOf(
                            com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory.EVENT_METADATA to true,
                            com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory.TASK_METADATA to input.startsAgentTurn,
                        ) else emptyMap(),
                    )
                    context.conversationHistory.add(inputTurn)
                    if (context.collaborationInputs.isNotEmpty()) {
                        context.collaborationInputs.add(inputTurn)
                    }
                }
                context.turnInputInbox?.acknowledge()
            }
        }

        val normalizedChatHistory =
            conversationService.normalizeConversationHistoryForModel(context.conversationHistory)
                .mergeAdjacentTurns { previous, current ->
                    com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory.canMergeUserTurns(previous, current)
                }
        context.conversationHistory.clear()
        context.conversationHistory.addAll(normalizedChatHistory)

        // Get current conversation history is now just the normalized context history
        var currentChatHistory: List<PromptTurn> = context.conversationHistory.toList()

        // 不再需要，因为结果在调用时已实时输出
        // context.roundManager.appendContent(toolResultMessage)
        // try { collector.emit(toolResultMessage) } catch (_: Exception) {}

        // Start new round - ensure tool execution response will be shown in a new message
        startAssistantResponseRound(context, collector)

        // Clearly show we're preparing to send tool result to AI
        if (!isSubTask) {
        withContext(Dispatchers.Main) {
            _inputProcessingState.value = InputProcessingState.ProcessingToolResult(displayToolNames)
            }
        }

        // Add short delay to make state change more visible
        delay(300)

        // Get all model parameters from preferences (with enabled state)
        val modelSnapshot = getModelExecutionSnapshot(
            context,
            functionType,
            chatModelConfigIdOverride,
            chatModelIndexOverride
        )
        val modelParameters = com.ai.assistance.operit.core.agent.collaboration.CollaborationModelParameters.apply(
            modelSnapshot.modelParameters,
            com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(this@EnhancedAIService.context).reasoningEffort(chatId),
            modelSnapshot.config.protocolSettingsForModel(modelSnapshot.config.modelName).reasoningEfforts,
        )

        // 获取对应功能类型的AIService实例
        val serviceForFunction = modelSnapshot.service
        
        // 获取工具列表（如果启用Tool Call）- 提前获取，以便在token计算中使用
        val availableTools = getAvailableToolsForFunction(
            functionType = functionType,
            chatId = chatId,
            promptFunctionType = promptFunctionType,
            roleCardId = roleCardId,
            modelConfig = modelSnapshot.config,
            isSubTask = isSubTask,
            toolsEnabled = context.toolsEnabled,
            isolatedToolPrompts = context.isolatedToolPrompts,
        )
 
        val currentTokens = estimatePreparedRequestWindow(
            serviceForFunction = serviceForFunction,
            preparedHistory = currentChatHistory,
            availableTools = availableTools,
            publishEstimate = true
        )

        // After a tool call, check if token usage exceeds the threshold
        if (maxTokens > 0) {
            val usageRatio = currentTokens.toDouble() / maxTokens.toDouble()

            if (usageRatio >= tokenUsageThreshold) {
                if (com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(this@EnhancedAIService.context).isAgent(chatId)) {
                    currentChatHistory = compactCollaborationHistory(
                        context, requireNotNull(chatId), currentChatHistory, serviceForFunction, availableTools, maxTokens,
                    )
                    context.conversationHistory.clear()
                    context.conversationHistory.addAll(currentChatHistory)
                } else {
                AppLogger.w(TAG, "Token usage ($usageRatio) exceeds threshold ($tokenUsageThreshold) after tool call. Triggering summary.")
                context.turnInputInbox?.seal()
                onTokenLimitExceeded?.invoke()
                context.isConversationActive.set(false)
                if (!isSubTask) {
                    stopAiService(characterName, avatarUri)
                }
                // 关键修复：在触发总结后，直接返回，因为后续流程将由回调处理
                return
                }
            }
        }

        // 清空之前的单次请求token计数
        _perRequestTokenCounts.value = null
        currentRequestInputTokenCount = 0
        currentRequestOutputTokenCount = 0
        currentRequestCachedInputTokenCount = 0
        
        // 使用新的Stream API处理工具执行结果
        withContext(Dispatchers.IO) {
            try {
                // 发送消息并获取响应流
                val aiStartTime = messageTimingNow()
                notifyModelRequestStarted(chatId, isSubTask, modelSnapshot)
                val inputUsage = beginInputUsage(serviceForFunction, currentChatHistory, availableTools)
                val responseStream =
                        serviceForFunction.sendMessage(
                                context = this@EnhancedAIService.context,
                                chatHistory = currentChatHistory,
                                modelParameters = modelParameters,
                                enableThinking = enableThinking,
                                stream = stream,
                                availableTools = availableTools,
                                onUsageReported = { usage, attempt -> reportInputUsage(inputUsage, usage, attempt) },
                                onTokensUpdated = { input, cachedInput, output ->
                                    currentRequestInputTokenCount = input.coerceAtLeast(0)
                                    currentRequestOutputTokenCount = output.coerceAtLeast(0)
                                    currentRequestCachedInputTokenCount = cachedInput.coerceAtLeast(0)
                                    _perRequestTokenCounts.value = Pair(input, output)
                                },
                                onNonFatalError = onNonFatalError,
                                statsCategory =
                                    tokenStatsCategoryFor(
                                        functionType = functionType,
                                        isSubTask = isSubTask,
                                    )
                        )

                // 更新状态为接收中
                if (!isSubTask) {
                withContext(Dispatchers.Main) {
                    _inputProcessingState.value =
                            InputProcessingState.Receiving(this@EnhancedAIService.context.getString(R.string.enhanced_receiving_tool_result))
                    }
                }

                // 处理流
                var chunkCount = 0
                var totalChars = 0
                var lastLogTime = messageTimingNow()
                var isFirstChunk = true
                val revisableStream = responseStream as? TextStreamEventCarrier
                val revisionTracker = TextStreamRevisionTracker()
                val replayRoundStart = context.emittedReplayCharCount.get()
                var processedRevisionEventCount = 0

                suspend fun drainRevisionEvents() {
                    val events = revisableStream?.eventChannel?.replayCache.orEmpty()
                    while (processedRevisionEventCount < events.size) {
                        val event = events[processedRevisionEventCount++]
                        context.eventChannel.emit(event)
                        when (event.eventType) {
                            TextStreamEventType.SAVEPOINT -> revisionTracker.savepoint(event.id)
                            TextStreamEventType.ROLLBACK -> {
                                val snapshot = revisionTracker.rollback(event.id)?.toString()
                                    ?: continue
                                context.streamBuffer.clear()
                                context.streamBuffer.append(snapshot)
                                context.roundManager.updateContent(snapshot)
                                context.emittedReplayCharCount.set(
                                    replayRoundStart + snapshot.length
                                )
                            }
                        }
                    }
                }

                try {
                responseStream.collect { content ->
                            drainRevisionEvents()
                            if (isFirstChunk) {
                                isFirstChunk = false
                                logMessageTiming(
                                    stage = "enhanced.processToolResults.firstResponseChunk",
                                    startTimeMs = aiStartTime,
                                    details = "toolNames=$displayToolNames, stream=$stream"
                                )
                            }

                            revisionTracker.append(content)

                            // 更新streamBuffer
                            context.streamBuffer.append(content)
                            context.roundManager.appendChunk(content)

                            // 累计统计
                            chunkCount++
                            totalChars += content.length

                            // 定期记录日志
                            val currentTime = messageTimingNow()
                            if (currentTime - lastLogTime > 5000) { // 每5秒记录一次
                                lastLogTime = currentTime
                            }

                            // 通过收集器将内容发射出去，让UI可以接收到
                            collector.emit(content)
                            context.emittedReplayCharCount.addAndGet(content.length)
                }
                } finally {
                    drainRevisionEvents()
                }

                // Update accumulated token counts and persist them
                val inputTokens = serviceForFunction.inputTokenCount
                val cachedInputTokens = serviceForFunction.cachedInputTokenCount
                val outputTokens = serviceForFunction.outputTokenCount
                accumulatedInputTokenCount = saturatedTokenSum(accumulatedInputTokenCount, inputTokens)
                accumulatedOutputTokenCount = saturatedTokenSum(accumulatedOutputTokenCount, outputTokens)
                accumulatedCachedInputTokenCount =
                    saturatedTokenSum(accumulatedCachedInputTokenCount, cachedInputTokens)
                currentRequestInputTokenCount = 0
                currentRequestOutputTokenCount = 0
                currentRequestCachedInputTokenCount = 0

                AppLogger.d(
                        TAG,
                        "Token count updated after tool result for $functionType. Input: $inputTokens, Output: $outputTokens, CachedInput: $cachedInputTokens. Turn Accumulated: $accumulatedInputTokenCount, $accumulatedOutputTokenCount, $accumulatedCachedInputTokenCount"
                )

                logMessageTiming(
                    stage = "enhanced.processToolResults.aiResponseComplete",
                    startTimeMs = aiStartTime,
                    details = "toolNames=$displayToolNames, totalChars=$totalChars"
                )

                // 流处理完成，处理完成逻辑
                processStreamCompletion(
                    context,
                    functionType,
                    promptFunctionType,
                    collector,
                    enableThinking,
                    enableMemoryAutoUpdate,
                    onNonFatalError,
                    onTokenLimitExceeded,
                    maxTokens,
                    tokenUsageThreshold,
                    isSubTask,
                    characterName,
                    avatarUri,
                    roleCardId,
                    chatId,
                    onToolInvocation,
                    notifyReplyOverride,
                    chatModelConfigIdOverride,
                    chatModelIndexOverride,
                    memorySpaceIdOverride,
                    stream,
                    enableGroupOrchestrationHint,
                    disableWarning
                )
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "处理工具执行结果被取消")
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "处理工具执行结果时出错", e)
                throw e
            } finally {
                logMessageTiming(
                    stage = "enhanced.processToolResults.complete",
                    startTimeMs = startTime,
                    details = "toolNames=$displayToolNames, resultCount=${results.size}"
                )
            }
        }
    }
    /**
     * Get the current input token count from the last API call
     * @return The number of input tokens used in the most recent request
     */
    fun getCurrentInputTokenCount(): Int {
        return accumulatedInputTokenCount
    }

    /**
     * Get the current output token count from the last API call
     * @return The number of output tokens generated in the most recent response
     */
    fun getCurrentOutputTokenCount(): Int {
        return accumulatedOutputTokenCount
    }

    /**
     * Get the current cached input token count accumulated across the current turn
     * @return The number of cached input tokens used in the current turn
     */
    fun getCurrentCachedInputTokenCount(): Int {
        return accumulatedCachedInputTokenCount
    }

    fun captureCurrentTurnTokenSnapshot(): TurnTokenSnapshot {
        return TurnTokenSnapshot(
            inputTokens = saturatedTokenSum(accumulatedInputTokenCount, currentRequestInputTokenCount),
            outputTokens = saturatedTokenSum(accumulatedOutputTokenCount, currentRequestOutputTokenCount),
            cachedInputTokens =
                saturatedTokenSum(accumulatedCachedInputTokenCount, currentRequestCachedInputTokenCount)
        )
    }

    fun setCurrentTurnTokenCounts(
        inputTokens: Int,
        outputTokens: Int,
        cachedInputTokens: Int = 0
    ) {
        accumulatedInputTokenCount = inputTokens.coerceAtLeast(0)
        accumulatedOutputTokenCount = outputTokens.coerceAtLeast(0)
        accumulatedCachedInputTokenCount = cachedInputTokens.coerceAtLeast(0)
        currentRequestInputTokenCount = 0
        currentRequestOutputTokenCount = 0
        currentRequestCachedInputTokenCount = 0
        _perRequestTokenCounts.value =
            Pair(accumulatedInputTokenCount, accumulatedOutputTokenCount)
        AppLogger.d(
            TAG,
            "Current turn token counts overridden. Input: $accumulatedInputTokenCount, Output: $accumulatedOutputTokenCount, CachedInput: $accumulatedCachedInputTokenCount"
        )
    }

    /** Reset token counters to zero Use this when starting a new conversation */
    fun resetTokenCounters() {
        Companion.resetTokenCounters(context)
    }

    /**
     * 重置指定功能类型或所有功能类型的token计数器
     * @param functionType 功能类型，如果为null则重置所有功能类型
     */
    suspend fun resetTokenCountersForFunction(functionType: FunctionType? = null) {
        Companion.resetTokenCountersForFunction(context, functionType)
    }

    /**
     * 生成对话总结
     * @param messages 要总结的消息列表
     * @return 生成的总结文本
     */
    suspend fun generateSummary(messages: List<Pair<String, String>>): String {
        return generateSummary(messages, null)
    }

    /**
     * 生成对话总结，并且包含上一次的总结内容
     * @param messages 要总结的消息列表
     * @param previousSummary 上一次的总结内容，可以为null
     * @return 生成的总结文本
     */
    suspend fun generateSummary(
            messages: List<Pair<String, String>>,
            previousSummary: String?,
            summaryConfig: ConversationSummaryConfig = ConversationSummaryConfig()
    ): String {
        return generateSummaryFromPromptTurns(messages.toPromptTurns(), previousSummary, summaryConfig)
    }

    suspend fun generateSummaryFromPromptTurns(
            messages: List<PromptTurn>,
            previousSummary: String?,
            summaryConfig: ConversationSummaryConfig = ConversationSummaryConfig()
    ): String {
        // 调用ConversationService中的方法
        return withContext(com.ai.assistance.operit.api.chat.llmprovider.OpenCodeSessionContext(providerSessionId)) {
            conversationService.generateSummaryFromPromptTurns(messages, previousSummary, multiServiceManager, summaryConfig)
        }
    }



    suspend fun generateConversationTitle(
        userText: String,
        attachmentFileNames: List<String> = emptyList(),
        fromRecentConversation: Boolean = false,
    ): String {
        return withContext(com.ai.assistance.operit.api.chat.llmprovider.OpenCodeSessionContext(providerSessionId)) {
            conversationService.generateConversationTitle(
                userText = userText,
                attachmentFileNames = attachmentFileNames,
                multiServiceManager = multiServiceManager,
                fromRecentConversation = fromRecentConversation,
            )
        }
    }

    /**
     * 获取指定功能类型的当前输入token计数
     * @param functionType 功能类型
     * @return 输入token计数
     */
    suspend fun getCurrentInputTokenCountForFunction(functionType: FunctionType): Int {
        return Companion.getCurrentInputTokenCountForFunction(context, functionType)
    }

    /**
     * 获取指定功能类型的当前输出token计数
     * @param functionType 功能类型
     * @return 输出token计数
     */
    suspend fun getCurrentOutputTokenCountForFunction(functionType: FunctionType): Int {
        return Companion.getCurrentOutputTokenCountForFunction(context, functionType)
    }

    private fun resolveToolDisplayName(tool: AITool): String {
        if (tool.name != "package_proxy" && tool.name != "proxy") {
            return tool.name
        }
        val targetToolName = tool.parameters
            .firstOrNull { it.name == "tool_name" }
            ?.value
            ?.trim()
            .orEmpty()
        return if (targetToolName.isNotBlank()) targetToolName else tool.name
    }

    /** Prepare the conversation history with system prompt */
    private suspend fun prepareConversationHistory(
            chatHistory: List<PromptTurn>,
            processedInput: String,
            chatId: String?,
            workspacePath: String?,
            workspaceEnv: String?,
            promptFunctionType: PromptFunctionType,
            customSystemPromptTemplate: String? = null,
            roleCardId: String?,
            enableGroupOrchestrationHint: Boolean,
            groupParticipantNamesText: String? = null,
            proxySenderName: String? = null,
            isSubTask: Boolean = false,
            functionType: FunctionType = FunctionType.CHAT,
            modelConfig: ModelConfigData,
            memorySpaceIdOverride: String? = null,
            additionalSystemPrompt: String? = null,
            dispatchHistoryHooks: (PromptHookContext) -> PromptHookContext =
                PromptHookRegistry::dispatchPromptHistoryHooks,
            dispatchSystemPromptComposeHooks: (PromptHookContext) -> PromptHookContext =
                PromptHookRegistry::dispatchSystemPromptComposeHooks,
            dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext =
                PromptHookRegistry::dispatchToolPromptComposeHooks
    ): List<PromptTurn> {
        // Check if the bounded image-recognition function model is available. This fallback also
        // applies to subtask chat models that do not support images themselves.
        val hasImageRecognition =
            resolveImageRecognitionAvailability(
                isSubTask = isSubTask,
                hasConfiguredBackend = multiServiceManager.hasImageRecognitionConfigured(),
            )
        val hasAudioRecognition = if (isSubTask) false else multiServiceManager.hasAudioRecognitionConfigured()
        val hasVideoRecognition = if (isSubTask) false else multiServiceManager.hasVideoRecognitionConfigured()

        // 获取当前功能类型（通常是聊天模型）的模型配置，用于判断聊天模型是否自带识图能力
        val config = modelConfig
        val useToolCallApi = config.enableToolCall
        val chatModelHasDirectImage = config.enableDirectImageProcessing
        val chatModelHasDirectAudio = config.enableDirectAudioProcessing
        val chatModelHasDirectVideo = config.enableDirectVideoProcessing
        val toolExposureMode = ToolExposureMode.resolve(config.apiProviderType)

        return conversationService.prepareConversationHistory(
                chatHistory,
                processedInput,
                chatId,
                workspacePath,
                workspaceEnv,
                packageManager,
                promptFunctionType,
                customSystemPromptTemplate,
                roleCardId,
                enableGroupOrchestrationHint,
                groupParticipantNamesText,
                proxySenderName,
                hasImageRecognition,
                hasAudioRecognition,
                hasVideoRecognition,
                chatModelHasDirectAudio,
                chatModelHasDirectVideo,
                useToolCallApi,
                chatModelHasDirectImage,
                toolExposureMode,
                memorySpaceIdOverride = memorySpaceIdOverride,
                additionalSystemPrompt = additionalSystemPrompt,
                isSubTask = isSubTask,
                dispatchHistoryHooks = dispatchHistoryHooks,
                dispatchSystemPromptComposeHooks = dispatchSystemPromptComposeHooks,
                dispatchToolPromptComposeHooks = dispatchToolPromptComposeHooks,
        )
    }

    private fun serializePromptHookModelParameters(
        modelParameters: List<com.ai.assistance.operit.data.model.ModelParameter<*>>
    ): List<Map<String, Any?>> {
        return modelParameters.map { parameter ->
            mapOf(
                "id" to parameter.id,
                "name" to parameter.name,
                "apiName" to parameter.apiName,
                "description" to parameter.description,
                "defaultValue" to parameter.defaultValue,
                "currentValue" to parameter.currentValue,
                "isEnabled" to parameter.isEnabled,
                "valueType" to parameter.valueType.name,
                "minValue" to parameter.minValue,
                "maxValue" to parameter.maxValue,
                "category" to parameter.category.name,
                "isCustom" to parameter.isCustom
            )
        }
    }

    private fun serializePromptHookToolPrompts(
        toolPrompts: List<ToolPrompt>?
    ): List<Map<String, Any?>> {
        return toolPrompts.orEmpty().map { tool ->
            mapOf(
                "categoryName" to "",
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.parameters,
                "details" to tool.details,
                "notes" to tool.notes,
                "parametersStructured" to
                    tool.parametersStructured.orEmpty().map { parameter ->
                        mapOf(
                            "name" to parameter.name,
                            "type" to parameter.type,
                            "description" to parameter.description,
                            "required" to parameter.required,
                            "default" to parameter.default
                        )
                    }
            )
        }
    }

    private fun deserializePromptHookToolPrompts(
        toolItems: List<Map<String, Any?>>
    ): List<ToolPrompt> {
        return toolItems.mapNotNull { item ->
            val name = item["name"] as? String ?: return@mapNotNull null
            val description = item["description"] as? String ?: return@mapNotNull null
            val parametersStructured = deserializePromptHookToolParameters(item["parametersStructured"])
            ToolPrompt(
                name = name,
                description = description,
                parameters = item["parameters"] as? String ?: "",
                parametersStructured = parametersStructured.ifEmpty { null },
                details = item["details"] as? String ?: "",
                notes = item["notes"] as? String ?: ""
            )
        }
    }

    private fun deserializePromptHookToolParameters(
        value: Any?
    ): List<ToolParameterSchema> {
        val items = value as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val parameter = item as? Map<*, *> ?: return@mapNotNull null
            val name = parameter["name"] as? String ?: return@mapNotNull null
            val description = parameter["description"] as? String ?: return@mapNotNull null
            ToolParameterSchema(
                name = name,
                type = parameter["type"] as? String ?: "string",
                description = description,
                required = parameter["required"] as? Boolean ?: true,
                default = (parameter["default"] as? String) ?: parameter["default"]?.toString()
            )
        }
    }

    private fun applyToolPromptComposeHooksToAvailableTools(
        availableTools: List<ToolPrompt>,
        chatId: String?,
        functionType: FunctionType,
        promptFunctionType: PromptFunctionType?,
        useEnglish: Boolean
    ): List<ToolPrompt> {
        val hookContext =
            PromptHookRegistry.dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "filter_tool_call_tools",
                    chatId = chatId,
                    functionType = functionType.name,
                    promptFunctionType = promptFunctionType?.name,
                    useEnglish = useEnglish,
                    availableTools = serializePromptHookToolPrompts(availableTools)
                )
            )
        return deserializePromptHookToolPrompts(hookContext.availableTools)
    }

    /** Cancel the current conversation */
    fun cancelConversation() {
        invalidateAllExecutionContexts("cancelConversation")

        // Set conversation inactive
        // isConversationActive.set(false) // This is now per-context, can't set a global one

        // Cancel all underlying AIService streaming instances
        initScope.launch {
            runCatching {
                multiServiceManager.cancelAllStreaming()
            }.onFailure { e ->
                AppLogger.e(TAG, "取消AIService流式输出失败", e)
            }
        }

        // Cancel all tool executions
        cancelAllToolExecutions()

        // Clean up current conversation content
        // roundManager.clearContent() // This is now per-context, can't clear a global one
        AppLogger.d(TAG, "Conversation canceled")

        // Reset input processing state
        _inputProcessingState.value = InputProcessingState.Idle

        // Reset per-request token counts
        _perRequestTokenCounts.value = null
        accumulatedInputTokenCount = 0
        accumulatedOutputTokenCount = 0
        accumulatedCachedInputTokenCount = 0
        currentRequestInputTokenCount = 0
        currentRequestOutputTokenCount = 0
        currentRequestCachedInputTokenCount = 0

        // Clear callback references
        currentResponseCallback = null
        currentCompleteCallback = null

        // 停止AI服务并关闭屏幕常亮
        stopAiService()

        AppLogger.d(TAG, "Conversation cancellation complete")
    }

    /**
     * Cancels this conversation and waits until every tool job has finished its cancellation
     * cleanup. ToolExecutionManager intentionally emits terminal tool results from a
     * NonCancellable block, so callers that persist a partial transcript must not detach it before
     * these jobs have completed.
     */
    suspend fun cancelConversationAndAwait() {
        cancelConversation()
        val jobs = toolExecutionJobs.values.toSet()
        jobs.forEach { job -> job.cancel() }
        jobs.forEach { job -> job.join() }
    }

    /** Cancel all tool executions */
    private fun cancelAllToolExecutions() {
        toolProcessingScope.coroutineContext.cancelChildren()
    }

    /**
     * 获取可用工具列表（用于Tool Call API）
     * 如果模型配置启用了Tool Call，返回工具列表；否则返回null
     */
    private suspend fun getAvailableToolsForFunction(
        functionType: FunctionType,
        chatId: String? = null,
        promptFunctionType: PromptFunctionType? = null,
        roleCardId: String? = null,
        modelConfig: ModelConfigData,
        isSubTask: Boolean = false,
        toolsEnabled: Boolean = true,
        isolatedToolPrompts: List<ToolPrompt>? = null,
    ): List<ToolPrompt>? {
        return try {
            if (!toolsEnabled) {
                AppLogger.d(TAG, "This turn explicitly disables tools")
                return null
            }
            if (isolatedToolPrompts != null) {
                AppLogger.d(
                    TAG,
                    "Using isolated per-turn tools: ${isolatedToolPrompts.map { it.name }}",
                )
                return isolatedToolPrompts.takeIf { it.isNotEmpty() }
            }
            AppLogger.d(
                TAG,
                "准备构建Tool Call工具列表: functionType=${functionType.name}, promptFunctionType=${promptFunctionType?.name}, chatId=${chatId ?: "null"}"
            )
            // 先读取全局工具开关
            val enableTools = apiPreferences.enableToolsFlow.first()
            val toolPromptVisibility = runCatching {
                apiPreferences.toolPromptVisibilityFlow.first()
            }.getOrElse { emptyMap() }
            val roleCardToolAccess = characterCardToolAccessResolver.resolve(
                roleCardId = roleCardId,
                packageManager = packageManager,
                globalToolVisibility = toolPromptVisibility
            )

            if (!enableTools) {
                AppLogger.d(TAG, "全局设置已禁用工具，本次调用不提供任何Tool Call工具")
                return null
            }

            // 获取对应功能类型的模型配置
            val config = modelConfig
            
            // 检查是否启用Tool Call
            if (!config.enableToolCall) {
                return null
            }

            val toolExposureMode = ToolExposureMode.resolve(config.apiProviderType)

            // 获取所有工具分类
            val isEnglish = LocaleUtils.getCurrentLanguage(context) == "en"

            // 后端识图服务是否可用（IMAGE_RECOGNITION 功能），用于 intent-based 视觉模型
            val hasBackendImageRecognition = multiServiceManager.hasImageRecognitionConfigured()

            val hasBackendAudioRecognition = multiServiceManager.hasAudioRecognitionConfigured()
            val hasBackendVideoRecognition = multiServiceManager.hasVideoRecognitionConfigured()

            val safBookmarkNames = runCatching {
                apiPreferences.safBookmarksFlow.first().map { it.name }
            }.getOrElse { emptyList() }

            // 当前功能模型（通常是聊天模型）是否支持直接看图
            val chatModelHasDirectImage = config.enableDirectImageProcessing

            val chatModelHasDirectAudio = config.enableDirectAudioProcessing
            val chatModelHasDirectVideo = config.enableDirectVideoProcessing

            val selectedTools = if (toolExposureMode == ToolExposureMode.CLI) {
                CliToolModeSupport.buildCliPublicToolPrompts(isEnglish).toMutableList()
            } else {
                val categories = if (isEnglish) {
                    SystemToolPrompts.getAIAllCategoriesEn(
                        hasBackendImageRecognition = hasBackendImageRecognition,
                        chatModelHasDirectImage = chatModelHasDirectImage,
                        hasBackendAudioRecognition = hasBackendAudioRecognition,
                        hasBackendVideoRecognition = hasBackendVideoRecognition,
                        chatModelHasDirectAudio = chatModelHasDirectAudio,
                        chatModelHasDirectVideo = chatModelHasDirectVideo,
                        safBookmarkNames = safBookmarkNames,
                        includeSubagentTools = !isSubTask || com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(context).isAgent(chatId)
                    )
                } else {
                    SystemToolPrompts.getAIAllCategoriesCn(
                        hasBackendImageRecognition = hasBackendImageRecognition,
                        chatModelHasDirectImage = chatModelHasDirectImage,
                        hasBackendAudioRecognition = hasBackendAudioRecognition,
                        hasBackendVideoRecognition = hasBackendVideoRecognition,
                        chatModelHasDirectAudio = chatModelHasDirectAudio,
                        chatModelHasDirectVideo = chatModelHasDirectVideo,
                        safBookmarkNames = safBookmarkNames,
                        includeSubagentTools = !isSubTask || com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(context).isAgent(chatId)
                    )
                }

                categories.flatMap { it.tools }.toMutableList().apply {
                    val collaborationVisibility =
                        com.ai.assistance.operit.core.agent.collaboration.CollaborationToolPolicy.visibility(context, chatId, isSubTask)
                    retainAll { tool ->
                        roleCardToolAccess.isBuiltinToolAllowed(tool.name) && collaborationVisibility[tool.name] != false
                    }
                }
            }

            if (toolExposureMode == ToolExposureMode.CLI) {
                AppLogger.d(
                    TAG,
                    "CLI Tool Mode已启用，提供 ${selectedTools.size} 个工具 (provider=${config.apiProviderType})"
                )
            } else if (config.enableToolCall) {
                selectedTools.add(
                    ToolPrompt(
                        name = "package_proxy",
                        description = "Proxy tool for package tools activated by use_package.",
                        parametersStructured = listOf(
                            ToolParameterSchema(
                                name = "tool_name",
                                type = "string",
                                description = "Target tool name from an activated package (for example: packageName:toolName)",
                                required = true
                            ),
                            ToolParameterSchema(
                                name = "params",
                                type = "object",
                                description = "JSON object of parameters to forward to the target tool",
                                required = true
                            )
                        )
                    )
                )
            }

            val hookedTools = applyToolPromptComposeHooksToAvailableTools(
                availableTools = selectedTools,
                chatId = chatId,
                functionType = functionType,
                promptFunctionType = promptFunctionType,
                useEnglish = isEnglish
            )

            if (hookedTools.isEmpty()) {
                AppLogger.d(TAG, "根据当前工具开关，未选择任何Tool Call工具")
                return null
            }

            AppLogger.d(
                TAG,
                "Tool Call已启用，提供 ${hookedTools.size} 个工具 (base=${selectedTools.size}, enableTools=$enableTools, visibleToolOverrides=${toolPromptVisibility.size}, roleCardCustomTools=${roleCardToolAccess.customEnabled})"
            )
            hookedTools
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取工具列表失败", e)
            null
        }
    }

    // --- Service Lifecycle Management ---

    /** 启动或更新前台服务为“AI 正在运行”状态，以保持应用活跃 */
    private fun startAiService(characterName: String? = null, avatarUri: String? = null) {
        val refCount = FOREGROUND_REF_COUNT.incrementAndGet()
        val appInForeground = ActivityLifecycleManager.getCurrentActivity() != null
        val alwaysListeningEnabled = runCatching {
            runBlocking { WakeWordPreferences(context).alwaysListeningEnabledFlow.first() }
        }.getOrDefault(false)
        val externalHttpEnabled = runCatching {
            runBlocking { ExternalHttpApiPreferences.getInstance(context).enabledFlow.first() }
        }.getOrDefault(false)
        if (!appInForeground &&
            !AIForegroundService.isRunning.get() &&
            !alwaysListeningEnabled &&
            !externalHttpEnabled
        ) {
            AppLogger.d(TAG, "应用不在前台，跳过启动 AIForegroundService")
            return
        }
        try {
            val updateIntent = Intent(context, AIForegroundService::class.java).apply {
                putExtra(AIForegroundService.EXTRA_STATE, AIForegroundService.STATE_RUNNING)
                if (characterName != null) {
                    putExtra(AIForegroundService.EXTRA_CHARACTER_NAME, characterName)
                }
                if (avatarUri != null) {
                    putExtra(AIForegroundService.EXTRA_AVATAR_URI, avatarUri)
                }
            }
            context.startService(updateIntent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "更新AI前台服务为运行中状态失败: ${e.message}", e)
        }

        if (refCount == 1) {
            ActivityLifecycleManager.checkAndApplyKeepScreenOn(true)
        }
    }

    private fun notifyReplyCompleted(
        chatId: String?,
        characterName: String? = null,
        avatarUri: String? = null,
        notifyReplyOverride: Boolean? = null
    ) {
        AIForegroundService.notifyReplyCompleted(
            context = context,
            chatId = chatId,
            characterName = characterName,
            rawReplyContent = lastReplyContent,
            avatarUri = avatarUri,
            notifyReplyOverride = notifyReplyOverride
        )
    }

    /** 将前台服务更新为“空闲/已完成”状态，但不真正停止服务 */
    private fun stopAiService(characterName: String? = null, avatarUri: String? = null) {
        val remaining = run {
            var remainingValue = -1
            while (true) {
                val current = FOREGROUND_REF_COUNT.get()
                if (current <= 0) {
                    remainingValue = -1
                    break
                }
                val next = current - 1
                if (FOREGROUND_REF_COUNT.compareAndSet(current, next)) {
                    remainingValue = next
                    break
                }
            }
            remainingValue
        }
        if (remaining < 0) return
        if (remaining > 0) return
         if (AIForegroundService.isRunning.get()) {
             AppLogger.d(TAG, "更新AI前台服务为闲置状态...")

            try {
                val stopIntent = Intent(context, AIForegroundService::class.java).apply {
                    putExtra(AIForegroundService.EXTRA_CHARACTER_NAME, characterName)
                    putExtra(AIForegroundService.EXTRA_AVATAR_URI, avatarUri)
                    putExtra(AIForegroundService.EXTRA_STATE, AIForegroundService.STATE_IDLE)
                }

                AppLogger.d(TAG, "传递闲置状态 - 角色: $characterName, 头像: $avatarUri")

                // 仅发送更新，不再真正停止前台服务
                context.startService(stopIntent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新AI前台服务为闲置状态失败: ${e.message}", e)
            }
        } else {
            AppLogger.d(TAG, "AI前台服务未在运行，无需更新闲置状态。")
        }

        // 使用管理器来恢复屏幕常亮设置
        ActivityLifecycleManager.checkAndApplyKeepScreenOn(false)
    }

    /**
     * 处理文件绑定操作（实例方法）
     * @param originalContent 原始文件内容
     * @param aiGeneratedCode AI生成的代码（包含"//existing code"标记）
     * @return 混合后的文件内容
     */
    suspend fun applyFileBinding(
            originalContent: String,
            aiGeneratedCode: String
    ): Pair<String, String> {
        return fileBindingService.processFileBinding(
                originalContent,
                aiGeneratedCode
        )
    }

    /**
     * 翻译文本功能
     * @param text 要翻译的文本
     * @return 翻译后的文本
     */
    suspend fun translateText(text: String): String {
        return conversationService.translateText(text, multiServiceManager)
    }

    /**
     * 自动生成工具包描述
     * @param pluginName 工具包名称
     * @param toolDescriptions 工具描述列表
     * @return 生成的工具包描述
     */
    suspend fun generatePackageDescription(
        pluginName: String,
        toolDescriptions: List<String>
    ): String {
        return conversationService.generatePackageDescription(pluginName, toolDescriptions, multiServiceManager)
    }


    /**
     * Manually saves the current conversation to the problem library.
     * @param conversationHistory The history of the conversation to save.
     * @param lastContent The content of the last message in the conversation.
     */
    fun saveConversationToMemoryAsync(
        conversationHistory: List<Pair<String, String>>,
        lastContent: String,
        memorySpaceIdOverride: String? = null,
        onSuccess: (suspend () -> Unit)? = null,
        onError: (suspend (Exception) -> Unit)? = null
    ) {
        AppLogger.d(TAG, "手动触发记忆更新...")
        toolProcessingScope.launch {
            try {
                val memoryService = multiServiceManager.getServiceForFunction(FunctionType.MEMORY)
                com.ai.assistance.operit.api.chat.library.MemoryLibrary.saveMemoryAsync(
                    context = context,
                    toolHandler = toolHandler,
                    conversationHistory = conversationHistory,
                    content = lastContent,
                    aiService = memoryService,
                    profileIdOverride = memorySpaceIdOverride,
                    onSuccess = {
                        AppLogger.d(TAG, "手动记忆更新成功")
                        onSuccess?.invoke()
                    },
                    onError = { e ->
                        AppLogger.e(TAG, "手动记忆更新失败", e)
                        onError?.invoke(e)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "手动记忆更新初始化失败", e)
                onError?.invoke(e)
            }
        }
    }

    /**
     * 使用识图模型分析图片
     * @param imagePath 图片路径
     * @param userIntent 用户意图，例如"这个图片里面有什么"、"图片的题目公式是什么"等
     * @return AI分析结果
     */
    suspend fun analyzeImageWithIntent(imagePath: String, userIntent: String?): String {
        return conversationService.analyzeImageWithIntent(imagePath, userIntent, multiServiceManager)
    }

    suspend fun analyzeAudioWithIntent(audioPath: String, userIntent: String?): String {
        return conversationService.analyzeAudioWithIntent(audioPath, userIntent, multiServiceManager)
    }

    suspend fun analyzeVideoWithIntent(videoPath: String, userIntent: String?): String {
        return conversationService.analyzeVideoWithIntent(videoPath, userIntent, multiServiceManager)
    }

    /**
     * The automatic review's calls are sub-tasks too, and they are the ones whose prompt reuse is
     * worth watching, so they are counted apart from the other sub-agents.
     */
    private fun tokenStatsCategoryFor(
        functionType: FunctionType,
        isSubTask: Boolean,
    ): TokenStatCategory =
        when {
            functionType == FunctionType.PERMISSION_REVIEWER -> TokenStatCategory.PERMISSION_REVIEWER
            isSubTask -> TokenStatCategory.SUBAGENT
            else -> TokenStatCategory.CHAT
        }
}
