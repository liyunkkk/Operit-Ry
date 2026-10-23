package com.ai.assistance.operit.ui.features.settings.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.LocalVoiceModels
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsIconBadge
import com.ai.assistance.operit.ui.features.settings.components.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.util.OnDemandResources
import java.io.File
import kotlinx.coroutines.*

private data class ComponentStorage(val bytes: Long, val ready: Boolean)

/** A fixed catalog, never a file browser: only replaceable component resources can be removed. */
@Composable
fun ComponentManagementScreen() {
    com.ai.assistance.operit.ui.components.InlineResourceDownloadHost()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val groups = remember(context) { OnDemandResources.componentGroups(context) }
    val downloads by OnDemandResources.downloads.collectAsState()
    val pending by OnDemandResources.pendingRequests.collectAsState()
    var storage by remember { mutableStateOf<Map<String, ComponentStorage>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage.collectAsState(initial = false)
    val cardColor = settingsCardColor(hasBackgroundImage)
    fun label(id: String): String = LocalVoiceModels.models.firstOrNull { "voice-${it.id}" == id }?.name
        ?: resources.getString(groups.first { it.id == id }.labelRes)

    LaunchedEffect(context) {
        while (isActive) {
            val snapshot = withContext(Dispatchers.IO) {
                groups.associate { group ->
                    group.id to ComponentStorage(
                        group.items.sumOf { it.target.length() + File(it.target.parentFile, "${it.target.name}.part").length() },
                        group.items.all { it.target.isFile && it.target.length() == it.resource.bytes }
                    )
                }
            }
            storage = snapshot
            selected = OnDemandResources.selectedVoiceModels(context)
            installed = withContext(Dispatchers.IO) { OnDemandResources.terminalInstalled(context) }
            delay(1000)
        }
    }
    fun active(id: String) = busy == id || pending.any { it.id == id } || OnDemandResources.isGroupActive(id)
    fun protected(id: String) = id.removePrefix("voice-") in selected

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsInfoBanner(text = stringResource(R.string.components_intro), backgroundColor = cardColor)
        }
        error?.let { message ->
            item {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        items(groups, key = { it.id }) { group ->
            val saved = storage[group.id]
            val progress = downloads[group.id]
            val working = active(group.id)
            val model = LocalVoiceModels.models.firstOrNull { "voice-${it.id}" == group.id }
            SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIconBadge(icon = componentIcon(group.labelRes, model != null), size = 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            label(group.id),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(
                                R.string.component_download_size,
                                Formatter.formatFileSize(context, group.items.sumOf { it.resource.bytes })
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    ComponentStatusChip(saved)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(model?.descriptionRes ?: when (group.labelRes) {
                        R.string.resource_terminal -> R.string.component_terminal_detail
                        R.string.resource_offline_speech -> R.string.voice_legacy_description
                        else -> R.string.component_export_detail
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (group.labelRes == R.string.resource_terminal && installed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.component_terminal_installed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (protected(group.id)) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.voice_model_delete_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (working) {
                    Spacer(Modifier.height(12.dp))
                    if (progress != null && progress.error == null) {
                        LinearProgressIndicator(
                            progress = { (progress.downloaded.toFloat() / progress.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${Formatter.formatFileSize(context, progress.downloaded)} / ${Formatter.formatFileSize(context, progress.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                progress?.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = saved != null && saved.bytes > 0 && !working && busy == null && !protected(group.id),
                        onClick = { deletion = group.id },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.voice_model_delete_action))
                    }
                    if (working) {
                        OutlinedButton(onClick = {
                            OnDemandResources.decline(group.id)
                            OnDemandResources.cancel(group.id)
                        }) { Text(stringResource(R.string.resource_download_cancel)) }
                    } else {
                        FilledTonalButton(
                            enabled = saved != null && !saved.ready && busy == null,
                            onClick = {
                                busy = group.id
                                error = null
                                scope.launch {
                                    try { withContext(Dispatchers.IO) { OnDemandResources.downloadComponent(context, group.id) } }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message ?: resources.getString(R.string.resource_download_failed_generic) }
                                    finally { busy = null }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (progress?.error != null) R.string.resource_download_retry else R.string.component_download))
                        }
                    }
                }
            }
        }
    }
    deletion?.let { id ->
        AlertDialog(
            onDismissRequest = { deletion = null },
            title = { Text(stringResource(R.string.voice_model_delete_title, label(id))) },
            text = { Text(stringResource(R.string.component_delete_detail)) },
            dismissButton = { TextButton(onClick = { deletion = null }) { Text(stringResource(R.string.voice_model_keep)) } },
            confirmButton = {
                TextButton(
                    enabled = !active(id) && !protected(id),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        deletion = null
                        busy = id
                        error = null
                        scope.launch {
                            try { OnDemandResources.deleteComponent(context, id) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: resources.getString(R.string.voice_model_delete_failed) }
                            finally { busy = null }
                        }
                    }
                ) { Text(stringResource(R.string.voice_model_delete_action)) }
            }
        )
    }
}

private fun componentIcon(labelRes: Int, isVoiceModel: Boolean): ImageVector = when {
    isVoiceModel -> Icons.Default.RecordVoiceOver
    labelRes == R.string.resource_terminal -> Icons.Default.Terminal
    labelRes == R.string.resource_offline_speech -> Icons.Default.Mic
    labelRes == R.string.resource_android_export -> Icons.Default.Android
    labelRes == R.string.resource_windows_export -> Icons.Default.DesktopWindows
    else -> Icons.Default.Extension
}

/** Small pill: downloaded, partly downloaded, or missing. */
@Composable
private fun ComponentStatusChip(saved: ComponentStorage?) {
    val (textRes, container, content) = when {
        saved == null -> Triple(R.string.component_loading, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        saved.ready -> Triple(R.string.component_ready, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        saved.bytes > 0 -> Triple(R.string.component_partial, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        else -> Triple(R.string.component_missing, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(textRes), style = MaterialTheme.typography.labelMedium)
            if (saved != null && saved.bytes > 0) {
                Text(
                    android.text.format.Formatter.formatShortFileSize(LocalContext.current, saved.bytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.8f)
                )
            }
        }
    }
}
