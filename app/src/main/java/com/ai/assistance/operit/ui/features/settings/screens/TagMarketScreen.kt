package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.ai.assistance.operit.ui.components.CustomScaffold
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.data.model.PromptTag
import com.ai.assistance.operit.data.model.TagType
import com.ai.assistance.operit.data.preferences.PromptTagManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsIconBadge
import com.ai.assistance.operit.ui.features.settings.components.SettingsSectionTitle
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// Import bilingual preset tags
// Note: presetTags list has been moved to TagMarketBilingualData.kt for better bilingual support
// Use bilingualPresetTags from TagMarketBilingualData.kt instead

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagMarketScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val promptTagManager = remember { PromptTagManager.getInstance(context) }
    var showSaveSuccessHighlight by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf<PresetTagBilingual?>(null) }
    var newTagName by remember { mutableStateOf("") }
    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage.collectAsState(initial = false)
    val cardColor = settingsCardColor(hasBackgroundImage)

    CustomScaffold() { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 按分类分组显示标签 (Group tags by category)
            val groupedTags = bilingualPresetTags.groupBy { it.getLocalizedCategory(context) }
            groupedTags.entries.forEachIndexed { index, (category, tags) ->
                item {
                    SettingsSectionTitle(
                        text = category,
                        icon = tags.first().icon,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp)
                    )
                }

                items(tags) { preset ->
                    PresetTagCard(
                        preset = preset,
                        context = context,
                        cardColor = cardColor,
                        onUseClick = {
                            selectedPreset = it
                            newTagName = it.getLocalizedName(context) // 默认使用预设名称 (Use preset name by default)
                            showCreateDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showCreateDialog && selectedPreset != null) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.add_tag)) },
            text = {
                Column {
                    Text(stringResource(R.string.add_to_tag_library, selectedPreset?.getLocalizedName(context) ?: ""))
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        label = { Text(stringResource(R.string.tag_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTagName.isNotBlank()) {
                            scope.launch {
                                promptTagManager.createPromptTag(
                                    name = newTagName,
                                    description = selectedPreset!!.getLocalizedDescription(context),
                                    promptContent = selectedPreset!!.getLocalizedPromptContent(context),
                                    tagType = selectedPreset!!.tagType
                                )
                                showCreateDialog = false
                                showSaveSuccessHighlight = true
                                // 保留在标签市场页面，用户可继续添加或编辑标签
                            }
                        }
                    }
                ) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 保存成功的底部高亮提示（1.5s 自动消失）
    if (showSaveSuccessHighlight) {
        LaunchedEffect(Unit) {
            delay(1500)
            showSaveSuccessHighlight = false
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = context.getString(com.ai.assistance.operit.R.string.save_successful), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun PresetTagCard(
    preset: PresetTagBilingual,
    context: android.content.Context,
    cardColor: androidx.compose.ui.graphics.Color,
    onUseClick: (PresetTagBilingual) -> Unit
) {
    SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBadge(icon = preset.icon, size = 40)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preset.getLocalizedName(context),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = preset.getLocalizedDescription(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 标签类型徽章 (Tag type badge)
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = preset.tagType.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.tag_content),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = preset.getLocalizedPromptContent(context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { onUseClick(preset) },
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.tag_add))
        }
    }
}
