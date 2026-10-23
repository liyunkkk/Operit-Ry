package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.LocalVoiceModels
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import android.text.format.Formatter
import com.ai.assistance.operit.util.OnDemandResources

@Composable
internal fun LocalVoiceModelPicker(recognition: Boolean, selected: String, enabled: Boolean,
                                  legacySelected: Boolean = false,
                                  onSelect: suspend (String) -> Unit) {
    val context = LocalContext.current
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var deletion by remember { mutableStateOf<LocalVoiceModels.Model?>(null) }
    val latestSelected by rememberUpdatedState(selected)
    val downloads by OnDemandResources.downloads.collectAsState()
    LaunchedEffect(revision, downloads) {
        sizes = LocalVoiceModels.models.associate { it.id to LocalVoiceModels.storedBytes(context, it.id) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.voice_local_models), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.voice_model_hint), style = MaterialTheme.typography.bodySmall)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.voice_model_guide_title),
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (recognition) R.string.voice_model_guide_asr
                    else R.string.voice_model_guide_tts), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.voice_model_comparison_note),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        LocalVoiceModels.models.filter { it.recognition == recognition }.forEach { model ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.name, style = MaterialTheme.typography.titleSmall)
                    val recommendation = when (model.id) {
                        "sensevoice-int8" -> R.string.voice_model_tag_sensevoice
                        "paraformer-small-int8" -> R.string.voice_model_tag_paraformer
                        "aishell3-int8" -> R.string.voice_model_tag_aishell
                        "melo-int8" -> R.string.voice_model_tag_melo
                        else -> null
                    }
                    recommendation?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    Text(stringResource(model.descriptionRes), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        links.openUri("https://huggingface.co/csukuangfj/${model.repo}/tree/${model.revision}")
                    }) { Text(stringResource(R.string.voice_model_source)) }
                    Text(stringResource(R.string.voice_model_size, model.bytes / 1048576.0),
                        style = MaterialTheme.typography.labelSmall)
                    val downloaded = model.downloaded(context)
                    TextButton(enabled = enabled && busy == null && (model.id != selected || !downloaded),
                        onClick = {
                            busy = model.id
                            error = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { LocalVoiceModels.ensure(context,model.id) }
                                    onSelect(model.id)
                                } catch (e: CancellationException) { throw e
                                } catch (e: Exception) { error = e.message
                                } finally { busy = null; revision++ }
                            }
                        }) {
                        if (busy == model.id) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else Text(when {
                            model.id == selected && downloaded -> stringResource(R.string.voice_model_active)
                            downloaded -> stringResource(R.string.voice_model_use)
                            else -> stringResource(R.string.voice_model_download,
                                stringResource(R.string.voice_model_size, model.bytes / 1048576.0))
                        })
                    }
                    val stored = sizes[model.id] ?: 0L
                    if (stored > 0) {
                        TextButton(
                            enabled = enabled && busy == null && model.id != selected &&
                                !OnDemandResources.isGroupActive("voice-${model.id}"),
                            onClick = { deletion = model }
                        ) {
                            Text(stringResource(R.string.voice_model_delete_size,
                                Formatter.formatFileSize(context, stored)))
                        }
                        if (model.id == selected) Text(
                            stringResource(R.string.voice_model_delete_selected),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (recognition) LegacyVoiceModelCard(enabled, legacySelected)
    }
    deletion?.let { model ->
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text(stringResource(R.string.voice_model_delete_title, model.name)) },
            text = { Text(stringResource(R.string.voice_model_delete_confirm,
                Formatter.formatFileSize(context, sizes[model.id] ?: 0L))) },
            dismissButton = {
                TextButton(onClick = { deletion = null }) { Text(stringResource(R.string.voice_model_keep)) }
            },
            confirmButton = {
                TextButton(enabled = enabled && busy == null && selected != model.id &&
                    !OnDemandResources.isGroupActive("voice-${model.id}"), onClick = {
                    deletion = null
                    busy = model.id
                    error = null
                    scope.launch {
                        try {
                            if (latestSelected != model.id) LocalVoiceModels.delete(context, model.id)
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { error = e.message
                        } finally { busy = null; revision++ }
                    }
                }) { Text(stringResource(R.string.voice_model_delete_action)) }
            }
        )
    }
}

@Composable
private fun LegacyVoiceModelCard(enabled: Boolean, selected: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads by OnDemandResources.downloads.collectAsState()
    var bytes by remember { mutableStateOf<Long?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(downloads, revision) {
        bytes = OnDemandResources.legacySpeechBytes(context)
    }
    val deletable = enabled && !selected && !busy && (bytes ?: 0) > 0 &&
        !OnDemandResources.isGroupActive(OnDemandResources.SPEECH_DIRECTORY)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.voice_legacy_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.voice_legacy_description), style = MaterialTheme.typography.bodySmall)
            if (selected) Text(stringResource(R.string.voice_model_delete_selected))
            when {
                busy || bytes == null -> CircularProgressIndicator(Modifier.size(18.dp))
                bytes == 0L -> Text(stringResource(R.string.voice_legacy_not_downloaded))
                else -> TextButton(enabled = deletable, onClick = { confirm = true }) {
                    Text(stringResource(R.string.voice_model_delete_size,
                        Formatter.formatFileSize(context, bytes ?: 0)))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.voice_model_delete_title,
            stringResource(R.string.voice_legacy_title))) },
        text = { Text(stringResource(R.string.voice_legacy_delete_confirm,
            Formatter.formatFileSize(context, bytes ?: 0))) },
        dismissButton = {
            TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.voice_model_keep)) }
        },
        confirmButton = {
            TextButton(enabled = deletable, onClick = {
                confirm = false
                busy = true
                error = null
                scope.launch {
                    try { OnDemandResources.deleteLegacySpeech(context) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message }
                    finally { busy = false; revision++ }
                }
            }) { Text(stringResource(R.string.voice_model_delete_action)) }
        }
    )
}
