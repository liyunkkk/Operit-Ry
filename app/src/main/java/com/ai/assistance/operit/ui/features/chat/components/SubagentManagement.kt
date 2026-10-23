package com.ai.assistance.operit.ui.features.chat.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.api.chat.library.MemoryLearningCoordinator
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.features.chat.components.part.formatToolExecutionDuration
import com.ai.assistance.operit.ui.features.chat.components.part.resolveSubagentDisplayedTool
import com.ai.assistance.operit.ui.features.chat.components.part.permissionReviewNoteForDisplay
import com.ai.assistance.operit.ui.features.tokenstats.formatCount
import com.ai.assistance.operit.ui.theme.stoppedAttention
import com.ai.assistance.operit.ui.permissions.PRIOR_REVIEW_FINGERPRINT_CHARS
import com.ai.assistance.operit.ui.permissions.PermissionReviewOutcome
import com.ai.assistance.operit.ui.permissions.PermissionReviewEvent
import com.ai.assistance.operit.ui.permissions.PermissionReviewEventRepository
import com.ai.assistance.operit.ui.permissions.PermissionReviewFailureKind
import com.ai.assistance.operit.ui.permissions.permissionReviewJumpMarker
import com.ai.assistance.operit.ui.permissions.PermissionReviewAuthorization
import com.ai.assistance.operit.ui.permissions.PermissionReviewRiskLevel
import com.ai.assistance.operit.ui.permissions.PermissionReviewStatus
import com.ai.assistance.operit.ui.permissions.PermissionReviewResponsePolicy
import com.ai.assistance.operit.ui.permissions.PermissionRiskScoreDisplay
import com.ai.assistance.operit.ui.permissions.PermissionRiskScoreRecord
import com.ai.assistance.operit.ui.permissions.PermissionRiskScoreRepository
import com.ai.assistance.operit.ui.permissions.PermissionRiskScoringSkip
import com.ai.assistance.operit.ui.permissions.PermissionRiskVerdict
import com.ai.assistance.operit.ui.permissions.ToolPermissionStop
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import com.ai.assistance.operit.ui.permissions.cacheReadPercent
import com.ai.assistance.operit.ui.permissions.display
import com.ai.assistance.operit.ui.permissions.inputTokens
import com.ai.assistance.operit.ui.permissions.permissionRiskScoreSummary
import kotlinx.coroutines.delay

/**
 * The list separates what the user asked for from what the app does on its own: the agents they
 * delegated to, and the background runs (the permission review, the memory extraction).
 */
internal enum class SubagentTaskKind {
    FOREGROUND,
    BACKGROUND,
}

/** The status narrowing inside one [SubagentTaskKind], offered behind the filter button. */
internal enum class SubagentRunStatusFilter {
    ALL,
    RUNNING,
    QUEUED,
    COMPLETED,
    INTERRUPTED,
    ERROR,
    ARCHIVED,
}

/** The two choices the management list opens on. */
internal data class SubagentListSelection(
    val kind: SubagentTaskKind,
    val status: SubagentRunStatusFilter,
)

private enum class SubagentManagementPage {
    RUNS,
    RECENT_DENIALS,
    RISK_SCORES,
}

internal enum class PermissionReviewRunDisplayState {
    ALLOWED,
    DENIED,
    INVALID_OUTPUT,
    CANCELLED_OR_TIMED_OUT,
    INTERRUPTED,
    ERROR,
}

internal suspend fun resolvePermissionReviewRunDisplayState(
    status: SubagentRunStatus,
    finalAssistantText: String?,
    reviewEvent: PermissionReviewEvent? = null,
): PermissionReviewRunDisplayState? =
    when (reviewEvent?.status) {
        PermissionReviewStatus.APPROVED -> PermissionReviewRunDisplayState.ALLOWED
        PermissionReviewStatus.DENIED -> PermissionReviewRunDisplayState.DENIED
        PermissionReviewStatus.TIMED_OUT,
        PermissionReviewStatus.ABORTED -> PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT
        PermissionReviewStatus.FAILED ->
            if (reviewEvent.failureKind == PermissionReviewFailureKind.INVALID_OUTPUT) {
                PermissionReviewRunDisplayState.INVALID_OUTPUT
            } else {
                PermissionReviewRunDisplayState.ERROR
            }
        PermissionReviewStatus.IN_PROGRESS -> null
        null -> when (status) {
        SubagentRunStatus.COMPLETED -> {
            val decision =
                if (finalAssistantText == null) {
                    null
                } else {
                    PermissionReviewResponsePolicy.extractToolCallAndEnforce(finalAssistantText)
                }
            when (decision?.outcome) {
                PermissionReviewOutcome.ALLOW -> PermissionReviewRunDisplayState.ALLOWED
                PermissionReviewOutcome.DENY -> PermissionReviewRunDisplayState.DENIED
                null -> PermissionReviewRunDisplayState.INVALID_OUTPUT
            }
        }
        SubagentRunStatus.CANCELLED -> PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT
        SubagentRunStatus.FAILED -> PermissionReviewRunDisplayState.ERROR
        SubagentRunStatus.INTERRUPTED -> PermissionReviewRunDisplayState.INTERRUPTED
        SubagentRunStatus.CREATED,
        SubagentRunStatus.QUEUED,
        SubagentRunStatus.RUNNING -> null
        }
    }

internal fun filterAndSortSubagentRuns(
    runs: List<SubagentRunEntity>,
    kind: SubagentTaskKind,
    statusFilter: SubagentRunStatusFilter,
    query: String = "",
    autoReviewDisplayName: String = "",
    memoryExtractionDisplayName: String = "",
): List<SubagentRunEntity> {
    val normalizedQuery = query.trim()
    val filtered =
        runs.filter { run ->
            val status = run.status.toSubagentRunStatus()
            val isAutoReview =
                run.agentProfileId == AgentProfileRepository.PERMISSION_REVIEWER_ID
            val isMemoryExtraction = run.isMemoryExtractionSubagentRun()
            val matchesKind =
                when (kind) {
                    SubagentTaskKind.FOREGROUND -> !isAutoReview && !isMemoryExtraction
                    SubagentTaskKind.BACKGROUND -> isAutoReview || isMemoryExtraction
                }
            val matchesStatus =
                when (statusFilter) {
                    SubagentRunStatusFilter.ALL -> run.archivedAt == null
                    SubagentRunStatusFilter.RUNNING ->
                        run.archivedAt == null &&
                            (status == SubagentRunStatus.CREATED ||
                                status == SubagentRunStatus.RUNNING)
                    SubagentRunStatusFilter.QUEUED ->
                        run.archivedAt == null && status == SubagentRunStatus.QUEUED
                    SubagentRunStatusFilter.COMPLETED ->
                        run.archivedAt == null && status == SubagentRunStatus.COMPLETED
                    SubagentRunStatusFilter.INTERRUPTED ->
                        run.archivedAt == null && status == SubagentRunStatus.INTERRUPTED
                    SubagentRunStatusFilter.ERROR ->
                        run.archivedAt == null && status == SubagentRunStatus.FAILED
                    SubagentRunStatusFilter.ARCHIVED -> run.archivedAt != null
                }
            matchesKind &&
                matchesStatus &&
                (normalizedQuery.isEmpty() ||
                    run.agentProfileId.contains(normalizedQuery, ignoreCase = true) ||
                    run.title.contains(normalizedQuery, ignoreCase = true) ||
                    (isAutoReview &&
                        autoReviewDisplayName.contains(normalizedQuery, ignoreCase = true)) ||
                    (isMemoryExtraction &&
                        memoryExtractionDisplayName.contains(normalizedQuery, ignoreCase = true)))
        }
    return filtered.sortedWith(
        compareBy<SubagentRunEntity> {
                if (statusFilter == SubagentRunStatusFilter.ARCHIVED) {
                    0
                } else {
                    when (it.status.toSubagentRunStatus()) {
                        SubagentRunStatus.CREATED,
                        SubagentRunStatus.RUNNING -> 0
                        SubagentRunStatus.QUEUED -> 1
                        else -> 2
                    }
                }
            }
            .thenByDescending { it.archivedAt ?: it.createdAt }
            .thenByDescending { it.id }
    )
}

internal fun SubagentRunEntity.isActiveSubagentRun(): Boolean =
    when (status.toSubagentRunStatus()) {
        SubagentRunStatus.CREATED,
        SubagentRunStatus.QUEUED,
        SubagentRunStatus.RUNNING -> true
        else -> false
    }

/**
 * The hidden background extraction the memory library audits. It is not an agent the user delegated
 * to, so it belongs to the background runs.
 */
internal fun SubagentRunEntity.isMemoryExtractionSubagentRun(): Boolean =
    externalOwnerType == MemoryLearningCoordinator.OWNER_TYPE

internal fun SubagentRunEntity.isPermissionReviewSubagentRun(): Boolean =
    agentProfileId == AgentProfileRepository.PERMISSION_REVIEWER_ID

/** A run the app starts on its own, which the list keeps apart from the delegated agents. */
internal fun SubagentRunEntity.isBackgroundSubagentRun(): Boolean =
    isPermissionReviewSubagentRun() || isMemoryExtractionSubagentRun()

/**
 * The list opens on the category that has something in it, and lands on the archive when the
 * category it picked only holds archived runs, so the first screen is never blank.
 */
internal fun initialSubagentListSelection(
    runs: List<SubagentRunEntity>,
    hasPermissionReviewEvents: Boolean,
): SubagentListSelection {
    val hasActiveForegroundRuns =
        runs.any { run -> run.archivedAt == null && !run.isBackgroundSubagentRun() }
    val hasActiveBackgroundRuns =
        runs.any { run -> run.archivedAt == null && run.isBackgroundSubagentRun() }
    val kind =
        when {
            // A category that is doing something opens first, so the list starts on live work.
            hasActiveForegroundRuns -> SubagentTaskKind.FOREGROUND
            // A review event without a run row of its own is background work too.
            hasActiveBackgroundRuns || hasPermissionReviewEvents -> SubagentTaskKind.BACKGROUND
            // Otherwise the category that at least has history, archived or not.
            runs.any { run -> !run.isBackgroundSubagentRun() } -> SubagentTaskKind.FOREGROUND
            runs.any { run -> run.isBackgroundSubagentRun() } -> SubagentTaskKind.BACKGROUND
            else -> SubagentTaskKind.FOREGROUND
        }
    val kindRuns = runs.filter { run -> run.belongsTo(kind) }
    val status =
        if (kindRuns.none { it.archivedAt == null } && kindRuns.any { it.archivedAt != null }) {
            SubagentRunStatusFilter.ARCHIVED
        } else {
            SubagentRunStatusFilter.ALL
        }
    return SubagentListSelection(kind = kind, status = status)
}

internal fun SubagentRunEntity.belongsTo(kind: SubagentTaskKind): Boolean =
    (kind == SubagentTaskKind.BACKGROUND) == isBackgroundSubagentRun()

/**
 * A review event whose run row is gone has no status of its own, so it is only listed in the full
 * background list and it is counted there only, keeping the counter and the rows in step.
 */
internal fun showsOrphanPermissionReviewEvents(
    kind: SubagentTaskKind,
    status: SubagentRunStatusFilter,
): Boolean = kind == SubagentTaskKind.BACKGROUND && status == SubagentRunStatusFilter.ALL

internal fun findPermissionReviewEventForRun(
    events: List<PermissionReviewEvent>,
    run: SubagentRunEntity,
): PermissionReviewEvent? =
    events.lastOrNull { event ->
        event.parentChatId == run.parentChatId &&
            (
                event.reviewerTaskId == run.id ||
                    (
                        event.reviewerTaskId == null &&
                            !run.parentToolCallId.isNullOrBlank() &&
                            event.action.targetId == run.parentToolCallId
                        )
                )
    }

/**
 * Files the landing spot for a jump into [run]'s reviewer conversation.
 *
 * Several reviews of one conversation share a reviewer chat, so opening it tells the reader which
 * conversation the review ran in but not which review they asked for. The request names the review, so
 * the transcript lands on the exchange that judged it instead of wherever the chat last was; a run with
 * no review event, or an ordinary sub-agent run, leaves nothing to land on.
 */
private fun requestReviewJump(run: SubagentRunEntity, event: PermissionReviewEvent?) {
    if (event == null) return
    TranscriptJumpHost.request(run.childChatId, permissionReviewJumpMarker(event.id))
}

internal fun findSubagentRunForPermissionReviewEvent(
    runs: List<SubagentRunEntity>,
    event: PermissionReviewEvent,
): SubagentRunEntity? =
    runs.firstOrNull { run ->
        run.parentChatId == event.parentChatId &&
            run.agentProfileId == AgentProfileRepository.PERMISSION_REVIEWER_ID &&
            (
                run.id == event.reviewerTaskId ||
                    (
                        event.reviewerTaskId == null &&
                            !run.parentToolCallId.isNullOrBlank() &&
                            run.parentToolCallId == event.action.targetId
                        )
                )
    }

internal fun visiblePermissionReviewEvents(
    runs: List<SubagentRunEntity>,
    events: List<PermissionReviewEvent>,
): List<PermissionReviewEvent> =
    events.filter { event ->
        findSubagentRunForPermissionReviewEvent(runs, event)?.archivedAt == null
    }

private fun String.toSubagentRunStatus(): SubagentRunStatus =
    runCatching { SubagentRunStatus.valueOf(this) }.getOrDefault(SubagentRunStatus.FAILED)

@Composable
internal fun SubagentManageButton(
    hasActiveRun: Boolean,
    reviewCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp),
    ) {
        Box {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription =
                    if (hasActiveRun && reviewCount > 0) {
                        stringResource(
                            R.string.subagent_manage_active_review_count,
                            reviewCount,
                        )
                    } else if (hasActiveRun) {
                        stringResource(R.string.subagent_manage_active)
                    } else if (reviewCount > 0) {
                        stringResource(R.string.subagent_manage_review_count, reviewCount)
                    } else {
                        stringResource(R.string.subagent_manage)
                    },
                modifier = Modifier.size(20.dp),
            )
            if (hasActiveRun || reviewCount > 0) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (reviewCount > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentSwitcherSheet(
    runs: List<SubagentRunEntity>,
    currentChildChatId: String,
    onSelect: (SubagentRunEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    remember(context) { PermissionReviewEventRepository.initialize(context); true }
    val reviewEvents by PermissionReviewEventRepository.events.collectAsState()
    val sortedRuns = remember(runs, currentChildChatId) {
        val currentRun = runs.firstOrNull { it.childChatId == currentChildChatId }
        // The run being read stays listed, so its own category is the one to show.
        val currentKind =
            if (currentRun?.isBackgroundSubagentRun() == true) {
                SubagentTaskKind.BACKGROUND
            } else {
                SubagentTaskKind.FOREGROUND
            }
        filterAndSortSubagentRuns(runs, currentKind, SubagentRunStatusFilter.ALL)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.subagent_switch_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (sortedRuns.isEmpty()) {
            Text(
                text = stringResource(R.string.subagent_manage_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(
                    items = sortedRuns,
                    key = { it.id },
                ) { run ->
                    val reviewEvent = findPermissionReviewEventForRun(reviewEvents, run)
                    SubagentRunRow(
                        run = run,
                        reviewEvent = reviewEvent,
                        selected = run.childChatId == currentChildChatId,
                        showActions = false,
                        onClick = {
                            requestReviewJump(run, reviewEvent)
                            onSelect(run)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubagentManagementDialog(
    parentChatId: String,
    parentTitle: String,
    runs: List<SubagentRunEntity>,
    currentChildChatId: String?,
    onSelect: (SubagentRunEntity) -> Unit,
    onStop: (SubagentRunEntity) -> Unit,
    onArchive: (SubagentRunEntity) -> Unit,
    onRestore: (SubagentRunEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    remember(context) { PermissionReviewEventRepository.initialize(context); true }
    remember(context) { PermissionRiskScoreRepository.initialize(context); true }
    val autoReviewDisplayName = stringResource(R.string.agent_profile_builtin_permission_reviewer_name)
    val memoryExtractionDisplayName = stringResource(R.string.subagent_memory_extraction)
    val reviewEvents by PermissionReviewEventRepository.events.collectAsState()
    val riskScoreRecords by PermissionRiskScoreRepository.records.collectAsState()
    val chatRiskScoreRecords =
        remember(riskScoreRecords, parentChatId) {
            riskScoreRecords
                .filter { record -> record.parentChatId == parentChatId }
                .sortedByDescending { record -> record.startedAt }
        }
    val parentReviewEvents =
        remember(reviewEvents, parentChatId) {
            reviewEvents.filter { event -> event.parentChatId == parentChatId }
        }
    val activeParentReviewEvents =
        remember(parentReviewEvents, runs) {
            visiblePermissionReviewEvents(runs, parentReviewEvents)
        }
    val initialSelection =
        initialSubagentListSelection(
            runs = runs,
            hasPermissionReviewEvents = activeParentReviewEvents.isNotEmpty(),
        )
    var selectedKind by
        remember(parentChatId) {
            mutableStateOf(
                initialSelection.kind
            )
        }
    var selectedStatus by
        remember(parentChatId) {
            mutableStateOf(
                initialSelection.status
            )
        }
    var searchQuery by remember(parentChatId) { mutableStateOf("") }
    var currentPage by remember(parentChatId) { mutableStateOf(SubagentManagementPage.RUNS) }
    LaunchedEffect(parentChatId, initialSelection) {
        if (initialSelection.kind != SubagentTaskKind.FOREGROUND &&
            selectedKind == SubagentTaskKind.FOREGROUND
        ) {
            selectedKind = initialSelection.kind
        }
        if (initialSelection.status != SubagentRunStatusFilter.ALL &&
            selectedStatus == SubagentRunStatusFilter.ALL
        ) {
            selectedStatus = initialSelection.status
        }
    }
    val deniedReviewEvents =
        remember(activeParentReviewEvents) {
            activeParentReviewEvents
                .filter { event ->
                    event.status == PermissionReviewStatus.DENIED
                }
                .sortedByDescending { event -> event.completedAt ?: event.startedAt }
        }
    val recentDeniedReviews =
        remember(deniedReviewEvents) {
            deniedReviewEvents
                .distinctBy { event -> event.actionFingerprint }
                .take(10)
        }
    val visibleRuns =
        remember(
            runs,
            selectedKind,
            selectedStatus,
            searchQuery,
            autoReviewDisplayName,
            memoryExtractionDisplayName,
        ) {
            filterAndSortSubagentRuns(
                runs,
                kind = selectedKind,
                statusFilter = selectedStatus,
                query = searchQuery,
                autoReviewDisplayName = autoReviewDisplayName,
                memoryExtractionDisplayName = memoryExtractionDisplayName,
            )
        }
    val orphanReviewEvents =
        remember(activeParentReviewEvents, runs, selectedKind, selectedStatus, searchQuery) {
            if (!showsOrphanPermissionReviewEvents(selectedKind, selectedStatus)) {
                emptyList()
            } else {
                val normalizedQuery = searchQuery.trim()
                activeParentReviewEvents
                    .asSequence()
                    .filter { event -> findSubagentRunForPermissionReviewEvent(runs, event) == null }
                    .filter { event ->
                        normalizedQuery.isEmpty() ||
                            event.action.toolName.contains(normalizedQuery, ignoreCase = true) ||
                            event.action.summary.contains(normalizedQuery, ignoreCase = true) ||
                            event.rationale.orEmpty().contains(normalizedQuery, ignoreCase = true)
                    }
                    .sortedByDescending { event -> event.completedAt ?: event.startedAt }
                    .toList()
            }
        }
    // A review event whose run row is gone is still a row of the background list, so it counts there.
    val orphanReviewEventCount =
        remember(runs, activeParentReviewEvents) {
            activeParentReviewEvents.count { event ->
                findSubagentRunForPermissionReviewEvent(runs, event) == null
            }
        }
    val kindCounts =
        remember(runs, orphanReviewEventCount) {
            SubagentTaskKind.entries.associateWith { kind ->
                filterAndSortSubagentRuns(runs, kind, SubagentRunStatusFilter.ALL).size +
                    if (showsOrphanPermissionReviewEvents(kind, SubagentRunStatusFilter.ALL)) {
                        orphanReviewEventCount
                    } else {
                        0
                    }
            }
        }
    val statusCounts =
        remember(runs, selectedKind, orphanReviewEventCount) {
            SubagentRunStatusFilter.entries.associateWith { status ->
                filterAndSortSubagentRuns(runs, selectedKind, status).size +
                    if (showsOrphanPermissionReviewEvents(selectedKind, status)) {
                        orphanReviewEventCount
                    } else {
                        0
                    }
            }
        }

    Dialog(
        onDismissRequest = {
            if (currentPage != SubagentManagementPage.RUNS) {
                currentPage = SubagentManagementPage.RUNS
            } else {
                onDismiss()
            }
        },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text =
                                    stringResource(
                                        when (currentPage) {
                                            SubagentManagementPage.RECENT_DENIALS ->
                                                R.string.permission_review_recent_denials
                                            SubagentManagementPage.RISK_SCORES ->
                                                R.string.permission_risk_scores_title
                                            SubagentManagementPage.RUNS ->
                                                R.string.subagent_manage
                                        }
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = parentTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (currentPage != SubagentManagementPage.RUNS) {
                                    currentPage = SubagentManagementPage.RUNS
                                } else {
                                    onDismiss()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
            ) {
                if (currentPage == SubagentManagementPage.RECENT_DENIALS) {
                    RecentPermissionDenialsPage(
                        deniedEvents = deniedReviewEvents,
                        recentDeniedEvents = recentDeniedReviews,
                        runs = runs,
                        onSelectRun = onSelect,
                    )
                } else if (currentPage == SubagentManagementPage.RISK_SCORES) {
                    PermissionRiskScoresPage(
                        records = chatRiskScoreRecords,
                        // The store is shared, so a full one explains why any chat's oldest rows are
                        // gone, whether or not this chat filled it on its own.
                        trimmed = riskScoreRecords.size >= PermissionRiskScoreRepository.MAX_RECORDS,
                    )
                } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            placeholder = {
                                Text(stringResource(R.string.subagent_search_hint))
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription =
                                                stringResource(R.string.clear_search),
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                        )
                    }
                    item(key = "filters") {
                        SubagentFilterRow(
                            selectedKind = selectedKind,
                            kindCounts = kindCounts,
                            selectedStatus = selectedStatus,
                            statusCounts = statusCounts,
                            onSelectKind = { selectedKind = it },
                            onSelectStatus = { selectedStatus = it },
                        )
                        HorizontalDivider()
                    }
                    if (selectedKind == SubagentTaskKind.BACKGROUND) {
                        item(key = "recent_denied_reviews") {
                            Card(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .clickable {
                                            currentPage = SubagentManagementPage.RECENT_DENIALS
                                        },
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.permission_review_recent_denials),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.permission_review_recent_denials_summary,
                                            deniedReviewEvents.size,
                                            deniedReviewEvents.distinctBy {
                                                event -> event.actionFingerprint
                                            }.size,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.permission_review_recent_denials_open),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        item(key = "risk_scores") {
                            val riskSummary = permissionRiskScoreSummary(chatRiskScoreRecords)
                            Card(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .clickable {
                                            currentPage = SubagentManagementPage.RISK_SCORES
                                        },
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.permission_risk_scores_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.permission_risk_scores_summary,
                                            riskSummary.scored,
                                            riskSummary.low,
                                            riskSummary.answeredCalls,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.permission_risk_scores_open),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (visibleRuns.isEmpty() && orphanReviewEvents.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text =
                                    if (searchQuery.isNotBlank()) {
                                        stringResource(R.string.subagent_search_empty)
                                    } else if (selectedStatus == SubagentRunStatusFilter.ARCHIVED) {
                                        stringResource(R.string.subagent_archived_empty)
                                    } else {
                                        stringResource(R.string.subagent_manage_empty)
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        items(
                            items = visibleRuns,
                            key = { it.id },
                        ) { run ->
                            val reviewEvent =
                                findPermissionReviewEventForRun(reviewEvents, run)
                            SubagentRunRow(
                                run = run,
                                reviewEvent = reviewEvent,
                                selected = run.childChatId == currentChildChatId,
                                showActions = true,
                                onClick = {
                                    requestReviewJump(run, reviewEvent)
                                    onSelect(run)
                                },
                                onStop = { onStop(run) },
                                onArchive = { onArchive(run) },
                                onRestore = { onRestore(run) },
                            )
                        }
                        if (showsOrphanPermissionReviewEvents(selectedKind, selectedStatus)) {
                            items(
                                items = orphanReviewEvents,
                                key = { event -> "review-event-${event.id}" },
                            ) { event ->
                                PermissionReviewEventRow(event)
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun permissionReviewStatusText(status: PermissionReviewStatus): String =
    stringResource(
        when (status) {
            PermissionReviewStatus.IN_PROGRESS -> R.string.permission_review_lifecycle_in_progress
            PermissionReviewStatus.APPROVED -> R.string.permission_review_lifecycle_approved
            PermissionReviewStatus.DENIED -> R.string.permission_review_lifecycle_denied
            PermissionReviewStatus.TIMED_OUT -> R.string.permission_review_lifecycle_timed_out
            PermissionReviewStatus.ABORTED -> R.string.permission_review_lifecycle_aborted
            PermissionReviewStatus.FAILED -> R.string.permission_review_lifecycle_failed
        }
    )

@Composable
private fun PermissionReviewEventRow(event: PermissionReviewEvent) {
    val context = LocalContext.current
    var showDetails by remember(event.id) { mutableStateOf(false) }
    val statusText = permissionReviewStatusText(event.status)
    val eventTime = event.completedAt ?: event.startedAt
    // The colour only warns. A call the review allowed is the ordinary outcome, and a list of them
    // has to stay quiet, so an approval reads neutral and the shield carries it.
    val isNegative =
        event.status == PermissionReviewStatus.DENIED ||
            event.status == PermissionReviewStatus.FAILED ||
            event.status == PermissionReviewStatus.TIMED_OUT
    val isOrdinary = event.status == PermissionReviewStatus.APPROVED
    val statusColor =
        when {
            isNegative -> MaterialTheme.colorScheme.error
            isOrdinary -> MaterialTheme.colorScheme.onSurfaceVariant
            event.status == PermissionReviewStatus.ABORTED ->
                MaterialTheme.colorScheme.stoppedAttention
            else -> MaterialTheme.colorScheme.primary
        }
    val statusIcon =
        when {
            isNegative -> Icons.Outlined.GppBad
            isOrdinary -> Icons.Outlined.Security
            else -> Icons.Outlined.GppMaybe
        }
    // The summary usually names the tool itself, so the name is only repeated when it adds something.
    val toolName =
        event.action.toolName.takeIf { name ->
            name.isNotBlank() && !event.action.summary.contains(name, ignoreCase = true)
        }
    // One reviewed call is one compact row, the way the review runs beside it read: the action is the
    // line to scan, the outcome sits under it with the time, and the batch it travelled in and
    // everything the reviewer said stay in the dialog behind a tap.
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable { showDetails = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.action.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text =
                        listOfNotNull(
                                statusText,
                                toolName,
                                DateUtils.getRelativeTimeSpanString(
                                        eventTime,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS,
                                    )
                                    .toString(),
                            )
                            .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.permission_review_detail_title)) },
            text = {
                Column(
                    modifier =
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    com.ai.assistance.operit.ui.permissions.AlwaysAllowReviewedToolButton(event.action.toolName)
                    Text(
                        text = stringResource(R.string.permission_review_detail_status, statusText),
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The row reads as one action, so where it sat in the batch it travelled in lives
                    // here, the same way the call's own badge reports it. A call that travelled alone
                    // has no batch to place itself in.
                    if (event.batchSize > 1) {
                        Text(
                            stringResource(
                                R.string.permission_review_detail_batch,
                                event.batchPosition,
                                event.batchSize,
                            )
                        )
                    }
                    Text(
                        stringResource(
                            R.string.permission_review_detail_action,
                            event.action.summary,
                        )
                    )
                    event.riskLevel?.let { risk ->
                        Text(
                            stringResource(
                                R.string.permission_review_detail_risk,
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
                                ),
                            )
                        )
                    }
                    event.userAuthorization?.let { authorization ->
                        Text(
                            stringResource(
                                R.string.permission_review_detail_authorization,
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
                                ),
                            )
                        )
                    }
                    event.failureKind?.let { failure ->
                        Text(
                            stringResource(
                                R.string.permission_review_detail_failure,
                                stringResource(
                                    when (failure) {
                                        PermissionReviewFailureKind.INVALID_OUTPUT ->
                                            R.string.permission_review_failure_invalid_output
                                        PermissionReviewFailureKind.TIMED_OUT ->
                                            R.string.permission_review_failure_timed_out
                                        PermissionReviewFailureKind.REVIEWER_ERROR ->
                                            R.string.permission_review_failure_reviewer_error
                                    }
                                ),
                            )
                        )
                    }
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
                    Text(
                        stringResource(R.string.permission_review_subagent_unavailable),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun permissionRiskScoreOutcomeText(display: PermissionRiskScoreDisplay): String =
    stringResource(
        when (display) {
            PermissionRiskScoreDisplay.LOW -> R.string.permission_risk_score_low
            PermissionRiskScoreDisplay.HIGH -> R.string.permission_risk_score_high
            PermissionRiskScoreDisplay.FAILED -> R.string.permission_risk_score_failed
            PermissionRiskScoreDisplay.SKIPPED -> R.string.permission_risk_score_skipped
            PermissionRiskScoreDisplay.RUNNING -> R.string.permission_risk_score_running
        }
    )

@Composable
private fun permissionRiskScoreOutcomeColor(display: PermissionRiskScoreDisplay): Color =
    when (display) {
        PermissionRiskScoreDisplay.LOW -> MaterialTheme.colorScheme.primary
        PermissionRiskScoreDisplay.HIGH,
        PermissionRiskScoreDisplay.FAILED -> MaterialTheme.colorScheme.error
        PermissionRiskScoreDisplay.SKIPPED,
        PermissionRiskScoreDisplay.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
private fun permissionRiskSkipText(skip: PermissionRiskScoringSkip): String =
    stringResource(
        when (skip) {
            PermissionRiskScoringSkip.STRICT_MODE -> R.string.permission_risk_skip_strict
            PermissionRiskScoringSkip.NO_SCORABLE_ACTION -> R.string.permission_risk_skip_no_action
            PermissionRiskScoringSkip.NO_MODEL -> R.string.permission_risk_skip_no_model
            PermissionRiskScoringSkip.OFFLINE -> R.string.permission_risk_skip_offline
            PermissionRiskScoringSkip.COOLDOWN -> R.string.permission_risk_skip_cooldown
            PermissionRiskScoringSkip.RETAINED_INSTRUCTIONS_UNAVAILABLE ->
                R.string.permission_risk_skip_retained
        }
    )

/**
 * What the fast level's pre-classification did for this conversation: the batches it scored and the
 * ones it could not score, and whether the verdict it produced is what answered later calls instead
 * of a review.
 */
@Composable
private fun PermissionRiskScoresPage(records: List<PermissionRiskScoreRecord>, trimmed: Boolean) {
    val context = LocalContext.current
    // A page that keeps its stored rows is still worth explaining when the level that produces them
    // is no longer selected, because nothing new will appear in it. What decides that is the whole
    // permission choice the app resolves, not the stored reuse level on its own: the deterministic
    // stops never score either.
    val permissionStop by
        remember(context) { ToolPermissionSystem.getInstance(context).permissionStopFlow }
            .collectAsState(initial = null)
    val summary = permissionRiskScoreSummary(records)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "risk_score_statistics") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.permission_risk_scores_statistics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionReviewStatisticCard(
                        value = summary.scored.toString(),
                        label = stringResource(R.string.permission_risk_scores_stat_total),
                        modifier = Modifier.weight(1f),
                    )
                    PermissionReviewStatisticCard(
                        value = summary.low.toString(),
                        label = stringResource(R.string.permission_risk_scores_stat_low),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionReviewStatisticCard(
                        value = summary.high.toString(),
                        label = stringResource(R.string.permission_risk_scores_stat_high),
                        modifier = Modifier.weight(1f),
                    )
                    PermissionReviewStatisticCard(
                        value = summary.failed.toString(),
                        label = stringResource(R.string.permission_risk_score_failed),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionReviewStatisticCard(
                        value = summary.skipped.toString(),
                        label = stringResource(R.string.permission_risk_scores_stat_unscored),
                        modifier = Modifier.weight(1f),
                    )
                    PermissionReviewStatisticCard(
                        value = summary.answeredCalls.toString(),
                        label = stringResource(R.string.permission_risk_scores_stat_answered),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.permission_risk_scores_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The stop is read asynchronously, so an unread one must not be reported as "not the
                // fast stop" and flash the line on a page that will keep scoring.
                if (records.isNotEmpty() &&
                    permissionStop != null &&
                    permissionStop != ToolPermissionStop.AUTO_REVIEW_FAST
                ) {
                    Text(
                        text = stringResource(R.string.permission_risk_scores_strict),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // Said only for a chat that has rows to miss: next to the empty state it would read
                // as those rows having been removed.
                if (trimmed && records.isNotEmpty()) {
                    Text(
                        text =
                            stringResource(
                                R.string.permission_risk_scores_trimmed,
                                PermissionRiskScoreRepository.MAX_RECORDS,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (records.isEmpty()) {
            item(key = "risk_scores_empty") {
                Text(
                    text = stringResource(R.string.permission_risk_scores_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            items(
                items = records,
                key = { record -> record.id },
            ) { record ->
                val display = record.display()
                val outcomeColor = permissionRiskScoreOutcomeColor(display)
                val toolSeparator = stringResource(R.string.permission_risk_score_tool_separator)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(outcomeColor)
                            )
                            Text(
                                text = permissionRiskScoreOutcomeText(display),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = outcomeColor,
                            )
                        }
                        if (record.toolNames.isNotEmpty()) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.permission_risk_score_tools,
                                        record.toolNames.joinToString(toolSeparator),
                                    ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                // A tool is named by whatever package registered it, so the line is
                                // capped rather than allowed to grow the card.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text =
                                if (record.scorableActions > 0) {
                                    stringResource(
                                        R.string.permission_risk_score_batch,
                                        record.scorableActions,
                                        record.totalActions,
                                    )
                                } else {
                                    stringResource(R.string.permission_risk_score_batch_no_action)
                                },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        record.skip?.let { skip ->
                            Text(
                                text = permissionRiskSkipText(skip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // What this batch's own classification call cost. Nothing is said for a batch
                        // that was never asked, or for one that is still being asked: neither has a
                        // call to report, and an unknown token count is not a zero.
                        if (record.skip == null &&
                            display != PermissionRiskScoreDisplay.RUNNING
                        ) {
                            val usage = record.usage
                            val input = usage?.inputTokens()
                            val output = usage?.outputTokens
                            val cached = usage?.cachedInputTokens
                            val share = usage?.cacheReadPercent()
                            val usageText =
                                when {
                                    input == null || output == null ->
                                        stringResource(
                                            R.string.permission_risk_score_usage_unreported
                                        )
                                    cached != null && share != null ->
                                        stringResource(
                                            R.string.permission_risk_score_usage_cached,
                                            formatCount(input),
                                            formatCount(cached),
                                            share,
                                            formatCount(output),
                                        )
                                    else ->
                                        // A provider that reported no cache field is not the same as
                                        // one that reported a cache read of nothing, so the cache read
                                        // is named as unreported rather than left out.
                                        stringResource(
                                            R.string.permission_risk_score_usage_cache_unreported,
                                            formatCount(input),
                                            formatCount(output),
                                        )
                                }
                            Text(
                                text = usageText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Only a batch that reached the classifier can say what it was shown; a batch
                        // that was skipped was never asked.
                        if (record.skip == null) {
                            Text(
                                text =
                                    if (record.priorReviewCount > 0) {
                                        stringResource(
                                            R.string.permission_risk_score_prior_reviews,
                                            record.priorReviewCount,
                                            record.priorReviewChars,
                                            record.priorReviewHash.take(PRIOR_REVIEW_FINGERPRINT_CHARS),
                                        )
                                    } else {
                                        stringResource(
                                            R.string.permission_risk_score_prior_reviews_none
                                        )
                                    },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (display == PermissionRiskScoreDisplay.FAILED) {
                            Text(
                                text = stringResource(R.string.permission_risk_score_failed_detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (display == PermissionRiskScoreDisplay.LOW) {
                            Text(
                                text =
                                    if (record.answeredCalls > 0) {
                                        stringResource(
                                            R.string.permission_risk_score_answered,
                                            record.answeredCalls,
                                        )
                                    } else {
                                        stringResource(R.string.permission_risk_score_not_used)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (record.discardedStoredScore) {
                            Text(
                                text = stringResource(R.string.permission_risk_score_discarded),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (record.refusedBySettings) {
                            Text(
                                text = stringResource(R.string.permission_risk_score_refused),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text =
                                DateUtils.getRelativeTimeSpanString(
                                        record.completedAt ?: record.startedAt,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS,
                                    )
                                    .toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPermissionDenialsPage(
    deniedEvents: List<PermissionReviewEvent>,
    recentDeniedEvents: List<PermissionReviewEvent>,
    runs: List<SubagentRunEntity>,
    onSelectRun: (SubagentRunEntity) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "denial_statistics") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.permission_review_recent_denials_statistics),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionReviewStatisticCard(
                        value = deniedEvents.size.toString(),
                        label = stringResource(R.string.permission_review_denial_total),
                        modifier = Modifier.weight(1f),
                    )
                    PermissionReviewStatisticCard(
                        value =
                            deniedEvents.distinctBy { event -> event.actionFingerprint }
                                .size
                                .toString(),
                        label = stringResource(R.string.permission_review_denial_unique_actions),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.permission_review_recent_denials_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (recentDeniedEvents.isEmpty()) {
            item(key = "recent_denials_empty") {
                Text(
                    text = stringResource(R.string.permission_review_recent_denials_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            items(
                items = recentDeniedEvents,
                key = { event -> event.id },
            ) { event ->
                val run = findSubagentRunForPermissionReviewEvent(runs, event)
                val eventTime = event.completedAt ?: event.startedAt
                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable(enabled = run != null) {
                                run?.let { selectedRun ->
                                    requestReviewJump(selectedRun, event)
                                    onSelectRun(selectedRun)
                                }
                            },
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text =
                                "[${event.batchPosition}/${event.batchSize}] " +
                                    event.action.summary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = event.action.toolName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        val rationale =
                            permissionReviewNoteForDisplay(
                                context = context,
                                rationale = event.rationale,
                                failureKind = event.failureKind,
                            )
                        if (rationale != null) {
                            Text(
                                text = rationale,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text =
                                DateUtils.getRelativeTimeSpanString(
                                        eventTime,
                                        System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS,
                                    )
                                    .toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text =
                                stringResource(
                                    if (run != null) {
                                        R.string.permission_review_open_subagent_details
                                    } else {
                                        R.string.permission_review_subagent_unavailable
                                    }
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (run != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionReviewStatisticCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Keep each category and its count together; wrap whole chips when the available width is tight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentFilterRow(
    selectedKind: SubagentTaskKind,
    kindCounts: Map<SubagentTaskKind, Int>,
    selectedStatus: SubagentRunStatusFilter,
    statusCounts: Map<SubagentRunStatusFilter, Int>,
    onSelectKind: (SubagentTaskKind) -> Unit,
    onSelectStatus: (SubagentRunStatusFilter) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        SubagentTaskKind.entries.forEach { kind ->
            SubagentKindChip(
                kind = kind,
                selected = kind == selectedKind,
                count = kindCounts[kind] ?: 0,
                onClick = { onSelectKind(kind) },
            )
        }
        SubagentStatusFilterButton(
            selectedStatus = selectedStatus,
            statusCounts = statusCounts,
            onSelectStatus = onSelectStatus,
        )
    }
}

/** One category, with how many runs it currently holds. */
@Composable
private fun SubagentKindChip(
    kind: SubagentTaskKind,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = subagentKindLabel(kind),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        },
    )
}

/**
 * The status narrowing of the current category. It is a menu rather than a row of chips, so the two
 * categories stay the only thing that takes space; the button wears the active status, and reads
 * "all statuses" while nothing is narrowed.
 */
@Composable
private fun SubagentStatusFilterButton(
    selectedStatus: SubagentRunStatusFilter,
    statusCounts: Map<SubagentRunStatusFilter, Int>,
    onSelectStatus: (SubagentRunStatusFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selectedStatus != SubagentRunStatusFilter.ALL,
            onClick = { expanded = true },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = {
                Text(
                    text = subagentStatusFilterLabel(selectedStatus),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = (statusCounts[selectedStatus] ?: 0).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = stringResource(R.string.subagent_filter_menu),
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SubagentRunStatusFilter.entries.forEach { status ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = subagentStatusFilterLabel(status),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = (statusCounts[status] ?: 0).toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = {
                        if (status == selectedStatus) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectStatus(status)
                    },
                )
            }
        }
    }
}

@Composable
private fun subagentKindLabel(kind: SubagentTaskKind): String =
    when (kind) {
        SubagentTaskKind.FOREGROUND -> stringResource(R.string.subagent_kind_foreground)
        SubagentTaskKind.BACKGROUND -> stringResource(R.string.subagent_kind_background)
    }

@Composable
private fun subagentStatusFilterLabel(status: SubagentRunStatusFilter): String =
    when (status) {
        SubagentRunStatusFilter.ALL -> stringResource(R.string.subagent_filter_status_all)
        SubagentRunStatusFilter.RUNNING -> stringResource(R.string.subagent_filter_running)
        SubagentRunStatusFilter.QUEUED -> stringResource(R.string.subagent_filter_queued)
        SubagentRunStatusFilter.COMPLETED -> stringResource(R.string.subagent_filter_completed)
        SubagentRunStatusFilter.INTERRUPTED -> stringResource(R.string.subagent_filter_interrupted)
        SubagentRunStatusFilter.ERROR -> stringResource(R.string.subagent_filter_error)
        SubagentRunStatusFilter.ARCHIVED -> stringResource(R.string.subagent_filter_archived)
    }

/**
 * A named v2 agent shows its own badge here too, the way the chat cards do; the auto review run and
 * anything that is not a named v2 agent keep the plain status mark.
 */
internal fun subagentRunShowsAgentIdentity(isAutoReview: Boolean, isV2Agent: Boolean): Boolean =
    !isAutoReview && isV2Agent

/** The agent's own accent for a named agent, the run status colour otherwise. */
internal fun subagentRunBadgeColor(
    showsAgentIdentity: Boolean,
    statusColor: Color,
    accent: Color,
): Color = if (showsAgentIdentity) accent else statusColor

/**
 * Only a v2 run names itself by its canonical agent path; another feature's owner id (the reading
 * companion's run id) is not a name, so it falls back to the version and the profile id.
 */
internal fun subagentRunTitle(
    isV2Agent: Boolean,
    externalOwnerId: String?,
    agentProfileId: String,
): String {
    val version = if (isV2Agent) "v2" else "v1"
    val ownerName = if (isV2Agent) externalOwnerId?.takeIf { it.isNotBlank() } else null
    return ownerName ?: "$version · ${agentProfileId.ifBlank { "subagent" }}"
}

@Composable
private fun SubagentRunRow(
    run: SubagentRunEntity,
    reviewEvent: PermissionReviewEvent? = null,
    selected: Boolean,
    showActions: Boolean,
    onClick: () -> Unit,
    onStop: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val chatCore =
        remember(context) {
            ChatRuntimeHolder.getInstance(context.applicationContext)
                .getCore(ChatRuntimeSlot.MAIN)
        }
    val childProcessingState = perChatValue(chatCore.inputProcessingStateByChatId, run.childChatId)
    val childLastToolName = perChatValue(chatCore.lastToolNameByChatId, run.childChatId)
    val childToolInvocations =
        perChatValue(chatCore.lastTurnToolInvocationCountByChatId, run.childChatId) ?: 0
    val status = run.status.toSubagentRunStatus()
    val isAutoReview = run.isPermissionReviewSubagentRun()
    val isMemoryExtraction = run.isMemoryExtractionSubagentRun()
    val isV2Agent =
        run.externalOwnerType ==
            com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.OWNER_TYPE
    val agentIdentitySeed = run.externalOwnerId?.takeIf { it.isNotBlank() } ?: run.childChatId
    val agentIdentity = remember(agentIdentitySeed) { subagentAgentIdentity(agentIdentitySeed) }
    // The chat list carries no messages of its own (its histories are built without them), so the
    // child's last turn has to be read from the child conversation, the way the chat card reads it.
    // The text is read and turned into the display state in one pass: a state of its own would let
    // the state be recomputed from a text that has not arrived yet, which paints an error colour on
    // a run that simply has not been read.
    val reviewDisplayState by
        produceState<PermissionReviewRunDisplayState?>(
            initialValue = null,
            isAutoReview,
            status,
            reviewEvent,
            run.childChatId,
        ) {
            value =
                if (isAutoReview) {
                    val finalAssistantText =
                        runCatching {
                                ChatHistoryManager.getInstance(context).loadChatMessages(run.childChatId)
                            }
                            .getOrNull()
                            ?.lastOrNull { it.sender == "ai" }
                            ?.content
                    resolvePermissionReviewRunDisplayState(
                        status,
                        finalAssistantText,
                        reviewEvent,
                    )
                } else {
                    null
                }
        }
    var nowMs by remember(run.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (run.isActiveSubagentRun()) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val currentTool =
        resolveSubagentDisplayedTool(
            childProcessingState = childProcessingState,
            lastToolName = childLastToolName,
        )
    val toolCount = maxOf(run.toolInvocationCount, childToolInvocations)
    val duration =
        formatToolExecutionDuration(
            context,
            ((run.completedAt ?: nowMs) - (run.startedAt ?: run.createdAt))
                .coerceAtLeast(0L),
        )
    val statusText =
        reviewDisplayState?.let { permissionReviewRunStatusText(it) }
            ?: subagentRunStatusText(status = status, currentTool = currentTool)
    val statusColor =
        when (reviewDisplayState) {
            // An approval is the ordinary outcome of a review run, the way a completed run is for any
            // other agent, so it reads neutral and the list stays quiet around the refusals.
            PermissionReviewRunDisplayState.ALLOWED ->
                MaterialTheme.colorScheme.onSurfaceVariant
            PermissionReviewRunDisplayState.DENIED -> MaterialTheme.colorScheme.tertiary
            PermissionReviewRunDisplayState.INVALID_OUTPUT,
            PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT,
            PermissionReviewRunDisplayState.ERROR -> MaterialTheme.colorScheme.error
            PermissionReviewRunDisplayState.INTERRUPTED ->
                MaterialTheme.colorScheme.stoppedAttention
            null -> when (status) {
            SubagentRunStatus.FAILED -> MaterialTheme.colorScheme.error
            SubagentRunStatus.INTERRUPTED -> MaterialTheme.colorScheme.stoppedAttention
            SubagentRunStatus.RUNNING -> MaterialTheme.colorScheme.primary
            SubagentRunStatus.QUEUED -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    // A v2 agent keeps its own badge colour here too, so the list reads like the chat cards do.
    val showsAgentIdentity = subagentRunShowsAgentIdentity(isAutoReview, isV2Agent)
    val badgeColor = subagentRunBadgeColor(showsAgentIdentity, statusColor, agentIdentity.accent)
    var showMenu by remember(run.id) { mutableStateOf(false) }

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        Color.Transparent
                    }
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector =
                        if (showsAgentIdentity) agentIdentity.icon else Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        when {
                            isAutoReview ->
                                stringResource(R.string.agent_profile_builtin_permission_reviewer_name)
                            isMemoryExtraction -> stringResource(R.string.subagent_memory_extraction)
                            else ->
                                subagentRunTitle(
                                    isV2Agent = isV2Agent,
                                    externalOwnerId = run.externalOwnerId,
                                    agentProfileId = run.agentProfileId,
                                )
                        },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = run.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text =
                        listOfNotNull(
                                statusText,
                                subagentCardStatsText(duration, toolCount, run.modelRoundCount),
                            )
                            .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showActions) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.subagent_more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (run.isActiveSubagentRun()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subagent_stop_task)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onStop?.invoke()
                                },
                            )
                        } else if (run.archivedAt == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subagent_archive)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Archive, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onArchive?.invoke()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subagent_restore)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Restore, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onRestore?.invoke()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun permissionReviewRunStatusText(state: PermissionReviewRunDisplayState): String =
    when (state) {
        PermissionReviewRunDisplayState.ALLOWED ->
            stringResource(R.string.permission_review_status_allowed)
        PermissionReviewRunDisplayState.DENIED ->
            stringResource(R.string.permission_review_status_denied)
        PermissionReviewRunDisplayState.INVALID_OUTPUT ->
            stringResource(R.string.permission_review_status_invalid)
        PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT ->
            stringResource(R.string.permission_review_status_timeout_or_cancelled)
        PermissionReviewRunDisplayState.INTERRUPTED ->
            stringResource(R.string.subagent_status_interrupted)
        PermissionReviewRunDisplayState.ERROR ->
            stringResource(R.string.permission_review_status_error)
    }

@Composable
private fun subagentRunStatusText(
    status: SubagentRunStatus,
    currentTool: String?,
): String =
    when (status) {
        SubagentRunStatus.CREATED -> stringResource(R.string.subagent_status_creating)
        SubagentRunStatus.QUEUED -> stringResource(R.string.subagent_filter_queued)
        SubagentRunStatus.RUNNING ->
            if (currentTool.isNullOrBlank()) {
                stringResource(R.string.subagent_status_thinking)
            } else {
                stringResource(R.string.subagent_status_calling_tool, currentTool)
            }
        // The tool count and the rest of the run statistics follow as their own line fragment.
        SubagentRunStatus.COMPLETED -> stringResource(R.string.subagent_status_completed)
        SubagentRunStatus.CANCELLED -> stringResource(R.string.subagent_status_cancelled)
        SubagentRunStatus.FAILED -> stringResource(R.string.subagent_status_error)
        SubagentRunStatus.INTERRUPTED ->
            stringResource(R.string.subagent_status_interrupted)
    }

@Composable
internal fun SubagentHeaderTitle(
    parentTitle: String,
    agentName: String,
    taskTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = parentTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = " / ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = agentName.ifBlank { "subagent" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.subagent_switch_title),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = taskTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
