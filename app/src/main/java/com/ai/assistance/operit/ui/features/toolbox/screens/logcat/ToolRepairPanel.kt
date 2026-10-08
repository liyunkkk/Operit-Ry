package com.ai.assistance.operit.ui.features.toolbox.screens.logcat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.ToolCallRepairLogger
import com.ai.assistance.operit.core.tools.ToolCallRepairRouter
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

@Composable
internal fun ToolRepairPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var data by remember { mutableStateOf<ToolCallRepairLogger.Page?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var previousPage by rememberSaveable { mutableStateOf(page) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    LaunchedEffect(page, refresh) {
        loading = true
        failure = null
        if (previousPage != page) {
            data = null
            listState.scrollToItem(0)
        }
        previousPage = page
        ToolCallRepairLogger.changes.collectLatest {
            try {
                data = ToolCallRepairLogger.load(context, page)
                failure = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tool_repairs_scope), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.tool_repairs_total, data?.total ?: 0))
            TextButton(enabled = !loading, onClick = { refresh++ }) {
                Text(stringResource(R.string.tool_repairs_refresh))
            }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (!loading && failure == null && data?.total == 0) {
                item { Text(stringResource(R.string.tool_repairs_empty)) }
            }
            itemsIndexed(data?.records.orEmpty()) { _, raw ->
                val record = remember(raw) { runCatching { JSONObject(raw) }.getOrNull() }
                Column(Modifier.fillMaxWidth().clickable(enabled = !loading) { detail = raw }
                    .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (record == null) {
                        Text(stringResource(R.string.tool_repairs_invalid_record))
                    } else {
                        Text("${record.optString("originalToolName")} → ${record.optString("targetToolName")}",
                            style = MaterialTheme.typography.titleSmall)
                        Text(dateFormat.format(Date(record.optLong("occurredAt"))),
                            style = MaterialTheme.typography.labelSmall)
                        val rules = record.optJSONArray("rules")?.let { array ->
                            (0 until array.length()).map { array.optString(it) }
                        } ?: listOf(record.optString("rule"))
                        rules.forEach { rule ->
                            val label = when (rule) {
                                ToolCallRepairRouter.READ_FILE_LINE_RANGE -> R.string.tool_repairs_line_range
                                ToolCallRepairRouter.TERMINAL_SEPARATOR -> R.string.tool_repairs_separator
                                ToolCallRepairRouter.TERMINAL_TIMEOUT -> R.string.tool_repairs_timeout
                                ToolCallRepairRouter.REDUNDANT_PACKAGE_NAME -> R.string.tool_repairs_package
                                ToolCallRepairRouter.PROXY_FLATTENED_TOOL_NAME -> R.string.tool_repairs_flattened_proxy
                                ToolCallRepairRouter.MEMORY_ARGUMENT_ALIAS -> R.string.tool_repairs_memory_argument
                                ToolCallRepairRouter.MEMORY_FINISH_ALIAS -> R.string.tool_repairs_memory_finish
                                else -> null
                            }
                            Text(if (label == null) rule else stringResource(label))
                        }
                        Text(stringResource(R.string.tool_errors_parameters,
                            record.optJSONArray("parameterNames")?.let { array ->
                                (0 until array.length()).joinToString { array.optString(it) }
                            }.orEmpty()), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = !loading && page > 0, onClick = {
                page--
            }) { Text(stringResource(R.string.tool_errors_previous)) }
            Text(stringResource(R.string.tool_errors_page, page + 1,
                (((data?.total ?: 0) + ToolCallRepairLogger.PAGE_SIZE - 1) / ToolCallRepairLogger.PAGE_SIZE).coerceAtLeast(1)))
            TextButton(enabled = !loading && (page + 1L) * ToolCallRepairLogger.PAGE_SIZE < (data?.total ?: 0),
                onClick = { page++ }) { Text(stringResource(R.string.tool_errors_next)) }
        }
    }
    detail?.let { raw ->
        AlertDialog(onDismissRequest = { detail = null },
            title = { Text(stringResource(R.string.tool_repairs_title)) },
            text = { SelectionContainer {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item { Text(runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)) }
                }
            } },
            confirmButton = { TextButton(onClick = { detail = null }) {
                Text(stringResource(R.string.tool_errors_close))
            } })
    }
}
