package com.ai.assistance.operit.ui.features.settings.screens

import com.ai.assistance.operit.ui.features.settings.components.settingsSearchTarget
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.data.preferences.ExternalHttpApiPreferences
import com.ai.assistance.operit.integrations.http.ExternalChatHttpNetworkInfo
import com.ai.assistance.operit.ui.components.CustomScaffold
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsIconBadge
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import kotlinx.coroutines.launch

@Composable
fun ExternalHttpChatSettingsScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { ExternalHttpApiPreferences.getInstance(context) }

    val enabled by preferences.enabledFlow.collectAsState(initial = false)
    val savedPort by preferences.portFlow.collectAsState(initial = ExternalHttpApiPreferences.DEFAULT_PORT)
    val bearerToken by preferences.bearerTokenFlow.collectAsState(initial = "")
    val serviceState by AIForegroundService.externalHttpState.collectAsState()

    var portText by remember { mutableStateOf(savedPort.toString()) }
    LaunchedEffect(savedPort) {
        portText = savedPort.toString()
    }

    var bearerTokenText by remember { mutableStateOf(bearerToken) }
    LaunchedEffect(bearerToken) {
        if (bearerTokenText != bearerToken) {
            bearerTokenText = bearerToken
        }
    }

    val accessUrls = remember(savedPort) {
        ExternalChatHttpNetworkInfo.getLocalIpv4Addresses().map { ip ->
            "http://$ip:$savedPort"
        }
    }
    val displayToken = bearerToken.ifBlank {
        context.getString(R.string.external_http_chat_token_not_generated)
    }
    val curlToken = bearerToken.ifBlank { "<bearer-token>" }
    val sampleBaseUrl = accessUrls.firstOrNull() ?: "http://127.0.0.1:$savedPort"
    val webEntryUrl = "$sampleBaseUrl/"
    val webApiBaseUrl = "$sampleBaseUrl/api/"
    val syncCurl = remember(sampleBaseUrl, curlToken) {
        """
curl -X POST "$sampleBaseUrl/api/external-chat" \
  -H "Authorization: Bearer $curlToken" \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"message":"你好","response_mode":"sync","show_floating":true,"initial_mode":"WINDOW","return_tool_status":false}'
        """.trimIndent()
    }
    val asyncCurl = remember(sampleBaseUrl, curlToken) {
        """
curl -X POST "$sampleBaseUrl/api/external-chat" \
  -H "Authorization: Bearer $curlToken" \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"message":"你好","response_mode":"async_callback","callback_url":"http://YOUR_PC:8080/callback"}'
        """.trimIndent()
    }
    val healthCurl = remember(sampleBaseUrl, curlToken) {
        "curl -H \"Authorization: Bearer $curlToken\" \"$sampleBaseUrl/api/health\""
    }
    val intentOverview = remember(context) {
        context.getString(
            R.string.external_http_chat_intent_overview,
            EXTERNAL_CHAT_INTENT_ACTION,
            EXTERNAL_CHAT_RESULT_ACTION
        )
    }
    val intentAdbExample = remember {
        """
adb shell am broadcast \
  -a $EXTERNAL_CHAT_INTENT_ACTION \
  --es request_id "req-001" \
  --es message "你好" \
  --ez show_floating true \
  --ez return_tool_status false \
  --es initial_mode "WINDOW" \
  --es reply_package "YOUR.APP.PACKAGE"
        """.trimIndent()
    }
    val hasBackgroundImage by
        remember { UserPreferencesManager.getInstance(context) }.useBackgroundImage.collectAsState(initial = false)
    val cardColor = settingsCardColor(hasBackgroundImage)
    val exampleContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun copyText(text: String, label: String, successMessage: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        showToast(successMessage)
    }

    fun savePort() {
        scope.launch {
            val parsedPort = portText.toIntOrNull()
            if (parsedPort == null || !ExternalHttpApiPreferences.isValidPort(parsedPort)) {
                showToast(context.getString(R.string.external_http_chat_invalid_port))
                return@launch
            }
            preferences.setPort(parsedPort)
            if (enabled) {
                AIForegroundService.ensureRunningForExternalHttp(context)
            }
            showToast(context.getString(R.string.external_http_chat_port_saved))
        }
    }

    fun saveToken() {
        scope.launch {
            if (bearerTokenText.length < 6) {
                showToast(context.getString(R.string.external_http_chat_token_length_error))
                return@launch
            }
            preferences.setBearerToken(bearerTokenText)
            showToast(context.getString(R.string.external_http_chat_token_saved))
        }
    }

    CustomScaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_enable),
                subtitle = stringResource(R.string.external_http_chat_enable_desc),
                cardColor = cardColor,
                icon = Icons.Default.PowerSettingsNew
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (enabled) {
                            stringResource(R.string.external_http_chat_service_enabled)
                        } else {
                            stringResource(R.string.external_http_chat_service_disabled)
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            scope.launch {
                                if (checked) {
                                    preferences.ensureBearerToken()
                                    preferences.setEnabled(true)
                                    AIForegroundService.ensureRunningForExternalHttp(context)
                                    showToast(context.getString(R.string.external_http_chat_service_enabled))
                                } else {
                                    preferences.setEnabled(false)
                                    AIForegroundService.stopExternalHttp(context)
                                    showToast(context.getString(R.string.external_http_chat_service_disabled))
                                }
                            }
                        }
                    )
                }
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_port),
                subtitle = stringResource(R.string.external_http_chat_port_desc),
                cardColor = cardColor,
                icon = Icons.Default.Router
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { newValue ->
                        portText = newValue.filter { it.isDigit() }.take(5)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.external_http_chat_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = ::savePort) {
                        Text(stringResource(R.string.external_http_chat_save_port))
                    }
                    if (enabled) {
                        TextButton(onClick = { AIForegroundService.ensureRunningForExternalHttp(context) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text(stringResource(R.string.external_http_chat_restart_service))
                        }
                    }
                }
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_token),
                subtitle = stringResource(R.string.external_http_chat_token_desc),
                cardColor = cardColor,
                icon = Icons.Default.Key
            ) {
                OutlinedTextField(
                    value = bearerTokenText,
                    onValueChange = { bearerTokenText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.external_http_chat_token)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = ::saveToken) {
                        Text(stringResource(R.string.external_http_chat_save_token))
                    }
                    TextButton(
                        onClick = {
                            if (bearerToken.isBlank()) {
                                showToast(context.getString(R.string.external_http_chat_token_not_generated))
                            } else {
                                copyText(
                                    text = bearerToken,
                                    label = "external-http-bearer-token",
                                    successMessage = context.getString(R.string.external_http_chat_token_copied)
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Text(stringResource(R.string.external_http_chat_copy_token))
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val newToken = preferences.resetBearerToken()
                                copyText(
                                    text = newToken,
                                    label = "external-http-bearer-token",
                                    successMessage = context.getString(R.string.external_http_chat_token_reset)
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(stringResource(R.string.external_http_chat_reset_token))
                    }
                }
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_status),
                subtitle = null,
                cardColor = cardColor,
                icon = Icons.Default.SettingsEthernet
            ) {
                val statusText = when {
                    serviceState.isRunning -> stringResource(
                        R.string.external_http_chat_status_running,
                        serviceState.port ?: savedPort
                    )
                    !serviceState.lastError.isNullOrBlank() -> stringResource(
                        R.string.external_http_chat_status_error,
                        serviceState.lastError ?: ""
                    )
                    else -> stringResource(R.string.external_http_chat_status_stopped)
                }
                Text(text = statusText, style = MaterialTheme.typography.bodyLarge)
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_web_title),
                subtitle = stringResource(R.string.external_http_chat_web_desc),
                cardColor = cardColor,
                icon = Icons.Default.Language
            ) {
                SelectionContainer {
                    Text(
                        text = webEntryUrl,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Text(
                    text = stringResource(R.string.external_http_chat_web_note, webApiBaseUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        copyText(
                            text = webEntryUrl,
                            label = "external-http-web-url",
                            successMessage = context.getString(R.string.external_http_chat_web_url_copied)
                        )
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.external_http_chat_copy_web_url))
                }
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_access_urls),
                subtitle = null,
                cardColor = cardColor,
                icon = Icons.Default.Lan
            ) {
                Text(
                    text = stringResource(
                        R.string.external_http_chat_bind_hint,
                        serviceState.port ?: savedPort
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (accessUrls.isEmpty()) {
                    Text(
                        text = stringResource(R.string.external_http_chat_no_lan_ip),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    accessUrls.forEach { url ->
                        SelectionContainer {
                            Text(text = url, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_sync_example),
                subtitle = null,
                cardColor = cardColor,
                icon = Icons.Default.Terminal
            ) {
                ExampleBlock(syncCurl, exampleContainerColor)
                TextButton(
                    onClick = {
                        copyText(
                            text = syncCurl,
                            label = "external-http-sync-curl",
                            successMessage = context.getString(R.string.external_http_chat_example_copied)
                        )
                    }
                ) {
                    Text(stringResource(R.string.external_http_chat_copy_example))
                }
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_async_example),
                subtitle = null,
                cardColor = cardColor,
                icon = Icons.Default.Terminal
            ) {
                ExampleBlock(asyncCurl, exampleContainerColor)
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_health_example),
                subtitle = null,
                cardColor = cardColor,
                icon = Icons.Default.Terminal
            ) {
                ExampleBlock(healthCurl, exampleContainerColor)
            }

            HttpSettingsCard(
                title = stringResource(R.string.external_http_chat_intent_title),
                subtitle = stringResource(R.string.external_http_chat_intent_desc),
                cardColor = cardColor,
                icon = Icons.Default.Android
            ) {
                SelectionContainer {
                    Text(
                        text = intentOverview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                ExampleBlock(intentAdbExample, exampleContainerColor)
                TextButton(
                    onClick = {
                        copyText(
                            text = intentAdbExample,
                            label = "external-intent-chat-adb",
                            successMessage = context.getString(R.string.external_http_chat_example_copied)
                        )
                    }
                ) {
                    Text(stringResource(R.string.external_http_chat_copy_example))
                }
            }
        }
    }
}

private const val EXTERNAL_CHAT_INTENT_ACTION = "com.ai.assistance.operit.EXTERNAL_CHAT"
private const val EXTERNAL_CHAT_RESULT_ACTION = "com.ai.assistance.operit.EXTERNAL_CHAT_RESULT"

@Composable
private fun HttpSettingsCard(
    title: String,
    subtitle: String?,
    cardColor: Color,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().settingsSearchTarget(title),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsIconBadge(icon = icon, size = 36)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun ExampleBlock(
    text: String,
    containerColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}
