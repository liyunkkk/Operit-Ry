package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.data.preferences.CodexAuthState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Authentication failures must not enter the chat provider's generic network retry loop. */
class CodexAuthenticationException(message: String) : IOException(message)

internal data class CodexAuthLease(val state: CodexAuthState, val generation: Long)

internal class CodexHttpException(val status: Int, val expiredToken: Boolean) :
    IOException("Codex HTTP $status")

internal fun isCodexExpiredToken(status: Int, body: String): Boolean =
    status == 401 && runCatching {
        JSONObject(body).optJSONObject("error")?.optString("code") == "token_expired"
    }.getOrDefault(false)

internal class CodexRefreshRejectedException(val status: Int, val requiresLogin: Boolean) :
    IOException("Codex OAuth refresh HTTP $status")

internal data class CodexAuthMessages(
    val notLoggedIn: String,
    val loginChanged: String,
    val loginRequired: String,
    val temporaryFailure: String,
    val recoveryRejected: String,
)

/** Shares one lock for refresh, login and logout. Leases bind retries to a login session, not just an account ID. */
internal class CodexAuthRecovery(
    private val read: () -> CodexAuthState?,
    private val refreshAndSave: suspend (CodexAuthState) -> CodexAuthState,
    private val messages: CodexAuthMessages,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var generation = 0L
    private var rejectedRefreshToken: String? = null

    suspend fun <T> changeLogin(change: suspend () -> T): T = mutex.withLock {
        // Invalidate outstanding requests even when a logout's remote revocation fails.
        generation++
        rejectedRefreshToken = null
        change()
    }

    suspend fun credentials(): CodexAuthLease = mutex.withLock {
        val state = current()
        val valid = if (state.expiresAtMillis - clock() > 300_000) state
            else refresh(state, "expiry_window")
        CodexAuthLease(valid, generation)
    }

    suspend fun ensureCurrent(lease: CodexAuthLease) = mutex.withLock {
        checkSession(lease, current())
    }

    suspend fun afterUnauthorized(failed: CodexAuthLease): CodexAuthLease = mutex.withLock {
        val state = current()
        checkSession(failed, state)
        if (state.accessToken != failed.state.accessToken) {
            log("401 recovery reused concurrent refresh")
            CodexAuthLease(state, generation)
        } else {
            val refreshed = refresh(state, "http_401_token_expired")
            checkSession(failed, refreshed)
            CodexAuthLease(refreshed, generation)
        }
    }

    private fun current(): CodexAuthState =
        read() ?: throw CodexAuthenticationException(messages.notLoggedIn)

    private fun checkSession(lease: CodexAuthLease, state: CodexAuthState) {
        if (lease.generation != generation || lease.state.accountId != state.accountId) throw changedLogin()
    }

    private fun changedLogin() = CodexAuthenticationException(messages.loginChanged)

    private suspend fun refresh(state: CodexAuthState, reason: String): CodexAuthState {
        if (rejectedRefreshToken == state.refreshToken) throw loginRequired()
        log("refresh start reason=$reason remainingSeconds=${(state.expiresAtMillis - clock()) / 1000}")
        try {
            val refreshed = persistCodexRefreshBeforeCancellation { refreshAndSave(state) }
            log("refresh succeeded remainingSeconds=${(refreshed.expiresAtMillis - clock()) / 1000}")
            return refreshed
        } catch (error: CancellationException) {
            throw error
        } catch (error: CodexRefreshRejectedException) {
            log("refresh failed httpStatus=${error.status} requiresLogin=${error.requiresLogin}")
            if (error.requiresLogin) {
                rejectedRefreshToken = state.refreshToken
                throw loginRequired()
            }
            throw CodexAuthenticationException(messages.temporaryFailure)
        } catch (_: Exception) {
            // Do not copy response bodies, token values or arbitrary exception messages into logs.
            log("refresh failed reason=network_or_invalid_response")
            throw CodexAuthenticationException(messages.temporaryFailure)
        }
    }

    private fun loginRequired() = CodexAuthenticationException(messages.loginRequired)
}

/** One recovery per logical operation, including callers that have their own network retries. */
internal class CodexRecoveryAttempt(private val rejectedMessage: String) {
    var used: Boolean = false
        private set

    suspend fun recover(lease: CodexAuthLease, refresh: suspend (CodexAuthLease) -> CodexAuthLease): CodexAuthLease {
        if (used) throw CodexAuthenticationException(rejectedMessage)
        used = true
        return refresh(lease)
    }
}
