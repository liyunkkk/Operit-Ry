package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.ComposerPredictionTextField
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.rememberComposerPredictionTransformation
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.rememberMentionVisualTransformation

@Composable
fun FullscreenInputDialog(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    composerPrediction: String? = null,
    onAcceptPrediction: (String) -> TextFieldValue? = { null },
) {
    val mentionVisualTransformation =
        rememberMentionVisualTransformation(MaterialTheme.typography.bodyLarge)
    var editorValue by remember(value) { mutableStateOf(value) }
    val composerTransformation = rememberComposerPredictionTransformation(
        editorValue, composerPrediction,
        mentionVisualTransformation,
    )

    fun finishEditing() {
        onValueChange(editorValue)
        onDismiss()
    }

    Dialog(
        onDismissRequest = { finishEditing() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { finishEditing() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.workflow_close)
                        )
                    }

                    Text(
                        text = stringResource(R.string.chat_fullscreen_input),
                        style = MaterialTheme.typography.titleMedium
                    )

                    IconButton(
                        onClick = {
                            onValueChange(editorValue)
                            onConfirm()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
                }

                HorizontalDivider()

                // Input Area
                ComposerPredictionTextField(
                    value = editorValue,
                    prediction = composerPrediction,
                    outlined = false,
                    onAcceptPrediction = { expected ->
                        val adopted = onAcceptPrediction(expected)
                        if (adopted != null) editorValue = adopted
                        adopted != null
                    },
                    onValueChange = {
                        editorValue = it
                        // Keep the shared draft current for prediction validation and speech input.
                        onValueChange(it)
                    },
                    visualTransformation = composerTransformation,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
