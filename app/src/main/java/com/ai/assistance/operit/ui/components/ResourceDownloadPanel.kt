package com.ai.assistance.operit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.OnDemandResources
import com.ai.assistance.operit.util.ResourceDownloadState

private var inlineDownloadHosts by mutableIntStateOf(0)

/** The management screen already displays progress; avoid covering it with duplicate cards. */
@Composable
internal fun InlineResourceDownloadHost() {
    DisposableEffect(Unit) {
        inlineDownloadHosts++
        onDispose { inlineDownloadHosts-- }
    }
}

/** Render only in the Activity, never in a floating-window Compose tree. */
@Composable
fun ResourceDownloadPanel(modifier: Modifier = Modifier) {
    if (inlineDownloadHosts > 0) return
    val downloads by OnDemandResources.downloads.collectAsState()
    val context = LocalContext.current
    Column(modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        downloads.values.forEach { state ->
            if (state.collapsed) {
                CollapsedDownloadRow(state) { OnDemandResources.setCollapsed(state.id, false) }
            } else {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(
                                when {
                                    state.error == null -> R.string.resource_download_title
                                    state.unconfirmed -> R.string.resource_download_unconfirmed_title
                                    state.cancelled -> R.string.resource_download_cancelled_title
                                    else -> R.string.resource_download_failed_title
                                },
                                state.name
                            )
                        )
                        if (state.error == null) {
                            Text(resourceDownloadProgressText(state))
                            LinearProgressIndicator(
                                progress = { resourceDownloadFraction(state) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(stringResource(R.string.resource_download_once))
                            Row {
                                TextButton(onClick = { OnDemandResources.cancel(state.id) }) {
                                    Text(stringResource(R.string.resource_download_cancel))
                                }
                                TextButton(onClick = { OnDemandResources.setCollapsed(state.id, true) }) {
                                    Text(stringResource(R.string.resource_download_collapse))
                                }
                            }
                        } else {
                            Text(state.error)
                            if (!state.cancelled) {
                                Text(stringResource(R.string.resource_download_retry_hint))
                            }
                            Row {
                                TextButton(onClick = { OnDemandResources.retry(context, state.id) }) {
                                    Text(stringResource(R.string.resource_download_retry))
                                }
                                TextButton(onClick = { OnDemandResources.dismiss(state.id) }) {
                                    Text(stringResource(R.string.resource_download_dismiss))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One-line summary so a long download stops covering the screen behind it. */
@Composable
private fun CollapsedDownloadRow(state: ResourceDownloadState, onExpand: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(
                    when {
                        state.error == null -> R.string.resource_download_title
                        state.unconfirmed -> R.string.resource_download_unconfirmed_title
                        state.cancelled -> R.string.resource_download_cancelled_title
                        else -> R.string.resource_download_failed_title
                    },
                    state.name
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            if (state.error == null) {
                Text(
                    resourceDownloadProgressText(state),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onExpand) {
                Text(stringResource(R.string.resource_download_expand))
            }
        }
    }
}
