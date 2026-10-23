package com.ai.assistance.operit.ui.features.chat.components.part

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import kotlinx.serialization.json.*

internal enum class ShellResultState(val title: Int, val error: Boolean = false) {
    RUNNING(R.string.shell_status_running),
    CANCELLING(R.string.shell_status_cancelling),
    COMPLETED(R.string.shell_status_completed),
    FAILED(R.string.shell_status_failed, true),
    TIMED_OUT(R.string.shell_status_timed_out, true),
    CANCELLED(R.string.shell_status_cancelled),
    UNKNOWN(R.string.shell_status_unknown),
}

internal fun shellResultState(toolName: String, result: String): ShellResultState? {
    if (toolName !in setOf("super_admin:shell", "super_admin:shell_wait", "super_admin:shell_cancel")) return null
    val data = runCatching { Json.parseToJsonElement(result).jsonObject }.getOrNull() ?: return null
    if ((data["runId"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) return null
    return when ((data["status"] as? JsonPrimitive)?.contentOrNull) {
        "running" -> ShellResultState.RUNNING
        "cancelling" -> ShellResultState.CANCELLING
        "completed" -> when ((data["exitCode"] as? JsonPrimitive)?.intOrNull) {
            0 -> ShellResultState.COMPLETED
            null -> ShellResultState.UNKNOWN
            else -> ShellResultState.FAILED
        }
        "failed" -> ShellResultState.FAILED
        "timed_out" -> ShellResultState.TIMED_OUT
        "cancelled" -> ShellResultState.CANCELLED
        else -> null
    }
}

@Composable
internal fun ShellTaskResultDisplay(
    toolName: String,
    result: String,
    state: ShellResultState,
    modifier: Modifier,
    summaryPrefix: String?,
    enableDialog: Boolean,
) {
    val label = stringResource(state.title)
    val clipboard = LocalClipboardManager.current
    var details by remember { mutableStateOf(false) }
    val icon = when (state) {
        ShellResultState.COMPLETED -> Icons.Default.Check
        ShellResultState.FAILED, ShellResultState.TIMED_OUT -> Icons.Default.Close
        ShellResultState.CANCELLED -> Icons.Default.Stop
        else -> Icons.Default.HourglassEmpty
    }
    val summary = listOfNotNull(label, summaryPrefix?.takeIf { it.isNotBlank() }).joinToString(" · ")
    Row(
        modifier = modifier.fillMaxWidth()
            .then(if (enableDialog) Modifier.clickable { details = true } else Modifier)
            .padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null,
            tint = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(summary, style = MaterialTheme.typography.bodySmall,
            color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (details && enableDialog) {
        ToolResultDetailDialog(
            toolName, result, !state.error,
            onDismiss = { details = false },
            onCopy = { clipboard.setText(AnnotatedString(result)) },
            titleOverride = label,
            statusIconOverride = icon,
            statusDescriptionOverride = label,
        )
    }
}
