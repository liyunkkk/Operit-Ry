package com.ai.assistance.operit.ui.features.memory.screens.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.ui.text.style.TextOverflow
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.Memory
import com.ai.assistance.operit.ui.features.memory.screens.graph.model.Edge
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryInfoDialog(
        memory: Memory,
        onDismiss: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
) {
    val scrollState = rememberScrollState()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // A raw UUID and the internal source key are developer details, so they stay available but are
    // not shown to every user who just wants to read their memory.
    var showTechnical by remember { mutableStateOf(false) }

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(R.string.memory_details_title)) },
            text = {
                Column(
                        modifier = Modifier.verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${stringResource(R.string.memory_title)}: ${memory.title}", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    Text(stringResource(R.string.memory_content) + ":", style = MaterialTheme.typography.titleSmall)
                    Text(memory.content)
                    HorizontalDivider()
                    Text("${stringResource(R.string.memory_folder)}: ${memory.folderPath?.ifEmpty { stringResource(R.string.memory_uncategorized) }}", style = MaterialTheme.typography.bodySmall)
                    Text("${stringResource(R.string.memory_source)}: ${memorySourceText(memory.source)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                            "${stringResource(R.string.memory_importance)}: " +
                                stringResource(R.string.memory_detail_percent, (memory.importance * 100).roundToInt()),
                            style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                            "${stringResource(R.string.memory_credibility)}: " +
                                stringResource(R.string.memory_detail_percent, (memory.credibility * 100).roundToInt()),
                            style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                            "${stringResource(R.string.memory_created_at)}: ${dateFormat.format(memory.createdAt)}",
                            style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                            "${stringResource(R.string.memory_updated_at)}: ${dateFormat.format(memory.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { showTechnical = !showTechnical }) {
                        Text(stringResource(if (showTechnical) R.string.memory_technical_hide
                            else R.string.memory_technical_show))
                    }
                    if (showTechnical) {
                        Text("${stringResource(R.string.memory_uuid)}: ${memory.uuid}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.Center
                ) {
                    Button(onClick = onEdit) { Text(stringResource(R.string.memory_edit)) }
                    Button(
                            onClick = { confirmingDelete = true },
                            colors =
                                    ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                    )
                    ) { Text(stringResource(R.string.memory_delete)) }
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.memory_close)) }
                }
            }
    )
    if (confirmingDelete) DeleteConfirmed(
        message = stringResource(R.string.memory_confirm_delete_node),
        onDismiss = { confirmingDelete = false },
        onConfirm = { confirmingDelete = false; onDelete() }
    )
}

/** Turns the stored source key into something a user can read, keeping unknown values verbatim. */
@Composable
internal fun memorySourceText(source: String): String = when (source) {
    "user_input" -> stringResource(R.string.memory_source_manual)
    "ai_created" -> stringResource(R.string.memory_source_ai_created)
    "merged_from_memory" -> stringResource(R.string.memory_source_merged)
    else -> source
}

/** Folder dropdown shared by the memory editor and the batch move, so both name folders the same way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderSelector(
    allFolderPaths: List<String>,
    selectedPath: String,
    onPathSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedPath,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.memory_folder_label2)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            allFolderPaths.forEach { path ->
                DropdownMenuItem(
                    text = { Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onPathSelected(path)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * A batch move needs a stated target. Without one the action silently drops the folder of every
 * selected memory while the user is looking at "all memories", which reads as data loss.
 */
@Composable
fun MoveMemoriesToFolderDialog(
    allFolderPaths: List<String>,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val uncategorized = stringResource(R.string.memory_uncategorized)
    // The folder list already reports an empty path as "uncategorized", so the extra option must not
    // show up twice.
    val options = remember(allFolderPaths, uncategorized) {
        listOf(uncategorized) + allFolderPaths.filterNot { it == uncategorized }
    }
    var target by remember { mutableStateOf(options.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_move_selected)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.memory_move_selected_hint, selectedCount),
                    style = MaterialTheme.typography.bodyMedium
                )
                FolderSelector(
                    allFolderPaths = options,
                    selectedPath = target,
                    onPathSelected = { target = it }
                )
            }
        },
        confirmButton = {
            // Nothing selected means nothing to move, so the button must not look like it works.
            Button(enabled = selectedCount > 0, onClick = { onConfirm(target) }) {
                Text(stringResource(R.string.memory_move_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Shared shape for the one-off delete confirmations that the list screens already had. */
@Composable
internal fun DeleteConfirmed(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.memory_confirm_delete_action))
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EdgeInfoDialog(
    edge: Edge,
    graph: com.ai.assistance.operit.ui.features.memory.screens.graph.model.Graph,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val sourceNode = graph.nodes.find { it.id == edge.sourceId }
    val targetNode = graph.nodes.find { it.id == edge.targetId }
    var confirmingDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_link_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${stringResource(R.string.memory_from)}: ${sourceNode?.label ?: stringResource(R.string.memory_uncategorized)}")
                Text("${stringResource(R.string.memory_to)}: ${targetNode?.label ?: stringResource(R.string.memory_uncategorized)}")
                HorizontalDivider()
                Text("${stringResource(R.string.memory_type)}: ${edge.label}")
                Text("${stringResource(R.string.memory_weight)}: ${edge.weight}")
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.Center
            ) {
                Button(onClick = onEdit) { Text(stringResource(R.string.memory_edit)) }
                Button(
                    onClick = { confirmingDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.memory_delete)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.memory_close)) }
            }
        }
    )
    if (confirmingDelete) DeleteConfirmed(
        message = stringResource(R.string.memory_confirm_delete_edge),
        onDismiss = { confirmingDelete = false },
        onConfirm = { confirmingDelete = false; onDelete() }
    )
}

@Composable
fun EditEdgeDialog(
    edge: Edge,
    onDismiss: () -> Unit,
    onSave: (type: String, weight: Float, description: String) -> Unit
) {
    var type by remember { mutableStateOf(edge.label ?: "related") }
    var weight by remember { mutableStateOf(edge.weight.toString()) }
    var description by remember { mutableStateOf("") } // 假设需要编辑description

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_edit_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text(stringResource(R.string.memory_type)) })
                OutlinedTextField(value = weight, onValueChange = { weight = it }, label = { Text(stringResource(R.string.memory_weight)) })
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.memory_description)) })
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(type, weight.toFloatOrNull() ?: 1.0f, description)
            }) { Text(stringResource(R.string.memory_save)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) } }
    )
}

@Composable
fun LinkMemoryDialog(
    sourceNodeLabel: String,
    targetNodeLabel: String,
    onDismiss: () -> Unit,
    onLink: (type: String, weight: Float, description: String) -> Unit
) {
    var type by remember { mutableStateOf("related") }
    var weight by remember { mutableStateOf("1.0") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_link_nodes, sourceNodeLabel, targetNodeLabel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = type,
                    onValueChange = { type = it },
                    label = { Text(stringResource(R.string.memory_type)) }
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text(stringResource(R.string.memory_weight)) }
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.memory_description)) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = weight.toFloatOrNull() ?: 1.0f
                    onLink(type, w, description)
                }
            ) { Text(stringResource(R.string.memory_create_link)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) } }
    )
}

@Composable
fun BatchDeleteConfirmDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.memory_delete_confirmation, selectedCount),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.memory_delete_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.confirm_delete))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
