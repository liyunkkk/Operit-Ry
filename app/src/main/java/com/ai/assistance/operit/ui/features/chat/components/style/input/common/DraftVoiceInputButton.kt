package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel

/** Has no send callback: transcription can only update the editable composer. */
@Composable
fun DraftVoiceInputButton(
    viewModel: ChatViewModel,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val chatId by viewModel.currentChatId.collectAsState()
    val context = LocalContext.current
    val latestEnabled by rememberUpdatedState(enabled)
    val controller = rememberDraftVoiceController(viewModel, value, onValueChange)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && latestEnabled) controller.open()
        else if (!it) Toast.makeText(context, R.string.voice_draft_permission, Toast.LENGTH_SHORT).show()
    }
    IconButton(modifier = modifier, enabled = enabled, onClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) controller.open()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) {
        Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.voice_draft_button))
    }
    if (controller.isActive) {
        DraftRecordingDialog(controller = controller, onDismiss = { controller.cancel() })
    }
}

@Composable
private fun DraftRecordingDialog(controller: DraftVoiceController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_draft_button)) },
        text = {
            Column {
                Text(stringResource(R.string.voice_draft_hint))
                Text(controller.errorText ?: controller.statusText)
                if (controller.liveText.isNotBlank()) Text(controller.liveText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = !controller.isPreparing && !controller.isProcessing,
                onClick = {
                    if (controller.isRecording) controller.finishRecording() else controller.open()
                }) {
                Text(stringResource(if (controller.isRecording) R.string.voice_draft_finish
                    else R.string.voice_draft_retry))
            }
        }
    )
}
