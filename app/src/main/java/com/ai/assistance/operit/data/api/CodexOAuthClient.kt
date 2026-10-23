package com.ai.assistance.operit.data.api

import android.net.Uri
import android.util.Base64
import com.ai.assistance.operit.BuildConfig
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class CodexPkceCodes(
    val verifier: String,
    val challenge: String,
)

data class CodexJwtClaims(
    val accountId: String?,
    val residency: String?,
    val email: String?,
    val expiresAtMillis: Long?,
)

data class CodexOAuthTokenResponse(
    val idToken: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
) {
    fun requireComplete(): CodexOAuthTokenResponse {
        require(!idToken.isNullOrBlank()) { "Codex OAuth response has no ID token" }
        require(!accessToken.isNullOrBlank()) { "Codex OAuth response has no access token" }
        require(!refreshToken.isNullOrBlank()) { "Codex OAuth response has no refresh token" }
        return this
    }
}

data class CodexDeviceCode(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Long,
    val verificationUrl: String,
)

class CodexDeviceCodeDisabledException : IOException("Codex device code authorization is disabled")

object CodexOAuthProtocol {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val ISSUER = "https://auth.openai.com"
    const val CODEX_RESPONSES_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
    const val CODEX_MODELS_ENDPOINT = "https://chatgpt.com/backend-api/codex/models"
    const val CODEX_USAGE_ENDPOINT = "https://chatgpt.com/backend-api/wham/usage"
    const val MODEL_CATALOG_ENDPOINT = "https://models.opencode.ai/api.json"
    const val PRIMARY_CALLBACK_PORT = 1455
    const val CALLBACK_PATH = "/auth/callback"
    const val OAUTH_TIMEOUT_MILLIS = 5 * 60 * 1000L

    fun generatePkce(random: SecureRandom = SecureRandom()): CodexPkceCodes {
        val verifierBytes = ByteArray(64)
        random.nextBytes(verifierBytes)
        val verifier = encodeBase64Url(verifierBytes)
        val challenge = encodeBase64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
        return CodexPkceCodes(verifier = verifier, challenge = challenge)
    }

    fun generateState(random: SecureRandom = SecureRandom()): String {
        val stateBytes = ByteArray(32)
        random.nextBytes(stateBytes)
        return encodeBase64Url(stateBytes)
    }

    fun buildAuthorizationUrl(
        redirectUri: String,
        pkce: CodexPkceCodes,
        state: String,
    ): String {
        return Uri.parse("$ISSUER/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter(
                "scope",
                "openid profile email offline_access",
            )
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", state)
            .appendQueryParameter("originator", "operit")
            .build()
            .toString()
    }

    fun parseJwtClaims(token: String): CodexJwtClaims? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[1].isBlank()) {
            return null
        }

        val payload = try {
            Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val json = try {
            JSONObject(String(payload, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            return null
        }

        val authClaims = json.optJSONObject("https://api.openai.com/auth")
        val accountId = firstNonBlank(
            json.optString("chatgpt_account_id", ""),
            authClaims?.optString("chatgpt_account_id", "").orEmpty(),
            firstOrganizationId(json.optJSONArray("organizations")),
        )
        val residency = firstNonBlank(
            json.optString("chatgpt_compute_residency", ""),
            authClaims?.optString("chatgpt_compute_residency", "").orEmpty(),
        )?.takeUnless { it == "no_constraint" }
        val email = firstNonBlank(
            json.optString("email", ""),
            json.optJSONObject("https://api.openai.com/profile")?.optString("email", "").orEmpty(),
        )
        val expiresAtMillis = json.optLong("exp", -1L)
            .takeIf { it > 0L }
            ?.times(1000L)

        return CodexJwtClaims(
            accountId = accountId,
            residency = residency,
            email = email,
            expiresAtMillis = expiresAtMillis,
        )
    }

    private fun firstOrganizationId(organizations: JSONArray?): String {
        return organizations?.optJSONObject(0)?.optString("id", "").orEmpty()
    }

    private fun firstNonBlank(vararg values: String): String? {
        return values.firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun encodeBase64Url(bytes: ByteArray): String {
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}

class CodexOAuthClient(
    private val client: OkHttpClient,
    private val issuer: String = CodexOAuthProtocol.ISSUER,
) {
    suspend fun requestDeviceCode(): CodexDeviceCode {
        val request = Request.Builder()
            .url(endpoint("/api/accounts/deviceauth/usercode"))
            .header("User-Agent", "Operit/${BuildConfig.VERSION_NAME}")
            .post(
                JSONObject().put("client_id", CodexOAuthProtocol.CLIENT_ID).toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .build()
        val response = executeRawRequest(request)
        if (response.code == 404) throw CodexDeviceCodeDisabledException()
        if (response.code !in 200..299) {
            throw IOException("Codex device code request failed with HTTP ${response.code}: ${oauthErrorMessage(response.body)}")
        }
        val body = response.body
        val json = JSONObject(body)
        val deviceAuthId = json.optString("device_auth_id").trim()
        val userCode = json.optString("user_code").trim()
        if (deviceAuthId.isBlank() || userCode.isBlank()) {
            throw IOException("Codex device code response is invalid")
        }
        return CodexDeviceCode(
            deviceAuthId = deviceAuthId,
            userCode = userCode,
            intervalSeconds = json.optString("interval").toLongOrNull()?.coerceIn(1L, 60L) ?: 5L,
            verificationUrl = endpoint("/codex/device"),
        )
    }

    /** Returns null while the user has not yet completed authorization. */
    suspend fun pollDeviceCode(code: CodexDeviceCode): CodexOAuthTokenResponse? {
        val request = Request.Builder()
            .url(endpoint("/api/accounts/deviceauth/token"))
            .header("User-Agent", "Operit/${BuildConfig.VERSION_NAME}")
            .post(
                JSONObject()
                    .put("device_auth_id", code.deviceAuthId)
                    .put("user_code", code.userCode)
                    .toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .build()
        val response = executeRawRequest(request)
        if (response.code == 403 || response.code == 404) return null
        if (response.code !in 200..299) {
            throw IOException("Codex device authorization failed with HTTP ${response.code}: ${oauthErrorMessage(response.body)}")
        }
        val json = JSONObject(response.body)
        val authorizationCode = json.optString("authorization_code").trim()
        val verifier = json.optString("code_verifier").trim()
        if (authorizationCode.isBlank() || verifier.isBlank()) {
            throw IOException("Codex device authorization response is invalid")
        }
        return exchangeAuthorizationCode(
            code = authorizationCode,
            redirectUri = endpoint("/deviceauth/callback"),
            verifier = verifier,
        )
    }

    suspend fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        verifier: String,
    ): CodexOAuthTokenResponse {
        val body = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to CodexOAuthProtocol.CLIENT_ID,
            "code_verifier" to verifier,
        ).toFormBody()
        return executeTokenRequest(
            Request.Builder()
                .url(endpoint("/oauth/token"))
                .post(body)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()
        ).requireComplete()
    }

    suspend fun refreshAccessToken(refreshToken: String): CodexOAuthTokenResponse {
        val body = listOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to CodexOAuthProtocol.CLIENT_ID,
        ).toFormBody()
        val response = executeRawRequest(Request.Builder()
            .url(endpoint("/oauth/token"))
            .post(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build())
        com.ai.assistance.operit.util.AppLogger.i("CodexAuthManager", "refresh HTTP status=${response.code}")
        if (response.code !in 200..299) {
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            val error = json?.opt("error")
            val code = if (error is JSONObject) error.optString("code") else error as? String
            throw CodexRefreshRejectedException(response.code, response.code in setOf(400, 401) &&
                code in setOf("invalid_grant", "refresh_token_expired", "refresh_token_reused", "refresh_token_revoked"))
        }
        return parseTokenResponse(response.body)
    }

    suspend fun revokeToken(token: String, tokenTypeHint: String) {
        val body = listOf(
            "token" to token,
            "token_type_hint" to tokenTypeHint,
            "client_id" to CodexOAuthProtocol.CLIENT_ID,
        ).toFormBody()
        executeRequest(
            Request.Builder()
                .url(endpoint("/oauth/revoke"))
                .post(body)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()
        )
    }

    private suspend fun executeTokenRequest(request: Request): CodexOAuthTokenResponse {
        val responseBody = executeRequest(request)
        return parseTokenResponse(responseBody)
    }

    private fun parseTokenResponse(responseBody: String): CodexOAuthTokenResponse {
        val json = JSONObject(responseBody)
        return CodexOAuthTokenResponse(
            idToken = json.optString("id_token", "").takeIf { it.isNotBlank() },
            accessToken = json.optString("access_token", "").takeIf { it.isNotBlank() },
            refreshToken = json.optString("refresh_token", "").takeIf { it.isNotBlank() },
            expiresInSeconds = json.optLong("expires_in", -1L).takeIf { it > 0L },
        )
    }

    private suspend fun executeRequest(request: Request): String {
        val response = executeRawRequest(request)
        if (response.code !in 200..299) {
            throw IOException(
                "Codex OAuth request failed with HTTP ${response.code}: " +
                    oauthErrorMessage(response.body),
            )
        }
        return response.body
    }

    private data class OAuthHttpResponse(val code: Int, val body: String)

    private suspend fun executeRawRequest(request: Request): OAuthHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val result = OAuthHttpResponse(it.code, it.body?.string().orEmpty())
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    private fun endpoint(path: String): String = issuer.trimEnd('/') + path

    private fun oauthErrorMessage(body: String): String {
        if (body.isBlank()) {
            return "empty response"
        }
        return try {
            val json = JSONObject(body)
            json.optString("error_description", "")
                .ifBlank { json.optString("error", "") }
                .ifBlank { "invalid response" }
        } catch (_: Exception) {
            "invalid response"
        }
    }

    private fun List<Pair<String, String>>.toFormBody(): okhttp3.RequestBody {
        val encoded = joinToString("&") { (key, value) ->
            java.net.URLEncoder.encode(key, StandardCharsets.UTF_8.name()) + "=" +
                java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }
        return encoded.toRequestBody("application/x-www-form-urlencoded".toMediaType())
    }
}
