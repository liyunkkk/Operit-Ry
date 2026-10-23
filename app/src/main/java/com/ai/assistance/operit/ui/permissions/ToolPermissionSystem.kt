package com.ai.assistance.operit.ui.permissions

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.core.agent.collaboration.CollaborationTools
import com.ai.assistance.operit.core.tools.PermissionReviewInternalTools
import com.ai.assistance.operit.data.model.AITool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

// Define DataStore
private val Context.toolPermissionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_permissions")

internal object PermissionCircuitBreakerNoticeState {
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount = _pendingCount.asStateFlow()

    fun enqueue() {
        _pendingCount.update { count -> count + 1 }
    }

    fun clear() {
        _pendingCount.value = 0
    }
}

/**
 * Permission levels for tool operations
 */
enum class PermissionLevel {
    ALLOW,       // Allow automatically without asking
    AUTO_REVIEW, // Allow proven workspace operations, review everything else
    ASK,         // Always ask
    FORBID;      // Never allow

    companion object {
        fun fromString(value: String?): PermissionLevel {
            return when (value) {
                "ALLOW" -> ALLOW
                "AUTO_REVIEW" -> AUTO_REVIEW
                // The three former middle levels were merged into AUTO_REVIEW. Preferences stored
                // by an older build are migrated here instead of silently falling back to ASK.
                "WORKSPACE" -> AUTO_REVIEW
                "WORKSPACE_REVIEWER" -> AUTO_REVIEW
                "REVIEWER" -> AUTO_REVIEW
                "CAUTION" -> ASK
                "ASK" -> ASK
                "FORBID" -> FORBID
                else -> ASK  // Default to ASK
            }
        }
    }
}

internal enum class ToolPermissionDenialSource {
    SETTINGS,
    USER,
    AUTOMATIC_REVIEW,
    AUTOMATIC_REVIEW_CANCELLED,
    AUTOMATIC_REVIEW_FAILED,
}

internal sealed interface ToolPermissionDecision {
    data object Allowed : ToolPermissionDecision

    data class Denied(
        val source: ToolPermissionDenialSource,
        val rejection: String,
        val interruptTurn: Boolean = false,
    ) : ToolPermissionDecision
}

/**
 * Centralized tool permission system that manages both permission storage and checking
 */
class ToolPermissionSystem private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ToolPermissionSystem"
        private const val PERMISSION_REQUEST_TIMEOUT_MS = 60000L // 60 seconds timeout
        /**
         * The fixed English note a reuse decision records on its review event. It states where the
         * answer came from rather than anything about the call, so the review UI never quotes it
         * back to the reader.
         */
        internal const val FAST_REVIEW_RATIONALE =
            "Answered from the asynchronous risk score of the recent course of action."
        /**
         * The fixed English note a call that the circuit breaker skipped records. It says why the
         * call never reached the reviewer, not anything about the call itself, so the review UI shows
         * its own wording instead of quoting this back to the reader.
         */
        internal const val REVIEW_SKIPPED_RATIONALE =
            "Skipped because repeated denials stopped this model turn."
        internal const val FAST_REVIEW_RESOLUTION_SOURCE = "fast_review_low_risk_score"
        internal const val MANUAL_OR_SETTING_ALLOW_RESOLUTION_SOURCE = "manual_or_setting_allow"
        internal const val MANUAL_OR_SETTING_DENY_RESOLUTION_SOURCE = "manual_or_setting_deny"
        /** The level changed while the reviewer was still working, so the new level decided it. */
        internal const val SETTINGS_REFRESHED_ALLOW_RESOLUTION_SOURCE = "settings_refreshed_allow"
        internal const val SETTINGS_REFRESHED_DENY_RESOLUTION_SOURCE = "settings_refreshed_deny"
        
        // DataStore keys
        private val MASTER_SWITCH = stringPreferencesKey("master_switch")
        
        // Default permission setting. A fresh install starts on the stop the slider itself names as
        // its default, so the stored level and the reuse store cannot disagree about it. It is
        // internal so the test can pin the stored value to that stop.
        internal val DEFAULT_MASTER_SWITCH = ToolPermissionStop.DEFAULT.level.name
        
        @Volatile
        private var INSTANCE: ToolPermissionSystem? = null
        
        fun getInstance(context: Context): ToolPermissionSystem {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ToolPermissionSystem(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // 工具权限存储：使用 "tool_permission_<tool_name>" 作为key
    private fun toolPermissionKey(toolName: String) = stringPreferencesKey("tool_permission_$toolName")

    val toolPermissionOverridesFlow: Flow<Map<String, PermissionLevel>> =
        context.toolPermissionsDataStore.data.map { preferences ->
            preferences.asMap().entries.mapNotNull { (key, value) ->
                if (key.name.startsWith("tool_permission_") && value is String)
                    key.name.removePrefix("tool_permission_") to PermissionLevel.fromString(value)
                else null
            }.toMap()
        }
    
    // Permission request management
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionRequestOverlay = PermissionRequestOverlay(context)
    private val circuitBreakerWarningOverlay = PermissionRequestOverlay(context)
    private val reviewPolicyStore = PermissionReviewPolicyStore(context)
    private var currentPermissionCallback: ((PermissionRequestResult) -> Unit)? = null
    private var permissionRequestInfo: Pair<AITool, String>? = null
    private var currentRequestToken: Long = -1L

    // Request token generator
    private val requestTokenGenerator = AtomicLong(0)

    // Mutex for serializing ASK requests
    private val askMutex = Mutex()
    
    // 存储当前颜色方案
    private var currentColorScheme: ColorScheme? = null
    
    /**
     * 设置当前使用的颜色方案
     */
    fun setColorScheme(colorScheme: ColorScheme?) {
        this.currentColorScheme = colorScheme
        permissionRequestOverlay.setColorScheme(colorScheme)
        circuitBreakerWarningOverlay.setColorScheme(colorScheme)
    }

    internal fun showAutomaticReviewCircuitBreakerWarning() {
        mainHandler.post {
            circuitBreakerWarningOverlay.showCircuitBreakerWarning(
                onUnavailable = PermissionCircuitBreakerNoticeState::enqueue,
            )
        }
    }
    
    // Permission request state flow
    private val _permissionRequestState = MutableStateFlow<Pair<AITool, String>?>(null)
    val permissionRequestState = _permissionRequestState.asStateFlow()

    private val _pendingPermissionRequestCount = MutableStateFlow(0)
    private val pendingPermissionRequestCount = _pendingPermissionRequestCount.asStateFlow()
    
    /**
     * Get permission level flow for a specific tool
     * If no permission is set for the tool, returns ASK as default
     */
    fun getToolPermissionFlow(toolName: String): Flow<PermissionLevel> {
        return context.toolPermissionsDataStore.data.map { preferences ->
            val key = toolPermissionKey(toolName)
            PermissionLevel.fromString(preferences[key] ?: PermissionLevel.ASK.name)
        }
    }
    
    // Registry of operation descriptions by tool name
    private val operationDescriptionRegistry =
        java.util.concurrent.ConcurrentHashMap<String, (AITool) -> String>()
    
    /**
     * Register a description generator for a tool
     */
    fun registerOperationDescription(toolName: String, descriptionGenerator: (AITool) -> String) {
        operationDescriptionRegistry[toolName] = descriptionGenerator
    }
    
    /**
     * Save permission level settings
     */
    suspend fun saveMasterSwitch(level: PermissionLevel) {
        context.toolPermissionsDataStore.edit { preferences ->
            preferences[MASTER_SWITCH] = level.name
        }
    }

    /**
     * The permission choice as the settings slider and the chat menus show it: the stored level and
     * the reuse level that comes with it, in one ordered value.
     */
    val permissionStopFlow: Flow<ToolPermissionStop> =
        combine(
            context.toolPermissionsDataStore.data.map { preferences ->
                val raw = preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH
                PermissionLevel.fromString(raw) to raw
            },
            reviewPolicyStore.reviewModeFlow,
        ) { (level, raw), mode -> resolvePermissionStop(level, raw, mode) }

    /**
     * Applies one stop of the permission slider.
     *
     * The reuse level is written first on purpose. While the two writes are in flight the combined
     * value can only be the previous stop or the chosen one: going ALLOW to AUTO_REVIEW_STRICT, the
     * middle state is still ALLOW, whereas the other order would briefly show AUTO_REVIEW_FAST, a
     * stop the user never picked and a looser one than the choice. Calls reviewed during that window
     * still run under the previous permission level, which this same order keeps until the end.
     */
    suspend fun savePermissionStop(stop: ToolPermissionStop) {
        stop.reviewMode?.let { mode -> reviewPolicyStore.saveReviewMode(mode) }
        saveMasterSwitch(stop.level)
    }
    
    /**
     * Save permission level for a specific tool
     */
    suspend fun saveToolPermission(toolName: String, level: PermissionLevel) {
        context.toolPermissionsDataStore.edit { preferences ->
            val key = toolPermissionKey(toolName)
            preferences[key] = level.name
        }
    }
    
    suspend fun clearToolPermission(toolName: String) {
        context.toolPermissionsDataStore.edit { preferences ->
            val key = toolPermissionKey(toolName)
            preferences.remove(key)
        }
    }
    
    /**
     * Save permission levels for multiple tools at once
     */
    suspend fun saveToolPermissions(toolPermissions: Map<String, PermissionLevel>) {
        context.toolPermissionsDataStore.edit { preferences ->
            toolPermissions.forEach { (toolName, level) ->
                val key = toolPermissionKey(toolName)
                preferences[key] = level.name
            }
        }
    }
    
    /**
     * Get permission level for a specific tool (synchronous, for one-time reads)
     * If no permission is set for the tool, returns ASK as default
     */
    suspend fun getToolPermission(toolName: String): PermissionLevel {
        val preferences = context.toolPermissionsDataStore.data.first()
        val key = toolPermissionKey(toolName)
        return PermissionLevel.fromString(preferences[key] ?: PermissionLevel.ASK.name)
    }
    
    suspend fun getToolPermissionOverride(toolName: String): PermissionLevel? {
        val preferences = context.toolPermissionsDataStore.data.first()
        val key = toolPermissionKey(toolName)
        val stored = preferences[key]
        return stored?.let { PermissionLevel.fromString(it) }
    }
    
    /**
     * Get human-readable description of an operation
     */
    fun getOperationDescription(tool: AITool): String {
        return operationDescriptionRegistry[tool.name]?.invoke(tool) ?: context.getString(R.string.tool_permission_operation, tool.name)
    }
    
    /**
     * Check if a tool is allowed to execute
     */
    internal suspend fun checkToolPermission(
        tool: AITool,
        conversationLabel: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        callerChatId: String? = null,
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
        timingScopeId: String? = null,
        targetId: String? = null,
        invocationIndex: Int = -1,
        batchPosition: Int = 1,
        batchSize: Int = 1,
        deferCircuitBreaker: Boolean = false,
        liveAssistantContent: CharSequence? = null,
    ): ToolPermissionDecision {
        AppLogger.d(TAG, "Starting permission check: ${tool.name}")

        val duplicateParameterNames =
            findDuplicateToolParameterNames(tool)
        if (duplicateParameterNames.isNotEmpty()) {
            return permissionDeniedBySettings(
                "Duplicate parameter names are ambiguous: " +
                    duplicateParameterNames.sorted().joinToString(", ")
            )
        }

        val reviewContext =
            ToolPermissionReviewContext(
                callerChatId = callerChatId,
                conversationLabel = conversationLabel,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                timingScopeId = timingScopeId,
                targetId = targetId,
                invocationIndex = invocationIndex,
                batchPosition = batchPosition,
                batchSize = batchSize,
                deferCircuitBreaker = deferCircuitBreaker,
                liveAssistantContent = liveAssistantContent,
            )

        val effectiveLevel = getEffectivePermissionLevel(tool.name)
        val currentRoute = resolveCurrentPermissionRoute(tool, reviewContext)
        if (
            !callerChatId.isNullOrBlank() &&
                currentRoute == PermissionRoute.REVIEWER &&
                PermissionReviewCircuitBreaker.isInterrupted(callerChatId, timingScopeId)
        ) {
            PermissionReviewEventRepository.initialize(context)
            val skippedReviewId = PermissionReviewInspectionRegistry.newReviewId()
            val skippedAction =
                PermissionReviewAction.fromTool(
                    tool = tool,
                    operationDescription = getOperationDescription(tool),
                    reviewContext = reviewContext,
                    targetId = targetId ?: skippedReviewId,
                )
            val now = System.currentTimeMillis()
            PermissionReviewEventRepository.publish(
                PermissionReviewEvent(
                    id = skippedReviewId,
                    parentChatId = callerChatId,
                    timingScopeId = timingScopeId,
                    invocationIndex = invocationIndex,
                    batchPosition = batchPosition,
                    batchSize = batchSize,
                    action = skippedAction,
                    actionFingerprint = skippedAction.fingerprint(),
                    status = PermissionReviewStatus.ABORTED,
                    startedAt = now,
                    completedAt = now,
                    rationale = REVIEW_SKIPPED_RATIONALE,
                )
            )
            return permissionDeniedByRepeatedDenials()
        }

        return evaluatePermissionLevel(
            level = effectiveLevel,
            tool = tool,
            reviewContext = reviewContext,
            onAsk = { requestPermission(tool, reviewContext) },
        )
    }

    suspend fun getEffectivePermissionLevel(toolName: String): PermissionLevel {
        val preferences = context.toolPermissionsDataStore.data.first()
        val masterSwitch = PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH)
        val key = toolPermissionKey(toolName)
        val overrideLevel =
            preferences[key]?.let { PermissionLevel.fromString(it) }
                ?: defaultPermissionLevelFor(toolName, masterSwitch)
        return resolveEffectivePermissionLevel(masterSwitch, overrideLevel)
    }
    
    /**
     * Request permission from the user to execute a tool.
     * ASK requests are serialized via askMutex.
     */
    private suspend fun requestPermission(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
    ): ToolPermissionDecision {
        _pendingPermissionRequestCount.update { it + 1 }
        try {
            while (true) {
                when (resolveCurrentPermissionRoute(tool, reviewContext)) {
                    PermissionRoute.ALLOW -> return ToolPermissionDecision.Allowed
                    PermissionRoute.FORBID -> return permissionDeniedBySettings()
                    PermissionRoute.REVIEWER ->
                        return reviewPermission(
                            tool = tool,
                            reviewContext = reviewContext,
                            pendingRequestAlreadyCounted = true,
                        )
                    PermissionRoute.ASK -> {
                        val lockedResult: ToolPermissionDecision? =
                            askMutex.withLock {
                                // Settings may have changed while this request waited in the queue.
                                when (resolveCurrentPermissionRoute(tool, reviewContext)) {
                                    PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
                                    PermissionRoute.FORBID -> permissionDeniedBySettings()
                                    PermissionRoute.ASK ->
                                        if (
                                            requestPermissionInternal(
                                                tool,
                                                reviewContext.conversationLabel,
                                            )
                                        ) {
                                            ToolPermissionDecision.Allowed
                                        } else {
                                            invalidateStoredRiskScores(reviewContext)
                                            permissionDeniedByUser()
                                        }
                                    PermissionRoute.REVIEWER -> null
                                }
                            }
                        if (lockedResult != null) return lockedResult
                    }
                }
            }
        } finally {
            _pendingPermissionRequestCount.update { count -> (count - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun reviewPermission(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
        pendingRequestAlreadyCounted: Boolean = false,
    ): ToolPermissionDecision {
        // Only the fast level reads a stored score, so the strict level keeps the previous behavior
        // exactly and runs the blocking reviewer for every call.
        val fastPathDecision = claimFastPathApproval(tool, reviewContext)
        val decision = fastPathDecision ?: try {
                AgentToolPermissionReviewer.getInstance(context).review(
                    tool = tool,
                    operationDescription = getOperationDescription(tool),
                    reviewContext = reviewContext,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Include setup/configuration failures as well as errors during the model request.
                // Raw exception messages may contain provider credentials.
                AppLogger.e(TAG, "Permission review could not complete", error)
                val failed = PermissionReviewResponsePolicy.failed(
                    reason = "The approval reviewer could not complete (${error.javaClass.simpleName}).",
                    failureKind = PermissionReviewFailureKind.REVIEWER_ERROR,
                )
                reviewContext.callerChatId?.let { chatId ->
                    PermissionReviewContinuationStore.forget(chatId)
                    PermissionReviewEventRepository.findForInvocation(
                        chatId, reviewContext.timingScopeId, reviewContext.invocationIndex,
                    )?.takeIf { it.status == PermissionReviewStatus.IN_PROGRESS }?.let { event ->
                        PermissionReviewEventRepository.update(event.id) {
                            it.copy(
                                status = PermissionReviewStatus.FAILED,
                                completedAt = System.currentTimeMillis(),
                                failureKind = failed.failureKind,
                                rationale = failed.rationale,
                            )
                        }
                    }
                }
                failed
            }
        if (fastPathDecision == null) {
            AppLogger.i(
                TAG,
                "Independent permission review completed: tool=${tool.name}, decision=${decision.outcome}, " +
                    "risk=${decision.riskLevel}, authorization=${decision.userAuthorization}, " +
                    "failure=${decision.failureKind}"
            )
        }
        if (decision.failureKind != null) {
            // No verdict was reached: leave the action unexecuted and let the caller decide
            // whether to retry. A service failure is neither a user refusal nor a review denial.
            invalidateStoredRiskScores(reviewContext)
            return permissionBlockedByReviewFailure(decision.failureKind, decision.rationale)
        }
        val refreshedDecision =
            resolveReviewDecisionAfterSettingsRefresh(
                approvalGranted = decision.outcome == PermissionReviewOutcome.ALLOW,
                latestRoute = resolveCurrentPermissionRoute(tool, reviewContext),
                reviewerRationale = decision.rationale,
            )
        if (!reviewContext.callerChatId.isNullOrBlank()) {
            // Settings can change while the reviewer turn is in flight. The event was finalized
            // with the reviewer's own result; if the refreshed route overrides it, record the
            // outcome that was actually enforced so the UI and audit history do not report the
            // opposite of what happened.
            PermissionReviewEventRepository.findForInvocation(
                reviewContext.callerChatId,
                reviewContext.timingScopeId,
                reviewContext.invocationIndex,
            )?.let { event ->
                val enforcedStatus =
                    if (refreshedDecision != null) {
                        reviewEventStatusForEnforcedDecision(refreshedDecision)
                    } else {
                        event.status
                    }
                if (enforcedStatus != event.status) {
                    PermissionReviewEventRepository.update(event.id) { current ->
                        current.copy(
                            status = enforcedStatus,
                            resolutionSource =
                                if (refreshedDecision is ToolPermissionDecision.Allowed) {
                                    SETTINGS_REFRESHED_ALLOW_RESOLUTION_SOURCE
                                } else {
                                    SETTINGS_REFRESHED_DENY_RESOLUTION_SOURCE
                                }
                        )
                    }
                }
            }
        }
        if (!reviewContext.deferCircuitBreaker &&
            !reviewContext.callerChatId.isNullOrBlank()
        ) {
            if (
                refreshedDecision is ToolPermissionDecision.Denied &&
                    refreshedDecision.source == ToolPermissionDenialSource.AUTOMATIC_REVIEW
            ) {
                val circuit =
                    PermissionReviewCircuitBreaker.recordDenial(
                        reviewContext.callerChatId,
                        reviewContext.timingScopeId,
                    )
                return refreshedDecision.copy(interruptTurn = circuit.interruptTurn)
            }
            if (refreshedDecision is ToolPermissionDecision.Allowed) {
                // A stored score is not a fresh judgement of what the model is doing right now, so
                // it must not clear the repeated-denial circuit breaker.
                if (fastPathDecision == null) {
                    PermissionReviewCircuitBreaker.recordNonDenial(
                        reviewContext.callerChatId,
                        reviewContext.timingScopeId,
                    )
                }
            }
        }
        if (refreshedDecision is ToolPermissionDecision.Denied) {
            // A refusal changes what the agent's authorization means for everything that follows,
            // so a stored low-risk score must not answer the next calls.
            invalidateStoredRiskScores(reviewContext)
        }
        return refreshedDecision
            ?: requestManualPermission(
                tool = tool,
                reviewContext = reviewContext,
                pendingRequestAlreadyCounted = pendingRequestAlreadyCounted,
            )
    }

    /**
     * The reuse level actually in force. A level an older build wrote predates the two automatic-review
     * stops and never answered a call from a stored score, so it stays strict until the user picks one
     * of the stops: that slider writes the level and the reuse level together.
     */
    private suspend fun effectiveReviewMode(): PermissionReviewMode =
        if (isLegacyAutoReviewLevel(context.toolPermissionsDataStore.data.first()[MASTER_SWITCH])) {
            PermissionReviewMode.STRICT
        } else {
            reviewPolicyStore.getReviewMode()
        }

    /**
     * Answers this call from the asynchronous risk score when the fast level produced a recent
     * low-risk verdict under the current authorization.
     *
     * The synthetic decision is deliberately shaped like a reviewer approval so the shared
     * reconciliation below still applies: if the level changed to ASK or FORBID while the score was
     * stored, that change wins and the call is prompted or denied instead of answered from the
     * score. Anything else — a high-risk verdict, a stale or missing score, a failed classifier, or
     * a changed authorization — leaves the call to the blocking reviewer, so the score can only ever
     * remove a review, never widen a permission.
     */
    private suspend fun claimFastPathApproval(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
    ): PermissionReviewDecision? {
        if (effectiveReviewMode() != PermissionReviewMode.FAST) return null
        val deferral =
            PermissionRiskScorer.getInstance(context)
                .resolveFastPath(
                    callerChatId = reviewContext.callerChatId,
                    turnScopeId = reviewContext.timingScopeId,
                )
        if (deferral != null) {
            AppLogger.d(
                TAG,
                "Automatic review deferred to the blocking reviewer: tool=${tool.name}, reason=$deferral"
            )
            return null
        }
        AppLogger.i(TAG, "Fast automatic review answered from a recent low-risk score: tool=${tool.name}")
        publishFastPathApprovalEvent(tool, reviewContext)
        return PermissionReviewDecision(
            outcome = PermissionReviewOutcome.ALLOW,
            riskLevel = PermissionReviewRiskLevel.LOW,
            userAuthorization = PermissionReviewAuthorization.UNKNOWN,
            rationale = FAST_REVIEW_RATIONALE,
        )
    }

    /**
     * Records the reuse so the review history explains why the call was allowed. The event is
     * published as already approved with its own resolution source; the reconciliation below only
     * rewrites it when a settings change enforced a different outcome.
     */
    private fun publishFastPathApprovalEvent(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
    ) {
        val chatId = reviewContext.callerChatId?.trim().orEmpty()
        if (chatId.isEmpty()) return
        PermissionReviewEventRepository.initialize(context)
        val reviewId = PermissionReviewInspectionRegistry.newReviewId()
        val action =
            PermissionReviewAction.fromTool(
                tool = tool,
                operationDescription = getOperationDescription(tool),
                reviewContext = reviewContext,
                targetId = reviewContext.targetId ?: reviewId,
            )
        val now = System.currentTimeMillis()
        PermissionReviewEventRepository.publish(
            PermissionReviewEvent(
                id = reviewId,
                parentChatId = chatId,
                timingScopeId = reviewContext.timingScopeId,
                invocationIndex = reviewContext.invocationIndex,
                batchPosition = reviewContext.batchPosition,
                batchSize = reviewContext.batchSize,
                action = action,
                actionFingerprint = action.fingerprint(),
                status = PermissionReviewStatus.APPROVED,
                startedAt = now,
                completedAt = now,
                riskLevel = PermissionReviewRiskLevel.LOW,
                rationale = FAST_REVIEW_RATIONALE,
                resolutionSource = FAST_REVIEW_RESOLUTION_SOURCE,
            )
        )
    }

    private suspend fun requestManualPermission(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
        pendingRequestAlreadyCounted: Boolean,
    ): ToolPermissionDecision {
        if (!pendingRequestAlreadyCounted) {
            _pendingPermissionRequestCount.update { it + 1 }
        }
        try {
            val manualDecision = askMutex.withLock {
                when (resolveCurrentPermissionRoute(tool, reviewContext)) {
                    PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
                    PermissionRoute.FORBID -> permissionDeniedBySettings()
                    else ->
                        if (
                            requestPermissionInternal(
                                tool = tool,
                                conversationLabel = reviewContext.conversationLabel,
                            )
                        ) {
                            ToolPermissionDecision.Allowed
                        } else {
                            invalidateStoredRiskScores(reviewContext)
                            permissionDeniedByUser()
                        }
                }
            }
            if (!reviewContext.callerChatId.isNullOrBlank()) {
                PermissionReviewEventRepository.findForInvocation(
                    reviewContext.callerChatId,
                    reviewContext.timingScopeId,
                    reviewContext.invocationIndex,
                )?.let { event ->
                    PermissionReviewEventRepository.update(event.id) { current ->
                        // The reviewer may have concluded before settings changed to ASK; the
                        // manual or setting decision below is what
                        // was actually enforced, so the event must not keep the opposite status.
                        current.copy(
                            status = reviewEventStatusForEnforcedDecision(manualDecision),
                            resolutionSource =
                                if (manualDecision is ToolPermissionDecision.Allowed) {
                                    MANUAL_OR_SETTING_ALLOW_RESOLUTION_SOURCE
                                } else {
                                    MANUAL_OR_SETTING_DENY_RESOLUTION_SOURCE
                                }
                        )
                    }
                }
            }
            return manualDecision
        } finally {
            if (!pendingRequestAlreadyCounted) {
                _pendingPermissionRequestCount.update { count -> (count - 1).coerceAtLeast(0) }
            }
        }
    }

    private suspend fun resolveCurrentPermissionRoute(
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
    ): PermissionRoute {
        val level = getEffectivePermissionLevel(tool.name)
        val workspaceApproved =
            level == PermissionLevel.AUTO_REVIEW &&
                WorkspaceToolPermissionPolicy.isAutoApproved(
                    context = context,
                    tool = tool,
                    workspacePath = reviewContext.workspacePath,
                    workspaceEnv = reviewContext.workspaceEnv,
                    callerChatId = reviewContext.callerChatId,
                )
        return resolvePermissionRoute(level, workspaceApproved)
    }

    private suspend fun evaluatePermissionLevel(
        level: PermissionLevel,
        tool: AITool,
        reviewContext: ToolPermissionReviewContext,
        onAsk: suspend () -> ToolPermissionDecision,
    ): ToolPermissionDecision {
        val workspaceApproved =
            level == PermissionLevel.AUTO_REVIEW &&
                WorkspaceToolPermissionPolicy.isAutoApproved(
                    context = context,
                    tool = tool,
                    workspacePath = reviewContext.workspacePath,
                    workspaceEnv = reviewContext.workspaceEnv,
                    callerChatId = reviewContext.callerChatId,
                )

        return when (resolvePermissionRoute(level, workspaceApproved)) {
            PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
            PermissionRoute.ASK -> onAsk()
            PermissionRoute.REVIEWER -> reviewPermission(tool, reviewContext)
            PermissionRoute.FORBID -> permissionDeniedBySettings()
        }
    }

    /**
     * Prepares the asynchronous risk score for one dispatched tool batch.
     *
     * Scoring starts when the batch is dispatched rather than when an approval is requested, so the
     * verdict for the actions the agent is about to run is already being computed while the reviews
     * of the same batch are still running. Every dispatched batch advances one scoring step, empty
     * ones included, so a stored score ages out after [PermissionRiskScorer.MAX_LAG_STEPS] batches,
     * the way the Codex adaptive scorer ages out its verdicts (Codex counts calls, this counts
     * batches, so a score here can answer more calls). A batch made only of tools the permission
     * system is never asked about is not an action batch and takes no step.
     *
     * Nothing here blocks the batch or decides anything: the tools that would be answered by the
     * workspace policy or by a permanent setting are only counted, not scored, and the score itself
     * can only ever remove a review. A batch those tools fill completely only ages a stored verdict
     * instead of discarding it, so the agent handing work to another agent cannot cost the next
     * reviewed action its low-risk score.
     */
    internal suspend fun prepareBatchRiskScores(
        tools: List<AITool>,
        callerChatId: String?,
        conversationLabel: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
        timingScopeId: String? = null,
        liveAssistantContent: CharSequence? = null,
    ) {
        // Scoring a tool the permission system is never asked about would spend a classification
        // call on work the agent never dispatched to the user, let that verdict answer a later real
        // call, and age the reuse window for an action nobody saw. A batch that held only such
        // tools is not an action batch at all and takes no step. Every other batch keeps its step,
        // empty ones included, so a dispatch that was intercepted whole still ages a stored verdict.
        val reviewableTools =
            tools.filterNot { tool ->
                PermissionReviewInternalTools.bypassesPermissionCheck(tool.name)
            }
        if (tools.isNotEmpty() && reviewableTools.isEmpty()) return
        val reviewContext =
            ToolPermissionReviewContext(
                callerChatId = callerChatId,
                conversationLabel = conversationLabel,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                timingScopeId = timingScopeId,
                batchSize = reviewableTools.size,
                liveAssistantContent = liveAssistantContent,
            )
        val actions =
            reviewableTools.map { tool ->
                val route = resolveCurrentPermissionRoute(tool, reviewContext)
                PermissionRiskAction(
                    canonical =
                        PermissionReviewAction.fromTool(
                            tool = tool,
                            operationDescription = getOperationDescription(tool),
                            reviewContext = reviewContext,
                            targetId = "",
                        ),
                    scorable = route == PermissionRoute.REVIEWER,
                    refusedBySettings = route == PermissionRoute.FORBID,
                )
            }
        PermissionRiskScorer.getInstance(context)
            .beginBatch(
                callerChatId = callerChatId,
                turnScopeId = timingScopeId,
                reviewMode = effectiveReviewMode(),
                actions = actions,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                liveAssistantContent = liveAssistantContent,
            )
    }

    /** A refusal changes what the agent may assume for everything that follows it. */
    private fun invalidateStoredRiskScores(reviewContext: ToolPermissionReviewContext) {
        PermissionRiskScorer.getInstance(context)
            .invalidate(reviewContext.callerChatId, reviewContext.timingScopeId)
    }

    /**
     * Shows a one-shot review even when the tool itself is normally allowed.
     *
     * This does not expose or persist "always allow", so an approval applies only to the current
     * guarded invocation. Requests share the normal serialized permission queue.
     */
    suspend fun requestExplicitApproval(
        tool: AITool,
        operationDescription: String,
        conversationLabel: String?,
    ): Boolean {
        _pendingPermissionRequestCount.update { it + 1 }
        try {
            return askMutex.withLock {
                requestPermissionInternal(
                    tool = tool,
                    conversationLabel = conversationLabel,
                    operationDescriptionOverride = operationDescription,
                    persistPermanentChoice = false,
                    allowPermanentChoice = false,
                )
            }
        } finally {
            _pendingPermissionRequestCount.update { count -> (count - 1).coerceAtLeast(0) }
        }
    }

    private suspend fun requestPermissionInternal(
        tool: AITool,
        conversationLabel: String?,
        operationDescriptionOverride: String? = null,
        persistPermanentChoice: Boolean = true,
        allowPermanentChoice: Boolean = true,
        reviewFailureKind: PermissionReviewFailureKind? = null,
    ): Boolean {
        return withContext(Dispatchers.Main.immediate) {
            val operationDescription =
                operationDescriptionOverride ?: getOperationDescription(tool)
            AppLogger.d(TAG, "Requesting permission: ${tool.name}")

            val requestInfo = Pair(tool, operationDescription)
            val token = requestTokenGenerator.incrementAndGet()
            currentRequestToken = token
            permissionRequestInfo = requestInfo
            _permissionRequestState.value = requestInfo
            AppLogger.d(TAG, "Permission request state updated: ${tool.name} token=$token")

            var timeoutTask: Runnable? = null
            var timeoutDisabled = false

            try {
                val result = suspendCancellableCoroutine { continuation ->
                    currentPermissionCallback = callback@{ permissionResult ->
                        if (token != currentRequestToken || !continuation.isActive) {
                            return@callback
                        }
                        AppLogger.d(
                            TAG,
                            "Permission result received: $permissionResult for ${tool.name} token=$token"
                        )
                        currentPermissionCallback = null
                        continuation.resume(permissionResult)
                    }

                    val requestTimeoutTask = Runnable {
                        AppLogger.d(
                            TAG,
                            "Timeout runnable fired for ${tool.name} token=$token timeoutDisabled=$timeoutDisabled"
                        )
                        if (token == currentRequestToken && !timeoutDisabled && continuation.isActive) {
                            AppLogger.d(TAG, "Permission request timed out: ${tool.name} token=$token")
                            currentPermissionCallback = null
                            permissionRequestOverlay.dismiss()
                            continuation.resume(PermissionRequestResult.DENY)
                        }
                    }
                    timeoutTask = requestTimeoutTask
                    mainHandler.postDelayed(requestTimeoutTask, PERMISSION_REQUEST_TIMEOUT_MS)

                    if (!permissionRequestOverlay.hasOverlayPermission()) {
                        AppLogger.w(TAG, "No overlay permission, requesting...")
                        permissionRequestOverlay.requestOverlayPermission()
                        currentPermissionCallback = null
                        if (continuation.isActive) {
                            continuation.resume(PermissionRequestResult.DENY)
                        }
                        return@suspendCancellableCoroutine
                    }

                    permissionRequestOverlay.show(
                        tool,
                        operationDescription,
                        conversationLabel = conversationLabel,
                        pendingRequestCount = pendingPermissionRequestCount,
                        reviewFailureKind = reviewFailureKind,
                        onResult = { permissionResult ->
                            handlePermissionResult(permissionResult)
                        },
                        onMinimized = {
                            if (token == currentRequestToken && !timeoutDisabled) {
                                AppLogger.d(TAG, "Request minimized - cancelling timeout token=$token")
                                timeoutDisabled = true
                                mainHandler.removeCallbacks(requestTimeoutTask)
                            }
                        },
                        allowPermanentChoice = allowPermanentChoice,
                    )
                }

                when (result) {
                    PermissionRequestResult.ALLOW -> true
                    PermissionRequestResult.DENY -> false
                    PermissionRequestResult.ALWAYS_ALLOW -> {
                        if (persistPermanentChoice) {
                            // Persist the choice before releasing the mutex to queued requests.
                            withContext(NonCancellable + Dispatchers.IO) {
                                saveToolPermission(tool.name, PermissionLevel.ALLOW)
                            }
                        }
                        true
                    }
                    PermissionRequestResult.ALWAYS_DENY -> {
                        if (persistPermanentChoice) {
                            // Persist before releasing the mutex so queued calls observe the deny.
                            withContext(NonCancellable + Dispatchers.IO) {
                                saveToolPermission(tool.name, PermissionLevel.FORBID)
                            }
                        }
                        false
                    }
                }
            } finally {
                timeoutTask?.let { mainHandler.removeCallbacks(it) }
                if (token == currentRequestToken) {
                    permissionRequestOverlay.dismiss()
                    currentRequestToken = -1L
                    currentPermissionCallback = null
                    permissionRequestInfo = null
                    _permissionRequestState.value = null
                }
            }
        }
    }
    
    /**
     * Handle permission request result
     */
    fun handlePermissionResult(result: PermissionRequestResult) {
        currentPermissionCallback?.invoke(result)
    }
    
    /**
     * Get current permission request info
     */
    fun getCurrentPermissionRequest(): Pair<AITool, String>? {
        return permissionRequestInfo
    }
    
    /**
     * Check if there is an active permission request
     */
    fun hasActivePermissionRequest(): Boolean {
        return permissionRequestInfo != null && currentPermissionCallback != null
    }
    
    /**
     * Refresh permission request state
     */
    fun refreshPermissionRequestState(): Boolean {
        return hasActivePermissionRequest()
    }
}

internal fun resolveEffectivePermissionLevel(
    masterLevel: PermissionLevel,
    toolOverride: PermissionLevel?,
): PermissionLevel = toolOverride ?: masterLevel

/**
 * The level a tool takes when the user has not chosen one for it, or null when it simply follows the
 * [masterLevel].
 *
 * The agent's own collaboration tools are how one agent hands work to another. They touch no user
 * data themselves, and the work they start is reviewed as each of its own tool calls runs, so asking
 * about the hand-off as well only adds friction. Updating the current chat's todo list also needs no
 * separate review. A level the user stored for one of these tools still
 * wins, which is why this is a default rather than an exemption from the permission system. A global
 * "forbid" also wins: that setting is a deliberate whitelist, not a gap for a default to fill.
 */
internal fun defaultPermissionLevelFor(
    toolName: String,
    masterLevel: PermissionLevel,
): PermissionLevel? =
    if (masterLevel != PermissionLevel.FORBID &&
        (CollaborationTools.isCollaborationTool(toolName) || toolName == "todowrite")) {
        PermissionLevel.ALLOW
    } else {
        null
    }

internal fun findDuplicateToolParameterNames(tool: AITool): Set<String> =
    tool.parameters.groupingBy { parameter -> parameter.name }.eachCount()
        .filterValues { count -> count > 1 }
        .keys

internal enum class PermissionRoute {
    ALLOW,
    ASK,
    REVIEWER,
    FORBID,
}

internal fun resolvePermissionRoute(
    level: PermissionLevel,
    workspaceApproved: Boolean,
): PermissionRoute =
    when (level) {
        PermissionLevel.ALLOW -> PermissionRoute.ALLOW
        PermissionLevel.AUTO_REVIEW ->
            if (workspaceApproved) PermissionRoute.ALLOW else PermissionRoute.REVIEWER
        PermissionLevel.ASK -> PermissionRoute.ASK
        PermissionLevel.FORBID -> PermissionRoute.FORBID
    }

internal fun resolveApprovalDecisionWithPermanentOverride(
    approvalGranted: Boolean,
    latestEffectiveLevel: PermissionLevel,
): Boolean =
    when (latestEffectiveLevel) {
        PermissionLevel.ALLOW -> true
        PermissionLevel.FORBID -> false
        else -> approvalGranted
    }

/** Levels an older build wrote before the automatic review was split into two stops. */
internal fun isLegacyAutoReviewLevel(rawLevel: String?): Boolean =
    rawLevel?.trim()?.uppercase() in LEGACY_AUTO_REVIEW_LEVELS

private val LEGACY_AUTO_REVIEW_LEVELS = setOf("WORKSPACE", "WORKSPACE_REVIEWER", "REVIEWER")

/** The slider stop that shows a stored level and reuse level. */
internal fun resolvePermissionStop(
    level: PermissionLevel,
    rawLevel: String?,
    mode: PermissionReviewMode,
): ToolPermissionStop =
    if (level == PermissionLevel.AUTO_REVIEW && isLegacyAutoReviewLevel(rawLevel)) {
        // The strict stop keeps what those levels did: every reviewed call ran the reviewer.
        ToolPermissionStop.AUTO_REVIEW_STRICT
    } else {
        ToolPermissionStop.of(level, mode)
    }

/**
 * Denial texts are written for the model, so the transcript would otherwise show a long English
 * instruction as the tool result. [permissionDenialSourceForMessage] recognizes these prefixes so
 * the UI can report the outcome instead; keep the prefixes in sync with the texts below.
 */
internal const val SETTINGS_DENIAL_PREFIX = "Tool execution denied by permission settings."
internal const val USER_DENIAL_PREFIX = "Tool execution denied by user."
internal const val AUTOMATIC_REVIEW_DENIAL_PREFIX = "Automatic permission review denied"
internal const val AUTOMATIC_REVIEW_CANCEL_PREFIX =
    "Tool execution cancelled because automatic permission review"
internal const val AUTOMATIC_REVIEW_FAILURE_PREFIX = "Automatic permission review failed"

/** Null when [message] is an ordinary tool error rather than a permission denial. */
internal fun permissionDenialSourceForMessage(message: String): ToolPermissionDenialSource? {
    val trimmed = message.trim()
    return when {
        trimmed.startsWith(AUTOMATIC_REVIEW_FAILURE_PREFIX) ->
            ToolPermissionDenialSource.AUTOMATIC_REVIEW_FAILED
        // The turn was stopped before this action ran, so reporting it as a denial would be wrong.
        trimmed.startsWith(AUTOMATIC_REVIEW_CANCEL_PREFIX) ->
            ToolPermissionDenialSource.AUTOMATIC_REVIEW_CANCELLED
        trimmed.startsWith(AUTOMATIC_REVIEW_DENIAL_PREFIX) ->
            ToolPermissionDenialSource.AUTOMATIC_REVIEW
        trimmed.startsWith(USER_DENIAL_PREFIX) -> ToolPermissionDenialSource.USER
        trimmed.startsWith(SETTINGS_DENIAL_PREFIX) -> ToolPermissionDenialSource.SETTINGS
        else -> null
    }
}

/** Null when [message] is an ordinary tool error rather than a permission denial. */
@StringRes
internal fun permissionDenialSummaryResId(message: String): Int? =
    when (permissionDenialSourceForMessage(message)) {
        ToolPermissionDenialSource.SETTINGS -> R.string.permission_denied_result_settings
        ToolPermissionDenialSource.USER -> R.string.permission_denied_result_user
        ToolPermissionDenialSource.AUTOMATIC_REVIEW ->
            R.string.permission_denied_result_auto_review
        ToolPermissionDenialSource.AUTOMATIC_REVIEW_CANCELLED ->
            R.string.permission_denied_result_auto_review_cancelled
        ToolPermissionDenialSource.AUTOMATIC_REVIEW_FAILED ->
            R.string.permission_review_failed_result
        null -> null
    }

/** The user-facing summary of a denial text, or null when [message] is an ordinary tool error. */
internal fun permissionDenialSummary(context: Context, message: String): String? =
    permissionDenialSummaryResId(message)?.let(context::getString)

internal fun permissionDeniedBySettings(reason: String? = null): ToolPermissionDecision.Denied =
    ToolPermissionDecision.Denied(
        source = ToolPermissionDenialSource.SETTINGS,
        rejection =
            SETTINGS_DENIAL_PREFIX +
                reason?.trim()?.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty(),
    )

internal fun permissionDeniedByUser(): ToolPermissionDecision.Denied =
    ToolPermissionDecision.Denied(
        source = ToolPermissionDenialSource.USER,
        rejection = USER_DENIAL_PREFIX,
    )

internal fun permissionBlockedByReviewFailure(
    kind: PermissionReviewFailureKind,
    rationale: String,
): ToolPermissionDecision.Denied {
    val cause = when (kind) {
        PermissionReviewFailureKind.TIMED_OUT -> "the AI approval review timed out"
        PermissionReviewFailureKind.INVALID_OUTPUT -> "the AI reviewer returned no valid decision"
        PermissionReviewFailureKind.REVIEWER_ERROR -> "the AI approval reviewer could not complete"
    }
    return ToolPermissionDecision.Denied(
        source = ToolPermissionDenialSource.AUTOMATIC_REVIEW_FAILED,
        rejection =
            "$AUTOMATIC_REVIEW_FAILURE_PREFIX [$kind]: $cause. " +
                "${rationale.trim().take(1_000)} " +
                "The requested action was NOT executed. No approval or denial verdict was reached. " +
                "You may retry the same action to request a fresh review. If the problem persists, " +
                "stop and tell the user the failure reason and ask them to check the permission " +
                "reviewer model configuration. Do not bypass approval or claim the action succeeded.",
    )
}

internal fun permissionDeniedByAutomaticReview(
    rationale: String,
): ToolPermissionDecision.Denied {
    val normalizedRationale = rationale.trim().take(1_000)
    val suffix = normalizedRationale.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()
    return ToolPermissionDecision.Denied(
        source = ToolPermissionDenialSource.AUTOMATIC_REVIEW,
        rejection =
            "$AUTOMATIC_REVIEW_DENIAL_PREFIX the action$suffix. It is not authorized yet: do " +
                "not reach the same goal another way (no rephrasing, splitting, encoding, " +
                "delegation, or a different tool, path, or command). Ask the user to authorize " +
                "this action (and only this action), then wait for their reply before retrying. " +
                "Once the user approves, retry the exact same action unchanged; a one-time " +
                "approval covers only that exact action and expires after 5 minutes. If this " +
                "action was rated catastrophic, do not ask the user to authorize it: a one-time " +
                "approval cannot apply, so wait for their instruction instead.",
    )
}

/**
 * The circuit breaker skips the call and publishes it as ABORTED, so the user never gets a review
 * prompt to authorize: promising a retry here would be wrong, and the turn is already over.
 * The source stays [ToolPermissionDenialSource.AUTOMATIC_REVIEW] because the caller counts these as
 * automatic-review denials; only the text differs, and it maps to the cancelled wording on screen.
 */
internal fun permissionDeniedByRepeatedDenials(): ToolPermissionDecision.Denied =
    ToolPermissionDecision.Denied(
        source = ToolPermissionDenialSource.AUTOMATIC_REVIEW,
        rejection =
            "$AUTOMATIC_REVIEW_CANCEL_PREFIX denied enough actions and stopped this turn, so no " +
                "further tool calls will run in it. Do not retry the denied actions; tell the user " +
                "which actions were denied and why, then wait for their instruction.",
        interruptTurn = true,
    )

/** Null means the latest setting now requires a manual prompt. */
internal fun resolveReviewDecisionAfterSettingsRefresh(
    approvalGranted: Boolean,
    latestRoute: PermissionRoute,
    reviewerRationale: String,
): ToolPermissionDecision? =
    when (latestRoute) {
        PermissionRoute.ALLOW -> ToolPermissionDecision.Allowed
        PermissionRoute.FORBID -> permissionDeniedBySettings()
        PermissionRoute.REVIEWER ->
            if (approvalGranted) {
                ToolPermissionDecision.Allowed
            } else {
                permissionDeniedByAutomaticReview(reviewerRationale)
            }
        PermissionRoute.ASK -> null
    }

/**
 * The event status must mirror the decision that was actually enforced. A manual or setting
 * decision reached after the reviewer finished may differ from the reviewer's own result, and a
 * stale APPROVED/DENIED status would misreport the outcome in the UI and audit history.
 */
internal fun reviewEventStatusForEnforcedDecision(
    decision: ToolPermissionDecision,
): PermissionReviewStatus =
    if (decision is ToolPermissionDecision.Allowed) {
        PermissionReviewStatus.APPROVED
    } else {
        PermissionReviewStatus.DENIED
    }
