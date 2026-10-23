package com.ai.assistance.operit.ui.features.chat.screens

import com.ai.assistance.operit.ui.features.chat.components.rememberChatAppearance

import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.ai.assistance.operit.util.AppLogger
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CodeOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.ui.components.CustomScaffold
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.CharacterCardChatModelBindingMode
import com.ai.assistance.operit.data.model.CharacterCardMemoryProfileBindingMode
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.data.preferences.WaifuPreferences
import com.ai.assistance.operit.data.repository.SubagentRunRepository
import com.ai.assistance.operit.features.reading.ReadingCompanionAudit
import com.ai.assistance.operit.ui.components.ErrorDialog
import com.ai.assistance.operit.ui.features.chat.components.*
import com.ai.assistance.operit.ui.features.chat.components.style.input.agent.AgentChatInputSection
import com.ai.assistance.operit.ui.features.chat.components.style.input.classic.ClassicChatInputSection
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.ChatInputEvents
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.ChatInputHookContext
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.ChatInputHookRegistry
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.ChatInputSubmitActions
import com.ai.assistance.operit.ui.features.chat.components.style.input.classic.ClassicChatSettingsBar
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.PendingQueueMessageItem
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.BubbleImageStyleConfig
import com.ai.assistance.operit.ui.features.chat.components.AndroidExportDialog
import com.ai.assistance.operit.ui.features.chat.components.ExportCompleteDialog
import com.ai.assistance.operit.ui.features.chat.components.ExportPlatformDialog
import com.ai.assistance.operit.ui.features.chat.components.ExportProgressDialog
import com.ai.assistance.operit.ui.features.chat.components.WindowsExportDialog
import com.ai.assistance.operit.ui.features.chat.webview.MentionSuggestionPanelStyle
import com.ai.assistance.operit.ui.features.chat.webview.workspace.WorkspaceScreen
import com.ai.assistance.operit.ui.features.chat.webview.MentionSuggestionPanel
import com.ai.assistance.operit.ui.features.chat.webview.computer.ComputerScreen
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.main.LocalTopBarActions
import com.ai.assistance.operit.ui.main.navigation.AppRouterGateway
import com.ai.assistance.operit.ui.main.PendingChatDraftHandler
import com.ai.assistance.operit.ui.main.components.LocalAppBarContentColor
import com.ai.assistance.operit.ui.main.screens.GestureStateHolder
import com.ai.assistance.operit.ui.main.SharedFileHandler
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.flowOf
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.ui.common.rememberLocal
import com.ai.assistance.operit.ui.main.components.LocalIsCurrentScreen
import com.ai.assistance.operit.ui.main.components.LocalSetScreenSoftInputMode
import com.ai.assistance.operit.ui.main.components.LocalSetUseScreenImePadding
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatHistoryDisplayMode
import com.ai.assistance.operit.ui.features.chat.viewmodel.PendingMessageQueueState
import com.ai.assistance.operit.ui.theme.getTextColorForBackground
import com.ai.assistance.operit.plugins.chatview.ChatViewEvent
import com.ai.assistance.operit.plugins.chatview.ChatViewHookParams
import com.ai.assistance.operit.plugins.chatview.ChatViewHookPluginRegistry
import java.util.UUID


@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview(showBackground = true)
fun AIChatScreen(
        padding: PaddingValues = PaddingValues(),
        viewModel: ChatViewModel? = null,
        isFloatingMode: Boolean = false,
        embedded: Boolean = false,
        onLoading: (Boolean) -> Unit = {},
        onError: (String) -> Unit = {},
        hasBackgroundImage: Boolean = false,
        onNavigateToTokenConfig: () -> Unit = {},
        onNavigateToSettings: () -> Unit = {},
        onNavigateToUserPreferences: () -> Unit = {},
        onNavigateToModelConfig: () -> Unit = {},
        onNavigateToFunctionalConfig: () -> Unit = onNavigateToSettings,
        onNavigateToOnboardingModelConfig: () -> Unit = {},
        onNavigateToModelPrompts: () -> Unit = {},
        onNavigateToPackageManager: () -> Unit = {},
        onNavigateBack: () -> Unit = {},
        onGestureConsumed: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val isCurrentScreen = LocalIsCurrentScreen.current
// Correctly initialize ViewModel using the viewModel() composable function
val actualViewModel: ChatViewModel = viewModel ?: viewModel { ChatViewModel(context.applicationContext) }

    // 设置权限系统的颜色方案
    LaunchedEffect(colorScheme) { actualViewModel.setPermissionSystemColorScheme(colorScheme) }

    // Monitor shared files from external apps
    val sharedFiles by SharedFileHandler.sharedFiles.collectAsState()
    val sharedFileText by SharedFileHandler.sharedFileText.collectAsState()
    val sharedText by SharedFileHandler.sharedText.collectAsState()

    // 添加麦克风权限请求启动器
    val requestMicrophonePermissionLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    // This launcher is now used inside the ViewModel's permission check flow
                    // It's kept here because it's tied to the composable lifecycle.
                    // The actual logic is now triggered from within the ViewModel after the check.
                } else {
                    // 权限被拒绝
                    android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.microphone_permission_denied),
                                    android.widget.Toast.LENGTH_SHORT
                            )
                            .show()
                }
            }

    // Get background image state
    val preferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val displayPreferencesManager = remember { DisplayPreferencesManager.getInstance(context) }
    val useBackgroundImage by preferencesManager.useBackgroundImage.collectAsState(initial = false)
    val backgroundImageUri by preferencesManager.backgroundImageUri.collectAsState(initial = null)
    val chatHeaderTransparent by preferencesManager.chatHeaderTransparent.collectAsState(initial = false)
    val chatInputTransparent by preferencesManager.chatInputTransparent.collectAsState(initial = false)
    val chatInputFloating by preferencesManager.chatInputFloating.collectAsState(initial = true)
    val chatInputLiquidGlassRaw by
        preferencesManager.chatInputLiquidGlass.collectAsState(initial = false)
    val chatInputWaterGlass by
        preferencesManager.chatInputWaterGlass.collectAsState(initial = false)
    val chatInputLiquidGlass = chatInputLiquidGlassRaw && !chatInputWaterGlass
    val chatHeaderHistoryIconColor by preferencesManager.chatHeaderHistoryIconColor.collectAsState(
            initial = null
    )
    val chatHeaderPipIconColor by preferencesManager.chatHeaderPipIconColor.collectAsState(initial = null)
    val chatHeaderOverlayMode by preferencesManager.chatHeaderOverlayMode.collectAsState(initial = false)
    val showInputProcessingStatus by preferencesManager.showInputProcessingStatus.collectAsState(initial = true)
    val enableEnterToSend by displayPreferencesManager.enableEnterToSend.collectAsState(initial = false)
    val showChatFloatingDotsAnimation by
        preferencesManager.showChatFloatingDotsAnimation.collectAsState(initial = true)
    val hasBackgroundImageFromPrefs = useBackgroundImage && backgroundImageUri != null
    val effectiveHasBackgroundImage = hasBackgroundImage || hasBackgroundImageFromPrefs

    val chatAppearance = rememberChatAppearance()
    val chatStyle = chatAppearance.chatStyle
    val inputStyle = chatAppearance.inputStyle
    val cursorUserBubbleLiquidGlass = chatAppearance.cursorUserBubbleLiquidGlass
    val cursorUserBubbleWaterGlass = chatAppearance.cursorUserBubbleWaterGlass
    val bubbleUserBubbleLiquidGlass = chatAppearance.bubbleUserBubbleLiquidGlass
    val bubbleUserBubbleWaterGlass = chatAppearance.bubbleUserBubbleWaterGlass
    val bubbleAiBubbleLiquidGlass = chatAppearance.bubbleAiBubbleLiquidGlass
    val bubbleAiBubbleWaterGlass = chatAppearance.bubbleAiBubbleWaterGlass
    val bubbleUserRoundedCornersEnabled = chatAppearance.bubbleUserRoundedCornersEnabled
    val bubbleAiRoundedCornersEnabled = chatAppearance.bubbleAiRoundedCornersEnabled
    val userMessageColor = chatAppearance.userMessageColor
    val aiMessageColor = chatAppearance.aiMessageColor
    val userTextColor = chatAppearance.userTextColor
    val aiTextColor = chatAppearance.aiTextColor
    val systemMessageColor = chatAppearance.systemMessageColor
    val systemTextColor = chatAppearance.systemTextColor
    val thinkingBackgroundColor = chatAppearance.thinkingBackgroundColor
    val thinkingTextColor = chatAppearance.thinkingTextColor
    val bubbleUserImageStyle = chatAppearance.bubbleUserImageStyle
    val bubbleAiImageStyle = chatAppearance.bubbleAiImageStyle
    val bubbleUserContentPaddingLeft = chatAppearance.bubbleUserContentPaddingLeft
    val bubbleUserContentPaddingRight = chatAppearance.bubbleUserContentPaddingRight
    val bubbleAiContentPaddingLeft = chatAppearance.bubbleAiContentPaddingLeft
    val bubbleAiContentPaddingRight = chatAppearance.bubbleAiContentPaddingRight
    val chatAreaHorizontalPadding = chatAppearance.chatAreaHorizontalPadding

    // 添加编辑按钮和编辑状态
    val editingMessageIndex = remember { mutableStateOf<Int?>(null) }
    val editingMessageContent = remember { mutableStateOf("") }

    // Collect state from ViewModel
    val apiKey by actualViewModel.apiKey.collectAsState()
    val activeChatConfigId by actualViewModel.activeChatConfigId.collectAsState()
    val modelName by actualViewModel.modelName.collectAsState()
    val chatHistory by actualViewModel.chatHistory.collectAsState()
    val displayedChatId by actualViewModel.displayedChatId.collectAsState()
    // 仅对当前会话显示处理中状态（影响“停止/发送”按钮）
    val isLoading by actualViewModel.currentChatIsLoading.collectAsState()
    val errorMessage by actualViewModel.errorMessage.collectAsState()
    // 按会话隔离的输入处理状态（用于进度条文案）
    val inputProcessingState by actualViewModel.currentChatInputProcessingState.collectAsState()

    val featureStates by actualViewModel.featureToggles.collectAsState()
    val enableThinkingMode by actualViewModel.enableThinkingMode.collectAsState() // 收集思考模式状态
    val thinkingQualityLevel by actualViewModel.thinkingQualityLevel.collectAsState()
    val enableMemoryAutoUpdate by actualViewModel.enableMemoryAutoUpdate.collectAsState()
    val enableMaxContextMode by actualViewModel.enableMaxContextMode.collectAsState()
    val enableTools by actualViewModel.enableTools.collectAsState()
    val toolPromptVisibility by actualViewModel.toolPromptVisibility.collectAsState()
    val toolPromptOrder by actualViewModel.toolPromptOrder.collectAsState()
    val disableStreamOutput by actualViewModel.disableStreamOutput.collectAsState()
    val disableUserPreferenceDescription by
            actualViewModel.disableUserPreferenceDescription.collectAsState()
    val summaryTokenThreshold by actualViewModel.summaryTokenThreshold.collectAsState()
    val isAutoReadEnabled by actualViewModel.isAutoReadEnabled.collectAsState()
    val showChatHistorySelector by actualViewModel.showChatHistorySelector.collectAsState()
    val chatHistories by actualViewModel.chatHistories.collectAsState()
    val chatHistoriesLoaded by actualViewModel.chatHistoriesLoaded.collectAsState()
    val currentChatId by actualViewModel.currentChatId.collectAsState()
    val hasNewerDisplayHistory by actualViewModel.hasNewerDisplayHistory.collectAsState()
    val isLoadingDisplayWindow by actualViewModel.isLoadingDisplayWindow.collectAsState()
    val popupMessage by actualViewModel.popupMessage.collectAsState()
    val chatViewRuntime = if (isFloatingMode) "floating" else "main"
    val chatViewId = rememberSaveable { UUID.randomUUID().toString() }
    val currentChatView = remember(chatHistories, currentChatId) {
        chatHistories.find { it.id == currentChatId }
    }
    val isSubagentChat = currentChatView?.chatKind == ChatKind.SUBAGENT.name
    val isHiddenReadingAuditChat =
        (
            currentChatView?.isHidden == true &&
                (ReadingCompanionAudit.isPermanentHiddenReason(currentChatView.hiddenReason) ||
                    currentChatView.hiddenReason == "MEMORY_LEARNING")
        ) || com.ai.assistance.operit.core.chat.AuditChatNavigation.hasPendingReturnFor(currentChatId)
    val isReadOnlyTranscript = isSubagentChat || isHiddenReadingAuditChat
    val subagentRunRepository = remember(context) { SubagentRunRepository.getInstance(context) }
    val currentSubagentRunFlow =
        remember(isSubagentChat, currentChatView?.id) {
            currentChatView
                ?.takeIf { isSubagentChat }
                ?.let { subagentRunRepository.observeByChildChatId(it.id) }
                ?: flowOf(null)
        }
    val currentSubagentRun by currentSubagentRunFlow.collectAsState(initial = null)
    val exitHiddenReadingAuditRun = {
        val auditChildChatId = currentChatView?.id ?: currentChatId.orEmpty()
        val rememberedReturnChatId =
            ReadingCompanionAudit.takeReturnChat(auditChildChatId)
        val fallbackReturnChatId =
            chatHistories
                .firstOrNull { chat ->
                    chat.id != auditChildChatId &&
                        !chat.isHidden &&
                        chat.chatKind == ChatKind.NORMAL.name
                }
                ?.id
                ?: chatHistories
                    .firstOrNull { chat ->
                        chat.id != auditChildChatId && !chat.isHidden
                    }
                    ?.id
        if (rememberedReturnChatId != null) {
            actualViewModel.switchChatLocally(
                rememberedReturnChatId,
                scrollToBottom = false,
            )
        } else {
            fallbackReturnChatId?.let { returnChatId ->
                actualViewModel.switchChat(returnChatId, scrollToBottom = false)
            }
        }
        onNavigateBack()
    }
    BackHandler(
        enabled =
            isHiddenReadingAuditChat ||
                (isSubagentChat && currentChatView?.parentChatId != null),
    ) {
        if (isHiddenReadingAuditChat) {
            exitHiddenReadingAuditRun()
        } else {
            currentChatView?.parentChatId?.let { parentChatId ->
                actualViewModel.switchChat(parentChatId, scrollToBottom = false)
            }
        }
    }
    val latestChatViewParams by rememberUpdatedState(
        ChatViewHookParams(
            context = context,
            viewId = chatViewId,
            chatId = currentChatId,
            workspacePath = currentChatView?.workspace,
            workspaceEnv = currentChatView?.workspaceEnv,
            runtime = chatViewRuntime,
            title = currentChatView?.title
        )
    )
    var hasDispatchedChatViewOpen by remember(chatViewId) { mutableStateOf(false) }
    LaunchedEffect(
        chatViewId,
        currentChatId,
        currentChatView?.workspace,
        currentChatView?.workspaceEnv,
        currentChatView?.title,
        chatViewRuntime
    ) {
        val event =
            if (hasDispatchedChatViewOpen) {
                ChatViewEvent.VIEW_UPDATED
            } else {
                hasDispatchedChatViewOpen = true
                ChatViewEvent.VIEW_OPENED
            }
        ChatViewHookPluginRegistry.dispatchAsync(event, latestChatViewParams)
    }
    DisposableEffect(chatViewId) {
        onDispose {
            ChatViewHookPluginRegistry.dispatchAsync(
                ChatViewEvent.VIEW_CLOSED,
                latestChatViewParams
            )
        }
    }
    // 收集滚动事件
    // 从ViewModel收集新的状态
    val shouldShowConfigDialog by actualViewModel.shouldShowConfigDialog.collectAsState()
    val isWorkspaceOpen by actualViewModel.isWorkspaceOpen.collectAsState()
    val isWorkspacePreparing by actualViewModel.isWorkspacePreparing.collectAsState()

    // 添加模型建议对话框状态
    var showModelSuggestionDialog by remember { mutableStateOf(false) }
    
    // 添加记忆文件夹选择对话框状态
    var showMemoryFolderDialog by remember { mutableStateOf(false) }

    // 当模型名称加载后，检查是否为建议更换的模型
    LaunchedEffect(modelName) {
        if (modelName.isNotBlank() && modelName.contains("deepseek-r1-0528-qwen3-8b:free", ignoreCase = true)) {
            showModelSuggestionDialog = true
        }
    }

    // 模型建议对话框
    if (showModelSuggestionDialog) {
        AlertDialog(
            onDismissRequest = { showModelSuggestionDialog = false },
            title = { Text(stringResource(R.string.model_suggestion_title)) },
            text = { Text(stringResource(R.string.model_suggestion_message)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showModelSuggestionDialog = false
                        // 如果用户已输入token，直接保存配置
                        actualViewModel.updateApiKey("")
                        actualViewModel.updateApiEndpoint(ApiPreferences.DEFAULT_API_ENDPOINT)
                        actualViewModel.updateModelName(ApiPreferences.DEFAULT_MODEL_NAME)
                        actualViewModel.updateApiProviderType(ApiProviderType.DEEPSEEK)
                        actualViewModel.saveApiSettings()

                    }) {
                        Text(stringResource(R.string.change_model))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelSuggestionDialog = false }) {
                    Text(stringResource(R.string.ignore))
                }
            }
        )
    }

    SharedIncomingContentHandler(
        sharedFiles = sharedFiles,
        sharedFileText = sharedFileText,
        sharedText = sharedText,
        chatHistories = chatHistories,
        currentChatId = currentChatId,
        onHandleSharedFiles = actualViewModel::handleSharedFiles,
        onHandleSharedText = actualViewModel::handleSharedText,
        onClearSharedFiles = SharedFileHandler::clearSharedFiles,
        onClearSharedText = SharedFileHandler::clearSharedText
    )

    val pendingChatDraft by PendingChatDraftHandler.pendingDraft.collectAsState()
    LaunchedEffect(pendingChatDraft, isCurrentScreen) {
        if (!isCurrentScreen) return@LaunchedEffect
        val draft = pendingChatDraft?.trim().orEmpty()
        if (draft.isBlank()) return@LaunchedEffect

        actualViewModel.createNewChatWithDraft(draft)
        PendingChatDraftHandler.clearPendingDraft()
    }


    // 添加WebView刷新相关状态
    val webViewRefreshCounter by actualViewModel.webViewRefreshCounter.collectAsState()

    // Floating window mode state
    val isFloatingMode by actualViewModel.isFloatingMode.collectAsState()
    val canDrawOverlays = remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // UI state
    val historyListState = rememberLazyListState()
    var selectedHistoryCategoryName by
        rememberLocal(
            "chat_history_selected_category",
            ChatHistoryCategory.ALL.name,
        )
    val selectedHistoryCategory =
        runCatching { ChatHistoryCategory.valueOf(selectedHistoryCategoryName) }
            .getOrDefault(ChatHistoryCategory.ALL)
    var collapsedHistoryGroups by
        rememberLocal(
            "chat_history_collapsed_groups",
            emptySet<String>(),
        )
    var collapsedHistoryCharacters by
        rememberLocal(
            "chat_history_collapsed_characters",
            emptySet<String>(),
        )
    val coroutineScope = rememberCoroutineScope()
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
    var historyDisplayMode by rememberLocal(
        "chat_history_display_mode",
        ChatHistoryDisplayMode.BY_FOLDER
    )
    var autoSwitchCharacterCard by rememberLocal(
        "chat_history_auto_switch_character_card",
        false
    )
    var autoSwitchChatOnCharacterSelect by rememberLocal(
        "chat_history_auto_switch_chat_on_character_select",
        false
    )
    val visibleAllChatHistories = remember(chatHistories) {
        chatHistories.filter {
            it.chatKind != ChatKind.SUBAGENT.name && !it.isHidden
        }
    }
    val localizedUserRoleName = stringResource(R.string.message_role_user)
    val displayedChatHistory =
        remember(
            chatHistory,
            isReadOnlyTranscript,
            currentSubagentRun,
            currentChatView?.characterCardName,
            currentChatView?.parentChatId,
            chatHistories,
            activeCharacterCard,
            localizedUserRoleName,
        ) {
            if (!isReadOnlyTranscript) {
                chatHistory
            } else {
                val parentCharacterName =
                    currentChatView?.characterCardName
                        ?: chatHistories
                            .firstOrNull { it.id == currentChatView?.parentChatId }
                            ?.characterCardName
                        ?: activeCharacterCard?.name
                        ?: ""
                val subagentName = resolveSubagentAgentName(currentSubagentRun)
                chatHistory.map { message ->
                    val displayedRoleName =
                        resolveSubagentTranscriptRoleName(
                            message = message,
                            parentAgentName = parentCharacterName,
                            subagentName = subagentName,
                            localizedUserRoleName = localizedUserRoleName,
                        )
                    if (displayedRoleName == message.roleName) {
                        message
                    } else {
                        message.copy(roleName = displayedRoleName)
                    }
                }
            }
        }
    val displayedChatHistories = remember(
        chatHistories,
        activePrompt,
        activeCharacterCard,
        activeCharacterGroup,
        historyDisplayMode
    ) {
        when (historyDisplayMode) {
            ChatHistoryDisplayMode.CURRENT_CHARACTER_ONLY -> {
                when (activePrompt) {
                    is ActivePrompt.CharacterGroup -> {
                        val group = activeCharacterGroup ?: return@remember emptyList()
                        visibleAllChatHistories.filter { history ->
                            history.characterGroupId == group.id
                        }
                    }
                    is ActivePrompt.CharacterCard -> {
                        val activeCard = activeCharacterCard ?: return@remember emptyList()
                        visibleAllChatHistories.filter { history ->
                            val historyCard = history.characterCardName
                            if (activeCard.isDefault) {
                                historyCard == null || historyCard == activeCard.name
                            } else {
                                historyCard == activeCard.name
                            }
                        }
                    }
                }
            }
            else -> visibleAllChatHistories
        }
    }
    LaunchedEffect(autoSwitchCharacterCard) {
        actualViewModel.setAutoSwitchCharacterCard(autoSwitchCharacterCard)
    }
    LaunchedEffect(autoSwitchChatOnCharacterSelect) {
        actualViewModel.setAutoSwitchChatOnCharacterSelect(autoSwitchChatOnCharacterSelect)
    }
    LaunchedEffect(
        activePrompt,
        activeCharacterCard,
        activeCharacterGroup,
        displayedChatHistories,
        currentChatId,
        chatHistories,
        historyDisplayMode
    ) {
        if (historyDisplayMode != ChatHistoryDisplayMode.CURRENT_CHARACTER_ONLY) {
            return@LaunchedEffect
        }
        val hasActiveTarget = when (activePrompt) {
            is ActivePrompt.CharacterGroup -> activeCharacterGroup != null
            is ActivePrompt.CharacterCard -> activeCharacterCard != null
        }
        if (!hasActiveTarget || displayedChatHistories.isEmpty()) {
            return@LaunchedEffect
        }
        val currentId = currentChatId ?: ""
        if (currentId.isBlank()) {
            actualViewModel.switchChat(displayedChatHistories.first().id)
        }
    }
    val characterCardBoundChatModelConfigId =
        activeCharacterCard
            ?.takeIf {
                activePrompt is ActivePrompt.CharacterCard &&
                    CharacterCardChatModelBindingMode.normalize(it.chatModelBindingMode) ==
                    CharacterCardChatModelBindingMode.FIXED_CONFIG &&
                    !it.chatModelConfigId.isNullOrBlank()
            }
            ?.chatModelConfigId
    val characterCardBoundChatModelIndex =
        activeCharacterCard?.chatModelIndex?.coerceAtLeast(0) ?: 0
    val characterCardBoundMemoryProfileId =
        activeCharacterCard
            ?.takeIf {
                activePrompt is ActivePrompt.CharacterCard &&
                    CharacterCardMemoryProfileBindingMode.normalize(it.memoryProfileBindingMode) ==
                    CharacterCardMemoryProfileBindingMode.FIXED_PROFILE &&
                    !it.memoryProfileId.isNullOrBlank()
            }
            ?.memoryProfileId
    


    // 滚动状态
    var chatAutoScrollStates by rememberSaveable {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
    var autoScrollToBottom by remember(currentChatId) {
        mutableStateOf(chatAutoScrollStates[currentChatId.orEmpty()] ?: true)
    }
    val onAutoScrollToBottomChange =
        remember(currentChatId) {
            { enabled: Boolean ->
                autoScrollToBottom = enabled
                currentChatId?.let { chatId ->
                    chatAutoScrollStates = chatAutoScrollStates + (chatId to enabled)
                }
                Unit
            }
        }
    val requestAutoScrollToBottom =
        remember(currentChatId) {
            {
                autoScrollToBottom = true
                currentChatId?.let { chatId ->
                    chatAutoScrollStates = chatAutoScrollStates + (chatId to true)
                }
                Unit
            }
        }
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // 移除原有的 snackbar 错误处理
    val snackbarHostState = remember { SnackbarHostState() }

    // 用新的错误弹窗替换原有的错误显示逻辑
    errorMessage?.let { message ->
        ErrorDialog(errorMessage = message, onDismiss = { actualViewModel.dismissErrorDialog() })
    }

    val toastEvent by actualViewModel.toastEvent.collectAsState()

    // Save chat on app exit
    DisposableEffect(Unit) {
        onDispose {
            // This is handled by the ViewModel
        }
    }

    var isSavingInitialConfiguration by remember { mutableStateOf(false) }
    var initialConfigurationSaveFailed by
        rememberSaveable(activeChatConfigId) { mutableStateOf(false) }
    val showConfig =
        shouldShowConfigDialog ||
            isSavingInitialConfiguration ||
            initialConfigurationSaveFailed

    // 添加手势状态
    var chatScreenGestureConsumed by remember { mutableStateOf(false) }
    val onChatScreenGestureConsumedChange = remember {
        { it: Boolean -> chatScreenGestureConsumed = it }
    }

    // 添加累计滑动距离变量
    var currentDrag by remember { mutableStateOf(0f) }
    val onCurrentDragChange = remember { { it: Float -> currentDrag = it } }
    var verticalDrag by remember { mutableStateOf(0f) }
    val onVerticalDragChange = remember { { it: Float -> verticalDrag = it } }
    val dragThreshold = 40f // 与PhoneLayout保持一致
    val onSwitchCharacter = remember(actualViewModel) {
        { target: CharacterSelectorTarget ->
            actualViewModel.switchActiveCharacterTarget(target)
        }
    }

    // 收集WebView显示状态
    val showWebView by actualViewModel.showWebView.collectAsState()
    // 收集AI电脑显示状态
    val showAiComputer by actualViewModel.showAiComputer.collectAsState()
    com.ai.assistance.operit.pet.PetViewedChatObserver(
        currentChatId.takeIf {
            !isFloatingMode && isCurrentScreen && !showChatHistorySelector &&
                !showWebView && !showAiComputer && !isWorkspacePreparing && !showConfig &&
                displayedChatId == currentChatId && !isLoadingDisplayWindow &&
                !hasNewerDisplayHistory
        },
    )
    val shouldUseChatLocalImeHandling =
        embedded &&
            inputStyle == UserPreferencesManager.INPUT_STYLE_AGENT &&
            !showWebView &&
            !showAiComputer
    var hasEverShownWebView by remember { mutableStateOf(false) }
    LaunchedEffect(showWebView, isWorkspacePreparing) {
        if (showWebView || isWorkspacePreparing) {
            hasEverShownWebView = true
        }
    }
    // 当手势状态改变时，通知父组件
    LaunchedEffect(chatScreenGestureConsumed, showWebView) {
        val finalGestureState = chatScreenGestureConsumed
        // 同时更新全局状态持有者，确保PhoneLayout能够访问到状态
        GestureStateHolder.isChatScreenGestureConsumed = finalGestureState
        onGestureConsumed(finalGestureState)
    }

    // 处理文件选择器请求
    val fileChooserRequest by actualViewModel.uiStateDelegate.fileChooserRequest.collectAsState()
    val fileChooserLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                // 处理文件选择结果
                actualViewModel.handleFileChooserResult(result.resultCode, result.data)
                // 清除请求
                actualViewModel.uiStateDelegate.clearFileChooserRequest()
            }

    // 启动文件选择器
    LaunchedEffect(fileChooserRequest) {
        fileChooserRequest?.let { fileChooserLauncher.launch(it) }
    }

    // 从CompositionLocal获取设置TopBar Actions的函数
    val setTopBarActions = LocalTopBarActions.current
    val appBarContentColor = LocalAppBarContentColor.current
    val setScreenSoftInputMode = LocalSetScreenSoftInputMode.current
    val setUseScreenImePadding = LocalSetUseScreenImePadding.current
    val requestedSoftInputMode =
        if (shouldUseChatLocalImeHandling) {
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    val shouldUseGlobalImePadding = !shouldUseChatLocalImeHandling
    val hasBoundWorkspace = !currentChatView?.workspace.isNullOrBlank()

    SideEffect {
        if (isCurrentScreen && !embedded) {
            setScreenSoftInputMode(requestedSoftInputMode)
            setUseScreenImePadding(shouldUseGlobalImePadding)
        }
    }


    // 当showWebView或showAiComputer状态改变时，更新TopAppBar的actions
    // 使用DisposableEffect确保当AIChatScreen离开组合时，actions被清空
    LaunchedEffect(isCurrentScreen, embedded, showWebView, showAiComputer, isWorkspacePreparing, appBarContentColor, hasBoundWorkspace) {
        if (isCurrentScreen && !embedded) {
            setTopBarActions {
                // AI电脑模式切换按钮
                IconButton(
                        enabled = !isWorkspacePreparing,
                        onClick = {
                            actualViewModel.onAiComputerButtonClick()
                        }
                ) {
                    Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = stringResource(R.string.ai_computer),
                            tint =
                            if (showAiComputer) MaterialTheme.colorScheme.primaryContainer
                            else appBarContentColor
                    )
                }

                // Web开发模式切换按钮
                IconButton(
                        enabled = !isWorkspacePreparing,
                        onClick = {
                            actualViewModel.onWorkspaceButtonClick()
                        }
                ) {
                    if (isWorkspacePreparing) {
                        CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = appBarContentColor
                        )
                    } else {
                        Icon(
                                imageVector =
                                if (hasBoundWorkspace) Icons.Default.Code
                                else Icons.Default.CodeOff,
                                contentDescription =
                                if (hasBoundWorkspace) stringResource(R.string.workspace)
                                else stringResource(R.string.setup_workspace),
                                tint =
                                if (showWebView) MaterialTheme.colorScheme.primaryContainer
                                else appBarContentColor
                        )
                    }
                }
            }
        }
    }

    // 导出相关状态
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
    var showCharacterSelector by remember { mutableStateOf(false) }

    var bottomBarHeightPx by remember { mutableStateOf(0) }
    var bottomDockHeightPx by remember { mutableStateOf(0) }
    var bottomDockRowCenterPx by remember { mutableStateOf(0) }
    val bottomBarHeightDp = with(density) { bottomBarHeightPx.toDp() }
    val bottomDockHeightDp = with(density) { bottomDockHeightPx.toDp() }
    val bottomDockRowCenterDp = with(density) { bottomDockRowCenterPx.toDp() }
    // The settings bar is a 28dp icon button frame with 8dp of vertical padding on either side, so
    // its centre sits 22dp above the bottom edge that `classicSettingsBarBottomPadding` positions.
    val classicSettingsButtonHalfHeightDp = 22.dp
    // The measured bottom bar also carries the todo / prefix-warning dock above the input card, and
    // the dock grows upward when its todo panel opens. Only the dock's own row may position the
    // settings button: the button centres on that row's content instead of floating one line above
    // it, and keeps its 6dp gap above the input card while the dock is empty.
    val inputBarHeightDp = (bottomBarHeightDp - bottomDockHeightDp).coerceAtLeast(0.dp)
    val classicSettingsBarBottomPadding =
        when {
            inputBarHeightDp <= 36.dp -> 18.dp
            bottomDockRowCenterDp > 0.dp ->
                inputBarHeightDp + bottomDockRowCenterDp - classicSettingsButtonHalfHeightDp
            // Unrelated to ChatTodoDock's 6dp row gap: this one is the settings button's own
            // clearance above the input card when the dock contributes no row to align with.
            else -> inputBarHeightDp - 6.dp
        }
    val inputBarTranslationYPx =
        if (shouldUseChatLocalImeHandling && imeBottomPx > 0) {
            imeBottomPx.toFloat()
        } else {
            0f
        }
    val chatViewportTranslationYPx = inputBarTranslationYPx
    Box(modifier = Modifier.fillMaxSize()) {
        CustomScaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
            // 根据前面的逻辑条件决定是否显示配置界面
            if (showConfig) {
                ConfigurationScreen(
                        apiKey = apiKey,
                        isSaving = isSavingInitialConfiguration,
                        onSaveApiKey = { normalizedApiKey ->
                            if (!isSavingInitialConfiguration) {
                                coroutineScope.launch {
                                    isSavingInitialConfiguration = true
                                    initialConfigurationSaveFailed = false
                                    try {
                                        actualViewModel.saveDeepSeekConfiguration(
                                            activeChatConfigId,
                                            normalizedApiKey
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        initialConfigurationSaveFailed = true
                                        actualViewModel.showErrorMessage(
                                            e.message ?: context.getString(R.string.save_failed)
                                        )
                                    } finally {
                                        isSavingInitialConfiguration = false
                                    }
                                }
                            }
                        },
                        onNavigateToTokenConfig = onNavigateToTokenConfig,
                        onNavigateToModelConfig = {
                            initialConfigurationSaveFailed = false
                            onNavigateToOnboardingModelConfig()
                        }
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .clipToBounds()
                ) {
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .graphicsLayer { translationY = -chatViewportTranslationYPx }
                    ) {
                        ChatScreenContent(
                                modifier = Modifier.fillMaxSize(),
                                configurationNotice = {
                                    if (!isFloatingMode && !isReadOnlyTranscript && !embedded) {
                                        ModelConfigurationNotice(onNavigateToFunctionalConfig)
                                    }
                                },
                                paddingValues =
                                        PaddingValues(), // Padding is already handled by the parent Box
                                bottomInset = bottomBarHeightDp,
                                 actualViewModel = actualViewModel,
                                 enableMessageDialogs = !isFloatingMode,
                                 readOnlyTranscript = isReadOnlyTranscript,
                                 showChatHistorySelector = showChatHistorySelector,
                                chatHistory = displayedChatHistory,
                                isLoading = isLoading,
                                userMessageColor = userMessageColor,
                                aiMessageColor = aiMessageColor,
                                userTextColor = userTextColor,
                                aiTextColor = aiTextColor,
                                systemMessageColor = systemMessageColor,
                                systemTextColor = systemTextColor,
                                thinkingBackgroundColor = thinkingBackgroundColor,
                                thinkingTextColor = thinkingTextColor,
                                hasBackgroundImage = effectiveHasBackgroundImage,
                                editingMessageIndex = editingMessageIndex,
                                editingMessageContent = editingMessageContent,
                                chatScreenGestureConsumed = chatScreenGestureConsumed,
                                onChatScreenGestureConsumed = onChatScreenGestureConsumedChange,
                                currentDrag = currentDrag,
                                onCurrentDragChange = onCurrentDragChange,
                                verticalDrag = verticalDrag,
                                onVerticalDragChange = onVerticalDragChange,
                                dragThreshold = dragThreshold,
                                autoScrollToBottom = autoScrollToBottom,
                                onAutoScrollToBottomChange = onAutoScrollToBottomChange,
                                coroutineScope = coroutineScope,
                                chatHistories = chatHistories,
                                currentChatId = currentChatId ?: "",
                                chatHeaderTransparent = chatHeaderTransparent,
                                chatHeaderHistoryIconColor = chatHeaderHistoryIconColor,
                                chatHeaderPipIconColor = chatHeaderPipIconColor,
                                chatHeaderOverlayMode = chatHeaderOverlayMode,
                                chatStyle = chatStyle, // Pass chat style
                                cursorUserBubbleLiquidGlass = cursorUserBubbleLiquidGlass,
                                cursorUserBubbleWaterGlass = cursorUserBubbleWaterGlass,
                                bubbleUserBubbleLiquidGlass = bubbleUserBubbleLiquidGlass,
                                bubbleUserBubbleWaterGlass = bubbleUserBubbleWaterGlass,
                                bubbleAiBubbleLiquidGlass = bubbleAiBubbleLiquidGlass,
                                bubbleAiBubbleWaterGlass = bubbleAiBubbleWaterGlass,
                                historyListState = historyListState,
                                showCharacterSelector = showCharacterSelector,
                                onShowCharacterSelectorChange = { showCharacterSelector = it },
                                onSwitchCharacter = onSwitchCharacter,
                                onOpenCharacterSettings = onNavigateToModelPrompts,
                                onExitHiddenReadingAuditRun = exitHiddenReadingAuditRun,
                                chatAreaHorizontalPadding = chatAreaHorizontalPadding,
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

                        if (
                            !isReadOnlyTranscript &&
                                inputStyle == UserPreferencesManager.INPUT_STYLE_CLASSIC
                        ) {
                            ClassicChatSettingsBar(
                                    modifier =
                                            Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(
                                                            end = 4.dp,
                                                            bottom = classicSettingsBarBottomPadding,
                                                    )
                                                    .graphicsLayer {
                                                        translationY = -inputBarTranslationYPx
                                                    },
                                    currentChatId = currentChatId,
                                    featureStates = featureStates,
                                    onToggleFeature = { featureKey ->
                                        actualViewModel.toggleFeature(featureKey)
                                    },
                                    inputMenuRuntime = chatViewRuntime,
                                    permissionStop =
                                            actualViewModel.masterPermissionStop
                                                    .collectAsState()
                                                    .value,
                                    onSetPermissionStop = actualViewModel::setMasterPermissionStop,
                                    enableThinkingMode = enableThinkingMode,
                                    onToggleThinkingMode = { actualViewModel.toggleThinkingMode() },
                                    thinkingQualityLevel = thinkingQualityLevel,
                                    onThinkingQualityLevelChange = {
                                        actualViewModel.updateThinkingQualityLevel(it)
                                    },
                                    maxWindowSizeInK =
                                            actualViewModel.maxWindowSizeInK.collectAsState().value,
                                    baseContextLengthInK =
                                            actualViewModel.baseContextLengthInK.collectAsState().value,
                                    maxContextLengthInK =
                                            actualViewModel.maxContextLengthInK.collectAsState().value,
                                    onContextLengthChange = {
                                        actualViewModel.updateContextLength(it)
                                    },
                                    enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                                    onToggleMemoryAutoUpdate = {
                                        actualViewModel.toggleMemoryAutoUpdate()
                                    },
                                    enableMaxContextMode = enableMaxContextMode,
                                    onToggleEnableMaxContextMode = {
                                        actualViewModel.toggleEnableMaxContextMode()
                                    },
                                    summaryTokenThreshold = summaryTokenThreshold,
                                    onSummaryTokenThresholdChange = {
                                        actualViewModel.updateSummaryTokenThreshold(it)
                                    },
                                    onNavigateToUserPreferences = onNavigateToUserPreferences,
                                    onNavigateToModelConfig = onNavigateToModelConfig,
                                    onNavigateToModelPrompts = onNavigateToModelPrompts,
                                    onNavigateToPackageManager = onNavigateToPackageManager,
                                    isAutoReadEnabled = isAutoReadEnabled,
                                    onToggleAutoRead = { actualViewModel.toggleAutoRead() },
                                    enableTools = enableTools,
                                    onToggleTools = { actualViewModel.toggleTools() },
                                    toolPromptVisibility = toolPromptVisibility,
                                    onSaveToolPromptVisibilityMap = { visibilityMap ->
                                        actualViewModel.saveToolPromptVisibilityMap(visibilityMap)
                                    },
                                    toolOrder = toolPromptOrder,
                                    onSaveToolOrder = { order ->
                                        actualViewModel.saveToolPromptOrder(order)
                                    },
                                    disableStreamOutput = disableStreamOutput,
                                    onToggleDisableStreamOutput = {
                                        actualViewModel.toggleDisableStreamOutput()
                                    },
                                    disableUserPreferenceDescription =
                                            disableUserPreferenceDescription,
                                    onToggleDisableUserPreferenceDescription = {
                                        actualViewModel.toggleDisableUserPreferenceDescription()
                                    },
                                    onManualMemoryUpdate = {
                                        actualViewModel.manuallyUpdateMemory()
                                    },
                                    characterCardBoundChatModelConfigId = characterCardBoundChatModelConfigId,
                                    characterCardBoundChatModelIndex = characterCardBoundChatModelIndex,
                                    characterCardBoundMemoryProfileId = characterCardBoundMemoryProfileId
                            )
                        }
                    }

                    if (!isReadOnlyTranscript) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .onGloballyPositioned {
                                        bottomBarHeightPx = it.size.height
                                    }
                                    .graphicsLayer { translationY = -inputBarTranslationYPx }
                        ) {
                            ChatInputBottomBar(
                                actualViewModel = actualViewModel,
                                inputStyle = inputStyle,
                                currentChatId = currentChatId,
                                inputMenuRuntime = chatViewRuntime,
                                enableEnterToSend = enableEnterToSend,
                                isLoading = isLoading,
                                inputState = inputProcessingState,
                                hasBackgroundImage = effectiveHasBackgroundImage,
                                chatInputTransparent = chatInputTransparent,
                                chatInputFloating = chatInputFloating,
                                chatInputLiquidGlass = chatInputLiquidGlass,
                                chatInputWaterGlass = chatInputWaterGlass,
                                showInputProcessingStatus = showInputProcessingStatus,
                                enableTools = enableTools,
                                isWorkspaceOpen = isWorkspaceOpen,
                                enableThinkingMode = enableThinkingMode,
                                thinkingQualityLevel = thinkingQualityLevel,
                                enableMaxContextMode = enableMaxContextMode,
                                featureStates = featureStates,
                                enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                                isAutoReadEnabled = isAutoReadEnabled,
                                disableStreamOutput = disableStreamOutput,
                                disableUserPreferenceDescription =
                                        disableUserPreferenceDescription,
                                onNavigateToUserPreferences = onNavigateToUserPreferences,
                                onNavigateToPackageManager = onNavigateToPackageManager,
                                toolPromptVisibility = toolPromptVisibility,
                                toolPromptOrder = toolPromptOrder,
                                onSaveToolOrder = { order ->
                                    actualViewModel.saveToolPromptOrder(order)
                                },
                                onNavigateToModelConfig = onNavigateToModelConfig,
                                characterCardBoundChatModelConfigId =
                                        characterCardBoundChatModelConfigId,
                                characterCardBoundChatModelIndex =
                                        characterCardBoundChatModelIndex,
                                characterCardBoundMemoryProfileId =
                                        characterCardBoundMemoryProfileId,
                                onShowMemoryFolderDialog = {
                                    showMemoryFolderDialog = true
                                },
                                onRequestAutoScrollToBottom = requestAutoScrollToBottom,
                                onDockHeightChange = { bottomDockHeightPx = it },
                                onDockRowCenterChange = { bottomDockRowCenterPx = it },
                            )
                        }
                    } else {
                        val todos by actualViewModel.currentTodos.collectAsState()
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .onGloballyPositioned {
                                        bottomBarHeightPx = it.size.height
                                    }
                        ) {
                            if (isSubagentChat) {
                                ChatTodoDock(chatId = currentChatId, todos = todos)
                            }
                        }
                    }

                    CharacterSelectorPanel(
                        isVisible = showCharacterSelector,
                        onDismiss = { showCharacterSelector = false },
                        onSelectCharacter = onSwitchCharacter,
                        onOpenCharacterSettings = onNavigateToModelPrompts
                    )

                    AnimatedVisibility(
                        visible = showChatHistorySelector,
                        enter = fadeIn(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(300)),
                        modifier = Modifier.matchParentSize()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    actualViewModel.toggleChatHistorySelector()
                                }
                        )
                    }

                    AnimatedVisibility(
                        visible = showChatHistorySelector,
                        enter = androidx.compose.animation.slideInHorizontally(
                            initialOffsetX = { -it },
                            animationSpec = tween(300)
                        ),
                        exit = androidx.compose.animation.slideOutHorizontally(
                            targetOffsetX = { -it },
                            animationSpec = tween(300)
                        ),
                        modifier = Modifier.matchParentSize()
                    ) {
                        val chatHistorySearchQuery by actualViewModel.chatHistorySearchQuery.collectAsState()
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.align(Alignment.TopStart)) {
                                ChatHistorySelectorPanel(
                                    actualViewModel = actualViewModel,
                                    chatHistories = displayedChatHistories,
                                    orderingChatHistories = chatHistories,
                                    searchableChatHistories = visibleAllChatHistories,
                                    currentChatId = currentChatId ?: "",
                                    showChatHistorySelector = showChatHistorySelector,
                                    historyListState = historyListState,
                                    onOpenHiddenChats = {
                                        AppRouterGateway.navigate(
                                            routeId = "native.hidden_chats",
                                            args = emptyMap(),
                                            source =
                                                com.ai.assistance.operit.ui.main.navigation
                                                    .RouteEntrySource.SCRIPT,
                                        )
                                    },
                                    onChatScreenGestureConsumed = onChatScreenGestureConsumedChange,
                                    searchQuery = chatHistorySearchQuery,
                                    onSearchQueryChange = actualViewModel::onChatHistorySearchQueryChange,
                                    activePrompt = activePrompt,
                                    historyDisplayMode = historyDisplayMode,
                                    onDisplayModeChange = { historyDisplayMode = it },
                                    autoSwitchCharacterCard = autoSwitchCharacterCard,
                                    onAutoSwitchCharacterCardChange = { autoSwitchCharacterCard = it },
                                    autoSwitchChatOnCharacterSelect = autoSwitchChatOnCharacterSelect,
                                    onAutoSwitchChatOnCharacterSelectChange = {
                                        autoSwitchChatOnCharacterSelect = it
                                    },
                                    selectedHistoryCategory = selectedHistoryCategory,
                                    onSelectedHistoryCategoryChange = {
                                        selectedHistoryCategoryName = it.name
                                    },
                                    collapsedHistoryGroups = collapsedHistoryGroups,
                                    onCollapsedHistoryGroupsChange = {
                                        collapsedHistoryGroups = it
                                    },
                                    collapsedHistoryCharacters = collapsedHistoryCharacters,
                                    onCollapsedHistoryCharactersChange = {
                                        collapsedHistoryCharacters = it
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        MentionSuggestionOverlay(
            actualViewModel = actualViewModel,
            bottomBarHeightPx = bottomBarHeightPx,
            inputBarTranslationYPx = inputBarTranslationYPx,
            panelStyle =
                MentionSuggestionPanelStyle(
                    hasBackgroundImage = effectiveHasBackgroundImage,
                    chatInputTransparent = chatInputTransparent,
                    chatInputFloating = chatInputFloating,
                    chatInputLiquidGlass = chatInputLiquidGlass,
                    chatInputWaterGlass = chatInputWaterGlass,
                ),
        )

        val contextEditor by actualViewModel.contextEditor.collectAsState()
        val contextEditorSaving by actualViewModel.contextEditorSaving.collectAsState()
        contextEditor?.let { target ->
            com.ai.assistance.operit.ui.features.chat.components.ChatContextEditorDialog(
                target, actualViewModel::dismissContextEditor, actualViewModel::saveContextEditor, contextEditorSaving)
        }

        val workspaceOverlayModifier =
            if (!embedded && showWebView) {
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
            } else {
                Modifier
                    .size(0.dp)
                    .clearAndSetSemantics {}
            }

        // Web开发模式作为浮层，现在位于Scaffold外部，可以覆盖整个屏幕
        Layout(
            modifier = workspaceOverlayModifier,
            content = {
                // The content is composed unconditionally, keeping it "alive"
                val currentChat = chatHistories.find { it.id == currentChatId }
                if (hasEverShownWebView && currentChat != null) {
                    WorkspaceScreen(
                        actualViewModel = actualViewModel,
                        currentChat = currentChat,
                        isVisible = !embedded && showWebView, // Pass visibility state
                        onExportClick = { workDir ->
                            webContentDir = workDir
                            AppLogger.d(
                                "AIChatScreen",
                                "正在导出工作区: ${workDir.absolutePath}, 聊天ID: $currentChatId"
                            )
                            showExportPlatformDialog = true
                        }
                    )
                }
            }
        ) { measurables, constraints ->
            if (measurables.isEmpty()) {
                layout(0, 0) {}
            } else {
                if (!embedded && showWebView) {
                    val placeable = measurables.first().measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                } else {
                    // 当不可见时，我们让布局大小为0，并且完全不测量或放置子项。
                    // 这可以跳过子项昂贵的测量/布局过程，从而解决性能问题，
                    // 同时由于它仍在组合中，因此可以保持其状态。
                    layout(0, 0) {}
                }
            }
        }

        // AI电脑模式作为浮层：关闭时完全移出组合，确保 SurfaceView 被释放，避免机型相关残影
        if (!embedded && showAiComputer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                ComputerScreen()
            }
        }

        AnimatedVisibility(
            visible = !embedded && isWorkspacePreparing,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {},
                contentAlignment = Alignment.Center
            ) {
                ElevatedCard {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.workspace_opening),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.workspace_opening_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 导出平台选择对话框
        if (showExportPlatformDialog) {
            ExportPlatformDialog(
                    onDismiss = { showExportPlatformDialog = false },
                    onSelectAndroid = {
                        showExportPlatformDialog = false
                        showAndroidExportDialog = true
                    },
                    onSelectWindows = {
                        showExportPlatformDialog = false
                        showWindowsExportDialog = true
                    }
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
                        exportStatus = context.getString(R.string.export_starting)

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
                        exportStatus = context.getString(R.string.export_starting)

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
                    onCancel = {
                        exportJob?.cancel()
                        showExportProgressDialog = false
                    }
            )
        }

        // 导出完成对话框
        if (showExportCompleteDialog) {
            ExportCompleteDialog(
                    success = exportSuccess,
                    filePath = exportFilePath,
                    errorMessage = exportErrorMessage,
                    onDismiss = { showExportCompleteDialog = false },
                    onOpenFile = { path ->
                        val tool = AITool(
                            name = if (path.endsWith(".apk", ignoreCase = true)) "install_app" else "open_file",
                            parameters = listOf(ToolParameter("path", path))
                        )
                        AIToolHandler.getInstance(context).executeTool(tool)
                    }
            )
        }

        ChatToastHost(
            message = toastEvent,
            onDismiss = { actualViewModel.clearToastEvent() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = 16.dp,
                    top = padding.calculateTopPadding() + 50.dp,
                    end = 16.dp
                ),
            maxHeight = 280.dp
        )
    }

    // Show popup message dialog when needed
    popupMessage?.let { message ->
        AlertDialog(
                onDismissRequest = { actualViewModel.clearPopupMessage() },
                title = { Text(stringResource(R.string.dialog_title_prompt)) },
                text = { Text(message ?: "") },
                confirmButton = {
                    TextButton(onClick = { actualViewModel.clearPopupMessage() }) { Text(stringResource(R.string.ok)) }
                }
        )
    }

    // Check for overlay permission on resume
    LaunchedEffect(Unit) {
        canDrawOverlays.value = Settings.canDrawOverlays(context)

        // If floating mode is on but no permission, turn it off
        if (isFloatingMode && !canDrawOverlays.value) {
            actualViewModel.toggleFloatingMode()
            Toast.makeText(
                            context,
                            context.getString(R.string.floating_window_permission_denied),
                            Toast.LENGTH_SHORT
                    )
                    .show()
        }
    }
    
    // 记忆文件夹选择对话框
    MemoryFolderSelectionDialog(
        visible = showMemoryFolderDialog,
        onDismiss = { showMemoryFolderDialog = false },
        onConfirm = { selectedFolders ->
            actualViewModel.captureMemoryFolders(selectedFolders)
        }
    )
}

@Composable
private fun ChatInputBottomBar(
    actualViewModel: ChatViewModel,
    inputStyle: String,
    currentChatId: String?,
    inputMenuRuntime: String,
    enableEnterToSend: Boolean,
    isLoading: Boolean,
    inputState: InputProcessingState,
    hasBackgroundImage: Boolean,
    chatInputTransparent: Boolean,
    chatInputFloating: Boolean,
    chatInputLiquidGlass: Boolean,
    chatInputWaterGlass: Boolean,
    showInputProcessingStatus: Boolean,
    enableTools: Boolean,
    isWorkspaceOpen: Boolean,
    enableThinkingMode: Boolean,
    thinkingQualityLevel: Int,
    enableMaxContextMode: Boolean,
    featureStates: Map<String, Boolean>,
    enableMemoryAutoUpdate: Boolean,
    isAutoReadEnabled: Boolean,
    disableStreamOutput: Boolean,
    disableUserPreferenceDescription: Boolean,
    onNavigateToUserPreferences: () -> Unit,
    onNavigateToPackageManager: () -> Unit,
    toolPromptVisibility: Map<String, Boolean>,
    toolPromptOrder: List<String> = emptyList(),
    onSaveToolOrder: (List<String>) -> Unit = {},
    onNavigateToModelConfig: () -> Unit,
    characterCardBoundChatModelConfigId: String?,
    characterCardBoundChatModelIndex: Int,
    characterCardBoundMemoryProfileId: String?,
    onShowMemoryFolderDialog: () -> Unit,
    onRequestAutoScrollToBottom: () -> Unit,
    onDockHeightChange: (Int) -> Unit,
    onDockRowCenterChange: (Int) -> Unit,
) {
    val todos by actualViewModel.currentTodos.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val waifuPreferences = remember(context) { WaifuPreferences.getInstance(context) }

    val userMessage by actualViewModel.userMessage.collectAsState()
    val composerPrediction by actualViewModel.composerPredictionText.collectAsState()
    val attachments by actualViewModel.attachments.collectAsState()
    val attachmentPanelState by actualViewModel.attachmentPanelState.collectAsState()
    val replyToMessage by actualViewModel.replyToMessage.collectAsState()
    val permissionStop by actualViewModel.masterPermissionStop.collectAsState()
    val isSummarizing by actualViewModel.isSummarizing.collectAsState()
    val isSendTriggeredSummarizing by actualViewModel.isSendTriggeredSummarizing.collectAsState()
    val isWaifuModeEnabled by waifuPreferences.enableWaifuModeFlow.collectAsState(initial = false)
    val isWaifuMergeSendEnabled by
        waifuPreferences.waifuEnableMergeSendFlow.collectAsState(initial = false)
    val waifuMergeSendDelayMs by
        waifuPreferences.waifuMergeSendDelayMsFlow.collectAsState(
            initial = WaifuPreferences.DEFAULT_WAIFU_MERGE_SEND_DELAY_MS
        )

    val isMessageProcessing =
        isLoading ||
            inputState is InputProcessingState.Connecting ||
            inputState is InputProcessingState.ExecutingTool ||
            inputState is InputProcessingState.ToolProgress ||
            inputState is InputProcessingState.Processing ||
            inputState is InputProcessingState.ProcessingToolResult ||
            inputState is InputProcessingState.Summarizing ||
            inputState is InputProcessingState.Receiving
    val isQueueBlocked = isMessageProcessing || isSummarizing || isSendTriggeredSummarizing

    val pendingQueueStates by actualViewModel.pendingMessageQueueStates.collectAsState()
    val pendingQueueState =
        currentChatId?.let(pendingQueueStates::get) ?: PendingMessageQueueState()
    val pendingQueueMessages = pendingQueueState.messages
    val isPendingQueueExpanded = pendingQueueState.isExpanded
    val waifuMergeBuffer = remember(currentChatId) { mutableStateListOf<String>() }
    val latestQueueBlocked = rememberUpdatedState(isQueueBlocked)
    val latestCurrentChatId = rememberUpdatedState(currentChatId)

    fun buildChatInputHookContext(
        eventName: String,
        text: String = userMessage.text,
        selectionStart: Int = userMessage.selection.start,
        selectionEnd: Int = userMessage.selection.end,
        source: String = inputStyle,
        submitSource: String = "",
        chatId: String? = currentChatId,
        attachmentCount: Int = attachments.size,
    ): ChatInputHookContext {
        val normalizedSelectionStart = selectionStart.coerceIn(0, text.length)
        val normalizedSelectionEnd = selectionEnd.coerceIn(0, text.length)
        return ChatInputHookContext(
            context = context,
            eventName = eventName,
            chatId = chatId,
            text = text,
            selectionStart = normalizedSelectionStart,
            selectionEnd = normalizedSelectionEnd,
            hasAttachments = attachmentCount > 0,
            attachmentCount = attachmentCount,
            isProcessing = isMessageProcessing,
            inputStyle = inputStyle,
            source = source,
            submitSource = submitSource
        )
    }

    fun handleUserMessageChange(value: TextFieldValue) {
        actualViewModel.updateUserMessage(value)
        ChatInputHookRegistry.dispatchNotification(
            buildChatInputHookContext(
                eventName = ChatInputEvents.INPUT_CHANGED,
                text = value.text,
                selectionStart = value.selection.start,
                selectionEnd = value.selection.end
            )
        )
    }

    fun showChatInputHookMessage(message: String?) {
        val normalizedMessage = message?.trim().orEmpty()
        if (normalizedMessage.isNotBlank()) {
            Toast.makeText(context, normalizedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(
        currentChatId,
        waifuMergeBuffer.size,
        isWaifuModeEnabled,
        isWaifuMergeSendEnabled,
        waifuMergeSendDelayMs
    ) {
        if (!isWaifuModeEnabled || !isWaifuMergeSendEnabled) {
            waifuMergeBuffer.clear()
            return@LaunchedEffect
        }
        if (waifuMergeBuffer.isEmpty()) {
            return@LaunchedEffect
        }

        delay(waifuMergeSendDelayMs.toLong())
        snapshotFlow { latestQueueBlocked.value }.first { blocked -> !blocked }

        val messages = waifuMergeBuffer.toList()
        if (messages.isEmpty()) {
            return@LaunchedEffect
        }

        val chatId = latestCurrentChatId.value
        if (chatId.isNullOrBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.chat_please_create_new_chat),
                Toast.LENGTH_SHORT,
            ).show()
            return@LaunchedEffect
        }

        val triggerText = messages.last()
        val removedVisibleMessage =
            actualViewModel.removeLastVisibleUserMessageFromCurrentChat(triggerText)
        if (!removedVisibleMessage) {
            return@LaunchedEffect
        }
        waifuMergeBuffer.clear()

        focusManager.clearFocus()
        actualViewModel.sendTextMessage(triggerText)
        onRequestAutoScrollToBottom()
        ChatInputHookRegistry.dispatchNotification(
            buildChatInputHookContext(
                eventName = ChatInputEvents.SUBMITTED,
                text = triggerText,
                selectionStart = triggerText.length,
                selectionEnd = triggerText.length,
                source = "waifu_merge",
                submitSource = "waifu_merge"
            )
        )
    }

    fun restorePendingQueueItem(chatId: String, item: PendingQueueMessageItem) {
        actualViewModel.restorePendingQueueMessage(chatId, item)
    }

    fun removePendingQueueMessageById(id: Long): PendingQueueMessageItem? {
        val chatId = currentChatId ?: return null
        return actualViewModel.removePendingQueueMessage(chatId, id)
    }

    val sendQueuedItemNow: (String, PendingQueueMessageItem) -> Unit =
        { queueChatId, item ->
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var queueItemResolved = false
                try {
                    val submitDecision =
                        ChatInputHookRegistry.dispatchSubmitRequested(
                            buildChatInputHookContext(
                                eventName = ChatInputEvents.SUBMIT_REQUESTED,
                                text = item.text,
                                selectionStart = item.text.length,
                                selectionEnd = item.text.length,
                                source = "queue",
                                submitSource = "queue",
                                chatId = queueChatId,
                                attachmentCount = item.attachments.size,
                            )
                        )
                    if (!actualViewModel.isPendingQueueItemCurrent(queueChatId, item)) {
                        queueItemResolved = true
                        return@launch
                    }
                    when (submitDecision.action) {
                        ChatInputSubmitActions.BLOCK -> {
                            restorePendingQueueItem(queueChatId, item)
                            queueItemResolved = true
                            showChatInputHookMessage(submitDecision.message)
                            return@launch
                        }
                        ChatInputSubmitActions.CONSUME -> {
                            queueItemResolved = true
                            showChatInputHookMessage(submitDecision.message)
                            return@launch
                        }
                    }
                    showChatInputHookMessage(submitDecision.noticeMessage)
                    val finalText = submitDecision.text ?: item.text

                    focusManager.clearFocus()
                    if (
                        !actualViewModel.trySendQueuedTextMessage(
                            finalText,
                            chatId = queueChatId,
                            chatGeneration = item.chatGeneration,
                            attachments = item.attachments,
                        )
                    ) {
                        restorePendingQueueItem(queueChatId, item)
                        queueItemResolved = true
                        actualViewModel.showToast(context.getString(R.string.chat_queue_send_deferred))
                        return@launch
                    }
                    queueItemResolved = true
                    if (latestCurrentChatId.value == queueChatId) {
                        onRequestAutoScrollToBottom()
                    }
                    ChatInputHookRegistry.dispatchNotification(
                        buildChatInputHookContext(
                            eventName = ChatInputEvents.SUBMITTED,
                            text = finalText,
                            selectionStart = finalText.length,
                            selectionEnd = finalText.length,
                            source = "queue",
                            submitSource = "queue",
                            chatId = queueChatId,
                            attachmentCount = item.attachments.size,
                        )
                    )
                } finally {
                    if (!queueItemResolved) {
                        restorePendingQueueItem(queueChatId, item)
                    }
                }
            }
        }

    fun steerPendingQueueMessage(id: Long) {
        val chatId = currentChatId ?: return
        val expectedTurnId = actualViewModel.steeringTurnId(chatId)
        if (expectedTurnId == null) {
            actualViewModel.showToast(context.getString(R.string.chat_steer_unavailable))
            return
        }
        val item = removePendingQueueMessageById(id) ?: return
        coroutineScope.launch {
            var resolved = false
            try {
                val decision = ChatInputHookRegistry.dispatchSubmitRequested(
                    buildChatInputHookContext(
                        eventName = ChatInputEvents.SUBMIT_REQUESTED,
                        text = item.text, selectionStart = item.text.length,
                        selectionEnd = item.text.length, source = "queue",
                        submitSource = "steer", chatId = chatId,
                        attachmentCount = item.attachments.size,
                    )
                )
                when (decision.action) {
                    ChatInputSubmitActions.BLOCK -> {
                        showChatInputHookMessage(decision.message)
                        return@launch
                    }
                    ChatInputSubmitActions.CONSUME -> {
                        resolved = true
                        showChatInputHookMessage(decision.message)
                        return@launch
                    }
                }
                showChatInputHookMessage(decision.noticeMessage)
                val finalItem = item.copy(text = decision.text ?: item.text)
                resolved = actualViewModel.trySteerQueuedMessage(chatId, expectedTurnId, finalItem)
                if (!resolved) {
                    actualViewModel.showToast(context.getString(R.string.chat_steer_unavailable))
                } else {
                    ChatInputHookRegistry.dispatchNotification(
                        buildChatInputHookContext(
                            eventName = ChatInputEvents.SUBMITTED, text = finalItem.text,
                            selectionStart = finalItem.text.length, selectionEnd = finalItem.text.length,
                            source = "queue", submitSource = "steer", chatId = chatId,
                            attachmentCount = finalItem.attachments.size,
                        )
                    )
                }
            } finally {
                if (!resolved) restorePendingQueueItem(chatId, item)
            }
        }
    }

    LaunchedEffect(isQueueBlocked, pendingQueueMessages.size, currentChatId) {
        val queueChatId = currentChatId ?: return@LaunchedEffect
        if (actualViewModel.hasPendingQueueAutoDequeue(queueChatId, isQueueBlocked)) {
            delay(250)
            if (!actualViewModel.isPendingQueueTargetBusy(queueChatId)) {
                actualViewModel.takeNextPendingQueueAutoDequeue(queueChatId)?.let { item ->
                    sendQueuedItemNow(queueChatId, item)
                }
            }
        }
    }

    val queueAddedMessage = stringResource(R.string.chat_queue_added)
    fun enqueueDraftToPendingQueue() {
        val draftText = userMessage.text.trim()
        if (draftText.isBlank() && attachments.isEmpty()) return
        val chatId = currentChatId ?: return

        if (!actualViewModel.enqueuePendingQueueMessage(
            chatId = chatId,
            text = draftText,
            isQueueBlocked = isQueueBlocked,
        )) return
        actualViewModel.updateUserMessage(TextFieldValue(""))
        actualViewModel.showToast(queueAddedMessage)
    }

    val pleaseCreateNewChatMessage = stringResource(R.string.chat_please_create_new_chat)
    val sendMessage: () -> Unit = {
        coroutineScope.launch {
            if (currentChatId.isNullOrBlank()) {
                Toast.makeText(
                    context,
                    pleaseCreateNewChatMessage,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            val submitDecision =
                ChatInputHookRegistry.dispatchSubmitRequested(
                    buildChatInputHookContext(
                        eventName = ChatInputEvents.SUBMIT_REQUESTED,
                        submitSource = "send"
                    )
                )
            when (submitDecision.action) {
                ChatInputSubmitActions.BLOCK -> {
                    showChatInputHookMessage(submitDecision.message)
                    return@launch
                }
                ChatInputSubmitActions.CONSUME -> {
                    if (submitDecision.clearInput) {
                        actualViewModel.updateUserMessage(TextFieldValue(""))
                        actualViewModel.resetAttachmentPanelState()
                    }
                    showChatInputHookMessage(submitDecision.message)
                    return@launch
                }
            }
            showChatInputHookMessage(submitDecision.noticeMessage)

            val finalText = submitDecision.text ?: userMessage.text
            if (finalText != userMessage.text) {
                actualViewModel.updateUserMessage(
                    TextFieldValue(
                        text = finalText,
                        selection = TextRange(finalText.length)
                    )
                )
            }
            val shouldUseWaifuMergeSend =
                isWaifuModeEnabled &&
                    isWaifuMergeSendEnabled &&
                    attachments.isEmpty() &&
                    replyToMessage == null &&
                    finalText.isNotBlank()
            if (shouldUseWaifuMergeSend) {
                val visibleText = finalText.trim()
                waifuMergeBuffer.add(visibleText)
                actualViewModel.addVisibleUserMessageToCurrentChat(visibleText)
                actualViewModel.updateUserMessage(TextFieldValue(""))
                actualViewModel.resetAttachmentPanelState()
                focusManager.clearFocus()
                onRequestAutoScrollToBottom()
                return@launch
            }
            focusManager.clearFocus()
            actualViewModel.sendUserMessage()
            actualViewModel.resetAttachmentPanelState()
            onRequestAutoScrollToBottom()
            ChatInputHookRegistry.dispatchNotification(
                buildChatInputHookContext(
                    eventName = ChatInputEvents.SUBMITTED,
                    text = finalText,
                    selectionStart = finalText.length,
                    selectionEnd = finalText.length,
                    submitSource = "send"
                )
            )
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        ChatTodoDock(
            chatId = currentChatId,
            todos = todos,
            modifier = Modifier.onGloballyPositioned { onDockHeightChange(it.size.height) },
            onRowCenterOffsetPx = onDockRowCenterChange,
        ) {
            SystemPromptRebuildNotice(chatId = currentChatId, busy = isQueueBlocked)
        }

        if (inputStyle == UserPreferencesManager.INPUT_STYLE_AGENT) {
            AgentChatInputSection(
                actualViewModel = actualViewModel,
                userMessage = userMessage,
                composerPrediction = composerPrediction,
                onUserMessageChange = { value -> handleUserMessageChange(value) },
                enableEnterToSend = enableEnterToSend,
                onSendMessage = sendMessage,
                onQueueMessage = { enqueueDraftToPendingQueue() },
                onCancelMessage = actualViewModel::cancelCurrentMessage,
                isLoading = isLoading,
                inputState = inputState,
                allowTextInputWhileProcessing = true,
                onAttachmentRequest = actualViewModel::handleAttachment,
                attachments = attachments,
                onRemoveAttachment = actualViewModel::removeAttachment,
                onInsertAttachment = actualViewModel::insertAttachmentReference,
                onAttachScreenContent = actualViewModel::captureScreenContent,
                onAttachNotifications = actualViewModel::captureNotifications,
                onAttachLocation = actualViewModel::captureLocation,
                onAttachMemory = onShowMemoryFolderDialog,
                onAttachPackage = actualViewModel::attachPackage,
                onTakePhoto = actualViewModel::handleTakenPhoto,
                hasBackgroundImage = hasBackgroundImage,
                chatInputTransparent = chatInputTransparent,
                chatInputFloating = chatInputFloating,
                chatInputLiquidGlass = chatInputLiquidGlass,
                chatInputWaterGlass = chatInputWaterGlass,
                externalAttachmentPanelState = attachmentPanelState,
                onAttachmentPanelStateChange = actualViewModel::updateAttachmentPanelState,
                showInputProcessingStatus = showInputProcessingStatus,
                enableTools = enableTools,
                enableThinkingMode = enableThinkingMode,
                onToggleThinkingMode = actualViewModel::toggleThinkingMode,
                thinkingQualityLevel = thinkingQualityLevel,
                onThinkingQualityLevelChange = actualViewModel::updateThinkingQualityLevel,
                replyToMessage = replyToMessage,
                onClearReply = actualViewModel::clearReplyToMessage,
                isWorkspaceOpen = isWorkspaceOpen,
                enableMaxContextMode = enableMaxContextMode,
                onToggleEnableMaxContextMode = actualViewModel::toggleEnableMaxContextMode,
                currentChatId = currentChatId,
                featureStates = featureStates,
                onToggleFeature = actualViewModel::toggleFeature,
                inputMenuRuntime = inputMenuRuntime,
                permissionStop = permissionStop,
                onSetPermissionStop = actualViewModel::setMasterPermissionStop,
                enableMemoryAutoUpdate = enableMemoryAutoUpdate,
                onToggleMemoryAutoUpdate = actualViewModel::toggleMemoryAutoUpdate,
                isAutoReadEnabled = isAutoReadEnabled,
                onToggleAutoRead = actualViewModel::toggleAutoRead,
                onToggleTools = actualViewModel::toggleTools,
                disableStreamOutput = disableStreamOutput,
                onToggleDisableStreamOutput = actualViewModel::toggleDisableStreamOutput,
                disableUserPreferenceDescription = disableUserPreferenceDescription,
                onToggleDisableUserPreferenceDescription =
                    actualViewModel::toggleDisableUserPreferenceDescription,
                onNavigateToUserPreferences = onNavigateToUserPreferences,
                onNavigateToPackageManager = onNavigateToPackageManager,
                toolPromptVisibility = toolPromptVisibility,
                onSaveToolPromptVisibilityMap = actualViewModel::saveToolPromptVisibilityMap,
                toolOrder = toolPromptOrder,
                onSaveToolOrder = actualViewModel::saveToolPromptOrder,
                onManualMemoryUpdate = actualViewModel::manuallyUpdateMemory,
                onNavigateToModelConfig = onNavigateToModelConfig,
                characterCardBoundChatModelConfigId = characterCardBoundChatModelConfigId,
                characterCardBoundChatModelIndex = characterCardBoundChatModelIndex,
                characterCardBoundMemoryProfileId = characterCardBoundMemoryProfileId,
                pendingQueueMessages = pendingQueueMessages,
                isPendingQueueExpanded = isPendingQueueExpanded,
                onPendingQueueExpandedChange = { expanded ->
                    currentChatId?.let { actualViewModel.setPendingQueueExpanded(it, expanded) }
                },
                onDeletePendingQueueMessage = { id ->
                    removePendingQueueMessageById(id)
                },
                onEditPendingQueueMessage = { id ->
                    removePendingQueueMessageById(id)?.let { queueItem ->
                        val text = queueItem.text
                        actualViewModel.restoreQueuedAttachments(queueItem)
                        actualViewModel.updateUserMessage(
                            TextFieldValue(
                                text = text,
                                selection = TextRange(text.length),
                            ),
                        )
                    }
                },
                onSteerPendingQueueMessage = ::steerPendingQueueMessage,

            )
        } else {
            ClassicChatInputSection(
                actualViewModel = actualViewModel,
                userMessage = userMessage,
                composerPrediction = composerPrediction,
                onUserMessageChange = { value -> handleUserMessageChange(value) },
                enableEnterToSend = enableEnterToSend,
                onSendMessage = sendMessage,
                onQueueMessage = { enqueueDraftToPendingQueue() },
                onCancelMessage = actualViewModel::cancelCurrentMessage,
                isLoading = isLoading,
                inputState = inputState,
                allowTextInputWhileProcessing = true,
                onAttachmentRequest = actualViewModel::handleAttachment,
                attachments = attachments,
                onRemoveAttachment = actualViewModel::removeAttachment,
                onInsertAttachment = actualViewModel::insertAttachmentReference,
                onAttachScreenContent = actualViewModel::captureScreenContent,
                onAttachNotifications = actualViewModel::captureNotifications,
                onAttachLocation = actualViewModel::captureLocation,
                onAttachMemory = onShowMemoryFolderDialog,
                onAttachPackage = actualViewModel::attachPackage,
                onTakePhoto = actualViewModel::handleTakenPhoto,
                hasBackgroundImage = hasBackgroundImage,
                chatInputTransparent = chatInputTransparent,
                chatInputFloating = chatInputFloating,
                chatInputLiquidGlass = chatInputLiquidGlass,
                chatInputWaterGlass = chatInputWaterGlass,
                externalAttachmentPanelState = attachmentPanelState,
                onAttachmentPanelStateChange = actualViewModel::updateAttachmentPanelState,
                showInputProcessingStatus = showInputProcessingStatus,
                enableTools = enableTools,
                permissionStop = permissionStop,
                onSetPermissionStop = actualViewModel::setMasterPermissionStop,
                onToggleTools = actualViewModel::toggleTools,
                replyToMessage = replyToMessage,
                enableThinkingMode = enableThinkingMode,
                onSetThinkingMode = actualViewModel::setThinkingMode,
                thinkingQualityLevel = thinkingQualityLevel,
                onThinkingQualityLevelChange = actualViewModel::updateThinkingQualityLevel,
                onClearReply = actualViewModel::clearReplyToMessage,
                isWorkspaceOpen = isWorkspaceOpen,
                pendingQueueMessages = pendingQueueMessages,
                isPendingQueueExpanded = isPendingQueueExpanded,
                onPendingQueueExpandedChange = { expanded ->
                    currentChatId?.let { actualViewModel.setPendingQueueExpanded(it, expanded) }
                },
                onDeletePendingQueueMessage = { id ->
                    removePendingQueueMessageById(id)
                },
                onEditPendingQueueMessage = { id ->
                    removePendingQueueMessageById(id)?.let { queueItem ->
                        val text = queueItem.text
                        actualViewModel.restoreQueuedAttachments(queueItem)
                        actualViewModel.updateUserMessage(
                            TextFieldValue(
                                text = text,
                                selection = TextRange(text.length),
                            ),
                        )
                    }
                },
                onSteerPendingQueueMessage = ::steerPendingQueueMessage,

            )
        }
    }
}

@Composable
private fun MentionSuggestionOverlay(
    actualViewModel: ChatViewModel,
    bottomBarHeightPx: Int,
    inputBarTranslationYPx: Float,
    panelStyle: MentionSuggestionPanelStyle,
) {
    val density = LocalDensity.current
    val showMentionSuggestionPanel by actualViewModel.showMentionSuggestionPanel.collectAsState()
    val bottomPaddingForSelector = with(density) { bottomBarHeightPx.toDp() }

    AnimatedVisibility(
        visible = showMentionSuggestionPanel,
        enter = fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = fadeOut(animationSpec = tween(durationMillis = 150)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = actualViewModel::hideMentionSuggestionPanel,
                        ),
            )
            MentionSuggestionPanel(
                modifier =
                    Modifier
                        .padding(start = 12.dp, end = 12.dp, bottom = bottomPaddingForSelector + 8.dp)
                        .graphicsLayer { translationY = -inputBarTranslationYPx }
                        .animateEnterExit(
                            enter = slideInVertically(
                                animationSpec = tween(durationMillis = 240),
                            ) { it / 4 },
                            exit = slideOutVertically(
                                animationSpec = tween(durationMillis = 200),
                            ) { it / 4 },
                        ),
                viewModel = actualViewModel,
                panelStyle = panelStyle,
                onFileSelected = { relativePath ->
                    actualViewModel.selectMentionWorkspaceEntry(relativePath)
                },
                onPackageSelected = actualViewModel::selectMentionPackage,
            )
        }
    }
}
