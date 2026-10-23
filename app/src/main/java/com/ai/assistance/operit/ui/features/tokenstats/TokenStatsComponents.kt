package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.features.settings.components.SettingsCardShape
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenCostCalculator
import com.ai.assistance.operit.data.stats.TokenStatsBaselineTotals
import com.ai.assistance.operit.data.stats.TokenStatsCostMode
import com.ai.assistance.operit.data.stats.TokenStatsDisplayModelBreakdown
import com.ai.assistance.operit.data.stats.TokenStatsDurationAggregate
import com.ai.assistance.operit.data.stats.TokenStatsLifetimeOverview
import com.ai.assistance.operit.data.stats.TokenStatsPreset
import com.ai.assistance.operit.data.stats.TokenStatsRangeData
import com.ai.assistance.operit.data.stats.TokenStatsTokenAggregate
import java.time.ZoneId
import java.util.Locale

// ==== 通用格式 ====

/** 金额：符号 + 4 位小数（图表/明细统一）。 */
internal fun formatMoney(amount: Double, currency: PricingCurrency): String =
    "${currency.symbol}${String.format(Locale.US, "%.4f", amount)}"

/** 每百万 token 单价。 */
internal fun formatPricePerMillion(price: Double, currency: PricingCurrency): String =
    "${currency.symbol}${String.format(Locale.US, "%.4f", price)}/1M"

/** 按次单价。 */
internal fun formatPricePerRequest(price: Double, currency: PricingCurrency): String =
    "${currency.symbol}${String.format(Locale.US, "%.4f", price)}/次"

internal fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

/** 统计页统一白色卡片；局部浅色 scheme 保证深色主题下控件与文字仍清晰。 */
@Composable
internal fun TokenStatsWhiteCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(
        colorScheme =
            scheme.copy(
                surface = TokenStatsCardContainer,
                onSurface = TokenStatsCardContent,
                surfaceVariant = Color(0xFFF5F5F5),
                onSurfaceVariant = TokenStatsCardMuted,
                outline = Color(0xFFBDBDBD),
                outlineVariant = Color(0xFFE0E0E0),
            ),
    ) {
        Card(
            modifier = modifier,
            shape = SettingsCardShape,
            colors = CardDefaults.cardColors(
                containerColor = TokenStatsCardContainer,
                contentColor = TokenStatsCardContent,
            ),
            content = content,
        )
    }
}

// ==== 生命周期累计总览（不受筛选） ====

@Composable
internal fun TokenStatsLifetimeCard(
    overview: TokenStatsLifetimeOverview,
    currency: PricingCurrency,
    manualRate: Double,
    rateIsEstimated: Boolean,
    includeLegacy: Boolean,
    onIncludeLegacyChange: (Boolean) -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.token_stats_lifetime_total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.summaryCardContent,
                    modifier = Modifier.weight(1f),
                )
                if (rateIsEstimated) {
                    EstimatedBadge(
                        text = stringResource(R.string.token_stats_rate_default_hint, manualRate),
                        textColor = colors.summaryCardContent,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.token_stats_include_legacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.summaryCardContent,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = includeLegacy,
                    onCheckedChange = onIncludeLegacyChange,
                )
            }

            Spacer(Modifier.height(8.dp))

            val eventTotals = overview.eventTotals
            val baseline = overview.baselineTotals
            val unknownCostContributions =
                includeLegacyValue(
                    eventTotals.cost.unknownContributionCount,
                    baseline.cost.unknownContributionCount,
                    includeLegacy,
                )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BigNumber(
                    label = stringResource(R.string.settings_total_requests),
                    value =
                        formatCount(
                            includeLegacyValue(eventTotals.requests, baseline.requests, includeLegacy)
                        ),
                    color = colors.summaryCardContent,
                )
                BigNumber(
                    label = stringResource(R.string.token_stats_tokens_total),
                    value = formatCompactCount(knownLifetimeTokenSum(overview, includeLegacy)),
                    color = colors.summaryCardContent,
                )
                BigNumber(
                    label = stringResource(R.string.settings_total_cost),
                    value =
                        formatMoney(
                            eventTotals.cost.knownAmount +
                                if (includeLegacy) baseline.cost.knownAmount else 0.0,
                            currency,
                        ),
                    color = colors.chartAccent,
                    alignEnd = true,
                )
            }

            Text(
                text = stringResource(R.string.token_stats_cost_estimate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.summaryCardContent.copy(alpha = 0.8f),
            )

            if (unknownCostContributions > 0L) {
                UnknownHint(
                    text = stringResource(
                        R.string.token_stats_unknown_cost,
                        unknownCostContributions,
                    ),
                    color = colors.unknownHint,
                )
            }
            if (unknownCostContributions == 0L &&
                eventTotals.cost.rateIsEstimated
            ) {
                Text(
                    text = stringResource(R.string.token_stats_rate_applied_hint, manualRate),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.summaryCardContent.copy(alpha = 0.8f),
                )
            }

            Spacer(Modifier.height(12.dp))

            TokenComponentLines(totals = eventTotals, textColor = colors.summaryCardContent)

            // 旧数据 baseline（估算口径，明确标注）
            if (includeLegacy && baseline.identityCount > 0L) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = colors.summaryCardContent.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.token_stats_baseline_estimate),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.summaryCardContent,
                        modifier = Modifier.weight(1f),
                    )
                    EstimatedBadge(
                        text = stringResource(R.string.token_stats_baseline_badge),
                        textColor = colors.summaryCardContent,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.token_stats_baseline_rows,
                        baseline.identityCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.summaryCardContent.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
                BaselineLine(
                    label = stringResource(R.string.token_stats_tokens_total),
                    value = formatCount(knownBaselineTokenSum(baseline)),
                    color = colors.summaryCardContent,
                )
                BaselineLine(
                    label = stringResource(R.string.settings_total_requests),
                    value = formatCount(baseline.requests),
                    color = colors.summaryCardContent,
                )
                BaselineLine(
                    label = stringResource(R.string.settings_total_cost),
                    value = formatMoney(baseline.cost.knownAmount, currency),
                    color = colors.summaryCardContent,
                )
                if (baseline.cost.unknownContributionCount > 0L) {
                    UnknownHint(
                        text = stringResource(
                            R.string.token_stats_unknown_cost,
                            baseline.cost.unknownContributionCount,
                        ),
                        color = colors.unknownHint,
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BigNumber(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    alignEnd: Boolean = false,
) {
    Column(
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        modifier = Modifier.weight(1f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.8f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
internal fun EstimatedBadge(text: String, textColor: androidx.compose.ui.graphics.Color) {
    val colors = LocalTokenStatsColors.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.estimatedBadgeContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun UnknownHint(text: String, color: androidx.compose.ui.graphics.Color) {
    if (!LocalShowUnknownHints.current) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TokenComponentLines(
    totals: com.ai.assistance.operit.data.stats.TokenStatsTotals,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TokenLine(
            label = stringResource(R.string.token_stats_token_uncached),
            aggregate = totals.uncachedInput,
            textColor = textColor,
        )
        TokenLine(
            label = stringResource(R.string.token_stats_token_cached),
            aggregate = totals.cachedInput,
            textColor = textColor,
        )
        TokenLine(
            label = stringResource(R.string.token_stats_token_cache_write),
            aggregate = totals.cacheWrite,
            textColor = textColor,
        )
        TokenLine(
            label = stringResource(R.string.token_stats_token_output),
            aggregate = totals.output,
            textColor = textColor,
        )
        TokenLine(
            label = stringResource(R.string.token_stats_token_reasoning),
            aggregate = totals.reasoning,
            textColor = textColor,
        )
    }
}

@Composable
private fun TokenLine(
    label: String,
    aggregate: TokenStatsTokenAggregate,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.85f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (LocalShowUnknownHints.current && aggregate.unknownEventCount > 0L) {
                Text(
                    text = stringResource(
                        R.string.token_stats_unknown_part_suffix,
                        aggregate.unknownEventCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalTokenStatsColors.current.unknownHint,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = formatCompactCount(aggregate.knownSum),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun BaselineLine(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.85f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/**
 * 事件 canonical 总 Token（聚合器逐事件推导，口径见
 * [com.ai.assistance.operit.data.stats.TokenStatsTotals.totalTokens]：
 * 权威 totalInputTokens 优先，fallback 按 cacheWriteSeparateBilling 决定输入口径，
 * 输出按 reasoningIncludedInOutput 决定是否补推理；未知分量保持 unknown 不当作 0）。
 * UI 展示总 Token 的唯一事实来源，不再从原始聚合字段自行重组。
 */
internal fun knownTokenSum(
    totals: com.ai.assistance.operit.data.stats.TokenStatsTotals,
): Long = totals.totalTokens.knownSum

/** 旧累计值没有额外 token 分类：inputTokens 已含缓存命中，只能按总输入和输出合计。 */
internal fun knownBaselineTokenSum(totals: TokenStatsBaselineTotals): Long =
    saturatedTokenSum(totals.inputTokens, totals.outputTokens)

/** 生命周期总 Token 必须同时包含新事件与迁移的旧累计 baseline。 */
internal fun knownLifetimeTokenSum(
    overview: TokenStatsLifetimeOverview,
    includeLegacy: Boolean = true,
): Long =
    includeLegacyValue(
        knownTokenSum(overview.eventTotals),
        knownBaselineTokenSum(overview.baselineTotals),
        includeLegacy,
    )

internal fun includeLegacyValue(eventValue: Long, baselineValue: Long, includeLegacy: Boolean): Long =
    if (includeLegacy) TokenCostCalculator.saturatedAdd(eventValue, baselineValue) else eventValue

internal fun saturatedTokenSum(vararg values: Long): Long =
    values.fold(0L, TokenCostCalculator::saturatedAdd)

// ==== 筛选栏 ====

/** 时间预设（10 预设 + 自定义）与模型/分类/状态/口径/币种筛选。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TokenStatsFilterBar(
    selectedPreset: TokenStatsPreset,
    selectedModels: Set<String>,
    availableModels: List<TokenStatsDisplayModelBreakdown>,
    knownModelNames: Map<String, String>,
    selectedCategories: Set<TokenStatCategory>?,
    selectedStatuses: Set<TokenStatStatus>?,
    costMode: TokenStatsCostMode,
    targetCurrency: PricingCurrency,
    onSelectPreset: (TokenStatsPreset) -> Unit,
    onCustomRange: () -> Unit,
    onDeleteRange: () -> Unit,
    onToggleModel: (String) -> Unit,
    onSelectAllModels: () -> Unit,
    onToggleCategory: (TokenStatCategory) -> Unit,
    onClearAllCategories: () -> Unit,
    onToggleStatus: (TokenStatStatus) -> Unit,
    onClearAllStatuses: () -> Unit,
    onSetCostMode: (TokenStatsCostMode) -> Unit,
    onSetCurrency: (PricingCurrency) -> Unit,
) {
    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            var showCostModeHelp by remember { mutableStateOf(false) }

            // 时间、展示币种和范围删除属于同一层级。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TimePresetDropdown(
                    selectedPreset,
                    onSelectPreset,
                    onCustomRange,
                    Modifier.weight(1f),
                )
                CurrencyChip(
                    currency = PricingCurrency.CNY,
                    selected = targetCurrency == PricingCurrency.CNY,
                    onClick = { onSetCurrency(PricingCurrency.CNY) },
                )
                CurrencyChip(
                    currency = PricingCurrency.USD,
                    selected = targetCurrency == PricingCurrency.USD,
                    onClick = { onSetCurrency(PricingCurrency.USD) },
                )
                // 删除当前时间范围：只删有时间戳的事件，不触碰 baseline（阶段 5）
                IconButton(onClick = onDeleteRange) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.token_stats_delete_range),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 查询维度固定三列，避免重要筛选藏在横向滚动区域。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModelFilterDropdown(
                    selectedModels,
                    availableModels,
                    knownModelNames,
                    onToggleModel,
                    onSelectAllModels,
                    Modifier.weight(1f),
                )
                CategoryFilterDropdown(
                    selectedCategories,
                    onToggleCategory,
                    onClearAllCategories,
                    Modifier.weight(1f),
                )
                StatusFilterDropdown(
                    selectedStatuses,
                    onToggleStatus,
                    onClearAllStatuses,
                    Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // 计价口径独占一行，帮助入口解释它只影响费用计算。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = costMode == TokenStatsCostMode.HISTORICAL,
                    onClick = { onSetCostMode(TokenStatsCostMode.HISTORICAL) },
                    label = { Text(stringResource(R.string.token_stats_mode_historical)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = costMode == TokenStatsCostMode.REVALUED,
                    onClick = { onSetCostMode(TokenStatsCostMode.REVALUED) },
                    label = { Text(stringResource(R.string.token_stats_mode_revalued)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showCostModeHelp = true }) {
                    Icon(
                        imageVector = Icons.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.token_stats_mode_help_title),
                        tint = TokenStatsCardMuted,
                    )
                }
            }

            if (showCostModeHelp) {
                AlertDialog(
                    onDismissRequest = { showCostModeHelp = false },
                    title = { Text(stringResource(R.string.token_stats_mode_help_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.token_stats_mode_historical_help))
                            Text(stringResource(R.string.token_stats_mode_revalued_help))
                            Text(
                                text = stringResource(R.string.token_stats_mode_rate_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = TokenStatsCardMuted,
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCostModeHelp = false }) {
                            Text(stringResource(R.string.token_stats_help_got_it))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CurrencyChip(
    currency: PricingCurrency,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(currency.code)
        },
    )
}

private fun TokenStatsPreset.labelRes(): Int =
    when (this) {
        TokenStatsPreset.LAST_5H -> R.string.token_stats_preset_5h
        TokenStatsPreset.LAST_12H -> R.string.token_stats_preset_12h
        TokenStatsPreset.LAST_24H -> R.string.token_stats_preset_24h
        TokenStatsPreset.TODAY -> R.string.token_stats_preset_today
        TokenStatsPreset.YESTERDAY -> R.string.token_stats_preset_yesterday
        TokenStatsPreset.LAST_7D -> R.string.token_stats_preset_7d
        TokenStatsPreset.LAST_30D -> R.string.token_stats_preset_30d
        TokenStatsPreset.THIS_MONTH -> R.string.token_stats_preset_this_month
        TokenStatsPreset.LAST_MONTH -> R.string.token_stats_preset_last_month
        TokenStatsPreset.CUSTOM -> R.string.token_stats_custom_range
    }

@Composable
private fun TimePresetDropdown(
    selected: TokenStatsPreset,
    onSelect: (TokenStatsPreset) -> Unit,
    onCustomRange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterDropdown(
        label = stringResource(selected.labelRes()),
        modifier = modifier,
    ) { dismiss ->
        TokenStatsPreset.entries.forEach { preset ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(preset.labelRes()),
                        fontWeight = if (preset == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    dismiss()
                    if (preset == TokenStatsPreset.CUSTOM) onCustomRange() else onSelect(preset)
                },
            )
        }
    }
}

@Composable
private fun ModelFilterDropdown(
    selectedModels: Set<String>,
    availableModels: List<TokenStatsDisplayModelBreakdown>,
    knownModelNames: Map<String, String>,
    onToggleModel: (String) -> Unit,
    onSelectAllModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 可选项 = 当前范围可用模型 + 已被选中但被筛选出当前结果的模型（P1-5）
    val options: List<Pair<String, String>> = remember(availableModels, selectedModels, knownModelNames) {
        val byId = availableModels.associateBy { it.displayModelId }
        buildList {
            availableModels.forEach { add(it.displayModelId to it.displayName) }
            selectedModels.forEach { id ->
                if (id !in byId) add(id to (knownModelNames[id] ?: id))
            }
        }
    }
    FilterDropdown(
        modifier = modifier,
        label = if (selectedModels.isEmpty()) {
            stringResource(R.string.token_stats_filter_all_models)
        } else {
            stringResource(R.string.token_stats_filter_models_count, selectedModels.size)
        },
    ) { dismiss ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.token_stats_filter_all_models),
                    fontWeight = FontWeight.Bold,
                )
            },
            onClick = {
                onSelectAllModels()
                dismiss()
            },
        )
        options.forEach { (modelId, displayName) ->
            val checked = selectedModels.isEmpty() || modelId in selectedModels
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleModel(modelId) },
                        )
                        Text(
                            displayName,
                            modifier = Modifier.padding(start = 4.dp),
                            maxLines = 1,
                        )
                    }
                },
                onClick = { onToggleModel(modelId) },
            )
        }
    }
}

@Composable
private fun CategoryFilterDropdown(
    selected: Set<TokenStatCategory>?,
    onToggle: (TokenStatCategory) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterDropdown(
        modifier = modifier,
        label = if (selected == null) {
            stringResource(R.string.token_stats_filter_all_categories)
        } else {
            stringResource(R.string.token_stats_filter_categories_count, selected.size)
        },
    ) { dismiss ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.token_stats_filter_all_categories),
                    fontWeight = FontWeight.Bold,
                )
            },
            onClick = {
                if (selected != null) onClearAll()
                dismiss()
            },
        )
        TokenStatCategory.entries.forEach { category ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected?.contains(category) == true,
                            onCheckedChange = { onToggle(category) },
                        )
                        Text(
                            stringResource(category.labelRes()),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
                onClick = { onToggle(category) },
            )
        }
    }
}

@Composable
private fun StatusFilterDropdown(
    selected: Set<TokenStatStatus>?,
    onToggle: (TokenStatStatus) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterDropdown(
        modifier = modifier,
        label = if (selected == null) {
            stringResource(R.string.token_stats_filter_all_statuses)
        } else {
            stringResource(R.string.token_stats_filter_statuses_count, selected.size)
        },
    ) { dismiss ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.token_stats_filter_all_statuses),
                    fontWeight = FontWeight.Bold,
                )
            },
            onClick = {
                if (selected != null) onClearAll()
                dismiss()
            },
        )
        TokenStatStatus.entries.forEach { status ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected?.contains(status) == true,
                            onCheckedChange = { onToggle(status) },
                        )
                        Text(
                            stringResource(status.labelRes()),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
                onClick = { onToggle(status) },
            )
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

internal fun TokenStatCategory.labelRes(): Int =
    when (this) {
        TokenStatCategory.CHAT -> R.string.token_stats_category_chat
        TokenStatCategory.SUBAGENT -> R.string.token_stats_category_subagent
        TokenStatCategory.SUMMARY -> R.string.token_stats_category_summary
        TokenStatCategory.TITLE -> R.string.token_stats_category_title
        TokenStatCategory.MEMORY -> R.string.token_stats_category_memory
        TokenStatCategory.CHARACTER_GENERATION -> R.string.token_stats_category_character
        TokenStatCategory.READING_COMPANION -> R.string.token_stats_category_reading_companion
        TokenStatCategory.CONNECTION_TEST -> R.string.token_stats_category_connection_test
        TokenStatCategory.PERMISSION_REVIEWER -> R.string.token_stats_category_permission_reviewer
        TokenStatCategory.PERMISSION_RISK_SCORER ->
            R.string.token_stats_category_permission_risk_scorer
        TokenStatCategory.COMPOSER_PREDICTION -> R.string.token_stats_category_composer_prediction
        TokenStatCategory.OTHER -> R.string.token_stats_category_other
    }

internal fun TokenStatStatus.labelRes(): Int =
    when (this) {
        TokenStatStatus.COMPLETED -> R.string.token_stats_status_completed
        TokenStatStatus.CANCELLED -> R.string.token_stats_status_cancelled
        TokenStatStatus.TIMEOUT -> R.string.token_stats_status_timeout
        TokenStatStatus.FAILED -> R.string.token_stats_status_failed
    }

// ==== 图表卡片 ====

@Composable
internal fun TokenStatsChartCard(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onSummaryClick: (() -> Unit)? = null,
    headerExtra: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val chartColors =
        LocalTokenStatsColors.current.copy(
            chartGrid = Color(0xFFE0E0E0),
            chartLabel = Color(0xFF5F6368),
            tooltipContainer = TokenStatsCardContainer,
            tooltipContent = Color(0xFF202124),
            unknownHint = Color(0xFF8A4B00),
        )
    CompositionLocalProvider(LocalTokenStatsColors provides chartColors) {
        TokenStatsWhiteCard(
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = chartColors.chartAccent,
                        modifier = if (onSummaryClick != null) {
                            Modifier.clickable(onClick = onSummaryClick)
                        } else {
                            Modifier
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                headerExtra()
                content()
            }
        }
    }
}

// ==== 模型卡片 ====

@Composable
internal fun TokenStatsModelCardsSection(
    models: List<TokenStatsDisplayModelBreakdown>,
    currency: PricingCurrency,
    costMode: TokenStatsCostMode,
    zone: ZoneId,
    onGroupManage: (TokenStatsDisplayModelBreakdown) -> Unit,
    onDelete: (TokenStatsDisplayModelBreakdown) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        models.forEach { model ->
            TokenStatsModelCard(
                model = model,
                currency = currency,
                costMode = costMode,
                zone = zone,
                onGroupManage = { onGroupManage(model) },
                onDelete = { onDelete(model) },
            )
        }
    }
}

@Composable
internal fun TokenStatsModelCard(
    model: TokenStatsDisplayModelBreakdown,
    currency: PricingCurrency,
    costMode: TokenStatsCostMode,
    zone: ZoneId,
    onGroupManage: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    var expanded by remember(model.displayModelId) { mutableStateOf(false) }

    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(R.string.token_stats_model_expand),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(
                            R.string.token_stats_model_identities_count,
                            model.identities.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TokenStatsCardMuted,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatMoney(model.totals.cost.knownAmount, currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.chartAccent,
                    )
                    Text(
                        text = stringResource(R.string.settings_request_count_label, model.totals.requests),
                        style = MaterialTheme.typography.bodySmall,
                        color = TokenStatsCardMuted,
                    )
                }
                // 阶段 5：删除对完整展示分组生效（可跨 provider/模型合并组），
                // 不再限制单 provider:model；危险操作在对话框两步确认。
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.token_stats_delete_model),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onGroupManage) {
                    Icon(
                        imageVector = Icons.Filled.Groups,
                        contentDescription = stringResource(R.string.token_stats_group_manage),
                        tint = TokenStatsCardMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                model.identities.forEach { identity ->
                    TokenStatsIdentityRow(identity = identity, currency = currency, costMode = costMode)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun TokenStatsIdentityRow(
    identity: com.ai.assistance.operit.data.stats.TokenStatsIdentityBreakdown,
    currency: PricingCurrency,
    costMode: TokenStatsCostMode,
) {
    val colors = LocalTokenStatsColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
    ) {
        Text(
            text = "${identity.provider} · ${identity.model}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        if (identity.configId.isNotEmpty()) {
            Text(
                text = stringResource(R.string.token_stats_config_id, identity.configId),
                style = MaterialTheme.typography.bodySmall,
                color = TokenStatsCardMuted,
            )
        }

        val totals = identity.totals
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${stringResource(R.string.token_stats_token_uncached)} ${formatCompactCount(totals.uncachedInput.knownSum)}" +
                    " · ${stringResource(R.string.token_stats_token_cached)} ${formatCompactCount(totals.cachedInput.knownSum)}" +
                    " · ${stringResource(R.string.token_stats_token_output)} ${formatCompactCount(totals.output.knownSum)}",
                style = MaterialTheme.typography.bodySmall,
                color = TokenStatsCardMuted,
            )
            Text(
                text = formatMoney(totals.cost.knownAmount, currency),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
        if (LocalShowUnknownHints.current && (totals.uncachedInput.unknownEventCount > 0L ||
            totals.cachedInput.unknownEventCount > 0L ||
            totals.output.unknownEventCount > 0L)
        ) {
            Text(
                text = stringResource(
                    R.string.token_stats_unknown_parts,
                    totals.uncachedInput.unknownEventCount +
                        totals.cachedInput.unknownEventCount +
                        totals.output.unknownEventCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.unknownHint,
            )
        }
        if (LocalShowUnknownHints.current && totals.cost.unknownContributionCount > 0L) {
            Text(
                text = stringResource(R.string.token_stats_unknown_cost, totals.cost.unknownContributionCount),
                style = MaterialTheme.typography.bodySmall,
                color = colors.unknownHint,
            )
        }

        // 单价：历史口径 = 事件快照；重估口径 = 当前解析价格
        identity.pricing?.let { pricing ->
            val priceText =
                if (!pricing.known) {
                    stringResource(R.string.token_stats_unknown_pricing)
                } else {
                    buildPricingText(pricing, currency)
                }
            Text(
                text = "${stringResource(R.string.token_stats_price_label)} $priceText",
                style = MaterialTheme.typography.bodySmall,
                color = TokenStatsCardMuted,
            )
        }
    }
}

/** 单价的展示文本（含计费方式与来源标签）。 */
@Composable
private fun buildPricingText(
    pricing: com.ai.assistance.operit.data.stats.TokenStatsPricingInfo,
    currency: PricingCurrency,
): String {
    val displayCurrency = pricing.currency
    val modeText =
        if (pricing.billingMode == com.ai.assistance.operit.data.model.BillingMode.TOKEN) {
            val parts = buildList {
                pricing.inputPricePerMillion?.let { add(formatPricePerMillion(it, displayCurrency)) }
                pricing.cachedInputPricePerMillion?.let { add(formatPricePerMillion(it, displayCurrency)) }
                pricing.cacheWritePricePerMillion?.let { add(formatPricePerMillion(it, displayCurrency)) }
                pricing.outputPricePerMillion?.let { add(formatPricePerMillion(it, displayCurrency)) }
            }
            if (parts.isEmpty()) stringResource(R.string.token_stats_unknown_pricing) else parts.joinToString(" · ")
        } else {
            pricing.pricePerRequest?.let { formatPricePerRequest(it, displayCurrency) }
                ?: stringResource(R.string.token_stats_unknown_pricing)
        }
    val sourceText =
        when (pricing.source) {
            com.ai.assistance.operit.data.stats.PricingSource.DEFAULT ->
                stringResource(R.string.token_stats_pricing_source_builtin)
            com.ai.assistance.operit.data.stats.PricingSource.PROVIDER_MODEL_OVERRIDE ->
                stringResource(R.string.token_stats_pricing_source_override)
            com.ai.assistance.operit.data.stats.PricingSource.CONFIG_OVERRIDE ->
                stringResource(R.string.token_stats_pricing_source_config)
            com.ai.assistance.operit.data.stats.PricingSource.LEGACY_OVERRIDE ->
                stringResource(R.string.token_stats_pricing_source_legacy)
            com.ai.assistance.operit.data.stats.PricingSource.UNKNOWN ->
                stringResource(R.string.token_stats_unknown_pricing)
        }
    return "$modeText（$sourceText）"
}

// ==== 汇率与币种设置卡 ====

@Composable
internal fun TokenStatsRateCard(
    manualRate: Double,
    rateIsEstimated: Boolean,
    onSaveRate: (Double) -> Boolean,
) {
    val colors = LocalTokenStatsColors.current
    var rateInput by remember { mutableStateOf(formatRateInput(manualRate)) }
    // 汇率外部变化（如从 DataStore 重新加载）时同步输入框
    LaunchedEffect(manualRate) {
        rateInput = formatRateInput(manualRate)
    }

    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_exchange_rate_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (rateIsEstimated) {
                    EstimatedBadge(
                        text = stringResource(R.string.token_stats_rate_default_badge),
                        textColor = TokenStatsCardContent,
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_exchange_rate_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = TokenStatsCardMuted,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = rateInput,
                    onValueChange = { rateInput = it },
                    label = { Text(stringResource(R.string.settings_usd_to_cny_rate_label)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val parsed = rateInput.toDoubleOrNull()
                        if (parsed == null || !onSaveRate(parsed)) {
                            // 非法输入保持原值并提示（Toast 由调用方统一处理）
                            rateInput = formatRateInput(manualRate)
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }

            if (rateIsEstimated) {
                Text(
                    text = stringResource(R.string.token_stats_rate_default_hint, manualRate),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.unknownHint,
                )
            }
        }
    }
}

private fun formatRateInput(rate: Double): String =
    String.format(Locale.US, "%.4f", rate).trimEnd('0').trimEnd('.')

/** 性能聚合的平均值格式化（无有效样本显示“无数据”而非 0）。 */
@Composable
internal fun durationSummaryText(aggregate: TokenStatsDurationAggregate): String {
    if (!aggregate.hasData) return stringResource(R.string.token_stats_perf_no_data)
    val avg = formatDuration(aggregate.averageMs)
    return stringResource(R.string.token_stats_perf_avg, avg)
}
