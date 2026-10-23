package com.ai.assistance.operit.ui.features.settings.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsIconBadge
import com.ai.assistance.operit.ui.features.settings.components.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.util.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Composable
fun StorageManagementScreen(
    onManageComponents: () -> Unit,
    onManageChats: () -> Unit,
    onBackup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var measuredAt by remember { mutableStateOf(0L) }
    var readingCache by remember { mutableStateOf(true) }
    var stopped by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val cache = remember { StorageUsageCache(context.applicationContext) }
    val progressFlow = remember { MutableStateFlow(StorageScanProgress(0, 0, null)) }
    val progress by progressFlow.collectAsState()

    val refresh: (Boolean) -> Unit = { clearPreview ->
        if (!busy) {
            busy = true
            failed = false
            cleared = false
            stopped = false
            timedOut = false
            progressFlow.value = StorageScanProgress(0, 0, null)
            scanJob = scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (clearPreview) {
                            // Let Coil synchronize its own journal; never delete its directory externally.
                            OperitApplication.globalImageLoader.diskCache?.clear()
                        }
                    }
                    cleared = clearPreview
                    val result = withTimeout(60_000) { withContext(Dispatchers.IO) {
                        val dataDir = File(context.applicationInfo.dataDir).canonicalFile
                        val roots = listOf(dataDir) +
                            context.getExternalFilesDirs(null).filterNotNull() +
                            context.externalCacheDirs.filterNotNull()
                        measureStorage(
                            roots,
                            classify = { storageCategory(it.relativeTo(dataDir).path) },
                            onProgress = { progressFlow.value = it },
                            checkCancelled = { ensureActive() }
                        )
                    } }
                    usage = result
                    measuredAt = System.currentTimeMillis()
                    withContext(Dispatchers.IO) { cache.write(CachedStorageUsage(result, measuredAt)) }
                } catch (_: TimeoutCancellationException) {
                    timedOut = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    AppLogger.e("StorageManagement", "Storage operation failed", e)
                } finally {
                    busy = false
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            val saved = withContext(Dispatchers.IO) { cache.read() }
            usage = saved?.usage
            measuredAt = saved?.measuredAt ?: 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w("StorageManagement", "Unable to read previous storage snapshot")
        } finally {
            readingCache = false
        }
        if (!isStorageSnapshotFresh(measuredAt, System.currentTimeMillis())) refresh(false)
    }

    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage.collectAsState(initial = false)
    val cardColor = settingsCardColor(hasBackgroundImage)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.storage_data_total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        usage?.let { Formatter.formatFileSize(context, it.totalBytes) }
                            ?: if (busy || readingCache) stringResource(R.string.storage_calculating) else "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                FilledTonalIconButton(onClick = { refresh(false) }, enabled = !busy && !readingCache) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.storage_refresh))
                }
            }
            if (measuredAt > 0) Text(
                stringResource(R.string.storage_last_measured, java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                ).format(java.util.Date(measuredAt))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                Text(stringResource(
                    R.string.storage_scan_progress, progress.files,
                    Formatter.formatFileSize(context, progress.bytes)
                ), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                progress.category?.let {
                    Text(stringResource(R.string.storage_scanning_category, stringResource(storageCategoryTitle(it))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { stopped = true; scanJob?.cancel() }) {
                    Text(stringResource(R.string.storage_stop_scan))
                }
            }
            listOfNotNull(
                stringResource(R.string.storage_scan_stopped).takeIf { stopped },
                stringResource(R.string.storage_scan_timeout).takeIf { timedOut },
                stringResource(R.string.storage_cache_cleared).takeIf { cleared },
                stringResource(R.string.storage_partial).takeIf { (usage?.skippedEntries ?: 0) > 0 }
            ).forEach { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (failed) Text(
                stringResource(R.string.storage_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        SettingsInfoBanner(text = stringResource(R.string.storage_scope), backgroundColor = cardColor)

        SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            StorageCategory.entries.forEachIndexed { index, category ->
                if (index > 0) SettingsRowDivider()
                val description = when (category) {
                    StorageCategory.MEDIA -> R.string.storage_media_hint
                    StorageCategory.COMPONENTS -> R.string.storage_components_hint
                    StorageCategory.DATABASES -> R.string.storage_databases_hint
                    StorageCategory.PREVIEW_CACHE -> R.string.storage_preview_hint
                    StorageCategory.OTHER -> R.string.storage_other_hint
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SettingsIconBadge(icon = storageCategoryIcon(category))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(storageCategoryTitle(category)),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            usage?.let { Formatter.formatFileSize(context, it.bytes.getValue(category)) } ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        stringResource(description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 48.dp, top = 4.dp)
                    )
                    val action: Pair<String, () -> Unit>? = when (category) {
                        StorageCategory.COMPONENTS -> stringResource(R.string.components_title) to onManageComponents
                        StorageCategory.DATABASES -> stringResource(R.string.settings_chat_history_management) to onManageChats
                        StorageCategory.PREVIEW_CACHE -> stringResource(R.string.storage_clear_preview) to { confirmClear = true }
                        else -> null
                    }
                    if (action != null) {
                        val enabled = category != StorageCategory.PREVIEW_CACHE ||
                            (!busy && (usage?.bytes?.get(category) ?: 0L) > 0L)
                        TextButton(
                            onClick = action.second,
                            enabled = enabled,
                            // TextButton adds 12dp inside, so its label lines up with the 48dp description.
                            modifier = Modifier.padding(start = 36.dp)
                        ) { Text(action.first) }
                    }
                }
            }
        }

        SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            SettingsNavRow(
                title = stringResource(R.string.settings_data_backup),
                subtitle = stringResource(R.string.settings_data_backup_desc),
                icon = Icons.Default.CloudUpload,
                onClick = onBackup
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.storage_clear_preview)) },
            text = { Text(stringResource(R.string.storage_clear_preview_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; refresh(true) }) {
                    Text(stringResource(R.string.storage_clear_preview))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun storageCategoryTitle(category: StorageCategory): Int = when (category) {
    StorageCategory.MEDIA -> R.string.storage_media
    StorageCategory.COMPONENTS -> R.string.storage_components
    StorageCategory.DATABASES -> R.string.storage_databases
    StorageCategory.PREVIEW_CACHE -> R.string.storage_preview_cache
    StorageCategory.OTHER -> R.string.storage_other
}

private fun storageCategoryIcon(category: StorageCategory): ImageVector = when (category) {
    StorageCategory.MEDIA -> Icons.Default.Image
    StorageCategory.COMPONENTS -> Icons.Default.Extension
    StorageCategory.DATABASES -> Icons.Default.Storage
    StorageCategory.PREVIEW_CACHE -> Icons.Default.Cached
    StorageCategory.OTHER -> Icons.Default.FolderOpen
}