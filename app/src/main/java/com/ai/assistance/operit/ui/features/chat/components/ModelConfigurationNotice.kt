package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.ui.features.settings.screens.getFunctionDisplayName

/** Persistent notice, outside the transcript, until the user repairs the affected bindings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelConfigurationNotice(onConfigure: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { FunctionalConfigManager(context.applicationContext) }
    val unavailable by manager.unavailableFunctionModelsFlow.collectAsState(initial = emptyList())
    if (unavailable.isEmpty()) return

    val names = unavailable.take(2).map { getFunctionDisplayName(it) }.joinToString("、")
    val affected = if (unavailable.size > 2) {
        stringResource(R.string.functional_model_notice_more, names, unavailable.size)
    } else names
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp).size(24.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.functional_model_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        affected,
                        modifier = Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onConfigure, modifier = Modifier.alignByBaseline()) {
                        Text(stringResource(R.string.functional_model_notice_configure))
                    }
                }
            }
        }
    }
}
