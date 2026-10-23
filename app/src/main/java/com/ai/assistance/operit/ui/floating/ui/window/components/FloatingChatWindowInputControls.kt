package com.ai.assistance.operit.ui.floating.ui.window.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.shadow
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.chatComposerShape
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.chatComposerColor
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.chatComposerTextStyle
import com.ai.assistance.operit.ui.theme.liquidGlass
import com.ai.assistance.operit.ui.theme.waterGlass
import com.ai.assistance.operit.ui.theme.isLiquidGlassSupported
import com.ai.assistance.operit.ui.theme.isWaterGlassSupported


import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.ui.features.chat.components.AttachmentChip
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.FloatingMode
import com.ai.assistance.operit.ui.floating.ReadOnlyTranscriptNotice
import com.ai.assistance.operit.ui.floating.rememberIsReadOnlyTranscript
import com.ai.assistance.operit.ui.floating.ui.window.viewmodel.FloatingChatWindowModeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun FloatingChatWindowInputControls(
    floatContext: FloatContext,
    viewModel: FloatingChatWindowModeViewModel
) {
    if (rememberIsReadOnlyTranscript(floatContext)) {
        LaunchedEffect(Unit) {
            floatContext.showInputDialog = false
            floatContext.showAttachmentPanel = false
        }
        ReadOnlyTranscriptNotice()
        return
    }
    if (floatContext.showAttachmentPanel && !floatContext.showInputDialog) {
        AttachmentPanelOverlay(floatContext, viewModel)
    }
    if (!floatContext.showInputDialog && floatContext.onSendMessage != null) {
        BottomInputBar(floatContext, viewModel)
    }
}

/** 底部输入栏 */
@Composable
private fun BottomInputBar(
    floatContext: FloatContext,
    viewModel: FloatingChatWindowModeViewModel
) {
    val context = LocalContext.current
    val preferences = remember(context) { UserPreferencesManager.getInstance(context) }
    val inputStyle by preferences.inputStyle.collectAsState(initial = UserPreferencesManager.INPUT_STYLE_CLASSIC)
    val floating by preferences.chatInputFloating.collectAsState(initial = true)
    val transparent by preferences.chatInputTransparent.collectAsState(initial = false)
    val liquid by preferences.chatInputLiquidGlass.collectAsState(initial = false)
    val water by preferences.chatInputWaterGlass.collectAsState(initial = false)
    val useBackgroundImage by preferences.useBackgroundImage.collectAsState(initial = false)
    val backgroundImageUri by preferences.backgroundImageUri.collectAsState(initial = null)
    val agent = inputStyle == UserPreferencesManager.INPUT_STYLE_AGENT
    val liquidEnabled = transparent && liquid && !water && isLiquidGlassSupported()
    val waterEnabled = transparent && water && isWaterGlassSupported()
    val shape = chatComposerShape(agent, floating)
    val surface = chatComposerColor(agent, transparent, useBackgroundImage && backgroundImageUri != null)
    val textStyle = chatComposerTextStyle(agent)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var isInputFocused by remember { mutableStateOf(false) }
    val hasContent = floatContext.userMessage.isNotBlank() || floatContext.attachments.isNotEmpty()
    val isProcessing = floatContext.chatService?.getChatCore()?.isLoading?.collectAsState()?.value ?: false

    fun releaseFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        floatContext.onInputFocusRequest?.invoke(false)
    }
    fun send() {
        if (isProcessing || !hasContent) return
        floatContext.onSendMessage?.invoke(floatContext.userMessage, PromptFunctionType.CHAT)
        floatContext.userMessage = ""
        floatContext.showAttachmentPanel = false
        releaseFocus()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { AIForegroundService.setWakeListeningSuspendedForIme(context, false) }
    }
    LaunchedEffect(isInputFocused) {
        floatContext.onInputFocusRequest?.invoke(isInputFocused)
        AIForegroundService.setWakeListeningSuspendedForIme(context, isInputFocused)
    }

    @Composable
    fun InputField(modifier: Modifier) {
        val fieldShape = RoundedCornerShape(14.dp)
        BasicTextField(
            value = floatContext.userMessage,
            onValueChange = { floatContext.userMessage = it },
            modifier = modifier.heightIn(min = 44.dp)
                .onFocusChanged { isInputFocused = it.isFocused },
            textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = if (agent) 6 else 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth()
                        .then(if (agent) Modifier else Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = if (hasContent) 1f else 0.72f),
                            fieldShape,
                        ))
                        .clip(fieldShape)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (floatContext.userMessage.isEmpty()) {
                        Text(stringResource(R.string.input_question_hint), style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
    }
    @Composable
    fun AttachmentButton() {
        IconButton(onClick = {
            releaseFocus()
            viewModel.toggleAttachmentPanel()
        }, modifier = Modifier.size(48.dp).clip(CircleShape).background(
            if (floatContext.showAttachmentPanel) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )) {
            Icon(Icons.Default.Add, stringResource(R.string.floating_add_attachment),
                tint = if (floatContext.showAttachmentPanel) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp))
        }
    }
    @Composable
    fun SendButton() {
        IconButton(onClick = {
            when {
                isProcessing -> floatContext.onCancelMessage?.invoke()
                hasContent -> send()
                else -> {
                    releaseFocus()
                    floatContext.voiceAutoTimeout = false
                    floatContext.voiceMode = true
                    floatContext.onModeChange(FloatingMode.FULLSCREEN)
                }
            }
        }, modifier = Modifier.size(48.dp).clip(CircleShape).background(
            if (isProcessing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )) {
            Icon(
                if (isProcessing) Icons.Default.Close else if (hasContent) Icons.Default.Send else Icons.Rounded.GraphicEq,
                stringResource(if (isProcessing) R.string.floating_cancel else if (hasContent)
                    R.string.floating_send else R.string.floating_voice_mode),
                tint = if (isProcessing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (floating) 8.dp else 0.dp, vertical = if (floating) 6.dp else 0.dp)
            .then(if (floating && !liquidEnabled && !waterEnabled) Modifier.shadow(4.dp, shape) else Modifier)
            .waterGlass(enabled = waterEnabled, shape = shape, containerColor = MaterialTheme.colorScheme.surface)
            .liquidGlass(enabled = liquidEnabled, shape = shape, containerColor = MaterialTheme.colorScheme.surface)
            .clip(shape).background(if (liquidEnabled || waterEnabled) Color.Transparent else surface)
            .padding(horizontal = if (agent) 12.dp else 14.dp, vertical = 6.dp),
    ) {
        if (floatContext.attachments.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(floatContext.attachments) { attachment ->
                    AttachmentChip(attachmentInfo = attachment, onInsert = {},
                        onRemove = { floatContext.onRemoveAttachment?.invoke(attachment.filePath) })
                }
            }
        }
        if (agent) {
            InputField(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AttachmentButton()
                Spacer(Modifier.weight(1f))
                SendButton()
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(Modifier.weight(1f))
                AttachmentButton()
                SendButton()
            }
        }
    }
}

/** 附件面板覆盖层 */
@SuppressLint("SuspiciousIndentation")
@Composable
private fun AttachmentPanelOverlay(
    floatContext: FloatContext,
    viewModel: FloatingChatWindowModeViewModel
) {
    FloatingAttachmentPanel(
        visible = floatContext.showAttachmentPanel,
        onAttachScreenContent = {
            floatContext.coroutineScope.launch {
                floatContext.onAttachmentRequest?.invoke("screen_capture")
            delay(500)
                floatContext.showAttachmentPanel = false
            }
        },
        onAttachNotifications = {
            floatContext.coroutineScope.launch {
            floatContext.onAttachmentRequest?.invoke("notifications_capture")
            delay(500)
                floatContext.showAttachmentPanel = false
            }
        },
        onAttachLocation = {
            floatContext.coroutineScope.launch {
                floatContext.onAttachmentRequest?.invoke("location_capture")
            delay(500)
                floatContext.showAttachmentPanel = false
            }
        },
        onAttachScreenOcr = {
            floatContext.onModeChange(FloatingMode.SCREEN_OCR)
            floatContext.showAttachmentPanel = false
        },
        onAttachPackage = {
            floatContext.showPackageSelector = true
        },
        onDismiss = { floatContext.showAttachmentPanel = false }
    )
}

