package com.ai.assistance.operit.ui.features.chat.components.style.input.classic

import com.ai.assistance.operit.ui.features.chat.components.style.input.common.chatComposerShape
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.chatComposerColor
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.chatComposerTextStyle

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Mic
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.composerPredictionSemantics
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.composerPredictionGesture
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.ComposerPredictionViewport
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.rememberComposerPredictionTransformation
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.visibleComposerPrediction
import com.ai.assistance.operit.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.core.tools.ToolProgressBus
import com.ai.assistance.operit.ui.common.animations.SimpleAnimatedVisibility
import com.ai.assistance.operit.ui.features.chat.components.AttachmentChip
import com.ai.assistance.operit.ui.features.chat.components.AttachmentSelectorPanel
import com.ai.assistance.operit.ui.features.chat.components.FullscreenInputDialog
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.rememberMentionVisualTransformation
import com.ai.assistance.operit.ui.features.chat.components.SimpleLinearProgressIndicator
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.PendingMessageQueuePanel
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.PendingQueueMessageItem
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.ThinkingStrengthControl
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.ComposerPermissionControl
import com.ai.assistance.operit.ui.permissions.ToolPermissionStop
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.VoiceRecordingWaveform
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.rememberDraftVoiceController
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.HoldToTalkArea
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.SuppressFieldLongPress
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.holdToTalk
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.rememberKeyboardDown
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.floating.FloatingMode
import com.ai.assistance.operit.ui.theme.isLiquidGlassSupported
import com.ai.assistance.operit.ui.theme.isWaterGlassSupported
import com.ai.assistance.operit.ui.theme.liquidGlass
import com.ai.assistance.operit.ui.theme.waterGlass
import com.ai.assistance.operit.util.ChatUtils
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import android.net.Uri

@Composable
fun ClassicChatInputSection(
    actualViewModel: ChatViewModel,
    userMessage: TextFieldValue,
    onUserMessageChange: (TextFieldValue) -> Unit,
    composerPrediction: String? = null,
    enableEnterToSend: Boolean = false,
    onSendMessage: () -> Unit,
    onQueueMessage: () -> Unit,
    onCancelMessage: () -> Unit,
    isLoading: Boolean,
    inputState: InputProcessingState = InputProcessingState.Idle,
    allowTextInputWhileProcessing: Boolean = false,
    onAttachmentRequest: (String) -> Unit = {},
    attachments: List<AttachmentInfo> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onInsertAttachment: (AttachmentInfo) -> Unit = {},
    onAttachScreenContent: () -> Unit = {},
    onAttachNotifications: () -> Unit = {},
    onAttachLocation: () -> Unit = {},
    onAttachMemory: () -> Unit = {},
    onAttachPackage: (String) -> Unit = {},
    onTakePhoto: (Uri) -> Unit,
    hasBackgroundImage: Boolean = false,
    chatInputTransparent: Boolean = false,
    chatInputFloating: Boolean = false,
    chatInputLiquidGlass: Boolean = false,
    chatInputWaterGlass: Boolean = false,
    modifier: Modifier = Modifier,
    externalAttachmentPanelState: Boolean? = null,
    onAttachmentPanelStateChange: ((Boolean) -> Unit)? = null,
    showInputProcessingStatus: Boolean = true,
    enableTools: Boolean = true,
    permissionStop: ToolPermissionStop = ToolPermissionStop.DEFAULT,
    onSetPermissionStop: (ToolPermissionStop) -> Unit = {},
    onToggleTools: () -> Unit = {},
    enableThinkingMode: Boolean = false,
    onSetThinkingMode: (Boolean) -> Unit = {},
    thinkingQualityLevel: Int = ApiPreferences.DEFAULT_THINKING_QUALITY_LEVEL,
    onThinkingQualityLevelChange: (Int) -> Unit = {},
    replyToMessage: ChatMessage? = null, // 回复目标消息
    onClearReply: (() -> Unit)? = null, // 清除回复状态的回调
    isWorkspaceOpen: Boolean = false,
    pendingQueueMessages: List<PendingQueueMessageItem> = emptyList(),
    isPendingQueueExpanded: Boolean = true,
    onPendingQueueExpandedChange: (Boolean) -> Unit = {},
    onDeletePendingQueueMessage: (Long) -> Unit = {},
    onEditPendingQueueMessage: (Long) -> Unit = {},
    onSteerPendingQueueMessage: (Long) -> Unit = {},
) {
    val showTokenLimitDialog = remember { mutableStateOf(false) }
    val showFullscreenInput = remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isProcessing =
        isLoading ||
            inputState is InputProcessingState.Connecting ||
            inputState is InputProcessingState.ExecutingTool ||
            inputState is InputProcessingState.ToolProgress ||
            inputState is InputProcessingState.Processing ||
            inputState is InputProcessingState.ProcessingToolResult ||
            inputState is InputProcessingState.Summarizing ||
            inputState is InputProcessingState.Receiving

    if (showTokenLimitDialog.value) {
        AlertDialog(
            onDismissRequest = {
                showTokenLimitDialog.value = false
            },
            title = { Text(context.getString(R.string.token_limit_warning)) },
            text = { Text(context.getString(R.string.token_limit_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTokenLimitDialog.value = false
                        onSendMessage()
                    }
                ) { Text(context.getString(R.string.continue_send)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTokenLimitDialog.value = false
                    },
                ) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
    val modernTextStyle = chatComposerTextStyle(agent = false)
    val mentionVisualTransformation = rememberMentionVisualTransformation(modernTextStyle)
    val composerTransformation = rememberComposerPredictionTransformation(
        userMessage, composerPrediction,
        mentionVisualTransformation,
    )
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    val toolProgressEvent by ToolProgressBus.progress.collectAsState()

    // Token limit calculation
    val currentWindowSize by actualViewModel.currentWindowSize.collectAsState()
    val maxWindowSizeInK by actualViewModel.maxWindowSizeInK.collectAsState()
    val maxTokens = (maxWindowSizeInK * 1024).toLong().coerceAtLeast(0L)
    val userMessageTokens = remember(userMessage.text) { ChatUtils.estimateTokenCount(userMessage.text) }
    val projectedTokens = userMessageTokens.toLong() + currentWindowSize

    val isOverTokenLimit =
        if (maxTokens > 0) {
            projectedTokens > maxTokens
        } else {
            false
        }

    val hasDraftText = userMessage.text.isNotBlank()
    val canSendMessage = hasDraftText || attachments.isNotEmpty()

    // Dictation only ever writes into the draft, so it stays a separate entry from the voice
    // conversation button at the end of the row.
    val dictation = rememberDraftVoiceController(actualViewModel, userMessage, onUserMessageChange)
    val dictationPermissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
            isGranted ->
            if (isGranted) {
                dictation.open()
            } else {
                actualViewModel.showToast(context.getString(R.string.voice_draft_permission))
            }
        }
    // Returns whether a session started; without the microphone permission it asks for it instead.
    val startDictation: (Boolean) -> Boolean = { holding ->
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            dictation.open(holding)
            true
        } else {
            dictationPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            false
        }
    }
    BackHandler(enabled = dictation.isActive) { dictation.cancel() }
    val holdArea = remember { HoldToTalkArea() }
    val keyboardDown = rememberKeyboardDown()

    val showQueueAction = isProcessing && canSendMessage
    val showCancelAction = isProcessing && !showQueueAction
    val sendButtonEnabled =
        when {
            isProcessing -> true // Queue / Cancel button
            canSendMessage -> true // Send button is always enabled if there's content
            else -> true // Mic button
        }

    val voicePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                actualViewModel.launchFloatingModeIn(
                    FloatingMode.FULLSCREEN,
                    colorScheme,
                    typography
                )
            } else {
                actualViewModel.showToast(context.getString(R.string.microphone_permission_denied_toast))
            }
        }

    // 控制附件面板的展开状态 - 使用外部状态或本地状态
    val (showAttachmentPanel, setShowAttachmentPanel) =
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                externalAttachmentPanelState ?: false
            )
        }

    // 当外部状态变化时更新本地状态
    androidx.compose.runtime.LaunchedEffect(externalAttachmentPanelState) {
        externalAttachmentPanelState?.let { setShowAttachmentPanel(it) }
    }

    // 当本地状态改变时通知外部
    androidx.compose.runtime.LaunchedEffect(showAttachmentPanel) {
        onAttachmentPanelStateChange?.invoke(showAttachmentPanel)
    }
    fun handleEnterSendAction() {
        if (!canSendMessage) return
        if (showQueueAction) {
            onQueueMessage()
            setShowAttachmentPanel(false)
            return
        }
        if (isOverTokenLimit) {
            showTokenLimitDialog.value = true
            return
        }
        onSendMessage()
        setShowAttachmentPanel(false)
    }

    // Releasing a hold to the send side sends the transcribed draft through the normal send path,
    // once this composition sees the text the transcription put in the box.
    var pendingVoiceSend by remember { mutableStateOf<String?>(null) }
    dictation.bindSend { pendingVoiceSend = actualViewModel.userMessage.value.text }
    LaunchedEffect(pendingVoiceSend, userMessage.text) {
        val expected = pendingVoiceSend ?: return@LaunchedEffect
        if (userMessage.text == expected) {
            pendingVoiceSend = null
            handleEnterSendAction()
        } else {
            delay(1000)
            pendingVoiceSend = null
        }
    }

    val surfaceColor = chatComposerColor(false, chatInputTransparent, hasBackgroundImage)
    val queueContainerColor = when {
        chatInputTransparent -> MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        else -> surfaceColor
    }
    val queueItemColor = when {
        chatInputTransparent -> MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        hasBackgroundImage -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        else -> MaterialTheme.colorScheme.surface
    }
    val inputLiquidGlassEnabled =
        chatInputTransparent && chatInputLiquidGlass && !chatInputWaterGlass && isLiquidGlassSupported()
    val inputWaterGlassEnabled =
        chatInputTransparent && chatInputWaterGlass && isWaterGlassSupported()
    val containerShape = chatComposerShape(agent = false, floating = chatInputFloating)
    val containerModifier =
        if (chatInputFloating) {
            modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        } else {
            modifier
        }

    Box(
        modifier =
            containerModifier
                .then(
                    if (chatInputFloating && !inputLiquidGlassEnabled && !inputWaterGlassEnabled) {
                        Modifier.shadow(4.dp, containerShape, clip = false)
                    } else {
                        Modifier
                    }
                )
                .waterGlass(
                    enabled = inputWaterGlassEnabled,
                    shape = containerShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    shadowElevation = if (chatInputFloating) 10.dp else 14.dp,
                    borderWidth = 0.7.dp,
                    overlayAlphaBoost = if (chatInputFloating) 0.04f else 0.08f,
                )
                .liquidGlass(
                    enabled = inputLiquidGlassEnabled,
                    shape = containerShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    shadowElevation = if (chatInputFloating) 10.dp else 14.dp,
                    borderWidth = 0.42.dp,
                    blurRadius = if (chatInputFloating) 16.dp else 20.dp,
                    overlayAlphaBoost = if (chatInputFloating) 0.06f else 0.10f,
                )
                .clip(containerShape)
                .background(
                    if (inputLiquidGlassEnabled || inputWaterGlassEnabled) {
                        Color.Transparent
                    } else {
                        surfaceColor
                    }
                ),
    ) {
        Column {
            // Reply preview section
            replyToMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = context.getString(R.string.reply_message),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        val previewText = message.content
                            .replace(Regex("<[^>]*>"), "") // 移除XML标签
                            .trim()
                            .let { if (it.length > 50) it.take(50) + "..." else it }
                        Text(
                            text = "${stringResource(R.string.reply_message)}: $previewText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        IconButton(
                            onClick = { onClearReply?.invoke() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = context.getString(R.string.cancel_reply),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            PendingMessageQueuePanel(
                queuedMessages = pendingQueueMessages,
                expanded = isPendingQueueExpanded,
                onExpandedChange = onPendingQueueExpandedChange,
                onDeleteMessage = onDeletePendingQueueMessage,
                onEditMessage = onEditPendingQueueMessage,
                onSteerMessage = onSteerPendingQueueMessage,
                containerColor = queueContainerColor,
                itemColor = queueItemColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Input processing indicator
            SimpleAnimatedVisibility(
                visible =
                    showInputProcessingStatus &&
                        inputState !is InputProcessingState.Idle &&
                        inputState !is InputProcessingState.Completed &&
                        inputState !is InputProcessingState.Error
            ) {
                val (progressColor, baseMessage) = when (inputState) {
                    is InputProcessingState.Connecting -> MaterialTheme.colorScheme.tertiary to inputState.message
                    is InputProcessingState.ExecutingTool -> MaterialTheme.colorScheme.secondary to context.getString(R.string.executing_tool, inputState.toolName)
                    is InputProcessingState.ToolProgress -> MaterialTheme.colorScheme.secondary to inputState.message
                    is InputProcessingState.Processing -> MaterialTheme.colorScheme.primary to inputState.message
                    is InputProcessingState.ProcessingToolResult -> MaterialTheme.colorScheme.tertiary.copy(
                        alpha = 0.8f
                    ) to context.getString(R.string.processing_tool_result, inputState.toolName)
                    is InputProcessingState.Summarizing -> MaterialTheme.colorScheme.tertiary to inputState.message
                    is InputProcessingState.Receiving -> MaterialTheme.colorScheme.secondary to inputState.message
                    else -> MaterialTheme.colorScheme.primary to ""
                }

                var message = baseMessage
                var progressValue = when (inputState) {
                    is InputProcessingState.Processing -> 0.3f
                    is InputProcessingState.Connecting -> 0.6f
                    is InputProcessingState.Summarizing -> 0.05f
                    is InputProcessingState.ToolProgress -> inputState.progress
                    else -> 1f
                }

                if (inputState is InputProcessingState.ExecutingTool) {
                    val event = toolProgressEvent
                    if (event != null && inputState.toolName.contains(event.toolName)) {
                        progressValue = event.progress
                        if (event.message.isNotBlank()) {
                            message = event.message
                        }
                    }
                }

                if (inputState is InputProcessingState.Summarizing) {
                    val event = toolProgressEvent
                    if (event != null && event.toolName == ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME) {
                        progressValue = event.progress
                        if (event.message.isNotBlank()) {
                            message = event.message
                        }
                    }
                }

                SimpleLinearProgressIndicator(
                    progress = progressValue,
                    modifier = Modifier.fillMaxWidth(),
                    color = progressColor
                )

                if (message.isNotBlank()) {
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 16.dp,
                                vertical = 4.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                            MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.8f),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }


            // Attachment chips row - only show if there are attachments
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 0.dp,
                                bottom = 6.dp
                            ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    items(attachments) { attachment ->
                        AttachmentChip(
                            attachmentInfo = attachment,
                            onRemove = {
                                onRemoveAttachment(
                                    attachment.filePath
                                )
                            },
                            onInsert = {
                                onInsertAttachment(attachment)
                            }
                        )
                    }
                }
            }

            val classicInputRowHorizontalPadding = if (chatInputFloating) 14.dp else 22.dp
            val classicInputRowVerticalPadding = if (chatInputFloating) 6.dp else 8.dp

            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = classicInputRowHorizontalPadding)
                    .padding(top = classicInputRowVerticalPadding, bottom = classicInputRowVerticalPadding),
            ) {
                // Input field (保持原有高度)

                val classicInputShape = RoundedCornerShape(14.dp)
                val classicInputEnabled = !isProcessing || allowTextInputWhileProcessing
                val classicInputBorderColor =
                    if (userMessage.text.isNotBlank()) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
                    }
                // The text sits on top and the actions underneath, so the field no longer carries a
                // frame of its own. One is drawn only when the card behind it is see-through, where
                // that frame is the only thing keeping the text readable.
                val classicInputNeedsBackdrop =
                    chatInputTransparent && !inputLiquidGlassEnabled && !inputWaterGlassEnabled

                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { holdArea.container = it }
                        .holdToTalk(
                            enabled = classicInputEnabled && keyboardDown && !dictation.isActive,
                            area = holdArea,
                            onStart = { startDictation(true) },
                            onTargetChange = dictation::setHoldTarget,
                            onRelease = dictation::releaseHold,
                        )
                        .then(
                            if (classicInputNeedsBackdrop) {
                                Modifier.border(1.dp, classicInputBorderColor, classicInputShape)
                            } else {
                                Modifier
                            }
                        )
                        .clip(classicInputShape)
                        .background(
                            if (classicInputNeedsBackdrop) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 4.dp),
                ) {

                if (dictation.isActive) {
                    val recognitionState by dictation.recognitionStateFlow.collectAsState()
                    val transcriptScroll = rememberScrollState()
                    LaunchedEffect(dictation.liveText, transcriptScroll.maxValue) {
                        transcriptScroll.scrollTo(transcriptScroll.maxValue)
                    }
                    // An error keeps the panel open with nothing to stop: the microphone is already
                    // off, so the row offers another attempt instead.
                    val dictationFailed = dictation.errorText != null
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)
                            .verticalScroll(transcriptScroll).padding(vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text =
                                dictation.liveText.ifBlank {
                                    dictation.errorText
                                        ?: if (dictation.isProcessing) {
                                            stringResource(R.string.voice_transcribing)
                                        } else {
                                            dictation.statusText
                                        }
                                },
                            style = modernTextStyle,
                            color =
                                if (dictation.liveText.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }

                    if (dictationFailed && dictation.liveText.isNotBlank()) {
                        Text(
                            text = dictation.errorText.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (dictation.isProcessing && dictation.liveText.isNotBlank()) {
                        // Letting go is not the end: the last words are still being turned into text.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.voice_transcribing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (dictation.isHolding) {
                        HoldToTalkRow(
                            releaseToSend = dictation.releaseToSend,
                            releaseToCancel = dictation.releaseToCancel,
                            volumeLevelFlow = dictation.volumeLevelFlow,
                            isRecording = dictation.isRecording,
                            isCapturing = dictation.isHearingEarly || recognitionState ==
                                com.ai.assistance.operit.api.speech.SpeechService.RecognitionState.RECOGNIZING,
                        )
                    } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { dictation.cancel() },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        if (dictationFailed) {
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            VoiceRecordingWaveform(
                                volumeLevelFlow = dictation.volumeLevelFlow,
                                isRecording = dictation.isRecording,
                                isCapturing = dictation.isHearingEarly || recognitionState ==
                                    com.ai.assistance.operit.api.speech.SpeechService.RecognitionState.RECOGNIZING,
                                modifier =
                                    Modifier.weight(1f).height(22.dp).padding(horizontal = 8.dp),
                            )
                        }

                        if (dictationFailed) {
                            IconButton(
                                onClick = { dictation.open() },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.voice_draft_retry),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { dictation.finishRecording() },
                                modifier = Modifier.size(36.dp),
                            ) {
                                if (dictation.isPreparing || dictation.isProcessing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Stop,
                                        contentDescription = stringResource(R.string.voice_draft_finish),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Takes what has already been recognised. It fills the message box; it
                        // never sends.
                        val hasLiveText = dictation.liveText.isNotBlank()
                        Box(
                            modifier =
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (hasLiveText) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .clickable(enabled = hasLiveText) { dictation.confirmNow() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription =
                                stringResource(R.string.voice_draft_fill_message_box),
                                tint =
                                if (hasLiveText) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    }
                } else {

                Row(verticalAlignment = Alignment.CenterVertically) {
                SuppressFieldLongPress(active = classicInputEnabled && keyboardDown) {
                BasicTextField(
                    value = userMessage,
                    onValueChange = onUserMessageChange,
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { holdArea.field = it }
                        .heightIn(min = 24.dp)
                        .composerPredictionSemantics(userMessage, composerPrediction,
                            enabled = classicInputEnabled,
                            onAccept = actualViewModel::acceptComposerPrediction)
                        .composerPredictionGesture(userMessage, composerPrediction,
                            enabled = classicInputEnabled,
                            onAccept = actualViewModel::acceptComposerPrediction)
                        .onPreviewKeyEvent { keyEvent ->
                            if (!enableEnterToSend) {
                                false
                            } else if (
                                keyEvent.type == KeyEventType.KeyDown &&
                                keyEvent.key == Key.Enter &&
                                !keyEvent.isShiftPressed
                            ) {
                                handleEnterSendAction()
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = modernTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = composerTransformation,
                    maxLines = if (visibleComposerPrediction(userMessage, composerPrediction) == null) 5 else Int.MAX_VALUE,
                    minLines = 1,
                    keyboardOptions =
                    KeyboardOptions(imeAction = if (enableEnterToSend) ImeAction.Send else ImeAction.Default),
                    keyboardActions =
                    if (enableEnterToSend) {
                        KeyboardActions(onSend = { handleEnterSendAction() })
                    } else {
                        KeyboardActions()
                    },
                    enabled = classicInputEnabled,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (userMessage.text.isEmpty() && visibleComposerPrediction(userMessage, composerPrediction) == null) {
                                Text(
                                    text =
                                        if (isWorkspaceOpen) {
                                            context.getString(R.string.input_question_with_workspace_hold)
                                        } else {
                                            context.getString(R.string.input_question_hint_hold)
                                        },
                                    style = modernTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ComposerPredictionViewport(
                                userMessage, composerPrediction, modernTextStyle, 5,
                                innerTextField,
                            )
                        }
                    },
                )
                }
                    Spacer(modifier = Modifier.width(4.dp))
                    // A plain box rather than IconButton: IconButton enforces a 48dp minimum touch
                    // target that would stretch the draft row past the text line and leave the icon
                    // sitting below the line it belongs to.
                    Box(
                        modifier =
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    showFullscreenInput.value = true
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = stringResource(R.string.chat_fullscreen_input),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Attachment button (+ 按钮) - 确保圆形

                Box(
                    modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (showAttachmentPanel)
                                MaterialTheme.colorScheme
                                    .primary
                            else
                                MaterialTheme.colorScheme
                                    .surfaceVariant
                        )
                        .clickable(
                            enabled = true,
                            onClick = {
                                setShowAttachmentPanel(
                                    !showAttachmentPanel
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = context.getString(R.string.add_attachment),
                        tint =
                        if (showAttachmentPanel)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                        Spacer(modifier = Modifier.width(6.dp))
                        ComposerPermissionControl(
                            enableTools = enableTools,
                            permissionStop = permissionStop,
                            onSetPermissionStop = onSetPermissionStop,
                            onToggleTools = onToggleTools,
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        ThinkingStrengthControl(
                            thinkingEnabled = enableThinkingMode,
                            qualityLevel = thinkingQualityLevel,
                            onSetThinkingMode = onSetThinkingMode,
                            onQualityLevelChange = onThinkingQualityLevelChange,
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = { startDictation(false) },
                            enabled = classicInputEnabled,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = stringResource(R.string.voice_draft_button),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Send button (发送按钮) - 确保圆形
                        Box(
                    modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                showCancelAction ->
                                    MaterialTheme
                                        .colorScheme
                                        .error
                                showQueueAction ->
                                    MaterialTheme.colorScheme.tertiary

                                canSendMessage ->
                                    if (isOverTokenLimit)
                                        MaterialTheme.colorScheme.secondary // Warning color
                                    else
                                        MaterialTheme.colorScheme.primary

                                else ->
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                            }
                        )
                        .clickable(
                            enabled = sendButtonEnabled,
                            onClick = {
                                when {
                                    showCancelAction ->
                                        onCancelMessage()
                                    showQueueAction -> {
                                        onQueueMessage()
                                        setShowAttachmentPanel(false)
                                    }

                                    canSendMessage -> {
                                        if (isOverTokenLimit) {
                                            showTokenLimitDialog.value = true
                                        } else {
                                            onSendMessage()
                                            // 发送消息后关闭附件面板
                                            setShowAttachmentPanel(
                                                false
                                            )
                                        }
                                    }

                                    else -> {
                                        actualViewModel.onFloatingButtonClick(
                                            FloatingMode.FULLSCREEN,
                                            voicePermissionLauncher,
                                            colorScheme,
                                            typography
                                        )
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val iconTint =
                        when {
                            showCancelAction -> MaterialTheme.colorScheme.onError
                            showQueueAction -> MaterialTheme.colorScheme.onTertiary
                            canSendMessage ->
                                if (isOverTokenLimit)
                                    MaterialTheme.colorScheme.onSecondary
                                else
                                    MaterialTheme.colorScheme.onPrimary

                            else -> MaterialTheme.colorScheme.onPrimary
                        }
                    Icon(
                        imageVector =
                        when {
                            showCancelAction -> Icons.Default.Close
                            showQueueAction -> Icons.Default.Add
                            canSendMessage -> Icons.AutoMirrored.Filled.Send
                            else -> Icons.Rounded.GraphicEq
                        },
                        contentDescription =
                        when {
                            showCancelAction -> context.getString(R.string.cancel)
                            showQueueAction -> context.getString(R.string.chat_queue_add_message)
                            canSendMessage -> context.getString(R.string.send)
                            else -> context.getString(R.string.voice_conversation_entry)
                        },
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }

            }



                }
                }

            }

            // Token limit warning
            if (isOverTokenLimit && canSendMessage && !showQueueAction) {
                Text(
                    text = stringResource(R.string.token_limit_exceeded_message, projectedTokens, maxTokens),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp)
                )
            }

            AttachmentSelectorPanel(
                visible = showAttachmentPanel,
                onAttachImage = { filePath ->
                    onAttachmentRequest(filePath)
                },
                onAttachFile = { filePath ->
                    onAttachmentRequest(filePath)
                },
                onAttachScreenContent = onAttachScreenContent,
                onAttachNotifications = onAttachNotifications,
                onAttachLocation = onAttachLocation,
                onAttachMemory = onAttachMemory,
                onAttachPackage = onAttachPackage,
                onTakePhoto = onTakePhoto,
                userQuery = userMessage.text,
                onDismiss = { setShowAttachmentPanel(false) }
            )

            if (showFullscreenInput.value) {
                FullscreenInputDialog(
                    value = userMessage,
                    composerPrediction = composerPrediction,
                    onAcceptPrediction = { expected ->
                        if (actualViewModel.acceptComposerPrediction(expected)) actualViewModel.userMessage.value else null
                    },
                    onValueChange = onUserMessageChange,
                    onDismiss = { showFullscreenInput.value = false },
                    onConfirm = { showFullscreenInput.value = false }
                )
            }

        }
    }
}

/**
 * The panel's bottom row while a finger holds the composer: stop on the left, send on the right;
 * sliding up turns both off and the label into the cancel warning.
 */
@Composable
private fun HoldToTalkRow(
    releaseToSend: Boolean,
    releaseToCancel: Boolean,
    volumeLevelFlow: kotlinx.coroutines.flow.StateFlow<Float>,
    isRecording: Boolean,
    isCapturing: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoldTarget(
            icon = androidx.compose.material.icons.Icons.Filled.Stop,
            highlighted = !releaseToSend && !releaseToCancel,
        )
        Text(
            text = stringResource(
                when {
                    releaseToCancel -> R.string.voice_hold_release_to_cancel
                    releaseToSend -> R.string.voice_hold_release_to_send
                    else -> R.string.voice_hold_release_to_stop
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (releaseToCancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp),
        )
        VoiceRecordingWaveform(
            volumeLevelFlow = volumeLevelFlow,
            isRecording = isRecording,
            isCapturing = isCapturing,
            modifier = Modifier.weight(1f).height(22.dp).padding(horizontal = 8.dp),
        )
        HoldTarget(
            icon = androidx.compose.material.icons.Icons.Filled.ArrowUpward,
            highlighted = releaseToSend,
        )
    }
}

@Composable
private fun HoldTarget(icon: androidx.compose.ui.graphics.vector.ImageVector, highlighted: Boolean) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (highlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint =
                if (highlighted) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
