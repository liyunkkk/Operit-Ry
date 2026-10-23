package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatTodo
import com.ai.assistance.operit.data.model.ChatTodoStatus

private const val TODO_DOCK_EXPANDED_ID = "chat-todo-dock"

/** Keeps the row's content clear of whatever sits under it, and counts as part of the row's box. */
private val TODO_DOCK_ROW_BOTTOM_GAP = 6.dp

internal fun currentTodoStep(todos: List<ChatTodo>): Int {
    val inProgressIndex = todos.indexOfFirst { it.status == ChatTodoStatus.IN_PROGRESS }
    val currentIndex =
        if (inProgressIndex >= 0) {
            inProgressIndex
        } else {
            todos.indexOfFirst { it.status == ChatTodoStatus.PENDING }
        }
    return if (currentIndex >= 0) currentIndex + 1 else todos.size
}

@Composable
fun ChatTodoDock(
    chatId: String?,
    todos: List<ChatTodo>,
    modifier: Modifier = Modifier,
    onRowCenterOffsetPx: (Int) -> Unit = {},
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val hasTodos = chatId != null && todos.isNotEmpty()
    val rowBottomGapPx = if (hasTodos) with(LocalDensity.current) { TODO_DOCK_ROW_BOTTOM_GAP.roundToPx() } else 0

    val allTerminal =
        todos.all {
            it.status == ChatTodoStatus.COMPLETED || it.status == ChatTodoStatus.CANCELLED
        }
    val expanded = hasTodos && TranscriptExpansionState.isExpanded(chatId.orEmpty(), TODO_DOCK_EXPANDED_ID)
    LaunchedEffect(chatId, allTerminal) {
        if (chatId != null && allTerminal) TranscriptExpansionState.setExpanded(chatId, TODO_DOCK_EXPANDED_ID, false)
    }

    val currentStep = currentTodoStep(todos)
    val terminalCount =
        todos.count {
            it.status == ChatTodoStatus.COMPLETED || it.status == ChatTodoStatus.CANCELLED
        }
    val progress = if (hasTodos) terminalCount.toFloat() / todos.size.toFloat() else 0f

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(visible = expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 4.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier =
                        Modifier.heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    todos.forEach { todo -> TodoDetailRow(todo) }
                }
            }
        }

        // Reports where this row's content sits, counted from the row's bottom edge: the settings
        // button beside the row has to share that centre, and the expandable panel above must not
        // move it. This node measures what is inside it, so the bottom gap is added back by hand.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = TODO_DOCK_ROW_BOTTOM_GAP.takeIf { hasTodos } ?: 0.dp)
                .onGloballyPositioned { onRowCenterOffsetPx(rowBottomGapPx + it.size.height / 2) },
            contentAlignment = Alignment.Center,
        ) {
            if (hasTodos) Surface(
                modifier = Modifier.clickable { TranscriptExpansionState.toggle(chatId.orEmpty(), TODO_DOCK_EXPANDED_ID) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        strokeCap = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.chat_todo_step_progress, currentStep, todos.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            leadingContent?.let { content ->
                Box(Modifier.align(Alignment.CenterStart)) { content() }
            }
        }
    }
}

@Composable
private fun TodoDetailRow(todo: ChatTodo) {
    val terminal =
        todo.status == ChatTodoStatus.COMPLETED || todo.status == ChatTodoStatus.CANCELLED
    val indicatorColor =
        when (todo.status) {
            ChatTodoStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
            ChatTodoStatus.COMPLETED -> MaterialTheme.colorScheme.primary
            ChatTodoStatus.CANCELLED -> MaterialTheme.colorScheme.outline
            ChatTodoStatus.PENDING -> MaterialTheme.colorScheme.outline
        }

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.then(if (terminal) Modifier.alpha(0.66f) else Modifier),
    ) {
        Box(modifier = Modifier.padding(top = 3.dp).size(16.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(14.dp)) {
                drawCircle(color = indicatorColor, style = Stroke(width = 1.8.dp.toPx()))
                if (todo.status == ChatTodoStatus.COMPLETED) {
                    drawCircle(color = indicatorColor, radius = 3.dp.toPx())
                }
            }
        }
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (todo.status == ChatTodoStatus.IN_PROGRESS) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = if (terminal) TextDecoration.LineThrough else TextDecoration.None,
        )
    }
}
