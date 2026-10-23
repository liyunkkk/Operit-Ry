package com.ai.assistance.operit.data.api

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.CodexAuthPreferences
import com.ai.assistance.operit.data.preferences.CodexAuthState
import com.ai.assistance.operit.data.preferences.CodexStoredUsageSnapshot
import com.ai.assistance.operit.data.preferences.CodexUsagePreferences
import com.ai.assistance.operit.util.AppLogger
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class CodexAuthManager private constructor(context: Context) {
    private val preferences = CodexAuthPreferences.getInstance(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val oauthClient = CodexOAuthClient(client = httpClient)
    private val usageClient = CodexUsageClient(client = httpClient)
    private val usagePreferences = CodexUsagePreferences.getInstance(context)
    internal val authMessages = CodexAuthMessages(
        context.getString(R.string.codex_auth_sign_in_required),
        context.getString(R.string.codex_auth_login_changed),
        context.getString(R.string.codex_auth_login_required),
        context.getString(R.string.codex_auth_refresh_temporary),
        context.getString(R.string.codex_auth_recovery_rejected),
    )
    private val recovery = CodexAuthRecovery(
        read = preferences::currentState,
        refreshAndSave = ::refreshAccessToken,
        messages = authMessages,
        log = { AppLogger.i(TAG, it) },
    )

    val authState: StateFlow<CodexAuthState?> = preferences.authState
    val usageSnapshotFlow: Flow<CodexStoredUsageSnapshot?> = usagePreferences.snapshotFlow

    suspend fun saveLoginTokens(tokens: CodexOAuthTokenResponse): CodexAuthState = recovery.changeLogin {
        val completeTokens = tokens.requireComplete()
        val accessToken = requireNotNull(completeTokens.accessToken)
        val idToken = requireNotNull(completeTokens.idToken)
        val refreshToken = requireNotNull(completeTokens.refreshToken)
        val accessClaims = CodexOAuthProtocol.parseJwtClaims(accessToken)
            ?: throw IOException("Codex access token is not a valid JWT")
        val idClaims = CodexOAuthProtocol.parseJwtClaims(idToken)
        val accountId = idClaims?.accountId ?: accessClaims.accountId
            ?: throw IOException("Codex token has no ChatGPT account ID")
        val expiresAt = accessClaims.expiresAtMillis
            ?: completeTokens.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }
            ?: throw IOException("Codex access token has no expiration")
        val state = CodexAuthState(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = expiresAt,
            accountId = accountId,
            residency = accessClaims.residency ?: idClaims?.residency,
            email = idClaims?.email ?: accessClaims.email,
        )
        preferences.save(state)
        state
    }

    suspend fun getValidAccessToken(): String = recovery.credentials().state.accessToken

    internal suspend fun credentials(): CodexAuthLease = recovery.credentials()
    internal suspend fun ensureCurrent(lease: CodexAuthLease) = recovery.ensureCurrent(lease)
    internal suspend fun refreshAfterUnauthorized(lease: CodexAuthLease): CodexAuthLease =
        recovery.afterUnauthorized(lease)

    internal suspend fun <T> withAuthRecovery(block: suspend (CodexAuthState) -> T): T {
        var lease = credentials()
        val attempt = CodexRecoveryAttempt(authMessages.recoveryRejected)
        while (true) {
            ensureCurrent(lease)
            try {
                val result = block(lease.state)
                ensureCurrent(lease)
                return result
            } catch (error: CodexHttpException) {
                if (error.status != 401) throw error
                if (!error.expiredToken) throw CodexAuthenticationException(authMessages.loginRequired)
                lease = attempt.recover(lease, ::refreshAfterUnauthorized)
            }
        }
    }

    private suspend fun refreshAccessToken(current: CodexAuthState): CodexAuthState {
        val response = oauthClient.refreshAccessToken(current.refreshToken)
        val accessToken = response.accessToken
            ?: throw IOException("Codex refresh response has no access token")
        val accessClaims = CodexOAuthProtocol.parseJwtClaims(accessToken)
            ?: throw IOException("Codex refreshed access token is not a valid JWT")
        val idClaims = response.idToken?.let(CodexOAuthProtocol::parseJwtClaims)
        val accountId = idClaims?.accountId ?: accessClaims.accountId ?: current.accountId
        val expiresAt = accessClaims.expiresAtMillis
            ?: response.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }
            ?: throw IOException("Codex refreshed access token has no expiration")
        val updated = current.copy(
            accessToken = accessToken,
            refreshToken = response.refreshToken ?: current.refreshToken,
            expiresAtMillis = expiresAt,
            accountId = accountId,
            residency = accessClaims.residency ?: idClaims?.residency ?: current.residency,
            email = idClaims?.email ?: accessClaims.email ?: current.email,
        )
        preferences.save(updated)
        return updated
    }

    suspend fun logout() = recovery.changeLogin {
        val current = preferences.currentState()
        try {
            if (current != null) {
                oauthClient.revokeToken(current.refreshToken, "refresh_token")
            }
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to revoke Codex OAuth token", error)
        } finally {
            preferences.clear()
        }
    }

    fun currentAccountId(): String? = preferences.currentState()?.accountId

    fun currentResidency(): String? = preferences.currentState()?.residency

    fun accountForAccessToken(accessToken: String): CodexAuthState =
        preferences.currentState()?.takeIf { it.accessToken == accessToken }
            ?: throw IOException("Codex login changed; retry with the current account")

    suspend fun fetchUsage(): Result<CodexUsageSnapshot> {
        return try {
            val (accountId, result) = withAuthRecovery { account ->
                account.accountId to usageClient.fetch(account.accessToken, account.accountId, account.residency).getOrThrow()
            }
            usagePreferences.save(accountId, result)
            Result.success(result)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to prepare Codex usage request", error)
            Result.failure(error)
        }
    }

    companion object {
        private const val TAG = "CodexAuthManager"

        @Volatile
        private var instance: CodexAuthManager? = null

        fun getInstance(context: Context): CodexAuthManager {
            return instance ?: synchronized(this) {
                instance ?: CodexAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

/** A rotated refresh token must be saved even if the caller stops the chat during the request. */
internal suspend fun <T> persistCodexRefreshBeforeCancellation(refreshAndSave: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    val result = withContext(NonCancellable) { refreshAndSave() }
    currentCoroutineContext().ensureActive()
    return result
}
