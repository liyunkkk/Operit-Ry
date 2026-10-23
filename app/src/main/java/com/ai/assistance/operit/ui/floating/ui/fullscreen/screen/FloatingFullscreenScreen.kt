package com.ai.assistance.operit.ui.floating.ui.fullscreen.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.WakeWordPreferences
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.FloatingMode
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.BottomControlBar
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.EditPanel
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.MessageDisplay
import com.ai.assistance.operit.ui.floating.ui.fullscreen.components.WaveVisualizerSection
import com.ai.assistance.operit.ui.floating.ui.fullscreen.viewmodel.rememberFloatingFullscreenModeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 全屏模式主屏幕
 */
@Composable
fun FloatingFullscreenMode(floatContext: FloatContext) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val service = floatContext.chatService
    val autoEnterVoiceChat = remember { floatContext.voiceMode }
    var autoEnteringVoice by remember(autoEnterVoiceChat) { mutableStateOf(autoEnterVoiceChat) }
    val viewModel = rememberFloatingFullscreenModeViewModel(context, floatContext, coroutineScope, initialWaveActive = autoEnterVoiceChat)
    
    // 偏好设置
    val preferencesManager = UserPreferencesManager.getInstance(context)
    val characterCardManager = remember { CharacterCardManager.getInstance(context) }
    val characterGroupCardManager = remember { CharacterGroupCardManager.getInstance(context) }
    val activePromptManager = remember { ActivePromptManager.getInstance(context) }
    val activePrompt by activePromptManager.activePromptFlow.collectAsState(
        initial = ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID)
    )
    val activeCharacterCard by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterCard -> characterCardManager.getCharacterCardFlow(prompt.id)
            is ActivePrompt.CharacterGroup -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCharacterGroup by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterGroup -> characterGroupCardManager.getCharacterGroupCardFlow(prompt.id)
            is ActivePrompt.CharacterCard -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCardAvatarUri by remember(activeCharacterCard?.id) {
        activeCharacterCard?.id?.let { preferencesManager.getAiAvatarForCharacterCardFlow(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupAvatarUri by remember(activeCharacterGroup?.id) {
        activeCharacterGroup?.id?.let { preferencesManager.getAiAvatarForCharacterGroupFlow(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupFallbackMemberCardId = remember(activeCharacterGroup?.members) {
        val sortedMembers = activeCharacterGroup?.members?.sortedBy { it.orderIndex }.orEmpty()
        sortedMembers.firstOrNull()?.characterCardId
    }
    val activeGroupFallbackMemberAvatarUri by remember(activeGroupFallbackMemberCardId) {
        activeGroupFallbackMemberCardId?.let { preferencesManager.getAiAvatarForCharacterCardFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeCharacterAvatarUri =
        when (activePrompt) {
            is ActivePrompt.CharacterGroup -> activeGroupAvatarUri ?: activeGroupFallbackMemberAvatarUri
            is ActivePrompt.CharacterCard -> activeCardAvatarUri
        }
    val globalAiAvatarUri by preferencesManager.customAiAvatarUri.collectAsState(initial = null)
    val aiAvatarUri = activeCharacterAvatarUri ?: globalAiAvatarUri

    val speechServiceProfiles = remember { SpeechServiceProfilesPreferences(context) }
    val currentTtsProfile by speechServiceProfiles.currentTtsProfileOrNullFlow.collectAsState(initial = null)
    val ttsCleanerRegexs = currentTtsProfile?.cleanerRegexs

    val wakePrefs = remember { WakeWordPreferences(context.applicationContext) }
    val autoNewChatGroup by wakePrefs.autoNewChatGroupFlow.collectAsState(
        initial = WakeWordPreferences.DEFAULT_AUTO_NEW_CHAT_GROUP
    )

    val volumeLevel by viewModel.volumeLevelFlow.collectAsState()
    
    var pendingSpeechPreview by remember { mutableStateOf<String?>(null) }
    var lastUserMessageTimestampBeforeSpeech by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(viewModel.isRecording, viewModel.userMessage) {
        if (viewModel.isRecording && viewModel.userMessage.isNotBlank()) {
            pendingSpeechPreview = viewModel.userMessage
        }
    }

    LaunchedEffect(viewModel.isRecording) {
        if (viewModel.isRecording) {
            lastUserMessageTimestampBeforeSpeech = floatContext.messages.lastOrNull { it.sender == "user" }?.timestamp
        }
    }

    LaunchedEffect(viewModel.isRecording) {
        if (!viewModel.isRecording && pendingSpeechPreview != null) {
            val snapshot = pendingSpeechPreview
            delay(1500)
            if (pendingSpeechPreview == snapshot) {
                pendingSpeechPreview = null
            }
        }
    }

    LaunchedEffect(floatContext.messages.lastOrNull()?.timestamp, viewModel.isRecording) {
        if (viewModel.isRecording) return@LaunchedEffect
        if (pendingSpeechPreview == null) return@LaunchedEffect
        val lastUser = floatContext.messages.lastOrNull { it.sender == "user" } ?: return@LaunchedEffect
        val beforeTs = lastUserMessageTimestampBeforeSpeech
        if (beforeTs != null && lastUser.timestamp != beforeTs) {
            pendingSpeechPreview = null
        }
    }
    
    // 监听语音识别结果
    LaunchedEffect(Unit) {
        viewModel.recognitionResultFlow.collectLatest { result ->
            viewModel.handleRecognitionResult(result.text, result.isFinal)
        }
    }
    
    // 初始化
    LaunchedEffect(Unit) {
        viewModel.initialize(
            autoEnterVoiceChat = autoEnterVoiceChat,
            wakeLaunched = service?.isWakeLaunched() == true,
            enableAutoTimeout = floatContext.voiceAutoTimeout,
        )
    }

    LaunchedEffect(viewModel.isWaveActive) {
        if (viewModel.isWaveActive) {
            autoEnteringVoice = false
        }
    }

    val latestMessage = floatContext.messages.lastOrNull()

    // 监听最新的AI消息
    LaunchedEffect(latestMessage?.timestamp, ttsCleanerRegexs) {
        val activeCleanerRegexs = ttsCleanerRegexs ?: return@LaunchedEffect
        viewModel.processAndSpeakAiMessage(
            latestMessage,
            activeCleanerRegexs
        )
    }

    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            viewModel.cleanup()
        }
    }
    
    // 监听是否需要自动勾选"圈选识别" (来自圈选识别返回)
    LaunchedEffect(floatContext.currentMode, floatContext.pendingScreenSelection) {
        if (floatContext.currentMode == FloatingMode.FULLSCREEN && floatContext.pendingScreenSelection) {
            viewModel.hasOcrSelection = true
            floatContext.pendingScreenSelection = false
        }
    }

    LaunchedEffect(viewModel.isWaveActive, autoEnteringVoice) {
        if (!viewModel.isWaveActive && !autoEnteringVoice && !viewModel.isEditMode) {
            floatContext.voiceMode = false
        }
    }

    // UI 布局
    val effectiveWaveActive = viewModel.isWaveActive || autoEnteringVoice
    val topInsetPadding = 0.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(10f)
                .padding(start = 16.dp, top = topInsetPadding + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        val group = autoNewChatGroup.trim().ifBlank {
                            WakeWordPreferences.DEFAULT_AUTO_NEW_CHAT_GROUP
                        }
                        val folderId =
                            ChatHistoryManager.getInstance(context.applicationContext)
                                .resolveOrCreateLegacyFolderId(
                                    group,
                                    activeCharacterCard?.name,
                                )
                        floatContext.chatService?.getChatCore()?.createNewChat(
                            folderId = folderId,
                            inheritGroupFromCurrent = false,
                            characterCardId = activeCharacterCard?.id,
                        )
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.AddComment,
                    contentDescription = stringResource(R.string.new_chat),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 顶部控制区域：返回窗口 / 语音模式 / 缩成语音球 / 关闭
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(10f)
                .padding(end = 16.dp, top = topInsetPadding + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回窗口模式
            IconButton(onClick = {
                floatContext.voiceMode = false
                floatContext.onModeChange(FloatingMode.WINDOW)
            }) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.floating_back_to_window),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 缩小成语音球
            IconButton(onClick = { floatContext.onModeChange(FloatingMode.VOICE_BALL) }) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = stringResource(R.string.floating_shrink_to_ball),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 关闭悬浮窗
            IconButton(
                onClick = {
                    viewModel.cleanup()
                    floatContext.onClose()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.floating_close_floating_window),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        
        val isBottomBarVisible = viewModel.showBottomControls && !viewModel.isEditMode && !effectiveWaveActive
        Column(Modifier.fillMaxSize().padding(top = 76.dp, bottom = if (isBottomBarVisible) 120.dp else 72.dp)) {
            if (effectiveWaveActive) BoxWithConstraints(Modifier.fillMaxWidth().weight(0.8f)) {
                val activeWaveSize = minOf(maxWidth, maxHeight, 300.dp)
                val activeAvatarSize = minOf(activeWaveSize * 0.65f, 120.dp)
                val centerTapTargetSize = 140.dp
                WaveVisualizerSection(
                    isWaveActive = viewModel.isWaveActive,
                    isRecording = viewModel.isRecording,
                    showAiLoadingEffect = viewModel.isVoiceCapturePausedForAi && !viewModel.isRecording,
                    volumeLevelFlow = if (viewModel.isWaveActive && viewModel.isRecording)
                        viewModel.volumeLevelFlow else null,
                    aiAvatarUri = aiAvatarUri,
                    activeWaveSize = activeWaveSize,
                    activeAvatarSize = activeAvatarSize,
                    onToggleActive = {
                        if (viewModel.isWaveActive) {
                            viewModel.exitWaveMode()
                        } else {
                            viewModel.enterWaveMode(enableAutoTimeout = false)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(1f)
                )

                // 语音态：头像区域提供一个最高层的点击出口，确保“点头像退出语音态”不被其它层拦截
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(centerTapTargetSize)
                        .zIndex(4f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.onCenterAvatarClick()
                        }
                )
            }
            
            if (effectiveWaveActive) {
                val state = floatContext.inputProcessingState.value
                val voiceStatus = when {
                    state is com.ai.assistance.operit.data.model.InputProcessingState.Error -> state.message
                    viewModel.isRecording -> stringResource(R.string.floating_listening)
                    viewModel.isProcessingSpeech -> stringResource(R.string.floating_recognizing)
                    viewModel.isPlaybackSuppressed -> viewModel.voiceStatus
                    viewModel.isVoiceCapturePausedForAi -> stringResource(R.string.floating_voice_answering)
                    viewModel.voiceStatus.isNotBlank() -> viewModel.voiceStatus
                    else -> stringResource(R.string.floating_voice_starting)
                }
                Text(voiceStatus, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            MessageDisplay(
                floatContext = floatContext,
                speechPreviewText = if (viewModel.isRecording) viewModel.userMessage else (pendingSpeechPreview ?: ""),
                showSpeechOverlay = viewModel.isRecording || pendingSpeechPreview != null,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        if (effectiveWaveActive && !viewModel.isEditMode) {
            Row(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                TextButton(onClick = { viewModel.toggleMicrophone() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (viewModel.microphoneMuted) R.string.voice_unmute else R.string.voice_mute))
                }
                if (viewModel.hasSpeechPlayback) {
                    TextButton(onClick = { viewModel.stopSpeaking() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.voice_stop_playback))
                    }
                }
                TextButton(
                    onClick = { viewModel.exitWaveMode(); floatContext.voiceMode = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.voice_end_conversation)) }
            }
        }

        // 编辑面板
        EditPanel(
            visible = viewModel.isEditMode,
            editableText = viewModel.editableText,
            onTextChange = { viewModel.editableText = it },
            onCancel = { viewModel.exitEditMode() },
            onSend = { viewModel.sendEditedMessage() },
            canSend = viewModel.canSendEditedMessage,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        
        // 底部控制栏
        BottomControlBar(
            visible = isBottomBarVisible,
            isRecording = viewModel.isRecording,
            isProcessingSpeech = viewModel.isProcessingSpeech,
            showDragHints = viewModel.showDragHints,
            floatContext = floatContext,
            onStartVoiceCapture = { viewModel.startVoiceCapture() },
            onStopVoiceCapture = { isCancel -> viewModel.stopVoiceCapture(isCancel) },
            isWaveActive = viewModel.isWaveActive,
            onToggleWaveMode = {
                if (viewModel.isWaveActive) {
                    viewModel.exitWaveMode()
                } else {
                    viewModel.enterWaveMode(enableAutoTimeout = false)
                }
            },
            onEnterEditMode = { text -> viewModel.enterEditMode(text) },
            onShowDragHintsChange = { viewModel.showDragHints = it },
            userMessage = viewModel.inputText,
            onUserMessageChange = { viewModel.inputText = it },
            attachScreenContent = viewModel.attachScreenContent,
            onAttachScreenContentChange = { viewModel.attachScreenContent = it },
            attachNotifications = viewModel.attachNotifications,
            onAttachNotificationsChange = { viewModel.attachNotifications = it },
            attachLocation = viewModel.attachLocation,
            onAttachLocationChange = { viewModel.attachLocation = it },
            hasOcrSelection = viewModel.hasOcrSelection,
            onHasOcrSelectionChange = { viewModel.hasOcrSelection = it },
            isTtsMuted = viewModel.isStreamingTtsMuted,
            onToggleTtsMute = { viewModel.toggleStreamingTtsMuted() },
            onSendClick = { viewModel.sendInputMessage() },
            volumeLevel = volumeLevel,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
