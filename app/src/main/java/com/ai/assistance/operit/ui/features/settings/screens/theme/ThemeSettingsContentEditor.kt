package com.ai.assistance.operit.ui.features.settings.screens.theme

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.model.ActivePrompt
import com.ai.assistance.operit.data.model.CharacterCard
import com.ai.assistance.operit.data.model.CharacterGroupCard
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.CharacterGroupCardManager
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsSearchFocus
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import com.ai.assistance.operit.ui.features.settings.sections.SaveThemeSettingsAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal data class ThemeSettingsShared(
    val context: android.content.Context,
    val preferencesManager: UserPreferencesManager,
    val displayPreferencesManager: DisplayPreferencesManager,
    val scope: CoroutineScope,
    val activePrompt: ActivePrompt,
    val activeCharacterCard: CharacterCard?,
    val activeCharacterGroup: CharacterGroupCard?,
    val activeThemeTargetName: String?,
    val activeThemeTargetAvatarUri: String?,
    val isGroupThemeTarget: Boolean,
    val saveThemeSettingsWithCharacterCard: SaveThemeSettingsAction,
)

@OptIn(ExperimentalMaterial3Api::class)
@NonRestartableComposable
@Composable
internal fun ThemeSettingsContent() {
    val context = LocalContext.current
    val preferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val displayPreferencesManager = remember { DisplayPreferencesManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val characterCardManager = remember { CharacterCardManager.getInstance(context) }
    val characterGroupCardManager = remember { CharacterGroupCardManager.getInstance(context) }
    val activePromptManager = remember { ActivePromptManager.getInstance(context) }

    val activePrompt by activePromptManager.activePromptFlow.collectAsState(
        initial = ActivePrompt.CharacterCard(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID),
    )
    val activeCharacterCard by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterCard -> characterCardManager.getCharacterCardFlow(prompt.id)
            is ActivePrompt.CharacterGroup -> flowOf(null)
        }
    }.collectAsState(initial = null)
    val activeCharacterGroup by remember(activePrompt) {
        when (val prompt = activePrompt) {
            is ActivePrompt.CharacterGroup -> characterGroupCardManager.getCharacterGroupCardFlow(prompt.id)
            is ActivePrompt.CharacterCard -> flowOf(null)
        }
    }.collectAsState(initial = null)

    ThemeSettingsContentEditor(
        preferencesManager = preferencesManager,
        displayPreferencesManager = displayPreferencesManager,
        scope = scope,
        activePrompt = activePrompt,
        activeCharacterCard = activeCharacterCard,
        activeCharacterGroup = activeCharacterGroup,
    )
}

@Composable
internal fun ThemeSettingsContentEditor(
    preferencesManager: UserPreferencesManager,
    displayPreferencesManager: DisplayPreferencesManager,
    scope: CoroutineScope,
    activePrompt: ActivePrompt,
    activeCharacterCard: CharacterCard?,
    activeCharacterGroup: CharacterGroupCard?,
) {
    val context = LocalContext.current
    val activeCardAvatarUri by remember(activeCharacterCard?.id) {
        activeCharacterCard?.id?.let { preferencesManager.getAiAvatarForCharacterCardFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val activeGroupAvatarUri by remember(activeCharacterGroup?.id) {
        activeCharacterGroup?.id?.let { preferencesManager.getAiAvatarForCharacterGroupFlow(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)
    val hasBackgroundImage by preferencesManager.useBackgroundImage.collectAsState(initial = false)
    val cardColors = CardDefaults.cardColors(
        containerColor = settingsCardColor(hasBackgroundImage),
    )
    // A settings search result opens the tab that holds the option.
    val searchTab = remember { themeSettingsTabForSearch(context, SettingsSearchFocus.peek()) }
    var selectedThemeTab by remember { mutableStateOf(searchTab ?: ThemeSettingsTab.BASIC) }
    var saveToast by remember { mutableStateOf<ThemeSettingsToast?>(null) }
    val scrollState = rememberScrollState()
    val activeThemeTargetName = activeCharacterGroup?.name ?: activeCharacterCard?.name
    val activeThemeTargetAvatarUri = activeGroupAvatarUri ?: activeCardAvatarUri
    val isGroupThemeTarget = activePrompt is ActivePrompt.CharacterGroup

    fun saveThemeToActiveTarget() {
        scope.launch {
            when (activePrompt) {
                is ActivePrompt.CharacterGroup -> {
                    activeCharacterGroup?.id?.let {
                        preferencesManager.saveCurrentThemeToCharacterGroup(it)
                    }
                }
                is ActivePrompt.CharacterCard -> {
                    activeCharacterCard?.id?.let {
                        preferencesManager.saveCurrentThemeToCharacterCard(it)
                    }
                }
            }
        }
    }

    fun saveThemeSettingsWithCharacterCard(saveAction: suspend () -> Unit) {
        scope.launch {
            saveAction()
            saveThemeToActiveTarget()
        }
    }

    val shared = ThemeSettingsShared(
        context = context,
        preferencesManager = preferencesManager,
        displayPreferencesManager = displayPreferencesManager,
        scope = scope,
        activePrompt = activePrompt,
        activeCharacterCard = activeCharacterCard,
        activeCharacterGroup = activeCharacterGroup,
        activeThemeTargetName = activeThemeTargetName,
        activeThemeTargetAvatarUri = activeThemeTargetAvatarUri,
        isGroupThemeTarget = isGroupThemeTarget,
        saveThemeSettingsWithCharacterCard = ::saveThemeSettingsWithCharacterCard,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ThemeSettingsTabbedContent(
            selectedTab = selectedThemeTab,
            onSelectedTabChange = { selectedThemeTab = it },
            scrollState = scrollState,
            modifier = Modifier.fillMaxSize(),
            basicContent = {
                ThemeSettingsBasicTab(
                    shared = shared,
                    cardColors = cardColors,
                    onShowSaveSuccessMessage = { saveToast = ThemeSettingsToast(R.string.theme_saved) },
                )
            },
            backgroundContent = {
                ThemeSettingsBackgroundTab(
                    shared = shared,
                    cardColors = cardColors,
                    scrollState = scrollState,
                    onShowSaveSuccessMessage = { saveToast = ThemeSettingsToast(R.string.theme_saved) },
                )
            },
            chatContent = {
                ThemeSettingsChatTab(
                    shared = shared,
                    cardColors = cardColors,
                )
            },
            inputContent = {
                ThemeSettingsInputTab(
                    shared = shared,
                    cardColors = cardColors,
                    onShowSaveSuccessMessage = { saveToast = ThemeSettingsToast(R.string.theme_saved) },
                )
            },
            interfaceContent = {
                ThemeSettingsInterfaceTab(
                    shared = shared,
                    cardColors = cardColors,
                    onShowSaveSuccessMessage = { saveToast = ThemeSettingsToast(R.string.theme_saved) },
                )
            },
            footerContent = {
                ThemeSettingsFooter(
                    onReset = {
                        scope.launch {
                            preferencesManager.resetThemeSettings()
                            if (isGroupThemeTarget) {
                                activeCharacterGroup?.id?.let { preferencesManager.deleteCharacterGroupTheme(it) }
                            } else {
                                activeCharacterCard?.id?.let { preferencesManager.deleteCharacterCardTheme(it) }
                            }
                            saveToast = ThemeSettingsToast(R.string.theme_reset_done)
                        }
                    },
                )
            },
        )

        ThemeSettingsToastHost(
            toast = saveToast,
            onDismiss = { saveToast = null },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}
