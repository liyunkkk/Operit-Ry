package com.ai.assistance.operit.ui.features.settings.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.ui.components.CustomScaffold
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.util.LocaleUtils
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsIconBadge
import com.ai.assistance.operit.ui.features.settings.components.SettingsInfoBanner
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.SettingsSectionTitle
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 语言设置界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current
    var currentLanguage by remember { mutableStateOf(LocaleUtils.getCurrentLanguage(context)) }
    val supportedLanguages = remember {
        // Limit the settings choices to maintained translations without removing legacy support.
        val settingsLanguages = setOf(
            LocaleUtils.LanguageCodes.AUTO,
            LocaleUtils.LanguageCodes.CHINESE,
            LocaleUtils.LanguageCodes.ENGLISH,
        )
        LocaleUtils.getSupportedLanguages().filter { it.code in settingsLanguages }
    }
    var isChangingLanguage by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(key1 = true) {
        currentLanguage = LocaleUtils.getCurrentLanguage(context)
    }

    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage.collectAsState(initial = false)
    val cardColor = settingsCardColor(hasBackgroundImage)

    fun selectLanguage(language: LocaleUtils.Language) {
        if (language.code == currentLanguage) return
        // 设置状态为正在切换语言
        isChangingLanguage = true

        // 应用新的语言设置（使用已初始化的全局实例）
        LocaleUtils.setAppLanguage(context, language.code)
        AgentProfileRepository.instance.initialize(context.applicationContext)

        // 显示切换语言的提示
        Toast.makeText(context, context.getString(R.string.language_changed), Toast.LENGTH_SHORT).show()

        // 延迟重启应用以确保语言设置完全生效
        scope.launch {
            delay(600) // 短暂延迟确保设置已保存
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        }
    }

    CustomScaffold() { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            SettingsSectionTitle(
                text = stringResource(R.string.select_language),
                icon = Icons.Default.Language
            )

            if (isChangingLanguage) {
                // 显示语言切换中的进度指示器
                SettingsCard(backgroundColor = cardColor) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                        Text(
                            text = stringResource(id = R.string.language_changing),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                SettingsCard(
                    backgroundColor = cardColor,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    supportedLanguages.forEachIndexed { index, language ->
                        if (index > 0) SettingsRowDivider()
                        LanguageItem(
                            language = language,
                            isSelected = language.code == currentLanguage,
                            onClick = { selectLanguage(language) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingsInfoBanner(
                text = stringResource(R.string.language_info),
                backgroundColor = cardColor
            )
        }
    }
}

@Composable
private fun LanguageItem(
    language: LocaleUtils.Language,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBadge(icon = Icons.Default.Language)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = language.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = language.nativeName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}