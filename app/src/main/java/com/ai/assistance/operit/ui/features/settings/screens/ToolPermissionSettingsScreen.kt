package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Build as OutlinedBuild
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.PermissionReviewInternalTools
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.components.SettingsSectionTitle
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.ui.features.settings.components.settingsSearchTarget
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.PermissionStopSlider
import com.ai.assistance.operit.ui.permissions.PermissionReviewPolicyStore
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import com.ai.assistance.operit.ui.permissions.ToolPermissionStop
import com.ai.assistance.operit.ui.permissions.defaultPermissionLevelFor
import com.ai.assistance.operit.ui.permissions.descriptionRes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPermissionSettingsScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    val toolHandler = remember { AIToolHandler.getInstance(context) }
    val toolPermissionSystem = remember { ToolPermissionSystem.getInstance(context) }
    val reviewPolicyStore = remember { PermissionReviewPolicyStore(context) }
    val reviewPolicySnapshot by
        reviewPolicyStore.snapshotFlow.collectAsState(
            initial =
                com.ai.assistance.operit.ui.permissions.PermissionReviewPolicySnapshot(
                    text = PermissionReviewPolicyStore.DEFAULT_POLICY,
                    version = "default",
                    customized = false,
                    managedAdditions = "",
                )
        )
    var policyDraft by remember { mutableStateOf("") }
    var policyExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val storedOverrides by toolPermissionSystem.toolPermissionOverridesFlow.collectAsState(initial = emptyMap())
    val allTools = remember(storedOverrides.keys) {
        (toolHandler.getAllToolNames().filterNot {
            it == "package_proxy" || it == "proxy" || it == "search" ||
                it in PermissionReviewInternalTools.names
        } + storedOverrides.keys).distinct()
    }
    val toolPermissions = remember { mutableStateMapOf<String, PermissionLevel>() }
    // The stored choice is read asynchronously, so the slider waits for it instead of starting on a
    // stop the user never chose and sliding to the real one a frame later.
    val selectedStop by toolPermissionSystem.permissionStopFlow.collectAsState(initial = null)
    // The stop under the finger, held until the value written to storage comes back, so the name and
    // the description below never disagree while dragging or right after a release. The stop the
    // hold started from is remembered with it: while the two writes land, storage can only report
    // that stop or the chosen one, so anything else means the choice came from somewhere else and
    // has to win.
    var pendingStop by remember { mutableStateOf<ToolPermissionStop?>(null) }
    var pendingBase by remember { mutableStateOf<ToolPermissionStop?>(null) }

    LaunchedEffect(selectedStop) {
        if (pendingStop != null && selectedStop != pendingBase) pendingStop = null
    }

    LaunchedEffect(storedOverrides) {
        toolPermissions.clear()
        toolPermissions.putAll(storedOverrides)
    }

    fun handlePermissionChange(toolName: String, newLevel: PermissionLevel) {
        val currentLevel = toolPermissions[toolName]
        if (currentLevel == newLevel) {
            // If the tool is already in the target level, move it back to ASK
            toolPermissions.remove(toolName)
            scope.launch {
                toolPermissionSystem.clearToolPermission(toolName)
            }
        } else {
            // Otherwise, move it to the new level
            toolPermissions[toolName] = newLevel
            scope.launch {
                toolPermissionSystem.saveToolPermission(toolName, newLevel)
            }
        }
    }

    fun selectPermissionStop(stop: ToolPermissionStop) {
        scope.launch { toolPermissionSystem.savePermissionStop(stop) }
    }

    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage.collectAsState(initial = false)
    val cardColor = settingsCardColor(hasBackgroundImage)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SettingsInfoBanner(
                text = stringResource(R.string.tool_permissions_description),
                backgroundColor = cardColor
            )
        }

        item {
            val globalTitle = stringResource(R.string.global_permission_switch)
            SettingsSectionTitle(
                text = globalTitle,
                icon = Icons.Outlined.Shield,
                modifier = Modifier.fillMaxWidth().settingsSearchTarget(globalTitle)
            )
            SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
                Column {
                    Text(
                        stringResource(R.string.global_permission_switch_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    selectedStop?.let { stop ->
                        val shownStop = pendingStop ?: stop
                        PermissionStopSlider(
                            stop = stop,
                            onStopPreview = { preview ->
                                if (pendingStop == null) pendingBase = selectedStop
                                pendingStop = preview
                            },
                            onStopSelected = { newStop ->
                                if (pendingStop == null) pendingBase = selectedStop
                                pendingStop = newStop
                                selectPermissionStop(newStop)
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(shownStop.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            val policyTitle = stringResource(R.string.permission_review_policy_title)
            SettingsSectionTitle(
                text = policyTitle,
                icon = Icons.Outlined.Policy,
                modifier = Modifier.fillMaxWidth().settingsSearchTarget(policyTitle)
            )
            SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
                Column {
                    Text(
                        stringResource(
                            R.string.permission_review_policy_description,
                            reviewPolicySnapshot.version,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            if (!policyExpanded) {
                                policyDraft = reviewPolicySnapshot.managedAdditions
                            }
                            policyExpanded = !policyExpanded
                        }
                    ) {
                        Text(
                            stringResource(
                                if (policyExpanded) R.string.permission_review_policy_collapse
                                else R.string.permission_review_policy_edit
                            )
                        )
                    }
                    AnimatedVisibility(policyExpanded) {
                        Column {
                            Text(
                                stringResource(R.string.permission_review_policy_managed_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = policyDraft,
                                onValueChange = { policyDraft = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                                placeholder = {
                                    Text(stringResource(R.string.permission_review_policy_placeholder))
                                },
                                minLines = 5,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch { reviewPolicyStore.saveCustomPolicy(policyDraft) }
                                    }
                                ) { Text(stringResource(R.string.save)) }
                                OutlinedButton(
                                    onClick = {
                                        policyDraft = ""
                                        scope.launch { reviewPolicyStore.reset() }
                                    }
                                ) { Text(stringResource(R.string.reset)) }
                            }
                        }
                    }
                }
            }
        }
        item {
            SettingsSectionTitle(
                text = stringResource(R.string.tool_permission_instruction_title),
                icon = Icons.Outlined.OutlinedBuild
            )
            SettingsInfoBanner(
                text = stringResource(R.string.tool_permission_instruction_content),
                backgroundColor = cardColor
            )
        }

        item {
            // A tool that carries its own default level is allowed without the user choosing it, so
            // it belongs in this list instead of in none of them. Its chip has no remove button:
            // there is no stored choice to clear, and giving it another level is what overrides it.
            val allowedByDefault =
                allTools.filter { toolName ->
                    toolName !in toolPermissions &&
                        selectedStop?.level?.let { level ->
                            defaultPermissionLevelFor(toolName, level)
                        } == PermissionLevel.ALLOW
                }
            PermissionGroup(
                level = PermissionLevel.ALLOW,
                allTools = allTools,
                toolsInLevel = toolPermissions.filterValues { it == PermissionLevel.ALLOW }.keys,
                defaultTools = allowedByDefault.toSet(),
                cardColor = cardColor,
                toolHandler = toolHandler,
                onToolToggled = { toolName -> handlePermissionChange(toolName, PermissionLevel.ALLOW) }
            )
        }
        item {
            PermissionGroup(
                level = PermissionLevel.AUTO_REVIEW,
                allTools = allTools,
                toolsInLevel =
                    toolPermissions.filterValues { it == PermissionLevel.AUTO_REVIEW }.keys,
                cardColor = cardColor,
                toolHandler = toolHandler,
                onToolToggled = { toolName ->
                    handlePermissionChange(toolName, PermissionLevel.AUTO_REVIEW)
                }
            )
        }
        item {
            PermissionGroup(
                level = PermissionLevel.ASK,
                allTools = allTools,
                toolsInLevel = toolPermissions.filterValues { it == PermissionLevel.ASK }.keys,
                cardColor = cardColor,
                toolHandler = toolHandler,
                onToolToggled = { toolName -> handlePermissionChange(toolName, PermissionLevel.ASK) }
            )
        }
        item {
            PermissionGroup(
                level = PermissionLevel.FORBID,
                allTools = allTools,
                toolsInLevel = toolPermissions.filterValues { it == PermissionLevel.FORBID }.keys,
                cardColor = cardColor,
                toolHandler = toolHandler,
                onToolToggled = { toolName -> handlePermissionChange(toolName, PermissionLevel.FORBID) }
            )
        }
    }
}

/** Chips shown inside a group before the rest move behind "show all". */
private const val PREVIEW_LIMIT = 8

/** Package tools are named `package:tool`; everything else is built in. */
private fun packageOf(toolName: String): String = toolName.substringBefore(':', "")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionGroup(
    level: PermissionLevel,
    allTools: List<String>,
    toolsInLevel: Set<String>,
    defaultTools: Set<String> = emptySet(),
    cardColor: Color,
    toolHandler: AIToolHandler,
    onToolToggled: (String) -> Unit
) {
    var showToolSelector by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var showDefaults by remember { mutableStateOf(false) }
    val chosen = remember(toolsInLevel) { toolsInLevel.sorted() }
    val defaults = remember(defaultTools) { defaultTools.sorted() }

    val (title, description, color) = when (level) {
        PermissionLevel.ALLOW -> Triple(
            stringResource(R.string.permission_level_allow),
            stringResource(R.string.permission_level_allow_description),
            MaterialTheme.colorScheme.primary
        )
        PermissionLevel.AUTO_REVIEW -> Triple(
            stringResource(R.string.permission_level_auto_review),
            stringResource(R.string.permission_level_auto_review_description),
            MaterialTheme.colorScheme.tertiary
        )
        PermissionLevel.FORBID -> Triple(
            stringResource(R.string.permission_level_forbid),
            stringResource(R.string.permission_level_forbid_description),
            MaterialTheme.colorScheme.error
        )
        PermissionLevel.ASK -> Triple(
            stringResource(R.string.permission_level_ask),
            stringResource(R.string.permission_level_ask_description),
            MaterialTheme.colorScheme.secondary
        )
    }

    SettingsCard(
        backgroundColor = cardColor,
        modifier = Modifier.settingsSearchTarget(title),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color, CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (chosen.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.tool_permission_count, chosen.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = color,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(color.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(
                    onClick = { showToolSelector = true },
                    modifier = Modifier.settingsSearchTarget(stringResource(R.string.add_tool))
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_tool), tint = color)
                }
            }

            if (chosen.isEmpty() && defaults.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.no_tools_in_group),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (chosen.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chosen.take(PREVIEW_LIMIT).forEach { toolName ->
                        ToolChip(toolName = toolName, onRemove = { onToolToggled(toolName) })
                    }
                }
                if (chosen.size > PREVIEW_LIMIT) {
                    TextButton(onClick = { showAll = true }) {
                        Text(stringResource(R.string.tool_permission_show_all, chosen.size))
                    }
                }
            }

            // A tool that carries its own default level is allowed without the user choosing it. It
            // has no stored choice to clear, so it sits folded away without a remove button; giving
            // it another level is what overrides it.
            if (defaults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showDefaults = !showDefaults }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.tool_permission_default_count, defaults.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (showDefaults) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(showDefaults) {
                    Column {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            defaults.forEach { ToolChip(toolName = it, onRemove = null) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.permission_level_allow_default_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showAll) {
        ToolListDialog(
            title = title,
            color = color,
            tools = chosen,
            onRemove = onToolToggled,
            onDismiss = { showAll = false }
        )
    }

    if (showToolSelector) {
        ToolSelectorDialog(
            allTools = allTools,
            toolsInLevel = toolsInLevel,
            toolHandler = toolHandler,
            onDismiss = { showToolSelector = false },
            onToolToggled = onToolToggled
        )
    }
}

@Composable
private fun ToolChip(toolName: String, onRemove: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(start = 10.dp, end = if (onRemove != null) 2.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            toolName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(vertical = 10.dp)
        )
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove_tool),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/** Every tool in one group, searchable and grouped by package, for when the preview is not enough. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolListDialog(
    title: String,
    color: Color,
    tools: List<String>,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val sections = remember(tools, searchQuery) {
        tools.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
            .groupBy(::packageOf)
            .toSortedMap()
    }
    val builtIn = stringResource(R.string.tool_permission_builtin_group)
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    // Rows sit on a lighter block in light mode and a raised one in dark mode, so both read as cards.
    val rowBlock =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerLowest

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = container)
        ) {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)) {
                Row(
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(12.dp).background(color, CircleShape))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.tool_permission_total, tools.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_tools)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (sections.isEmpty()) {
                    Text(
                        stringResource(
                            if (tools.isEmpty()) R.string.no_tools_in_group else R.string.tool_permission_no_match
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    sections.forEach { (pkg, names) ->
                        stickyHeader(key = "header:$pkg", contentType = "header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(container)
                                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (pkg.isEmpty()) Icons.Default.Build else Icons.Default.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    pkg.ifEmpty { builtIn },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    names.size.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(color.copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        itemsIndexed(names, key = { _, name -> "tool:$name" }, contentType = { _, _ -> "tool" }) { index, toolName ->
                            val first = index == 0
                            val last = index == names.lastIndex
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = if (first) 16.dp else 0.dp,
                                            topEnd = if (first) 16.dp else 0.dp,
                                            bottomStart = if (last) 16.dp else 0.dp,
                                            bottomEnd = if (last) 16.dp else 0.dp
                                        )
                                    )
                                    .background(rowBlock)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .padding(start = 16.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (pkg.isEmpty()) toolName else toolName.substringAfter(':'),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { onRemove(toolName) }) {
                                        Icon(
                                            Icons.Default.RemoveCircleOutline,
                                            contentDescription = stringResource(R.string.remove_tool),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                if (!last) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolSelectorDialog(
    allTools: List<String>,
    toolsInLevel: Set<String>,
    toolHandler: AIToolHandler,
    onDismiss: () -> Unit,
    onToolToggled: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val descriptions = remember(allTools) {
        allTools.associateWith { toolHandler.getToolDescription(it) }
    }
    val filteredTools = allTools.filter { it.contains(searchQuery, ignoreCase = true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 16.dp)) {
                Text(
                    stringResource(R.string.select_tools),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_tools)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredTools) { toolName ->
                        val isSelected = toolsInLevel.contains(toolName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToolToggled(toolName) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToolToggled(toolName) }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = toolName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = descriptions[toolName] ?: toolName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.done))
                }
            }
        }
    }
}
