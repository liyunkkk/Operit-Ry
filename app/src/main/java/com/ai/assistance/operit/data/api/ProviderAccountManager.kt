package com.ai.assistance.operit.data.api

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.ui.features.codex.CodexOAuthLoopbackCallbackServer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal class AccountHttpException(val status: Int) : IOException("Account request failed: HTTP $status")

enum class AccountProvider(val type: ApiProviderType, val port: Int) {
    GROK(ApiProviderType.GROK_ACCOUNT, 56121),
    COMMAND_CODE(ApiProviderType.COMMAND_CODE, 5959),
    ANTIGRAVITY(ApiProviderType.GOOGLE_ANTIGRAVITY, 51121),
    CLAUDE(ApiProviderType.CLAUDE_ACCOUNT, ClaudeOAuthProtocol.CALLBACK_PORT);

    companion object {
        fun from(type: ApiProviderType?) = entries.firstOrNull { it.type == type }
    }
}

data class ProviderAccount(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val projectId: String = "",
    val email: String = "",
    val identity: String = UUID.randomUUID().toString(),
)

internal data class ProviderLoginSession(
    val server: CodexOAuthLoopbackCallbackServer,
    val authorizationUrl: String,
    val state: String,
    val verifier: String,
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String,
)

/** Account credentials never share the API-key configuration or its export format. */
class ProviderAccountManager private constructor(context: Context, val provider: AccountProvider) {
    private val appContext = context.applicationContext
    private val storeName = "provider_oauth_${provider.name.lowercase()}"
    private fun createPreferences() = EncryptedSharedPreferences.create(
        appContext, storeName,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private var preferences = try { createPreferences() } catch (_: java.security.GeneralSecurityException) {
        // Restoring Android backup cannot restore the device-bound Keystore key.
        appContext.deleteSharedPreferences(storeName)
        createPreferences()
    }
    private val mutex = Mutex()
    private val mutableAccount = MutableStateFlow(readAccount())
    val account = mutableAccount.asStateFlow()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    private fun readAccount(): ProviderAccount? {
        return try { readStoredAccount() } catch (_: SecurityException) {
            appContext.deleteSharedPreferences(storeName)
            preferences = createPreferences()
            null
        } catch (_: JSONException) {
            preferences.edit().remove("account").apply()
            null
        }
    }

    private fun readStoredAccount(): ProviderAccount? {
        val raw = preferences.getString("account", null) ?: return null
        val json = JSONObject(raw)
        return ProviderAccount(
            json.getString("access"), json.getString("refresh"), json.getLong("expires"),
            json.optString("project"), json.optString("email"), json.getString("identity"),
        )
    }

    private fun save(value: ProviderAccount) {
        preferences.edit().putString("account", JSONObject().apply {
            put("access", value.accessToken); put("refresh", value.refreshToken)
            put("expires", value.expiresAt); put("project", value.projectId)
            put("email", value.email); put("identity", value.identity)
        }.toString()).apply()
        mutableAccount.value = value
    }

    suspend fun logout() = mutex.withLock {
        preferences.edit().remove("account").apply()
        mutableAccount.value = null
    }

    internal suspend fun startLogin(
        googleClientId: String = "",
        googleClientSecret: String = "",
    ): ProviderLoginSession {
        check(provider != AccountProvider.COMMAND_CODE) { "Use Command Code login" }
        val discovery = if (provider == AccountProvider.GROK) discoverXai() else null
        val id = when (provider) {
            AccountProvider.GROK -> XAI_CLIENT_ID
            AccountProvider.CLAUDE -> ClaudeOAuthProtocol.CLIENT_ID
            else -> googleClientId.trim().ifBlank { preferences.getString("client_id", "").orEmpty() }
                .ifBlank { AntigravityClientConfig.CLIENT_ID }
        }
        val secret = if (provider == AccountProvider.ANTIGRAVITY) {
            googleClientSecret.ifBlank { preferences.getString("client_secret", "").orEmpty() }
                .ifBlank { AntigravityClientConfig.CLIENT_SECRET }
        } else ""
        require(id.isNotBlank() && (provider != AccountProvider.ANTIGRAVITY || secret.isNotBlank())) {
            "Antigravity OAuth client ID and client secret are required"
        }
        val pkce = CodexOAuthProtocol.generatePkce()
        val state = CodexOAuthProtocol.generateState()
        // Claude's OAuth app registers the `localhost` spelling of its callback, so that is the one
        // the redirect has to advertise; the listener still binds loopback.
        val callbackHost =
            if (provider == AccountProvider.CLAUDE) ClaudeOAuthProtocol.CALLBACK_HOST else "127.0.0.1"
        val server = CodexOAuthLoopbackCallbackServer.open(provider.port, "/callback", callbackHost)
        try {
            val url = if (provider == AccountProvider.CLAUDE) {
                ClaudeOAuthProtocol.buildAuthorizationUrl(
                    redirectUri = server.redirectUri,
                    challenge = pkce.challenge,
                    state = state,
                )
            } else {
                val endpoint = discovery?.first ?: "https://accounts.google.com/o/oauth2/v2/auth"
                Uri.parse(endpoint).buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", id)
                    .appendQueryParameter("redirect_uri", server.redirectUri)
                    .appendQueryParameter("scope", if (provider == AccountProvider.GROK) XAI_SCOPE else GOOGLE_SCOPE)
                    .appendQueryParameter("code_challenge", pkce.challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("state", state)
                    .apply {
                        if (provider == AccountProvider.GROK) appendQueryParameter("nonce", UUID.randomUUID().toString())
                        else {
                            appendQueryParameter("access_type", "offline")
                            // Antigravity 的原生客户端用 `prompt=consent`；`select_account` 会让
                            // Google 返回一个 Antigravity OAuth 客户端无法处理的账号选择提示。
                            appendQueryParameter("prompt", if (provider == AccountProvider.ANTIGRAVITY) "consent" else "consent select_account")
                        }
                    }.build().toString()
            }
            val tokenEndpoint = when (provider) {
                AccountProvider.CLAUDE -> ClaudeOAuthProtocol.TOKEN_ENDPOINT
                else -> discovery?.second ?: GOOGLE_TOKEN
            }
            return ProviderLoginSession(server, url, state, pkce.verifier, tokenEndpoint, id, secret)
        } catch (error: Throwable) {
            server.close()
            throw error
        }
    }

    internal suspend fun completeLogin(session: ProviderLoginSession, callback: Uri) = mutex.withLock {
        // Claude's callback can fold the state into the code as `code#state`; the others carry it as
        // a query parameter, so their codes are taken verbatim and the state → declined → code check
        // order below stays exactly as it was.
        val callbackCode = callback.getQueryParameter("code").orEmpty()
        val callbackState = callback.getQueryParameter("state").orEmpty()
        val (code, state) =
            if (provider == AccountProvider.CLAUDE) {
                ClaudeOAuthProtocol.splitAuthorizationCode(callbackCode, callbackState)
            } else {
                callbackCode to callbackState
            }
        require(state == session.state) { "OAuth state mismatch" }
        check(callback.getQueryParameter("error") == null) { "OAuth authorization was declined" }
        require(code.isNotBlank()) { "Missing OAuth authorization code" }
        val tokens = if (provider == AccountProvider.CLAUDE) {
            requestJson(
                session.tokenEndpoint,
                ClaudeOAuthProtocol.authorizationCodeBody(
                    code = code,
                    state = state,
                    redirectUri = session.server.redirectUri,
                    verifier = session.verifier,
                ).toBody(),
            )
        } else {
            val form = tokenForm(session.clientId, session.clientSecret).apply {
                add("grant_type", "authorization_code"); add("code", code)
                add("redirect_uri", session.server.redirectUri); add("code_verifier", session.verifier)
            }.build()
            requestJson(session.tokenEndpoint, form, oauthTokenRequest = true)
        }
        val value = parseTokens(tokens)
        val complete = if (provider == AccountProvider.ANTIGRAVITY) {
            value.copy(projectId = discoverProject(value.accessToken))
        } else value
        currentCoroutineContext().ensureActive()
        preferences.edit().putString("client_id", session.clientId)
            .putString("client_secret", session.clientSecret).apply()
        save(complete)
    }

    suspend fun validAccount(): ProviderAccount = mutex.withLock {
        val current = account.value ?: throw IOException("Account is not logged in")
        if (provider == AccountProvider.COMMAND_CODE) return@withLock current
        if (current.expiresAt - System.currentTimeMillis() > 120_000) return@withLock current
        val endpoint = when (provider) {
            AccountProvider.GROK -> discoverXai().second
            AccountProvider.CLAUDE -> ClaudeOAuthProtocol.TOKEN_ENDPOINT
            else -> GOOGLE_TOKEN
        }
        val body = if (provider == AccountProvider.CLAUDE) {
            ClaudeOAuthProtocol.refreshBody(current.refreshToken).toBody()
        } else {
            tokenForm(
                if (provider == AccountProvider.GROK) XAI_CLIENT_ID
                else preferences.getString("client_id", "").orEmpty(),
                preferences.getString("client_secret", "").orEmpty(),
            ).add("grant_type", "refresh_token").add("refresh_token", current.refreshToken).build()
        }
        val updated = withContext(NonCancellable) {
            // Refresh grants can rotate. Persist the successful response even if the caller stops.
            parseTokens(requestJson(endpoint, body, oauthTokenRequest = true), current).also(::save)
        }
        currentCoroutineContext().ensureActive()
        updated
    }

    suspend fun saveCommandCodeApiKey(rawKey: String) = mutex.withLock {
        check(provider == AccountProvider.COMMAND_CODE)
        val key = rawKey.trim()
        require(key.isNotEmpty() && key.none { it.isWhitespace() || it.isISOControl() }) { "Invalid API key" }
        val user = commandCodeJson("https://api.commandcode.ai/alpha/whoami", key).optJSONObject("user")
        val id = user?.optString("id").orEmpty()
        val name = user?.optString("userName").orEmpty()
        if (id.isBlank() || name.isBlank()) throw IOException("Invalid account identity")
        currentCoroutineContext().ensureActive()
        save(ProviderAccount(key, "", Long.MAX_VALUE, email = name))
    }

    suspend fun availableCommandCodeModels(): List<com.ai.assistance.operit.data.model.ModelOption> {
        check(provider == AccountProvider.COMMAND_CODE)
        val json = commandCodeJson("https://api.commandcode.ai/provider/v1/models", validAccount().accessToken)
        val rows = json.optJSONArray("data") ?: throw IOException("Missing model catalog")
        return (0 until minOf(rows.length(), 256)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            com.ai.assistance.operit.data.model.ModelOption(id, row.optString("name").ifBlank { id })
        }.distinctBy { it.id }
    }

    /**
     * Command Code reports the same billing surface its official CLI's usage view reads: the rolling
     * 5-hour and weekly windows off `/alpha/billing/credits`, plus subscription-scoped spend for the
     * USD figure. Every sub-request soft-fails, so a partial outage still shows what did answer;
     * only the credits call is load-bearing because it carries the windows themselves.
     */
    suspend fun fetchCommandCodeQuota(): ProviderQuota {
        check(provider == AccountProvider.COMMAND_CODE)
        val bearer = validAccount().accessToken
        val orgId = commandCodeJsonOrNull(COMMAND_CODE_WHOAMI, bearer)
            ?.let { it.optJSONObject("data") ?: it }
            ?.optJSONObject("org")?.optString("id")?.trim()?.takeIf { it.isNotEmpty() }
        val orgQuery = CommandCodeQuota.orgIdQuery(orgId?.let { Uri.encode(it) })
        val credits = commandCodeJsonOrNull("$COMMAND_CODE_CREDITS$orgQuery", bearer)
            ?: throw IOException("Command Code credits are unavailable")
        val body = credits.optJSONObject("data") ?: credits
        val limits = body.optJSONObject("windowLimits")
        val windows = CommandCodeQuota.windows(limits)
        val spend = commandCodeSpend(bearer, body.optJSONObject("credits"), orgQuery)
        val quota = ProviderQuota(
            windows = windows,
            creditsUsedUsd = spend?.used,
            creditsLimitUsd = spend?.limit,
            creditsRemainingUsd = spend?.remaining,
        )
        if (quota.isEmpty) throw IOException("Command Code reported no quota")
        return quota
    }

    /**
     * Spend against the remaining credit pools. The unscoped usage summary is lifetime spend, so a
     * percentage is only truthful once a billing period start scopes the query; without one the USD
     * figure is omitted rather than computed against the wrong denominator.
     */
    private suspend fun commandCodeSpend(
        bearer: String,
        pools: JSONObject?,
        orgQuery: String,
    ): CommandCodeQuota.Spend? {
        if (pools == null) return null
        val subscription = commandCodeJsonOrNull("$COMMAND_CODE_SUBSCRIPTIONS$orgQuery", bearer)
            ?.let { it.optJSONObject("data") ?: it }
        // Trimmed, not just tested for blankness: padding must never reach the `since` query value.
        val periodStart = subscription?.optString("currentPeriodStart")?.trim()
        val summary = periodStart?.takeIf { it.isNotBlank() }?.let {
            commandCodeJsonOrNull(
                CommandCodeQuota.appendQuery(
                    "$COMMAND_CODE_USAGE_SUMMARY$orgQuery",
                    "since=${Uri.encode(it)}",
                ),
                bearer,
            )?.let { body -> body.optJSONObject("data") ?: body }
        }
        return CommandCodeQuota.spend(pools, periodStart, summary)
    }

    /** Soft-fail GET: a quota probe must never turn a provider hiccup into a thrown error path. */
    private suspend fun commandCodeJsonOrNull(url: String, key: String): JSONObject? = try {
        commandCodeJson(url, key)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun commandCodeJson(url: String, key: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("Authorization", "Bearer $key").header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                continuation.resumeWithException(error)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val json = response.use {
                        if (!it.isSuccessful) throw AccountHttpException(it.code)
                        val source = it.body?.source() ?: throw IOException("Empty account response")
                        if (source.request(262145)) throw IOException("Account response too large")
                        try { JSONObject(source.readUtf8()) }
                        catch (_: JSONException) { throw IOException("Invalid account response") }
                    }
                    continuation.resume(json)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }

    private fun tokenForm(id: String, secret: String) = FormBody.Builder().add("client_id", id).apply {
        if (secret.isNotBlank()) add("client_secret", secret)
    }

    private fun parseTokens(json: JSONObject, previous: ProviderAccount? = null): ProviderAccount {
        val access = json.optString("access_token").also { require(it.isNotBlank()) { "Missing access token" } }
        val refresh = json.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
            .also { require(it.isNotBlank()) { "Missing refresh token" } }
        val seconds = json.optLong("expires_in", 3600).also { require(it in 1..31_536_000) }
        // Claude names the subscribed account in the token body; the OIDC providers put it in the
        // id token instead.
        val email = ClaudeOAuthProtocol.accountEmail(json).ifBlank {
            CodexOAuthProtocol.parseJwtClaims(json.optString("id_token"))?.email.orEmpty()
        }
        return ProviderAccount(access, refresh, System.currentTimeMillis() + seconds * 1000,
            previous?.projectId.orEmpty(),
            email.ifBlank { previous?.email.orEmpty() },
            previous?.identity ?: UUID.randomUUID().toString())
    }

    private suspend fun discoverXai(): Pair<String, String> {
        val json = requestJson("https://auth.x.ai/.well-known/openid-configuration")
        return validateXaiEndpoint(json.getString("authorization_endpoint")) to
            validateXaiEndpoint(json.getString("token_endpoint"))
    }

    private suspend fun discoverProject(token: String): String {
        val loaded = try { requestJson("$CCA/v1internal:loadCodeAssist",
            JSONObject("""{"metadata":{"ideType":"ANTIGRAVITY"}}""").toBody(), token)
        } catch (_: AccountHttpException) { null }
        extractProject(loaded)?.let { return it }
        repeat(5) {
            val result = try {
                requestJson("https://daily-cloudcode-pa.googleapis.com/v1internal:onboardUser",
                    JSONObject().put("tier_id", "free-tier").put("metadata", JSONObject()
                        .put("ide_type", "ANTIGRAVITY").put("ide_name", "antigravity")
                        .put("ide_version", ANTIGRAVITY_UA)).toBody(), token)
            } catch (failure: AccountHttpException) {
                if (failure.status != 429 && failure.status < 500) throw failure
                delay(2000)
                return@repeat
            }
            if (result.optBoolean("done")) extractProject(result.optJSONObject("response"))?.let { return it }
            delay(2000)
        }
        throw IOException("No Cloud Code Assist project is available for this account")
    }

    suspend fun availableModels(): JSONObject {
        val value = validAccount()
        return requestJson("$CCA/v1internal:fetchAvailableModels",
            JSONObject().put("project", value.projectId).toBody(), value.accessToken)
    }

    suspend fun availableGrokModels(): List<com.ai.assistance.operit.data.model.ModelOption> {
        val value = validAccount()
        val root = requestJson("https://cli-chat-proxy.grok.com/v1/models", token = value.accessToken)
        val models = root.optJSONArray("data") ?: throw IOException("Grok model list is missing")
        return (0 until models.length()).mapNotNull { index ->
            val model = models.optJSONObject(index) ?: return@mapNotNull null
            val id = model.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // The Grok OAuth chat-completions route does not support this Responses-only model.
            if (id == "grok-4.20-multi-agent-0309") return@mapNotNull null
            com.ai.assistance.operit.data.model.ModelOption(id, model.optString("name").ifBlank { id })
        }
    }

    /**
     * Grok reports the weekly credits window that actually gates prompting, and the legacy monthly
     * dollar pool when that window is not reported. Both live behind the same account token the
     * chat transport already uses, so this never touches an API-key configuration.
     */
    suspend fun fetchGrokQuota(): ProviderQuota {
        check(provider == AccountProvider.GROK)
        val bearer = validAccount().accessToken
        GrokQuota.userIdFromAccessToken(bearer)?.let { userId ->
            val body = grokJsonOrNull(XAI_CREDITS_URL, bearer, mapOf("x-userid" to userId))
            GrokQuota.weeklyCredits(body)?.let { (percent, resetAt) ->
                return ProviderQuota(listOf(ProviderQuotaWindow(QuotaWindow.WEEKLY, percent, resetAt)))
            }
        }
        GrokQuota.monthlyDollars(grokJsonOrNull(XAI_BILLING_URL, bearer, emptyMap()))?.let {
            (percent, resetAt) ->
            return ProviderQuota(listOf(ProviderQuotaWindow(QuotaWindow.MONTHLY, percent, resetAt)))
        }
        throw IOException("Grok reported no quota")
    }

    /**
     * Claude reports subscription windows per credential, so the probe rides the account's own
     * bearer token. Only the two canonical windows are surfaced: the per-model Opus/Sonnet buckets
     * have no place in [QuotaWindow].
     */
    suspend fun fetchClaudeQuota(): ProviderQuota {
        check(provider == AccountProvider.CLAUDE)
        val bearer = validAccount().accessToken
        val body = requestJson(
            ClaudeOAuthProtocol.USAGE_ENDPOINT,
            token = bearer,
            extraHeaders =
                ClaudeOAuthProtocol.USAGE_EXTRA_HEADERS +
                    ("anthropic-beta" to ClaudeOAuthProtocol.OAUTH_USAGE_BETA),
        )
        val windows =
            listOf(
                QuotaWindow.FIVE_HOUR to body.optJSONObject("five_hour"),
                QuotaWindow.WEEKLY to body.optJSONObject("seven_day"),
            ).mapNotNull { (window, row) ->
                val percent = QuotaParsing.percent(row?.opt("utilization")) ?: return@mapNotNull null
                ProviderQuotaWindow(window, percent, QuotaParsing.resetAt(row?.opt("resets_at")))
            }
        val quota = ProviderQuota(windows = windows)
        if (quota.isEmpty) throw IOException("Claude reported no quota")
        return quota
    }

    /** Soft-fail GET: a quota probe must never turn a provider hiccup into a thrown error path. */
    private suspend fun grokJsonOrNull(
        url: String,
        bearer: String,
        extraHeaders: Map<String, String>,
    ): JSONObject? = try {
        requestJson(url, token = bearer, extraHeaders = extraHeaders)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun requestJson(
        url: String,
        body: RequestBody? = null,
        token: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        oauthTokenRequest: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json").apply {
                if (body != null) post(body)
                if (token != null) {
                    header("Authorization", "Bearer $token")
                    if (provider == AccountProvider.ANTIGRAVITY) header("User-Agent", ANTIGRAVITY_UA)
                    else if (provider == AccountProvider.GROK) GROK_HEADERS.forEach { (key, value) -> header(key, value) }
                }
                // Antigravity 的 OAuth token 交换/刷新必须伪装成原生客户端，否则上游网关能识别
                // 非官方调用者并在刷新时拒绝轮换。
                if (oauthTokenRequest) {
                    header("Host", "oauth2.googleapis.com")
                    header("User-Agent", "Go-http-client/2.0")
                }
                extraHeaders.forEach { (key, value) -> header(key, value) }
            }.build()
            client.newCall(request).execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) throw AccountHttpException(response.code)
                try { JSONObject(response.body?.string() ?: throw IOException("Empty account response")) }
                catch (_: JSONException) { throw IOException("Invalid account response") }
            }
        }

    companion object {
        // Cloud Code Assist 的多端点：daily 为原生 Antigravity 默认的每日端点，prod 为正式端点。
        // 不这么做后果：只打 prod 会让原生客户端的激活/模型发现路径拿不到 daily 独有的数据。
        const val CCA = "https://daily-cloudcode-pa.googleapis.com"
        const val CCA_PROD = "https://cloudcode-pa.googleapis.com"
        // 意图：伪装 Antigravity Hub 原生客户端版本与平台指纹。
        // 不这么做后果：上游 Cloud Code 会拒绝低于 2.9.0 的客户端访问新模型并标记异常流量。
        const val ANTIGRAVITY_UA = "antigravity/hub/2.9.1 darwin/arm64"
        val GROK_HEADERS = mapOf(
            "x-grok-client-identifier" to "operit", "x-grok-client-version" to "0.2.93",
            "x-xai-token-auth" to "xai-grok-cli", "x-authenticateresponse" to "authenticate-response",
            "User-Agent" to "Operit/${com.ai.assistance.operit.BuildConfig.VERSION_NAME}",
        )
        private const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
        private const val COMMAND_CODE_WHOAMI = "https://api.commandcode.ai/alpha/whoami"
        private const val COMMAND_CODE_CREDITS = "https://api.commandcode.ai/alpha/billing/credits"
        private const val COMMAND_CODE_SUBSCRIPTIONS = "https://api.commandcode.ai/alpha/billing/subscriptions"
        private const val COMMAND_CODE_USAGE_SUMMARY = "https://api.commandcode.ai/alpha/usage/summary"
        private const val XAI_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        private const val XAI_SCOPE = "openid profile email offline_access grok-cli:access api:access"
        private const val XAI_BILLING_URL = "https://cli-chat-proxy.grok.com/v1/billing"
        private const val XAI_CREDITS_URL = "$XAI_BILLING_URL?format=credits"
        private val GOOGLE_SCOPE = listOf("cloud-platform", "userinfo.email", "userinfo.profile",
            "cclog", "experimentsandconfigs").joinToString(" ") { "https://www.googleapis.com/auth/$it" }
        private val instances = mutableMapOf<AccountProvider, ProviderAccountManager>()
        @Synchronized fun get(context: Context, provider: AccountProvider): ProviderAccountManager =
            instances.getOrPut(provider) { ProviderAccountManager(context.applicationContext, provider) }
        internal fun validateXaiEndpoint(url: String): String {
            val uri = java.net.URI(url)
            require(uri.scheme == "https" && uri.userInfo == null &&
                (uri.host == "x.ai" || uri.host?.endsWith(".x.ai") == true)) { "Unexpected xAI OAuth endpoint" }
            return url
        }
        internal fun extractProject(json: JSONObject?): String? =
            listOf("cloudaicompanionProject", "projectId", "project").firstNotNullOfOrNull { key ->
                when (val value = json?.opt(key)) {
                    is String -> value.takeIf { it.isNotBlank() }
                    is JSONObject -> value.optString("id").takeIf { it.isNotBlank() }
                    else -> null
                }
            }
        private fun JSONObject.toBody() = toString().toRequestBody("application/json".toMediaType())
    }
}
