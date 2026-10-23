package com.ai.assistance.operit.ui.features.chat.components.part

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator
import com.ai.assistance.operit.core.tools.ToolExecutionTimingKey
import com.ai.assistance.operit.core.tools.ToolExecutionTimingRepository
import com.ai.assistance.operit.core.tools.ToolExecutionTimingSnapshot
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.repository.SubagentRunRepository
import com.ai.assistance.operit.ui.features.chat.components.LocalTranscriptRuns
import com.ai.assistance.operit.ui.features.chat.components.SubagentAgentCard
import com.ai.assistance.operit.ui.features.chat.components.SubagentCardStatus
import com.ai.assistance.operit.ui.features.chat.components.TranscriptJumpHost
import com.ai.assistance.operit.ui.features.chat.components.agentBadgeSeed
import com.ai.assistance.operit.ui.features.chat.components.subagentAgentIdentity
import com.ai.assistance.operit.ui.features.chat.components.subagentInterruptionHintRes
import com.ai.assistance.operit.ui.permissions.PermissionReviewEventRepository
import com.ai.assistance.operit.ui.permissions.PermissionReviewArchive
import com.ai.assistance.operit.ui.permissions.PermissionReviewEvent
import com.ai.assistance.operit.ui.permissions.PermissionReviewAuthorization
import com.ai.assistance.operit.ui.theme.stoppedAttention
import com.ai.assistance.operit.ui.permissions.PermissionReviewExactOverrideState
import com.ai.assistance.operit.ui.permissions.PermissionReviewFailureKind
import com.ai.assistance.operit.ui.permissions.PermissionReviewResponsePolicy
import com.ai.assistance.operit.ui.permissions.PermissionReviewRiskLevel
import com.ai.assistance.operit.ui.permissions.PermissionReviewStatus
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import com.ai.assistance.operit.ui.permissions.effectiveExactOverrideState
import com.ai.assistance.operit.ui.permissions.permissionDenialSummary
import com.ai.assistance.operit.ui.permissions.permissionReviewJumpMarker
import com.ai.assistance.operit.util.ChatMarkupRegex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class PersistedToolExecution(
    val callId: String?,
    val toolName: String,
    val state: ToolExecutionState,
    val durationMs: Long?,
    val success: Boolean,
    val resultText: String,
)

private val FILE_EDIT_TOOL_NAMES = setOf("apply_file", "create_file", "edit_file")

internal data class SubagentTaskRowContent(
    val title: String,
    val summary: String,
)

internal sealed interface SubagentRunLookup {
    data class TaskId(val taskId: String, val parentChatId: String) : SubagentRunLookup

    data class ParentCall(val parentChatId: String, val callId: String) : SubagentRunLookup
}

internal fun resolveSubagentRunLookup(
    requestedTaskId: String?,
    parentChatId: String?,
    callId: String?,
): SubagentRunLookup? {
    val normalizedParentChatId = parentChatId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val normalizedTaskId = requestedTaskId?.trim()?.takeIf(String::isNotEmpty)
    if (normalizedTaskId != null) {
        return SubagentRunLookup.TaskId(normalizedTaskId, normalizedParentChatId)
    }
    val normalizedCallId = callId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return SubagentRunLookup.ParentCall(normalizedParentChatId, normalizedCallId)
}

internal fun buildSubagentTaskRowContent(
    agentName: String?,
    durationText: String?,
    statusText: String,
): SubagentTaskRowContent =
    SubagentTaskRowContent(
        title = agentName?.trim()?.takeIf { it.isNotEmpty() } ?: "subagent",
        summary =
            listOfNotNull(
                    durationText?.trim()?.takeIf { it.isNotEmpty() },
                    statusText.trim().takeIf { it.isNotEmpty() },
                )
                .joinToString(" · "),
    )

@Composable
internal fun rememberPersistedToolExecutions(
    messageKey: Long,
    content: String,
): Map<Int, PersistedToolExecution> {
    com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice.current?.let {
        return it.document.toolExecutions
    }
    val state = remember(messageKey) {
        androidx.compose.runtime.mutableStateOf<Map<Int, PersistedToolExecution>>(emptyMap())
    }
    androidx.compose.runtime.LaunchedEffect(messageKey, content) {
        state.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            parsePersistedToolExecutions(content)
        }
    }
    return state.value
}

internal fun parsePersistedToolExecutions(content: String): Map<Int, PersistedToolExecution> {
    if (content.isBlank()) return emptyMap()

    val executions = buildMap {
        ChatMarkupRegex.toolResultTagWithAttrs.findAll(content).forEach { match ->
            val attrs = match.groupValues[2]
            val invocationIndex = readFinalInvocationIndex(attrs) ?: return@forEach
            val state =
                readXmlAttribute(attrs, "execution_state")
                    ?.uppercase()
                    ?.let { runCatching { ToolExecutionState.valueOf(it) }.getOrNull() }
                    ?: ToolExecutionState.COMPLETED
            val durationMs = readXmlAttribute(attrs, "duration_ms")?.toLongOrNull()
            val success = readXmlAttribute(attrs, "status").equals("success", ignoreCase = true)
            val toolName = readXmlAttribute(attrs, "name").orEmpty()
            val callId = readXmlAttribute(attrs, "call_id")
            val body = match.groupValues[3]
            val resultText =
                ChatMarkupRegex.contentTag.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            put(
                invocationIndex,
                PersistedToolExecution(
                    callId = callId,
                    toolName = toolName,
                    state = state,
                    durationMs = durationMs,
                    success = success,
                    resultText = resultText,
                ),
            )
        }
    }
    // Earlier steering builds stored a turn-global index in a message-local segment.
    // Recover only a complete contiguous result set; do not guess for partial batches.
    val offset = executions.keys.minOrNull() ?: return executions
    val toolCount = ChatMarkupRegex.toolCallPattern.findAll(content).count()
    if (offset > 0 && toolCount == executions.size &&
        executions.keys.sorted() == (offset until offset + toolCount).toList()
    ) {
        return executions.mapKeys { (index, _) -> index - offset }
    }
    return executions
}

private fun readXmlAttribute(attrs: String, name: String): String? =
    Regex("""\b${Regex.escape(name)}="([^"]*)"""", RegexOption.IGNORE_CASE)
        .find(attrs)
        ?.groupValues
        ?.getOrNull(1)

private fun readFinalInvocationIndex(attrs: String): Int? {
    if (!readXmlAttribute(attrs, "final").equals("true", ignoreCase = true)) return null
    return readXmlAttribute(attrs, "invocation_index")
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }
}

internal fun shouldRenderStandaloneToolResult(content: String): Boolean {
    val match = ChatMarkupRegex.toolResultTagWithAttrs.matchEntire(content.trim()) ?: return true
    return readFinalInvocationIndex(match.groupValues[2]) == null
}

internal fun resolveLiveToolExecution(
    liveExecution: ToolExecutionTimingSnapshot?,
    persistedExecution: PersistedToolExecution?,
    allowUnmatchedLiveExecution: Boolean,
): ToolExecutionTimingSnapshot? {
    val live = liveExecution ?: return null
    val persistedCallId = persistedExecution?.callId?.takeIf { it.isNotBlank() }
    if (persistedCallId == null) {
        return live.takeIf { allowUnmatchedLiveExecution || it.state.isStillInFlight() }
    }
    return live.takeIf { it.callId == persistedCallId }
}

/**
 * 非终止状态只存在于该调用正在当前轮次中真实处理的过程。聊天切换后消息按静态内容重载
 * （无流、最终结果未写入内容），此时仍需继续展示审核横幅与执行耗时，因此运行中的调用
 * 允许与持久化结果无关地直接匹配；终止状态仍要求 callId 一致，避免误配其他变体的快照。
 */
private fun ToolExecutionState.isStillInFlight(): Boolean =
    this == ToolExecutionState.WAITING_AUTHORIZATION ||
        this == ToolExecutionState.WAITING_EXECUTION ||
        this == ToolExecutionState.RUNNING

internal fun shouldAllowUnmatchedTaskExecution(
    requestedToolName: String?,
    liveExecution: ToolExecutionTimingSnapshot?,
): Boolean =
    requestedToolName == "task" && liveExecution?.toolName in setOf("task", "proxy")

@Composable
internal fun ToolExecutionStatusDisplay(
    timingScopeId: String?,
    invocationIndex: Int,
    persistedExecution: PersistedToolExecution?,
    allowUnmatchedLiveExecution: Boolean,
    enableDialogs: Boolean,
    modifier: Modifier = Modifier,
    requestedToolName: String? = null,
    requestedSubagentName: String? = null,
    requestedSubagentTaskId: String? = null,
    requestedSubagentTask: String? = null,
) {
    val context = LocalContext.current
    remember(context) { PermissionReviewEventRepository.initialize(context); true }
    val timings by ToolExecutionTimingRepository.timings.collectAsState()
    val chatCore =
        remember(context) {
            ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
        }
    val currentChatId by chatCore.currentChatId.collectAsState(initial = null)
    val reviewEvents by PermissionReviewEventRepository.events.collectAsState()
    // Older reviews leave the shared list as other chats add theirs; the chat's own archive keeps them.
    val archivedReviews by
        remember(context, currentChatId) {
                currentChatId?.let { PermissionReviewArchive.observe(context, it) }
                    ?: kotlinx.coroutines.flow.MutableStateFlow<List<PermissionReviewEvent>?>(null)
            }
            .collectAsState()
    val reviewEvent =
        remember(reviewEvents, archivedReviews, currentChatId, timingScopeId, invocationIndex) {
            val matches = { event: PermissionReviewEvent ->
                event.parentChatId == currentChatId &&
                    event.timingScopeId == timingScopeId &&
                    event.invocationIndex == invocationIndex
            }
            reviewEvents.lastOrNull(matches) ?: archivedReviews?.lastOrNull(matches)
        }
    val liveExecutionCandidate =
        timingScopeId
            ?.let { scopeId -> timings[ToolExecutionTimingKey(scopeId, invocationIndex)] }
    val liveExecution =
        resolveLiveToolExecution(
            liveExecution = liveExecutionCandidate,
            persistedExecution = persistedExecution,
            allowUnmatchedLiveExecution =
                allowUnmatchedLiveExecution ||
                    shouldAllowUnmatchedTaskExecution(
                        requestedToolName = requestedToolName,
                        liveExecution = liveExecutionCandidate,
                    ),
        )
    val state = liveExecution?.state ?: persistedExecution?.state ?: return
    val toolName =
        requestedToolName
            ?: liveExecution?.toolName
            ?: persistedExecution?.toolName
            ?: return

    if (toolName == "task") {
        val success = liveExecution?.success ?: persistedExecution?.success ?: false
        Column(modifier = modifier) {
        reviewEvent?.let { event ->
            PermissionReviewLifecycleDisplay(event, enableDialogs = enableDialogs)
        }
        SubagentTaskStatusDisplay(
            callId = liveExecution?.callId ?: persistedExecution?.callId,
            fallbackState = state,
            fallbackStartedAtElapsedMs = liveExecution?.startedAtElapsedMs,
            fallbackDurationMs = liveExecution?.durationMs ?: persistedExecution?.durationMs,
            executionSuccess = success,
            executionResultText =
                resolveResultText(context, liveExecution, persistedExecution, success),
            requestedSubagentName = requestedSubagentName,
            requestedSubagentTaskId = requestedSubagentTaskId,
            modifier = Modifier,
        )
        }
        return
    }

    val callKind = subagentCallKind(toolName)
    if (callKind != null) {
        val success = liveExecution?.success ?: persistedExecution?.success ?: false
        SubagentCallStatusDisplay(
            callKind = callKind,
            callId = liveExecution?.callId ?: persistedExecution?.callId,
            fallbackState = state,
            executionSuccess = success,
            executionResultText =
                resolveResultText(context, liveExecution, persistedExecution, success),
            requestedAgentName = requestedSubagentName,
            bodyText = requestedSubagentTask?.takeIf { it.isNotBlank() },
            reviewEvent = reviewEvent,
            enableDialogs = enableDialogs,
            modifier = modifier,
        )
        return
    }

    var currentElapsedMs by remember(liveExecution?.startedAtElapsedMs) {
        mutableLongStateOf(resolveElapsedMs(liveExecution))
    }
    LaunchedEffect(liveExecution?.state, liveExecution?.startedAtElapsedMs) {
        while (liveExecution?.state == ToolExecutionState.RUNNING) {
            currentElapsedMs = resolveElapsedMs(liveExecution)
            delay(100L)
        }
    }

    Column(modifier = modifier) {
        reviewEvent?.let { event ->
            PermissionReviewLifecycleDisplay(event, enableDialogs = enableDialogs)
        }
    when (state) {
        ToolExecutionState.WAITING_AUTHORIZATION -> {
            ToolPendingStatusRow(
                // An automatic review runs without asking anyone, so "waiting for authorization"
                // would tell the user to wait for a prompt that is never coming.
                text =
                    if (reviewEvent?.status == PermissionReviewStatus.IN_PROGRESS) {
                        stringResource(R.string.tool_waiting_auto_review)
                    } else {
                        stringResource(R.string.tool_waiting_authorization)
                    },
                modifier = Modifier,
            )
        }
        ToolExecutionState.WAITING_EXECUTION -> {
            ToolPendingStatusRow(
                text = stringResource(R.string.tool_waiting_execution),
                modifier = Modifier,
            )
        }
        ToolExecutionState.RUNNING -> {
            ToolPendingStatusRow(
                text =
                    stringResource(
                        R.string.tool_executing_duration,
                        formatToolExecutionDuration(context, currentElapsedMs),
                    ),
                modifier = Modifier,
            )
        }
        ToolExecutionState.COMPLETED -> {
            val durationMs = liveExecution?.durationMs ?: persistedExecution?.durationMs ?: 0L
            val success = liveExecution?.success ?: persistedExecution?.success ?: false
            val resultText =
                resolveResultText(context, liveExecution, persistedExecution, success)
            ToolExecutionResultDisplay(
                toolName = toolName,
                result = resultText,
                isSuccess = success,
                summaryPrefix = formatToolExecutionDuration(context, durationMs),
                enableDialog = enableDialogs,
                modifier = Modifier,
            )
        }
        ToolExecutionState.NOT_EXECUTED -> {
            val success = false
            val resultText = resolveResultText(context, liveExecution, persistedExecution, success)
            ToolExecutionResultDisplay(
                toolName = liveExecution?.toolName ?: persistedExecution?.toolName.orEmpty(),
                result = resultText,
                isSuccess = false,
                summaryPrefix = stringResource(R.string.tool_not_executed),
                enableDialog = enableDialogs,
                modifier = Modifier,
            )
        }
    }
    }
}

@Composable
private fun PermissionReviewLifecycleDisplay(
    event: PermissionReviewEvent,
    // A service-hosted window has no activity token, so a dialog opened from one would crash the
    // process; the badge keeps its line and its outcome in that case and only drops the detail view.
    enableDialogs: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { SubagentRunRepository.getInstance(context) }
    val chatCore =
        remember(context) {
            ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
        }
    val reviewerRunFlow =
        remember(event.reviewerTaskId, event.parentChatId, event.action.targetId) {
            event.reviewerTaskId?.let(repository::observeById)
                ?: repository.observeByParentChatId(event.parentChatId).map { runs ->
                    runs.lastOrNull { run ->
                        run.agentProfileId == AgentProfileRepository.PERMISSION_REVIEWER_ID &&
                            run.parentToolCallId == event.action.targetId
                    }
                }
        }
    val reviewerRun by reviewerRunFlow.collectAsState(initial = null)
    var showDetails by remember(event.id) { mutableStateOf(false) }
    var overrideNowMs by remember(event.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(event.exactOverrideExpiresAt) {
        val expiresAt = event.exactOverrideExpiresAt ?: return@LaunchedEffect
        val remaining = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        delay(remaining)
        overrideNowMs = System.currentTimeMillis()
    }
    val overrideState = event.effectiveExactOverrideState(overrideNowMs)
    // An archived review belongs to a turn that is long over; only one still in the live list can
    // take a one-time approval.
    val liveEvents by PermissionReviewEventRepository.events.collectAsState()
    val canApproveOnce = remember(liveEvents, event.id) { liveEvents.any { it.id == event.id } }
    val lifecycle =
        when (event.status) {
            PermissionReviewStatus.IN_PROGRESS ->
                stringResource(R.string.permission_review_lifecycle_in_progress)
            PermissionReviewStatus.APPROVED ->
                stringResource(R.string.permission_review_lifecycle_approved)
            PermissionReviewStatus.DENIED ->
                stringResource(R.string.permission_review_lifecycle_denied)
            PermissionReviewStatus.TIMED_OUT ->
                stringResource(R.string.permission_review_lifecycle_timed_out)
            PermissionReviewStatus.ABORTED ->
                stringResource(R.string.permission_review_lifecycle_aborted)
            PermissionReviewStatus.FAILED ->
                stringResource(R.string.permission_review_lifecycle_failed)
        }
    // A refused, failed or timed-out review is the outcome the badge has to shout about. A review
    // that was aborted — including one a restart left unfinished — decided nothing at all, so it
    // stays neutral instead of borrowing the look of an approval.
    val isNegativeOutcome =
        event.status == PermissionReviewStatus.DENIED ||
            event.status == PermissionReviewStatus.FAILED ||
            event.status == PermissionReviewStatus.TIMED_OUT
    val isUnresolved = event.status == PermissionReviewStatus.ABORTED
    // Only a risk the reviewer itself assessed reads as high. A review that broke records a stand-in
    // high risk, and a denial that follows it can be the user's own answer rather than the review's.
    val isAssessedHighRisk =
        event.failureKind == null &&
            (event.riskLevel == PermissionReviewRiskLevel.HIGH ||
                event.riskLevel == PermissionReviewRiskLevel.CRITICAL)
    // A run of reviewed calls stays quiet: grey while the review is ordinary, whether it is running,
    // allowed or left unfinished, and the error colour only when there is something to warn about.
    val statusColor =
        if (isNegativeOutcome) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    // An outcome worth warning about is spelled out on the line, because the colour alone cannot say
    // whether the call was refused, ran out of time, or never got an answer at all.
    val badgeLabel =
        stringResource(
            when (event.status) {
                PermissionReviewStatus.DENIED ->
                    when {
                        // A refusal that came from the user's own answer (or from the settings it
                        // switched to) is not the reviewer's verdict and must not be reported as
                        // one.
                        // Both a user refusal and a level that changed to "forbid" while the review
                        // was in flight decide the call outside the reviewer, and the result line
                        // says so, so the badge must not credit the automatic review either.
                        event.resolutionSource ==
                            ToolPermissionSystem.MANUAL_OR_SETTING_DENY_RESOLUTION_SOURCE ||
                            event.resolutionSource ==
                                ToolPermissionSystem.SETTINGS_REFRESHED_DENY_RESOLUTION_SOURCE ->
                            R.string.permission_review_badge_denied_by_user
                        isAssessedHighRisk -> R.string.permission_review_badge_denied_high_risk
                        else -> R.string.permission_review_badge_denied
                    }
                PermissionReviewStatus.TIMED_OUT -> R.string.permission_review_badge_timed_out
                PermissionReviewStatus.FAILED -> R.string.permission_review_badge_failed
                // "Aborted" and "allowed" share the neutral colour, so the label has to tell them
                // apart on its own.
                PermissionReviewStatus.ABORTED -> R.string.permission_review_badge_aborted
                else -> R.string.permission_level_auto_review
            }
        )
    // The badge names the outcome itself when it has one; for the quiet states the outcome lives in
    // the colour alone, so a reader that cannot see it is told about it here, and told again when it
    // changes.
    val semanticsDescription =
        if (isNegativeOutcome) badgeLabel
        else stringResource(R.string.permission_review_badge_description, lifecycle)
    // One short line per reviewed call. Which batch it belonged to, where the verdict came from and
    // everything the reviewer said belong to the dialog this line opens, so a run of reviewed calls
    // stays scannable. The colour only warns, the words name what the warning is, and the shield
    // carries the outcome of the quiet states.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // The line stays as tall as its own text: a run of reviewed calls has to read as a
                // run, and a full touch target on every one of them turned that run into a column of
                // 48dp rows. The row is still the way into the review details.
                .clip(RoundedCornerShape(6.dp))
                // Without a dialog the line stays a status line rather than a button that answers
                // nothing, so the touch target is left off entirely.
                .then(
                    if (enableDialogs) {
                        Modifier.clickable(role = Role.Button) { showDetails = true }
                    } else {
                        Modifier
                    }
                )
                // The line already carries the outcome, so it is set once instead of being merged
                // with the label and read twice.
                .clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = semanticsDescription
                }
                .padding(start = 24.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (event.status == PermissionReviewStatus.IN_PROGRESS) {
            CircularProgressIndicator(
                // The same 16dp box as the settled icon, so the label does not move a pixel when
                // the review ends and the shield takes the spinner's place.
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = statusColor,
            )
        } else {
            Icon(
                imageVector =
                    when {
                        isNegativeOutcome -> Icons.Outlined.GppBad
                        isUnresolved -> Icons.Outlined.GppMaybe
                        else -> Icons.Outlined.Security
                    },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = badgeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (showDetails && enableDialogs) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.permission_review_detail_title)) },
            text = {
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    com.ai.assistance.operit.ui.permissions.AlwaysAllowReviewedToolButton(event.action.toolName)
                    Text(
                        stringResource(R.string.permission_review_detail_status, lifecycle),
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // A call that travelled alone has no batch to place itself in.
                    if (event.batchSize > 1) {
                        Text(
                            stringResource(
                                R.string.permission_review_detail_batch,
                                event.batchPosition,
                                event.batchSize,
                            )
                        )
                    }
                    event.resolutionSource?.let { source ->
                        Text(
                            stringResource(
                                R.string.permission_review_detail_resolution,
                                stringResource(
                                    when {
                                        // A reused approval is an allow, but it was not decided by
                                        // the user or by a settings change, so it must not borrow the
                                        // "allow" wording.
                                        source ==
                                            ToolPermissionSystem.FAST_REVIEW_RESOLUTION_SOURCE ->
                                            R.string.permission_review_resolved_reused
                                        source.endsWith("allow") ->
                                            R.string.permission_review_resolved_allow
                                        else -> R.string.permission_review_resolved_deny
                                    }
                                ),
                            )
                        )
                    }
                    if (event.status == PermissionReviewStatus.DENIED) {
                        // A catastrophic action is denied before the one-time override is even
                        // considered, so offering the button would promise a retry that the review
                        // cannot allow. Say so instead of recording an approval that never applies.
                        val overrideCannotApply =
                            event.failureKind == null &&
                                event.riskLevel == PermissionReviewRiskLevel.CRITICAL
                        if (overrideCannotApply) {
                            Text(
                                stringResource(
                                    R.string.permission_review_override_unavailable_catastrophic
                                ),
                                modifier = Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else if (canApproveOnce) {
                            Button(
                                enabled =
                                    overrideState == null ||
                                        overrideState == PermissionReviewExactOverrideState.EXPIRED,
                                onClick = {
                                    scope.launch {
                                        PermissionReviewEventRepository.approveExactActionOnce(event.id)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            ) {
                                Text(
                                    stringResource(
                                        when (overrideState) {
                                            PermissionReviewExactOverrideState.PENDING ->
                                                R.string.permission_review_override_recorded
                                            PermissionReviewExactOverrideState.IN_REVIEW ->
                                                R.string.permission_review_override_in_review
                                            PermissionReviewExactOverrideState.CONSUMED ->
                                                R.string.permission_review_override_consumed
                                            PermissionReviewExactOverrideState.EXPIRED ->
                                                R.string.permission_review_override_expired
                                            null -> R.string.permission_review_allow_exact_once
                                        }
                                    )
                                )
                            }
                            if (overrideState == PermissionReviewExactOverrideState.PENDING) {
                                Text(
                                    stringResource(R.string.permission_review_override_next_step),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(
                            R.string.permission_review_detail_action,
                            event.action.summary,
                        )
                    )
                    event.riskLevel?.let { risk ->
                        val riskLabel =
                            stringResource(
                                when (risk) {
                                    PermissionReviewRiskLevel.LOW ->
                                        R.string.permission_review_value_low
                                    PermissionReviewRiskLevel.MEDIUM ->
                                        R.string.permission_review_value_medium
                                    PermissionReviewRiskLevel.HIGH ->
                                        R.string.permission_review_value_high
                                    PermissionReviewRiskLevel.CRITICAL ->
                                        R.string.permission_review_value_critical
                                }
                            )
                        Text(
                            stringResource(
                                R.string.permission_review_detail_risk,
                                riskLabel,
                            )
                        )
                    }
                    event.userAuthorization?.let { authorization ->
                        val authorizationLabel =
                            stringResource(
                                when (authorization) {
                                    PermissionReviewAuthorization.UNKNOWN ->
                                        R.string.permission_review_value_unknown
                                    PermissionReviewAuthorization.LOW ->
                                        R.string.permission_review_value_low
                                    PermissionReviewAuthorization.MEDIUM ->
                                        R.string.permission_review_value_medium
                                    PermissionReviewAuthorization.HIGH ->
                                        R.string.permission_review_value_high
                                }
                            )
                        Text(
                            stringResource(
                                R.string.permission_review_detail_authorization,
                                authorizationLabel,
                            )
                        )
                    }
                    event.failureKind?.let { failure ->
                        val failureLabel =
                            stringResource(
                                when (failure) {
                                    PermissionReviewFailureKind.INVALID_OUTPUT ->
                                        R.string.permission_review_failure_invalid_output
                                    PermissionReviewFailureKind.TIMED_OUT ->
                                        R.string.permission_review_failure_timed_out
                                    PermissionReviewFailureKind.REVIEWER_ERROR ->
                                        R.string.permission_review_failure_reviewer_error
                                }
                            )
                        Text(
                            stringResource(
                                R.string.permission_review_detail_failure,
                                failureLabel,
                            )
                        )
                    }
                    // A reused approval already says so in the line above, in the reader's own
                    // language. The note stored for it is a fixed English sentence about where the
                    // answer came from, not about this call, so it is never quoted back — not even
                    // when a settings change rewrote the outcome of the same event.
                    val rationale =
                        permissionReviewNoteForDisplay(
                            context = context,
                            rationale = event.rationale,
                            failureKind = event.failureKind,
                        )
                    if (rationale != null) {
                        Text(
                            stringResource(
                                R.string.permission_review_detail_rationale,
                                rationale,
                            )
                        )
                    }
                    reviewerRun?.let { run ->
                        val runStatus =
                            stringResource(
                                when (run.status) {
                                    SubagentRunStatus.CREATED.name ->
                                        R.string.subagent_status_creating
                                    SubagentRunStatus.QUEUED.name ->
                                        R.string.subagent_filter_queued
                                    SubagentRunStatus.RUNNING.name ->
                                        R.string.subagent_status_thinking
                                    SubagentRunStatus.COMPLETED.name ->
                                        R.string.subagent_status_completed
                                    SubagentRunStatus.CANCELLED.name ->
                                        R.string.subagent_status_cancelled
                                    SubagentRunStatus.INTERRUPTED.name ->
                                        R.string.subagent_status_interrupted
                                    else -> R.string.subagent_status_error
                                }
                            )
                        Text(
                            stringResource(
                                R.string.permission_review_detail_subagent_status,
                                runStatus,
                            )
                        )
                    }
                }
            },
            confirmButton = {
                if (reviewerRun != null) {
                    TextButton(
                        onClick = {
                            showDetails = false
                            val run = requireNotNull(reviewerRun)
                            // Several reviews of one conversation share a reviewer chat, so name the
                            // review the reader asked for: the transcript lands on the exchange that
                            // judged it rather than on whatever the chat last showed.
                            TranscriptJumpHost.request(
                                run.childChatId,
                                permissionReviewJumpMarker(event.id),
                            )
                            chatCore.switchChat(run.childChatId, scrollToBottom = false)
                        }
                    ) {
                        Text(stringResource(R.string.permission_review_open_subagent))
                    }
                } else {
                    TextButton(onClick = { showDetails = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            },
            dismissButton =
                reviewerRun?.let {
                    {
                        TextButton(onClick = { showDetails = false }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                },
        )
    }
}

@Composable
internal fun ToolExecutionResultDisplay(
    toolName: String,
    result: String,
    isSuccess: Boolean,
    modifier: Modifier = Modifier,
    summaryPrefix: String? = null,
    enableDialog: Boolean = true,
) {
    val context = LocalContext.current
    // 消息内容里的 <tool_result> 块等入口不经过 resolveResultText，这里兜底把权限拒绝原文换成结论；
    // 已经换过的文本再走一次是幂等的。
    val displayedResult = remember(context, result, isSuccess) {
        if (isSuccess) result else permissionDenialDisplayText(context, result)
    }
    val shellState = remember(toolName, displayedResult, isSuccess) {
        if (isSuccess) shellResultState(toolName, displayedResult) else null
    }
    if (shellState != null) {
        ShellTaskResultDisplay(toolName, displayedResult, shellState, modifier, summaryPrefix, enableDialog)
        return
    }
    val fileDiff = remember(toolName, displayedResult, isSuccess) {
        parseFileDiffResult(toolName, displayedResult, isSuccess)
    }
    if (fileDiff != null) {
        FileDiffDisplay(
            diff = fileDiff,
            modifier = modifier,
            summaryPrefix = summaryPrefix,
            enableDialog = enableDialog,
        )
    } else {
        ToolResultDisplay(
            toolName = toolName,
            result = displayedResult,
            isSuccess = isSuccess,
            modifier = modifier,
            summaryPrefix = summaryPrefix,
            enableDialog = enableDialog,
        )
    }
}

internal fun parseFileDiffResult(
    toolName: String,
    result: String,
    isSuccess: Boolean,
): FileDiff? {
    if (!isSuccess || toolName !in FILE_EDIT_TOOL_NAMES) return null
    if (!result.contains("<file-diff")) return null

    // 容忍尾部截断：结果文本超过持久化上限（MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS）
    // 被截断时，截断点可能落在 </file-diff> 或 CDATA 闭合符内。此时仍渲染可用的
    // 部分 diff，而不是回退为普通工具结果展示。
    val fileDiffBlock =
        Regex("""<file-diff\b[^>]*>[\s\S]*?(?:</file-diff\s*>|$)""")
            .find(result)
            ?.value
            ?: return null
    val path = Regex("""<file-diff\s+[^>]*path="([^"]+)"""")
        .find(fileDiffBlock)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val details = Regex("""\bdetails="([^"]*)"""")
        .find(fileDiffBlock)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    val diffContent =
        Regex("""<!\[CDATA\[([\s\S]*?)(?:]]>|$)""")
            .find(fileDiffBlock)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        ?: return null
    return FileDiff(path = path, diffContent = diffContent, details = details)
}

@Composable
private fun ToolPendingStatusRow(
    text: String,
    modifier: Modifier,
    isSuccess: Boolean = true,
    showStatusIcon: Boolean = false,
    onClick: (() -> Unit)? = null,
    onStopClick: (() -> Unit)? = null,
) {
    CanvasToolResultRow(
        summary = text,
        isSuccess = isSuccess,
        semanticDescription = text,
        modifier = modifier,
        showStatusIcon = showStatusIcon,
        onClick = onClick,
        onStopClick = onStopClick,
        stopDescription = stringResource(R.string.subagent_stop_task),
    )
}

@Composable
private fun SubagentTaskResultRow(
    agentName: String,
    summary: String,
    modifier: Modifier,
    isSuccess: Boolean,
    /** A run that stopped without failing is not a failed one, and it says so in its own tone. */
    isInterrupted: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val accentColor =
        when {
            isInterrupted -> MaterialTheme.colorScheme.stoppedAttention
            isSuccess -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        }
    val rowClickModifier =
        if (onClick != null) {
            Modifier.clickable(
                role = Role.Button,
                onClick = onClick,
            )
        } else {
            Modifier
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .then(rowClickModifier)
                .padding(start = 24.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.SubdirectoryArrowRight,
            contentDescription = null,
            tint = accentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = null,
            tint = accentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agentName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (isSuccess || isInterrupted) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What a collaboration call did, in the words its own row carries. A v1 `task` is not one of them:
 * that row is about the run it created and keeps the renderer that shows the run itself.
 */
internal enum class SubagentCallKind {
    DISPATCHED,
    MESSAGE_SENT,
    TASK_FOLLOWUP,
}

internal fun subagentCallKind(toolName: String): SubagentCallKind? =
    when (toolName) {
        "spawn_agent" -> SubagentCallKind.DISPATCHED
        "send_message" -> SubagentCallKind.MESSAGE_SENT
        "followup_task" -> SubagentCallKind.TASK_FOLLOWUP
        else -> null
    }

/** Why a collaboration row reports a failure of its own, or null when its call went through. */
internal enum class SubagentCallFailure {
    /** The call never started, so nothing was dispatched or delivered. */
    NEVER_RAN,

    /** The call ran and came back with an execution error. */
    EXECUTION_ERROR,
}

/**
 * Whether a collaboration call has a failure of its own to report. The row records what the call
 * did and nothing else: what the agent did afterwards belongs to the rows that carry its messages
 * and its result, so the run's later state never reaches this row. Only the call's own end does —
 * it never started, or it came back with an execution error while no run stands behind it.
 */
internal fun subagentCallFailure(
    fallbackState: ToolExecutionState,
    executionSuccess: Boolean,
    runOwnedByCall: Boolean,
): SubagentCallFailure? =
    when {
        // A run of its own says the call handed the work over, whatever the call did afterwards.
        runOwnedByCall -> null
        fallbackState == ToolExecutionState.NOT_EXECUTED -> SubagentCallFailure.NEVER_RAN
        fallbackState == ToolExecutionState.COMPLETED && !executionSuccess ->
            SubagentCallFailure.EXECUTION_ERROR
        else -> null
    }

/**
 * The run a collaboration row stands for: the one this call created, or failing that the agent it
 * addressed. A spawn row is the call's own, so its run answers to the call id; a follow-up row
 * writes to an agent that is already running and only knows the name it wrote to, which is the same
 * name the run wears as its path.
 */
internal fun findCallRun(
    runs: List<SubagentRunEntity>,
    callId: String?,
    target: String?,
): SubagentRunEntity? {
    val candidates =
        runs.filter {
            it.externalOwnerType == CollaborationCoordinator.OWNER_TYPE &&
                it.archivedAt == null &&
                it.agentProfileId != AgentProfileRepository.PERMISSION_REVIEWER_ID
        }
    val call = callId?.takeIf { it.isNotBlank() }
    if (call != null) {
        candidates.lastOrNull { it.parentToolCallId == call }?.let { return it }
    }
    val name = target?.takeIf { it.isNotBlank() } ?: return null
    val seed = agentBadgeSeed(name)
    return candidates.lastOrNull {
        val owner = it.externalOwnerId
        !owner.isNullOrBlank() && agentBadgeSeed(owner) == seed
    }
}

/**
 * The call side of a v2 collaboration: the row keeps the addressed agent's badge and says what the
 * call did — a task handed over, a message delivered — instead of repeating the run's live status,
 * and tapping it opens the floating card that shows what the call handed over.
 */
@Composable
private fun SubagentCallStatusDisplay(
    callKind: SubagentCallKind,
    callId: String?,
    fallbackState: ToolExecutionState,
    executionSuccess: Boolean,
    executionResultText: String,
    requestedAgentName: String?,
    bodyText: String?,
    reviewEvent: PermissionReviewEvent?,
    enableDialogs: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { SubagentRunRepository.getInstance(context) }
    val chatCore =
        remember(context) {
            ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)
        }
    val parentChatId by chatCore.currentChatId.collectAsState()
    val sharedRuns = LocalTranscriptRuns.current?.takeIf { it.chatId == parentChatId }
    val runsFlow =
        remember(parentChatId, repository) {
            parentChatId?.takeIf { it.isNotBlank() }?.let { repository.observeByParentChatId(it) }
        }
    val runs =
        sharedRuns?.runs
            ?: (runsFlow ?: flowOf(emptyList())).collectAsState(initial = emptyList()).value
    val run =
        remember(runs, callId, requestedAgentName) {
            findCallRun(runs, callId, requestedAgentName)
        }
    val childChatId = run?.childChatId
    val agentPath =
        run?.externalOwnerId?.takeIf { it.isNotBlank() }
            ?: requestedAgentName?.takeIf { it.isNotBlank() }
            ?: "subagent"
    val failure =
        subagentCallFailure(
            fallbackState = fallbackState,
            executionSuccess = executionSuccess,
            runOwnedByCall =
                callId?.isNotBlank() == true && run?.parentToolCallId == callId,
        )
    val openConversation: (() -> Unit)? =
        if (childChatId.isNullOrBlank()) {
            null
        } else {
            { chatCore.switchChat(childChatId, scrollToBottom = false) }
        }

    Column(modifier = modifier) {
        reviewEvent?.let { event ->
            PermissionReviewLifecycleDisplay(event, enableDialogs = enableDialogs)
        }
        SubagentAgentCard(
            agentPath = agentPath,
            statusText =
                when (failure) {
                    null -> stringResource(subagentCallKindLabelRes(callKind))
                    SubagentCallFailure.NEVER_RAN -> stringResource(R.string.tool_not_executed)
                    SubagentCallFailure.EXECUTION_ERROR -> stringResource(R.string.execution_failed)
                },
            identity = remember(agentPath) { subagentAgentIdentity(agentPath) },
            statusColor =
                if (failure == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            modifier = Modifier.padding(start = 24.dp, end = 8.dp, bottom = 8.dp),
            chatId = parentChatId,
            childChatId = childChatId,
            body = bodyText,
            failureText =
                if (failure == null) null else executionResultText.takeIf { it.isNotBlank() },
            onOpenConversation = openConversation,
        )
    }
}

private fun subagentCallKindLabelRes(callKind: SubagentCallKind): Int =
    when (callKind) {
        SubagentCallKind.DISPATCHED -> R.string.subagent_status_dispatched
        SubagentCallKind.MESSAGE_SENT -> R.string.subagent_message_sent
        SubagentCallKind.TASK_FOLLOWUP -> R.string.subagent_task_followup
    }

@Composable
private fun SubagentTaskStatusDisplay(
    callId: String?,
    fallbackState: ToolExecutionState,
    fallbackStartedAtElapsedMs: Long?,
    fallbackDurationMs: Long?,
    executionSuccess: Boolean,
    executionResultText: String,
    requestedSubagentName: String?,
    requestedSubagentTaskId: String?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val repository = remember(context) { SubagentRunRepository.getInstance(context) }
    val chatCore =
        remember(context) {
            ChatRuntimeHolder.getInstance(context.applicationContext)
                .getCore(ChatRuntimeSlot.MAIN)
        }
    val parentChatId by chatCore.currentChatId.collectAsState()
    val runFlow =
        remember(parentChatId, callId, requestedSubagentTaskId) {
            when (
                val lookup =
                    resolveSubagentRunLookup(
                        requestedTaskId = requestedSubagentTaskId,
                        parentChatId = parentChatId,
                        callId = callId,
                    )
            ) {
                is SubagentRunLookup.TaskId ->
                    repository.observeById(lookup.taskId).map { candidate ->
                        candidate?.takeIf { it.parentChatId == lookup.parentChatId }
                    }
                is SubagentRunLookup.ParentCall ->
                    repository.observeByParentToolCallId(
                        lookup.parentChatId,
                        lookup.callId,
                        com.ai.assistance.operit.core.agent.AgentProfileRepository
                            .PERMISSION_REVIEWER_ID,
                    )
                null -> flowOf(null)
            }
        }
    val sharedRuns = com.ai.assistance.operit.ui.features.chat.components.LocalTranscriptRuns.current
        ?.takeIf { it.chatId == parentChatId }
    val run = if (sharedRuns != null) {
        when (val lookup = resolveSubagentRunLookup(requestedSubagentTaskId, parentChatId, callId)) {
            is SubagentRunLookup.TaskId -> sharedRuns.runs.firstOrNull { it.id == lookup.taskId }
            is SubagentRunLookup.ParentCall -> sharedRuns.runs.lastOrNull {
                it.parentToolCallId == lookup.callId &&
                    it.agentProfileId != com.ai.assistance.operit.core.agent.AgentProfileRepository.PERMISSION_REVIEWER_ID
            }
            null -> null
        }
    } else runFlow.collectAsState(initial = null).value

    if (run == null) {
        var fallbackElapsedMs by
            remember(fallbackStartedAtElapsedMs, fallbackDurationMs) {
                mutableLongStateOf(
                    fallbackDurationMs
                        ?: fallbackStartedAtElapsedMs
                            ?.let {
                                (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
                            }
                        ?: 0L
                )
            }
        LaunchedEffect(fallbackState, fallbackStartedAtElapsedMs, fallbackDurationMs) {
            while (
                fallbackState == ToolExecutionState.RUNNING &&
                    fallbackStartedAtElapsedMs != null
            ) {
                fallbackElapsedMs =
                    (SystemClock.elapsedRealtime() - fallbackStartedAtElapsedMs)
                        .coerceAtLeast(0L)
                delay(100L)
            }
        }

        val fallbackStatusText =
            when (fallbackState) {
                ToolExecutionState.WAITING_AUTHORIZATION ->
                    stringResource(R.string.tool_waiting_authorization)
                ToolExecutionState.WAITING_EXECUTION ->
                    stringResource(R.string.tool_waiting_execution)
                ToolExecutionState.RUNNING -> stringResource(R.string.subagent_status_thinking)
                ToolExecutionState.COMPLETED -> stringResource(R.string.subagent_status_completed)
                ToolExecutionState.NOT_EXECUTED -> stringResource(R.string.tool_not_executed)
            }
        val fallbackDurationText =
            if (fallbackDurationMs != null || fallbackStartedAtElapsedMs != null) {
                formatToolExecutionDuration(context, fallbackElapsedMs)
            } else {
                null
            }
        val rowContent =
            buildSubagentTaskRowContent(
                agentName = requestedSubagentName,
                durationText = fallbackDurationText,
                statusText = fallbackStatusText,
            )
        val isFallbackTerminal =
            fallbackState == ToolExecutionState.COMPLETED ||
                fallbackState == ToolExecutionState.NOT_EXECUTED
        val fallbackResult =
            permissionDenialDisplayText(context, extractSubagentTaskResult(executionResultText))
                .ifBlank { stringResource(R.string.subagent_result_empty) }
        var showFallbackResultDialog by
            remember(callId, fallbackState) {
                androidx.compose.runtime.mutableStateOf(false)
            }

        if (showFallbackResultDialog && isFallbackTerminal) {
            ToolResultDetailDialog(
                toolName = "task",
                result = fallbackResult,
                isSuccess = executionSuccess,
                titleOverride =
                    stringResource(
                        R.string.subagent_result_title,
                        rowContent.title,
                    ),
                metadata = rowContent.summary,
                onDismiss = { showFallbackResultDialog = false },
                onCopy = {
                    clipboardManager.setText(AnnotatedString(fallbackResult))
                },
            )
        }

        SubagentTaskResultRow(
            agentName = rowContent.title,
            summary = rowContent.summary,
            modifier = modifier,
            isSuccess =
                fallbackState != ToolExecutionState.NOT_EXECUTED &&
                    (!isFallbackTerminal || executionSuccess),
            onClick =
                if (isFallbackTerminal) {
                    { showFallbackResultDialog = true }
                } else {
                    null
                },
        )
        return
    }

    val resolvedRun = requireNotNull(run)
    val parentRunsFlow =
        remember(resolvedRun.parentChatId) {
            repository.observeByParentChatId(resolvedRun.parentChatId)
        }
    val parentRuns = sharedRuns?.runs ?: parentRunsFlow.collectAsState(initial = emptyList()).value
    val processingStates by chatCore.inputProcessingStateByChatId.collectAsState()
    val lastToolNames by chatCore.lastToolNameByChatId.collectAsState()
    val lastTurnToolInvocationCounts by
        chatCore.lastTurnToolInvocationCountByChatId.collectAsState()
    val childProcessingState = processingStates[resolvedRun.childChatId]
    val status =
        runCatching { SubagentRunStatus.valueOf(resolvedRun.status) }
            .getOrDefault(SubagentRunStatus.FAILED)

    var nowMs by remember(resolvedRun.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status, resolvedRun.startedAt, resolvedRun.completedAt) {
        while (
            status == SubagentRunStatus.CREATED ||
                status == SubagentRunStatus.QUEUED ||
                status == SubagentRunStatus.RUNNING
        ) {
            nowMs = System.currentTimeMillis()
            delay(100L)
        }
    }
    val startMs = resolvedRun.startedAt ?: resolvedRun.createdAt
    val endMs = resolvedRun.completedAt ?: nowMs
    val durationText =
        formatToolExecutionDuration(context, (endMs - startMs).coerceAtLeast(0L))
    val queuePosition =
        parentRuns
            .asSequence()
            .filter { it.status == SubagentRunStatus.QUEUED.name }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .indexOfFirst { it.id == resolvedRun.id }
            .let { if (it >= 0) it + 1 else 1 }
    val currentTool =
        resolveSubagentDisplayedTool(
            childProcessingState = childProcessingState,
            lastToolName = lastToolNames[resolvedRun.childChatId],
        )
    val toolInvocationCount =
        maxOf(
            lastTurnToolInvocationCounts[resolvedRun.childChatId]?.coerceAtLeast(0) ?: 0,
            resolvedRun.toolInvocationCount.coerceAtLeast(0),
        )
    val runIsTerminal =
        status == SubagentRunStatus.COMPLETED ||
            status == SubagentRunStatus.FAILED ||
            status == SubagentRunStatus.INTERRUPTED ||
            status == SubagentRunStatus.CANCELLED
    val resultIsSynchronizing =
        runIsTerminal &&
            executionResultText.isBlank() &&
            fallbackState != ToolExecutionState.COMPLETED &&
            fallbackState != ToolExecutionState.NOT_EXECUTED
    val baseStatusText =
        when (status) {
            SubagentRunStatus.CREATED -> stringResource(R.string.subagent_status_creating)
            SubagentRunStatus.QUEUED ->
                stringResource(R.string.subagent_status_queued, queuePosition)
            SubagentRunStatus.RUNNING ->
                if (currentTool.isNullOrBlank()) {
                    stringResource(R.string.subagent_status_thinking)
                } else {
                    stringResource(R.string.subagent_status_calling_tool, currentTool)
                }
            SubagentRunStatus.COMPLETED ->
                if (resultIsSynchronizing) {
                    stringResource(R.string.subagent_status_syncing_result)
                } else if (toolInvocationCount > 0) {
                    stringResource(
                        R.string.subagent_status_completed_with_tool_count,
                        toolInvocationCount,
                    )
                } else {
                    stringResource(R.string.subagent_status_completed)
                }
            SubagentRunStatus.FAILED -> stringResource(R.string.subagent_status_error)
            SubagentRunStatus.INTERRUPTED ->
                stringResource(R.string.subagent_status_interrupted)
            SubagentRunStatus.CANCELLED -> stringResource(R.string.subagent_status_cancelled)
        }
    val agentName =
        resolvedRun.agentProfileId.ifBlank {
            requestedSubagentName?.takeIf { it.isNotBlank() } ?: "subagent"
        }
    val statusText =
        stringResource(
            R.string.subagent_status_with_agent,
            agentName,
            baseStatusText,
        )
    val rowContent =
        buildSubagentTaskRowContent(
            agentName = agentName,
            durationText = durationText,
            statusText = baseStatusText,
        )
    val isSuccess =
        status != SubagentRunStatus.FAILED &&
            status != SubagentRunStatus.INTERRUPTED &&
            status != SubagentRunStatus.CANCELLED
    val isInterrupted = status == SubagentRunStatus.INTERRUPTED
    // A run the app stopped still owes the user the reason it stopped, the way the chat card does.
    val interruptionNote =
        if (isInterrupted) {
            subagentInterruptionHintRes(SubagentCardStatus.INTERRUPTED, resolvedRun.error)
                ?.let { stringResource(it) }
        } else {
            null
        }
    val isTerminal =
        runIsTerminal && !resultIsSynchronizing
    val terminalResult =
        permissionDenialDisplayText(context, extractSubagentTaskResult(executionResultText))
            .ifBlank { stringResource(R.string.subagent_result_empty) }
    var showResultDialog by remember(resolvedRun.id) { androidx.compose.runtime.mutableStateOf(false) }

    if (showResultDialog && isTerminal) {
        ToolResultDetailDialog(
            toolName = "task",
            result = terminalResult,
            isSuccess = isSuccess,
            stoppedNote = interruptionNote,
            titleOverride =
                stringResource(
                    R.string.subagent_result_title,
                    resolvedRun.title,
                ),
            metadata = "$durationText · $statusText",
            onDismiss = { showResultDialog = false },
            onCopy = {
                clipboardManager.setText(AnnotatedString(terminalResult))
            },
            primaryActionLabel = stringResource(R.string.subagent_open_conversation),
            onPrimaryAction = {
                showResultDialog = false
                chatCore.switchChat(
                    resolvedRun.childChatId,
                    scrollToBottom = false,
                )
            },
        )
    }

    SubagentTaskResultRow(
        agentName = rowContent.title,
        summary = rowContent.summary,
        modifier = modifier,
        isSuccess = isSuccess,
        isInterrupted = isInterrupted,
        onClick = {
            if (isTerminal) {
                showResultDialog = true
            } else {
                chatCore.switchChat(
                    resolvedRun.childChatId,
                    scrollToBottom = false,
                )
            }
        },
    )
}

internal fun extractSubagentTaskResult(rawResult: String): String {
    val result =
        Regex("""<task_result>([\s\S]*?)</task_result>""", RegexOption.IGNORE_CASE)
            .find(rawResult)
            ?.groupValues
            ?.getOrNull(1)
        ?: Regex("""<task_error>([\s\S]*?)</task_error>""", RegexOption.IGNORE_CASE)
            .find(rawResult)
            ?.groupValues
            ?.getOrNull(1)
        ?: rawResult

    return result
        .trim()
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

internal fun resolveSubagentDisplayedTool(
    childProcessingState: InputProcessingState?,
    lastToolName: String?,
): String? =
    when (childProcessingState) {
        is InputProcessingState.ExecutingTool -> childProcessingState.toolName
        is InputProcessingState.ToolProgress -> childProcessingState.toolName
        is InputProcessingState.ProcessingToolResult -> childProcessingState.toolName
        else -> lastToolName
    }?.takeIf { it.isNotBlank() }

private fun resolveElapsedMs(snapshot: ToolExecutionTimingSnapshot?): Long {
    val startedAt = snapshot?.startedAtElapsedMs ?: return 0L
    return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
}

private fun resolveResultText(
    context: Context,
    liveExecution: ToolExecutionTimingSnapshot?,
    persistedExecution: PersistedToolExecution?,
    success: Boolean,
): String {
    val raw =
        if (persistedExecution != null) {
            persistedExecution.resultText
        } else if (liveExecution != null) {
            if (success) liveExecution.resultText else liveExecution.errorText.ifBlank {
                liveExecution.resultText
            }
        } else {
            ""
        }
    if (success) return raw

    val text = ChatMarkupRegex.errorTag.find(raw)?.groupValues?.getOrNull(1)?.trim() ?: raw
    return permissionDenialDisplayText(context, text)
}

/**
 * A permission denial carries a long English instruction written for the model, so the transcript
 * reports the outcome instead of pasting that instruction as the tool result.
 *
 * A subagent turn that the review stopped ends as "Turn <id> failed: <reason>", so the wrapper is
 * dropped as well; the turn id means nothing to the reader and would otherwise hide the prefix.
 */
internal fun permissionDenialDisplayText(context: Context, text: String): String =
    permissionDenialSummary(context, stripTurnFailedWrapper(text)) ?: text

/**
 * A turn that ends in a failure reports it as "Turn <id> failed: <reason>". The id is internal
 * bookkeeping, so a reason that starts with a permission prefix is only recognizable once it goes.
 */
internal fun stripTurnFailedWrapper(text: String): String =
    TURN_FAILED_WRAPPER.replaceFirst(text, "")

private val TURN_FAILED_WRAPPER = Regex("""^Turn\s+\S+\s+failed:\s*""")

/**
 * The reader's version of the note stored on a review event, or null when the note says nothing
 * about the call: a reused score, a call the circuit breaker skipped, or a review that broke all
 * store an English sentence about the machinery, and the UI already names those outcomes itself.
 */
internal fun permissionReviewNoteForDisplay(
    context: Context,
    rationale: String?,
    failureKind: PermissionReviewFailureKind?,
): String? =
    when {
        rationale.isNullOrBlank() -> null
        failureKind != null -> null
        rationale in INTERNAL_REVIEW_NOTES -> null
        rationale == ToolPermissionSystem.REVIEW_SKIPPED_RATIONALE ->
            context.getString(R.string.permission_denied_result_auto_review_cancelled)
        else -> rationale
    }

/** Notes the app itself writes onto a review event; none of them describe the call. */
private val INTERNAL_REVIEW_NOTES =
    setOf(
        ToolPermissionSystem.FAST_REVIEW_RATIONALE,
        PermissionReviewResponsePolicy.NO_RATIONALE_ALLOW,
        PermissionReviewResponsePolicy.NO_RATIONALE_DENY,
        PermissionReviewResponsePolicy.REVIEW_CANCELLED_RATIONALE,
    )

internal fun formatToolExecutionDuration(context: Context, durationMs: Long): String {
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    return when {
        safeDurationMs < 1_000L ->
            context.getString(R.string.tool_duration_milliseconds, safeDurationMs)
        safeDurationMs < 60_000L ->
            context.getString(R.string.tool_duration_seconds, safeDurationMs / 1_000.0)
        else -> {
            val totalSeconds = safeDurationMs / 1_000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            context.getString(R.string.tool_duration_minutes_seconds, minutes, seconds)
        }
    }
}
