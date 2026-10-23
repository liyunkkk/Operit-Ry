package com.ai.assistance.operit.ui.features.chat.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ActivePrompt

import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatHistoryDisplayMode
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.BubbleImageStyleConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.InputChip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.components.MessageEditor
import com.ai.assistance.operit.ui.main.screens.GestureStateHolder
import kotlin.math.roundToInt

/**
 * 待确认的撤回请求。索引与"是否丢弃工具轮次"一起写入，避免连续点击时两次异步判断互相覆盖。
 */
private data class MessageRevertRequest(
    val index: Int,
    val removesToolCalls: Boolean,
    val editedContent: String? = null,
)

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreenContent(
        modifier: Modifier = Modifier,
        paddingValues: PaddingValues,
        bottomInset: Dp = 0.dp,
         actualViewModel: ChatViewModel,
         enableMessageDialogs: Boolean = true,
         readOnlyTranscript: Boolean = false,
         enableToolDetailDialogs: Boolean? = null,  // 工具详情弹窗开关，null 时跟随 enableDialogs
         showChatHistorySelector: Boolean,
        chatHistory: List<ChatMessage>,
        isLoading: Boolean,
        userMessageColor: Color,
        aiMessageColor: Color,
        userTextColor: Color,
        aiTextColor: Color,
        systemMessageColor: Color,
        systemTextColor: Color,
        thinkingBackgroundColor: Color,
        thinkingTextColor: Color,
        hasBackgroundImage: Boolean,
        editingMessageIndex: MutableState<Int?>,
        editingMessageContent: MutableState<String>,
        chatScreenGestureConsumed: Boolean,
        onChatScreenGestureConsumed: (Boolean) -> Unit,
        currentDrag: Float,
        onCurrentDragChange: (Float) -> Unit,
        verticalDrag: Float,
        onVerticalDragChange: (Float) -> Unit,
        dragThreshold: Float,
        autoScrollToBottom: Boolean,
        onAutoScrollToBottomChange: (Boolean) -> Unit,
        coroutineScope: CoroutineScope,
        chatHistories: List<ChatHistory>,
        currentChatId: String,
        chatHeaderTransparent: Boolean,
        chatHeaderHistoryIconColor: Int?,
          chatHeaderPipIconColor: Int?,
          chatHeaderOverlayMode: Boolean,
        chatStyle: ChatStyle,
        cursorUserBubbleLiquidGlass: Boolean = false,
        cursorUserBubbleWaterGlass: Boolean = false,
        bubbleUserBubbleLiquidGlass: Boolean = false,
        bubbleUserBubbleWaterGlass: Boolean = false,
        bubbleAiBubbleLiquidGlass: Boolean = false,
        bubbleAiBubbleWaterGlass: Boolean = false,
        historyListState: LazyListState,
        showCharacterSelector: Boolean,
        onShowCharacterSelectorChange: (Boolean) -> Unit,
        onSwitchCharacter: (CharacterSelectorTarget) -> Unit,
        onOpenCharacterSettings: () -> Unit,
        onExitHiddenReadingAuditRun: () -> Unit,
        chatAreaHorizontalPadding: Float = 16f,
        bubbleUserImageStyle: BubbleImageStyleConfig? = null,
        bubbleAiImageStyle: BubbleImageStyleConfig? = null,
        bubbleUserRoundedCornersEnabled: Boolean = true,
        bubbleAiRoundedCornersEnabled: Boolean = true,
        bubbleUserContentPaddingLeft: Float = 12f,
        bubbleUserContentPaddingRight: Float = 12f,
        bubbleAiContentPaddingLeft: Float = 12f,
        bubbleAiContentPaddingRight: Float = 12f,
        showChatFloatingDotsAnimation: Boolean = true,
        configurationNotice: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }

    // Multi-select mode state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val transcriptSelection = remember(currentChatId) { TranscriptSelection() }
    var selectedMessageIndices by transcriptSelection.forMessages(chatHistory)
    val selectableMessageIndices = remember(chatHistory) {
        chatHistory.mapIndexedNotNull { index, message ->
            if (message.sender == "user" || message.sender == "ai") index else null
        }.toSet()
    }
    var isGeneratingImage by remember { mutableStateOf(false) }
    var showSharePreviewDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    var sharePreviewUri by remember { mutableStateOf<Uri?>(null) }
    var sharePreviewThinkingExpanded by remember { mutableStateOf(false) }
    var sharePreviewExpandThinkToolsGroups by remember { mutableStateOf(false) }
    var sharePreviewIncludeBackground by remember { mutableStateOf(hasBackgroundImage) }
    var sharePreviewBorderWidth by remember { mutableStateOf(1.5f) }

    // Export state
    val context = LocalContext.current
    var showExportPlatformDialog by remember { mutableStateOf(false) }
    var showAndroidExportDialog by remember { mutableStateOf(false) }
    var showWindowsExportDialog by remember { mutableStateOf(false) }
    var showExportProgressDialog by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(exportJob) {
        val completion = exportJob?.invokeOnCompletion {
            if (it is kotlinx.coroutines.CancellationException) showExportProgressDialog = false
        }
        onDispose { completion?.dispose() }
    }
    var showExportCompleteDialog by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportStatus by remember { mutableStateOf("") }
    var exportSuccess by remember { mutableStateOf(false) }
    var exportFilePath by remember { mutableStateOf<String?>(null) }
    var exportErrorMessage by remember { mutableStateOf<String?>(null) }
    var webContentDir by remember { mutableStateOf<File?>(null) }
    var editingMessageType by remember { mutableStateOf<String?>(null) }
    // 这一轮回复里的协作消息：它们各自是独立消息，编辑器里按发生顺序作为内容片段呈现。
    val editingMemoryFragments = remember { mutableStateOf<List<MemoryEditFragment>>(emptyList()) }
    var pendingRollback by remember { mutableStateOf<MessageRevertRequest?>(null) }
    var pendingRewind by remember { mutableStateOf<MessageRevertRequest?>(null) }
    val hasOlderDisplayHistory by actualViewModel.hasOlderDisplayHistory.collectAsState()
    val processMetadata by actualViewModel.processMetadata.collectAsState()
    val displayedChatId by actualViewModel.displayedChatId.collectAsState()
    val hasNewerDisplayHistory by actualViewModel.hasNewerDisplayHistory.collectAsState()
    val isLoadingDisplayWindow by actualViewModel.isLoadingDisplayWindow.collectAsState()
    
    // 监听朗读状态
    val isSpeechSessionActive by actualViewModel.isSpeechSessionActive.collectAsState()
    val isSpeechPaused by actualViewModel.isSpeechPaused.collectAsState()
    val isAutoReadEnabled by actualViewModel.isAutoReadEnabled.collectAsState()
    LaunchedEffect(readOnlyTranscript) {
        if (readOnlyTranscript) {
            isMultiSelectMode = false
            selectedMessageIndices = emptySet()
            editingMessageIndex.value = null
            editingMemoryFragments.value = emptyList()
            pendingRollback = null
            pendingRewind = null
        }
    }
    LaunchedEffect(isSpeechSessionActive, isSpeechPaused, isAutoReadEnabled) {
        AppLogger.d(
            "ChatScreenContent",
            "speechControls session=$isSpeechSessionActive paused=$isSpeechPaused autoRead=$isAutoReadEnabled visible=${isSpeechSessionActive || isSpeechPaused || isAutoReadEnabled}"
        )
    }
    // 打开编辑器的那一刻才去读这一轮的协作消息，所以回调本身可以一直复用，不受转录刷新影响。
    val latestChatHistory by rememberUpdatedState(chatHistory)
    val onSelectMessageToEditCallback = remember(
        editingMessageIndex,
        editingMessageContent,
        editingMemoryFragments,
    ) {
        { index: Int, message: ChatMessage, senderType: String ->
            editingMessageIndex.value = index
            editingMessageContent.value = message.content
            editingMessageType = senderType
            editingMemoryFragments.value =
                if (senderType == "ai") {
                    collaborationMemoryFragments(latestChatHistory, index, includeAssistant = true)
                } else {
                    emptyList()
                }
        }
    }

    val activeRunStartedAt by actualViewModel.activeRunStartedAt.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(
        com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiSubmit provides
            if (readOnlyTranscript) null else actualViewModel::submitDynamicUi,
    ) {
    Box(modifier = modifier.fillMaxSize().padding(paddingValues)) {
        if (chatHeaderOverlayMode && chatHeaderTransparent) {
            // 覆盖模式：Header浮动在ChatArea之上
            Box(modifier = Modifier.fillMaxSize()) {
                ChatArea(
                        chatHistory = chatHistory,
                        currentChatId = currentChatId,
                        isLoading = isLoading,
                        activeRunStartedAt = activeRunStartedAt[currentChatId],
                        enableDialogs = enableMessageDialogs && !readOnlyTranscript,
                        allowTranscriptMutation = !readOnlyTranscript,
                        enableToolDetailDialogs = enableToolDetailDialogs ?: enableMessageDialogs,
                        userMessageColor = userMessageColor,
                        aiMessageColor = aiMessageColor,
                        userTextColor = userTextColor,
                        aiTextColor = aiTextColor,
                        systemMessageColor = systemMessageColor,
                        systemTextColor = systemTextColor,
                        thinkingBackgroundColor = thinkingBackgroundColor,
                        thinkingTextColor = thinkingTextColor,
                        hasBackgroundImage = hasBackgroundImage,
                        modifier = Modifier.fillMaxSize(),
                        onSelectMessageToEdit = onSelectMessageToEditCallback,
                        onDeleteMessage = { index -> actualViewModel.deleteMessage(index) },
                        onDeleteCurrentMessageVariant = { index ->
                            actualViewModel.deleteCurrentMessageVariant(index)
                        },
                        onDeleteMessagesFrom = { index -> actualViewModel.deleteMessagesFrom(index) },
                        onRollbackToMessage = { index ->
                            coroutineScope.launch {
                                pendingRollback =
                                    MessageRevertRequest(
                                        index = index,
                                        removesToolCalls =
                                            actualViewModel.willRevertDropToolCalls(index),
                                    )
                            }
                        },
                        onRegenerateMessage = { index -> actualViewModel.regenerateSingleAiMessage(index) },
                        onSwitchMessageVariant = { index, targetVariantIndex ->
                            actualViewModel.switchMessageVariant(index, targetVariantIndex)
                        },
                        onSpeakMessage = { content -> actualViewModel.speakMessage(content) },
                        onAutoReadMessage = { content -> actualViewModel.enableAutoReadAndSpeak(content) },
                        onReplyToMessage = { message -> actualViewModel.setReplyToMessage(message) },
                        onToggleFavoriteMessage = { timestamp, isFavorite ->
                            actualViewModel.setMessageFavorite(timestamp, isFavorite)
                        },
                        onCreateBranch = { timestamp -> actualViewModel.createBranch(timestamp) },
                        onInsertSummary = { message -> actualViewModel.insertSummary(message) },
                        onMentionRoleFromAvatar = { roleName -> actualViewModel.insertRoleMention(roleName) },
                        autoScrollToBottom = autoScrollToBottom,
                        onAutoScrollToBottomChange = onAutoScrollToBottomChange,
                        hasOlderDisplayHistory = hasOlderDisplayHistory,
                        processMetadata = processMetadata,
                        onLoadProcess = actualViewModel::loadTranscriptProcess,
                        transcriptReady = displayedChatId == currentChatId,
                        hasNewerDisplayHistory = hasNewerDisplayHistory,
                        isLoadingDisplayWindow = isLoadingDisplayWindow,
                        onLoadOlderDisplayWindow = {
                            actualViewModel.loadOlderMessagesForCurrentChat()
                        },
                        onLoadNewerDisplayWindow = {
                            actualViewModel.loadNewerMessagesForCurrentChat()
                        },
                        onShowLatestDisplayWindow = {
                            actualViewModel.showLatestMessagesForCurrentChat()
                        },
                        loadMessageLocatorEntries = { chatId, query ->
                            actualViewModel.loadChatMessageLocatorPreviews(chatId, query)
                        },
                        onRevealMessageForLocator = { targetTimestamp ->
                            actualViewModel.revealMessageForCurrentChat(targetTimestamp)
                        },
                        topPadding = headerHeight,
                        bottomPadding = bottomInset,
                        chatStyle = chatStyle,
                        cursorUserBubbleLiquidGlass = cursorUserBubbleLiquidGlass,
                        cursorUserBubbleWaterGlass = cursorUserBubbleWaterGlass,
                        bubbleUserBubbleLiquidGlass = bubbleUserBubbleLiquidGlass,
                        bubbleUserBubbleWaterGlass = bubbleUserBubbleWaterGlass,
                        bubbleAiBubbleLiquidGlass = bubbleAiBubbleLiquidGlass,
                        bubbleAiBubbleWaterGlass = bubbleAiBubbleWaterGlass,
                        isMultiSelectMode = isMultiSelectMode,
                        selectedMessageIndices = selectedMessageIndices,
                        onToggleMultiSelectMode = { initialIndex ->
                            isMultiSelectMode = !isMultiSelectMode
                            if (!isMultiSelectMode) {
                                selectedMessageIndices = emptySet()
                            } else if (initialIndex != null) {
                                // 进入多选模式时，自动选中触发的消息
                                selectedMessageIndices = setOf(initialIndex)
                            }
                        },
                        onToggleMessageSelection = { index ->
                            selectedMessageIndices = if (selectedMessageIndices.contains(index)) {
                                selectedMessageIndices - index
                            } else {
                                selectedMessageIndices + index
                            }
                        },
                        horizontalPadding = chatAreaHorizontalPadding.dp,
                        bubbleUserImageStyle = bubbleUserImageStyle,
                        bubbleAiImageStyle = bubbleAiImageStyle,
                        bubbleUserRoundedCornersEnabled = bubbleUserRoundedCornersEnabled,
                        bubbleAiRoundedCornersEnabled = bubbleAiRoundedCornersEnabled,
                        bubbleUserContentPaddingLeft = bubbleUserContentPaddingLeft,
                        bubbleUserContentPaddingRight = bubbleUserContentPaddingRight,
                        bubbleAiContentPaddingLeft = bubbleAiContentPaddingLeft,
                        bubbleAiContentPaddingRight = bubbleAiContentPaddingRight,
                        showChatFloatingDotsAnimation = showChatFloatingDotsAnimation,
                )
                Column(
                    Modifier.onGloballyPositioned { coordinates ->
                        headerHeight = with(density) { coordinates.size.height.toDp() }
                    }
                ) {
                    ChatScreenHeader(
                        actualViewModel = actualViewModel,
                        showChatHistorySelector = showChatHistorySelector,
                        chatHeaderTransparent = chatHeaderTransparent,
                        chatHeaderHistoryIconColor = chatHeaderHistoryIconColor,
                        chatHeaderPipIconColor = chatHeaderPipIconColor,
                        onExitHiddenReadingAuditRun = onExitHiddenReadingAuditRun,
                        onCharacterSwitcherClick = { onShowCharacterSelectorChange(true) }
                    )
                    configurationNotice()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                ChatScreenHeader(
                        actualViewModel = actualViewModel,
                        showChatHistorySelector = showChatHistorySelector,
                        chatHeaderTransparent = chatHeaderTransparent,
                        chatHeaderHistoryIconColor = chatHeaderHistoryIconColor,
                        chatHeaderPipIconColor = chatHeaderPipIconColor,
                        onExitHiddenReadingAuditRun = onExitHiddenReadingAuditRun,
                        onCharacterSwitcherClick = { onShowCharacterSelectorChange(true) }
                )
                configurationNotice()
                ChatArea(
                        chatHistory = chatHistory,
                        currentChatId = currentChatId,
                        isLoading = isLoading,
                        activeRunStartedAt = activeRunStartedAt[currentChatId],
                        enableDialogs = enableMessageDialogs && !readOnlyTranscript,
                        allowTranscriptMutation = !readOnlyTranscript,
                        enableToolDetailDialogs = enableToolDetailDialogs ?: enableMessageDialogs,
                        userMessageColor = userMessageColor,
                        aiMessageColor = aiMessageColor,
                        userTextColor = userTextColor,
                        aiTextColor = aiTextColor,
                        systemMessageColor = systemMessageColor,
                        systemTextColor = systemTextColor,
                        thinkingBackgroundColor = thinkingBackgroundColor,
                        thinkingTextColor = thinkingTextColor,
                        hasBackgroundImage = hasBackgroundImage,
                        modifier = Modifier.fillMaxSize(),
                        onSelectMessageToEdit = onSelectMessageToEditCallback,
                        onDeleteMessage = { index -> actualViewModel.deleteMessage(index) },
                        onDeleteCurrentMessageVariant = { index ->
                            actualViewModel.deleteCurrentMessageVariant(index)
                        },
                        onDeleteMessagesFrom = { index -> actualViewModel.deleteMessagesFrom(index) },
                        onRollbackToMessage = { index ->
                            coroutineScope.launch {
                                pendingRollback =
                                    MessageRevertRequest(
                                        index = index,
                                        removesToolCalls =
                                            actualViewModel.willRevertDropToolCalls(index),
                                    )
                            }
                        },
                        onRegenerateMessage = { index -> actualViewModel.regenerateSingleAiMessage(index) },
                        onSwitchMessageVariant = { index, targetVariantIndex ->
                            actualViewModel.switchMessageVariant(index, targetVariantIndex)
                        },
                        onSpeakMessage = { content -> actualViewModel.speakMessage(content) },
                        onReplyToMessage = { message -> actualViewModel.setReplyToMessage(message) },
                        onToggleFavoriteMessage = { timestamp, isFavorite ->
                            actualViewModel.setMessageFavorite(timestamp, isFavorite)
                        },
                        onCreateBranch = { timestamp -> actualViewModel.createBranch(timestamp) },
                        onInsertSummary = { message -> actualViewModel.insertSummary(message) },
                        onAutoReadMessage = { content -> actualViewModel.enableAutoReadAndSpeak(content) },
                        onMentionRoleFromAvatar = { roleName -> actualViewModel.insertRoleMention(roleName) },
                        autoScrollToBottom = autoScrollToBottom,
                        onAutoScrollToBottomChange = onAutoScrollToBottomChange,
                        hasOlderDisplayHistory = hasOlderDisplayHistory,
                        processMetadata = processMetadata,
                        onLoadProcess = actualViewModel::loadTranscriptProcess,
                        transcriptReady = displayedChatId == currentChatId,
                        hasNewerDisplayHistory = hasNewerDisplayHistory,
                        isLoadingDisplayWindow = isLoadingDisplayWindow,
                        onLoadOlderDisplayWindow = {
                            actualViewModel.loadOlderMessagesForCurrentChat()
                        },
                        onLoadNewerDisplayWindow = {
                            actualViewModel.loadNewerMessagesForCurrentChat()
                        },
                        onShowLatestDisplayWindow = {
                            actualViewModel.showLatestMessagesForCurrentChat()
                        },
                        loadMessageLocatorEntries = { chatId, query ->
                            actualViewModel.loadChatMessageLocatorPreviews(chatId, query)
                        },
                        onRevealMessageForLocator = { targetTimestamp ->
                            actualViewModel.revealMessageForCurrentChat(targetTimestamp)
                        },
                        bottomPadding = bottomInset,
                        chatStyle = chatStyle,
                        cursorUserBubbleLiquidGlass = cursorUserBubbleLiquidGlass,
                        cursorUserBubbleWaterGlass = cursorUserBubbleWaterGlass,
                        bubbleUserBubbleLiquidGlass = bubbleUserBubbleLiquidGlass,
                        bubbleUserBubbleWaterGlass = bubbleUserBubbleWaterGlass,
                        bubbleAiBubbleLiquidGlass = bubbleAiBubbleLiquidGlass,
                        bubbleAiBubbleWaterGlass = bubbleAiBubbleWaterGlass,
                        isMultiSelectMode = isMultiSelectMode,
                        selectedMessageIndices = selectedMessageIndices,
                        horizontalPadding = chatAreaHorizontalPadding.dp,
                        onToggleMultiSelectMode = { initialIndex ->
                            isMultiSelectMode = !isMultiSelectMode
                            if (!isMultiSelectMode) {
                                selectedMessageIndices = emptySet()
                            } else if (initialIndex != null) {
                                // 进入多选模式时，自动选中触发的消息
                                selectedMessageIndices = setOf(initialIndex)
                            }
                        },
                        onToggleMessageSelection = { index ->
                            selectedMessageIndices = if (selectedMessageIndices.contains(index)) {
                                selectedMessageIndices - index
                            } else {
                                selectedMessageIndices + index
                            }
                        },
                        showChatFloatingDotsAnimation = showChatFloatingDotsAnimation,
                        bubbleUserImageStyle = bubbleUserImageStyle,
                        bubbleAiImageStyle = bubbleAiImageStyle,
                        bubbleUserRoundedCornersEnabled = bubbleUserRoundedCornersEnabled,
                        bubbleAiRoundedCornersEnabled = bubbleAiRoundedCornersEnabled,
                        bubbleUserContentPaddingLeft = bubbleUserContentPaddingLeft,
                        bubbleUserContentPaddingRight = bubbleUserContentPaddingRight,
                        bubbleAiContentPaddingLeft = bubbleAiContentPaddingLeft,
                        bubbleAiContentPaddingRight = bubbleAiContentPaddingRight,
                )
            }
        }

        // 多选模式底部操作栏
        AnimatedVisibility(
            visible = isMultiSelectMode && !readOnlyTranscript,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomInset + 16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 显示选中数量
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 取消按钮移到左侧
                        IconButton(
                            onClick = {
                                isMultiSelectMode = false
                                selectedMessageIndices = emptySet()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.exit_multi_select),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        Text(
                            text = if (selectedMessageIndices.isEmpty()) {
                                stringResource(R.string.multi_select)
                            } else {
                                stringResource(R.string.selected_count, selectedMessageIndices.size)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val allSelectableSelected =
                            selectableMessageIndices.isNotEmpty() &&
                                    selectableMessageIndices.all { selectedMessageIndices.contains(it) }

                        TextButton(
                            onClick = {
                                selectedMessageIndices =
                                        if (allSelectableSelected) {
                                            emptySet()
                                        } else {
                                            selectableMessageIndices
                                        }
                            },
                            enabled = selectableMessageIndices.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = stringResource(
                                        if (allSelectableSelected) R.string.clear_selection else R.string.select_all_messages
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        FilledIconButton(
                            onClick = {
                                if (selectedMessageIndices.isNotEmpty()) {
                                    val selectedMessages =
                                        selectedMessageIndices
                                            .mapNotNull { index -> chatHistory.getOrNull(index) }
                                            .sortedBy { it.timestamp }
                                    actualViewModel.enqueueSelectedMessagesForMemoryAutoSave(
                                        selectedMessages
                                    )
                                }
                            },
                            enabled = selectedMessageIndices.isNotEmpty(),
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.add_selected_to_memory),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 分享按钮
                        FilledIconButton(
                            onClick = {
                                if (selectedMessageIndices.isNotEmpty() && !isGeneratingImage) {
                                    // 预览参数仅对本次截图生效，不污染正常聊天展示状态
                                    sharePreviewUri = null
                                    sharePreviewThinkingExpanded = false
                                    sharePreviewExpandThinkToolsGroups = false
                                    sharePreviewIncludeBackground = hasBackgroundImage
                                    sharePreviewBorderWidth = 1.5f
                                    showSharePreviewDialog = true
                                }
                            },
                            enabled = selectedMessageIndices.isNotEmpty() && !isGeneratingImage,
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.share_selected),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 删除按钮
                        FilledIconButton(
                            onClick = {
                                if (selectedMessageIndices.isNotEmpty()) {
                                    showDeleteSelectedConfirmDialog = true
                                }
                            },
                            enabled = selectedMessageIndices.isNotEmpty(),
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete_selected),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Stop reading button
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val endPadding = 16.dp
            val bottomPadding = 80.dp
            val fabSize = 40.dp

            val containerWidthPx = with(density) { maxWidth.toPx() }
            val containerHeightPx = with(density) { maxHeight.toPx() }
            val fabSizePx = with(density) { fabSize.toPx() }
            val bottomInsetPx = with(density) { bottomInset.toPx() }

            val initialOffsetXPx =
                (containerWidthPx - with(density) { (endPadding + fabSize).toPx() }).coerceAtLeast(0f)
            val initialOffsetYPx =
                (containerHeightPx - with(density) { (bottomPadding + bottomInset + fabSize).toPx() }).coerceAtLeast(0f)

            var stopButtonOffsetXPx by rememberSaveable { mutableFloatStateOf(initialOffsetXPx) }
            var stopButtonOffsetYPx by rememberSaveable { mutableFloatStateOf(initialOffsetYPx) }

            val maxX = (containerWidthPx - fabSizePx).coerceAtLeast(0f)
            val maxY = (containerHeightPx - fabSizePx - bottomInsetPx).coerceAtLeast(0f)

            // 屏幕尺寸变化（旋转/分屏）时，确保按钮仍然在可见区域内
            LaunchedEffect(maxX, maxY) {
                stopButtonOffsetXPx = stopButtonOffsetXPx.coerceIn(0f, maxX)
                stopButtonOffsetYPx = stopButtonOffsetYPx.coerceIn(0f, maxY)
            }

            AnimatedVisibility(
                visible = isSpeechSessionActive || isSpeechPaused || isAutoReadEnabled,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier
                    .offset {
                        IntOffset(
                            stopButtonOffsetXPx.roundToInt(),
                            stopButtonOffsetYPx.roundToInt()
                        )
                    }
                    .pointerInput(maxX, maxY) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            stopButtonOffsetXPx = (stopButtonOffsetXPx + dragAmount.x).coerceIn(0f, maxX)
                            stopButtonOffsetYPx = (stopButtonOffsetYPx + dragAmount.y).coerceIn(0f, maxY)
                        }
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallFloatingActionButton(
                        modifier = Modifier.align(Alignment.Top),
                        onClick = {
                            AppLogger.d(
                                "ChatScreenContent",
                                "speechControls pauseClick session=$isSpeechSessionActive paused=$isSpeechPaused autoRead=$isAutoReadEnabled"
                            )
                            actualViewModel.pauseSpeaking()
                        },
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = stringResource(R.string.pause_reading),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = isSpeechPaused,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Top)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallFloatingActionButton(
                                onClick = {
                                    AppLogger.d(
                                        "ChatScreenContent",
                                        "speechControls resumeClick session=$isSpeechSessionActive paused=$isSpeechPaused autoRead=$isAutoReadEnabled"
                                    )
                                    actualViewModel.resumeSpeaking()
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.resume_reading),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            SmallFloatingActionButton(
                                onClick = {
                                    AppLogger.d(
                                        "ChatScreenContent",
                                        "speechControls stopClick session=$isSpeechSessionActive paused=$isSpeechPaused autoRead=$isAutoReadEnabled"
                                    )
                                    actualViewModel.stopSpeaking()
                                },
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = stringResource(R.string.stop_reading),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showDeleteSelectedConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteSelectedConfirmDialog = false },
                title = { Text(stringResource(R.string.confirm_delete)) },
                text = { Text(stringResource(R.string.chat_delete_selected_messages_confirm, selectedMessageIndices.size)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            actualViewModel.deleteMessagesByTimestamp(transcriptSelection.timestamps)
                            selectedMessageIndices = emptySet()
                            isMultiSelectMode = false
                            showDeleteSelectedConfirmDialog = false
                        }
                    ) { Text(stringResource(R.string.confirm_delete_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteSelectedConfirmDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        if (showSharePreviewDialog) {
            LaunchedEffect(
                showSharePreviewDialog,
                selectedMessageIndices,
                sharePreviewThinkingExpanded,
                sharePreviewExpandThinkToolsGroups,
                sharePreviewIncludeBackground,
                sharePreviewBorderWidth
            ) {
                if (showSharePreviewDialog && selectedMessageIndices.isNotEmpty() && !isGeneratingImage) {
                    isGeneratingImage = true
                    actualViewModel.shareMessages(
                        context = context,
                        messageIndices = selectedMessageIndices,
                        userMessageColor = userMessageColor,
                        aiMessageColor = aiMessageColor,
                        userTextColor = userTextColor,
                        aiTextColor = aiTextColor,
                        systemMessageColor = systemMessageColor,
                        systemTextColor = systemTextColor,
                        thinkingBackgroundColor = thinkingBackgroundColor,
                        thinkingTextColor = thinkingTextColor,
                        chatStyle = chatStyle,
                        cursorUserBubbleLiquidGlass = cursorUserBubbleLiquidGlass,
                        cursorUserBubbleWaterGlass = cursorUserBubbleWaterGlass,
                        bubbleUserBubbleLiquidGlass = bubbleUserBubbleLiquidGlass,
                        bubbleUserBubbleWaterGlass = bubbleUserBubbleWaterGlass,
                        bubbleAiBubbleLiquidGlass = bubbleAiBubbleLiquidGlass,
                        bubbleAiBubbleWaterGlass = bubbleAiBubbleWaterGlass,
                        initialThinkingExpanded = sharePreviewThinkingExpanded,
                        expandThinkToolsGroups = sharePreviewExpandThinkToolsGroups,
                        includeBackground = sharePreviewIncludeBackground,
                        borderWidthDp = sharePreviewBorderWidth,
                        forceShowThinkingProcess = true,
                        onSuccess = { uri ->
                            sharePreviewUri = uri
                            isGeneratingImage = false
                        },
                        onError = { error ->
                            isGeneratingImage = false
                            AppLogger.e("ChatScreenContent", "Generate share image failed: $error")
                            android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            ShareImagePreviewDialog(
                imageUri = sharePreviewUri,
                isGenerating = isGeneratingImage,
                thinkingExpanded = sharePreviewThinkingExpanded,
                expandThinkToolsGroups = sharePreviewExpandThinkToolsGroups,
                includeBackground = sharePreviewIncludeBackground,
                borderWidth = sharePreviewBorderWidth,
                onThinkingExpandedChange = { sharePreviewThinkingExpanded = it },
                onExpandThinkToolsGroupsChange = { sharePreviewExpandThinkToolsGroups = it },
                onIncludeBackgroundChange = { sharePreviewIncludeBackground = it },
                onBorderWidthChange = { sharePreviewBorderWidth = it },
                onDismiss = {
                    showSharePreviewDialog = false
                    sharePreviewUri = null
                },
                onShare = {
                    sharePreviewUri?.let { uri ->
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_selected)))
                            showSharePreviewDialog = false
                            isMultiSelectMode = false
                            selectedMessageIndices = emptySet()
                            sharePreviewUri = null
                        } catch (e: Exception) {
                            AppLogger.e("ChatScreenContent", "Share failed", e)
                            android.widget.Toast.makeText(context, context.getString(R.string.share_failed), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onSave = {
                    sharePreviewUri?.let { uri ->
                        coroutineScope.launch {
                            val saved = saveShareImageToGallery(context, uri)
                            android.widget.Toast.makeText(
                                context,
                                if (saved) context.getString(R.string.image_saved) else context.getString(R.string.save_failed),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }

        // 导出平台选择对话框
        if (showExportPlatformDialog) {
            ExportPlatformDialog(
                    onDismiss = { showExportPlatformDialog = false },
                    onSelectAndroid = { showAndroidExportDialog = true },
                    onSelectWindows = { showWindowsExportDialog = true }
            )
        }

        // Android导出设置对话框
        if (showAndroidExportDialog && webContentDir != null) {
            AndroidExportDialog(
                    workDir = webContentDir!!,
                    onDismiss = { showAndroidExportDialog = false },
                    onExport = { packageName, appName, iconUri, versionName, versionCode ->
                        showAndroidExportDialog = false
                        showExportProgressDialog = true
                        exportProgress = 0f
                        exportStatus = context.getString(R.string.chat_starting_export)

                        // 启动导出过程
                        exportJob = coroutineScope.launch {
                            exportAndroidApp(
                                    context = context,
                                    packageName = packageName,
                                    appName = appName,
                                    versionName = versionName,
                                    versionCode = versionCode,
                                    iconUri = iconUri,
                                    webContentDir = webContentDir!!,
                                    onProgress = { progress, status ->
                                        exportProgress = progress
                                        exportStatus = status
                                    },
                                    onComplete = { success, filePath, errorMessage ->
                                        showExportProgressDialog = false
                                        exportSuccess = success
                                        exportFilePath = filePath
                                        exportErrorMessage = errorMessage
                                        showExportCompleteDialog = true
                                    }
                            )
                        }
                    }
            )
        }

        // Windows导出设置对话框
        if (showWindowsExportDialog && webContentDir != null) {
            WindowsExportDialog(
                    workDir = webContentDir!!,
                    onDismiss = { showWindowsExportDialog = false },
                    onExport = { appName, iconUri ->
                        showWindowsExportDialog = false
                        showExportProgressDialog = true
                        exportProgress = 0f
                        exportStatus = context.getString(R.string.chat_starting_export)

                        // 启动导出过程
                        exportJob = coroutineScope.launch {
                            exportWindowsApp(
                                    context = context,
                                    appName = appName,
                                    iconUri = iconUri,
                                    webContentDir = webContentDir!!,
                                    onProgress = { progress, status ->
                                        exportProgress = progress
                                        exportStatus = status
                                    },
                                    onComplete = { success, filePath, errorMessage ->
                                        showExportProgressDialog = false
                                        exportSuccess = success
                                        exportFilePath = filePath
                                        exportErrorMessage = errorMessage
                                        showExportCompleteDialog = true
                                    }
                            )
                        }
                    }
            )
        }

        // 导出进度对话框
        if (showExportProgressDialog) {
            ExportProgressDialog(
                    progress = exportProgress,
                    status = exportStatus,
                    onCancel = { exportJob?.cancel(); showExportProgressDialog = false }
            )
        }

        // 导出完成对话框
        if (showExportCompleteDialog) {
            ExportCompleteDialog(
                    success = exportSuccess,
                    filePath = exportFilePath,
                    errorMessage = exportErrorMessage,
                    onDismiss = { showExportCompleteDialog = false },
                    onOpenFile = { filePath ->
                        try {
                            val file = File(filePath)
                            val fileUri =
                                    FileProvider.getUriForFile(
                                            context,
                                            context.applicationContext.packageName +
                                                    ".fileprovider",
                                            file
                                    )
                            val intent = Intent(Intent.ACTION_VIEW)
                            intent.setDataAndType(
                                    fileUri,
                                    "application/vnd.android.package-archive"
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            AppLogger.e("ChatScreenContent", "无法打开文件: $filePath", e)
                        } catch (e: Exception) {
                            AppLogger.e("ChatScreenContent", "文件操作错误: ${e.message}", e)
                        }
                    }
            )
        }

        // 当需要编辑消息时，显示消息编辑器
        if (editingMessageIndex.value != null) {
            MessageEditor(
                editingMessageContent = editingMessageContent,
                memoryFragments = editingMemoryFragments.value,
                memoryAnchorTimestamp = editingMemoryFragments.value.firstOrNull {
                    it.messageIndex == editingMessageIndex.value
                }?.timestamp,
                onMemoryFragmentsChange = { updatedFragments ->
                    editingMemoryFragments.value = updatedFragments
                },
                onCancel = {
                    editingMessageIndex.value = null
                    editingMessageContent.value = ""
                    editingMemoryFragments.value = emptyList()
                },
                onSave = {
                    val index = editingMessageIndex.value
                    if (index != null) {
                        val editedMessage =
                            chatHistory[index].copy(
                                content = editingMessageContent.value,
                                contentStream = null
                            )
                        val fragments = editingMemoryFragments.value
                        actualViewModel.saveMemoryEdit(
                            index = index,
                            editedMessage = editedMessage,
                            fragmentContents =
                                fragments
                                    .filter { it.changed && it.timestamp != editedMessage.timestamp }
                                    .associate { fragment ->
                                        fragment.timestamp to
                                            if (fragment.isAssistant) fragment.body else collaborationBodyReplaced(
                                                fragment.content,
                                                fragment.body,
                                            )
                                    },
                            deletedFragments =
                                fragments.filter { it.deleted }.map { it.timestamp }.toSet(),
                        )
                    }
                    editingMessageIndex.value = null
                    editingMessageContent.value = ""
                    editingMemoryFragments.value = emptyList()
                },
                onResend = {
                    val index = editingMessageIndex.value
                    if (index != null) {
                        val editedContent = editingMessageContent.value

                        // 编辑并重发同样会删除此后的轮次，无论是否绑定工作区都要先确认改动不会被撤回。
                        coroutineScope.launch {
                            pendingRewind =
                                MessageRevertRequest(
                                    index = index,
                                    removesToolCalls =
                                        actualViewModel.willRevertDropToolCalls(index),
                                    editedContent = editedContent,
                                )
                        }
                    }
                    editingMessageIndex.value = null
                    editingMessageContent.value = ""
                    editingMemoryFragments.value = emptyList()
                },
                showResendButton = editingMessageType == "user"
            )
        }

        val rollbackRequest = pendingRollback
        if (rollbackRequest != null) {
            MessageRevertConfirmDialog(
                mode = MessageRevertMode.ROLLBACK,
                removedRangeHasToolCalls = rollbackRequest.removesToolCalls,
                onConfirm = {
                    actualViewModel.rollbackToMessage(rollbackRequest.index)
                    pendingRollback = null
                },
                onDismiss = {
                    pendingRollback = null
                }
            )
        }

        val rewindRequest = pendingRewind
        val rewindEditedContent = rewindRequest?.editedContent
        if (rewindRequest != null && rewindEditedContent != null) {
            MessageRevertConfirmDialog(
                mode = MessageRevertMode.EDIT_AND_RESEND,
                removedRangeHasToolCalls = rewindRequest.removesToolCalls,
                onConfirm = {
                    actualViewModel.rewindAndResendMessage(rewindRequest.index, rewindEditedContent)
                    pendingRewind = null
                },
                onDismiss = {
                    pendingRewind = null
                }
            )
        }
    }
}
}

@Composable
fun ChatHistorySelectorPanel(
        actualViewModel: ChatViewModel,
        chatHistories: List<ChatHistory>,
        orderingChatHistories: List<ChatHistory>,
        searchableChatHistories: List<ChatHistory>,
        currentChatId: String,
        showChatHistorySelector: Boolean,
        historyListState: LazyListState,
        onOpenHiddenChats: (() -> Unit)? = null,
        onChatScreenGestureConsumed: (Boolean) -> Unit,
        searchQuery: String,
        onSearchQueryChange: (String) -> Unit,
        activePrompt: ActivePrompt,
        historyDisplayMode: ChatHistoryDisplayMode,
        onDisplayModeChange: (ChatHistoryDisplayMode) -> Unit,
        autoSwitchCharacterCard: Boolean,
        onAutoSwitchCharacterCardChange: (Boolean) -> Unit,
        autoSwitchChatOnCharacterSelect: Boolean,
        onAutoSwitchChatOnCharacterSelectChange: (Boolean) -> Unit,
        selectedHistoryCategory: ChatHistoryCategory,
        onSelectedHistoryCategoryChange: (ChatHistoryCategory) -> Unit,
        collapsedHistoryGroups: Set<String>,
        onCollapsedHistoryGroupsChange: (Set<String>) -> Unit,
        collapsedHistoryCharacters: Set<String>,
        onCollapsedHistoryCharactersChange: (Set<String>) -> Unit,
) {
    // 历史选择器面板（不再包含遮罩层，遮罩层已在外部处理）
    // Wider on normal phones so titles and folder names fit; a strip stays visible to tap and close.
    // Sized from the real container so embedded chat screens keep that strip too.
    BoxWithConstraints {
    val panelWidth = (maxWidth * 0.86f).coerceAtMost(340.dp)
    Box(
            modifier =
                    Modifier.width(panelWidth)
                            .fillMaxHeight()
                            .background(
                                    color =
                                            MaterialTheme.colorScheme.surface.copy(
                                                    alpha = 0.95f
                                            ),
                                    shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                            )
    ) {
        val activeStreamingChatIds by actualViewModel.activeStreamingChatIds.collectAsState()
        val regeneratingTitleIds by actualViewModel.regeneratingTitleIds.collectAsState()
        val chatFolders by actualViewModel.chatFolders.collectAsState()
        // 直接使用ChatHistorySelector
        ChatHistorySelector(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                onNewChat = {
                    characterCardName,
                    characterGroupId,
                    inheritGroupFromCurrent ->
                    actualViewModel.createNewChat(
                        characterCardName = characterCardName,
                        characterGroupId = characterGroupId,
                        inheritGroupFromCurrent = inheritGroupFromCurrent,
                    )
                    // 创建新对话后自动收起侧边框
                    actualViewModel.showChatHistorySelector(false)
                },
                onCreateFolderWithInitialChat = {
                    parentFolderId,
                    folderName,
                    characterCardName,
                    characterGroupId,
                    onResult ->
                    actualViewModel.createFolderWithInitialChat(
                        parentFolderId = parentFolderId,
                        folderName = folderName,
                        characterCardName = characterCardName,
                        characterGroupId = characterGroupId,
                    ) { result ->
                        onResult(result)
                        if (result.isSuccess) {
                            actualViewModel.showChatHistorySelector(false)
                        }
                    }
                },
                onSelectChat = { chatId ->
                    actualViewModel.switchChat(chatId)
                    // 切换聊天后也自动收起侧边框
                    actualViewModel.showChatHistorySelector(false)
                },
                onDeleteChat = { chatId -> actualViewModel.deleteChatHistory(chatId) },
                onUpdateChatTitle = { chatId, newTitle ->
                    actualViewModel.updateChatTitle(chatId, newTitle)
                },
                onRegenerateChatTitle = actualViewModel::regenerateChatTitle,
                regeneratingTitleIds = regeneratingTitleIds,
                onUpdateChatBinding = { chatId, characterCardName, characterGroupId ->
                    actualViewModel.updateChatCharacterBinding(chatId, characterCardName, characterGroupId)
                },
                chatHistories = chatHistories,
                orderingChatHistories = orderingChatHistories,
                searchableChatHistories = searchableChatHistories,
                chatFolders = chatFolders,
                currentId = currentChatId,
                activeStreamingChatIds = activeStreamingChatIds,
                lazyListState = historyListState,
                onBack = { actualViewModel.toggleChatHistorySelector() },
                onOpenHiddenChats = onOpenHiddenChats,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                historyDisplayMode = historyDisplayMode,
                onDisplayModeChange = onDisplayModeChange,
                autoSwitchCharacterCard = autoSwitchCharacterCard,
                onAutoSwitchCharacterCardChange = onAutoSwitchCharacterCardChange,
                autoSwitchChatOnCharacterSelect = autoSwitchChatOnCharacterSelect,
                onAutoSwitchChatOnCharacterSelectChange = onAutoSwitchChatOnCharacterSelectChange,
                onQuickScrollInteractionChange = { consumed ->
                    GestureStateHolder.isChatScreenGestureConsumed = consumed
                    onChatScreenGestureConsumed(consumed)
                },
                activePrompt = activePrompt,
                selectedCategory = selectedHistoryCategory,
                onSelectedCategoryChange = onSelectedHistoryCategoryChange,
                collapsedGroups = collapsedHistoryGroups,
                onCollapsedGroupsChange = onCollapsedHistoryGroupsChange,
                collapsedCharacters = collapsedHistoryCharacters,
                onCollapsedCharactersChange = onCollapsedHistoryCharactersChange,
        )
    }
    }
}
