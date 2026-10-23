package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.data.preferences.CodexAuthState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodexAuthRecoveryTest {
    private val messages = CodexAuthMessages("absent", "changed", "login", "temporary", "rejected")
    private val initial = CodexAuthState("fake-access-old", "fake-refresh-old", 1_000_000L, "account-a")

    @Test fun concurrentExpiredResponsesRefreshOnlyOnce() = runBlocking {
        var saved = initial
        var refreshes = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auth = CodexAuthRecovery({ saved }, {
            refreshes++
            entered.complete(Unit)
            release.await()
            it.copy(accessToken = "fake-access-new", refreshToken = "fake-refresh-new").also { saved = it }
        }, messages, { 0L })
        val lease = auth.credentials()
        val first = async { auth.afterUnauthorized(lease) }
        entered.await()
        val followers = List(5) { async { auth.afterUnauthorized(lease) } }
        release.complete(Unit)
        val recovered = listOf(first).plus(followers).awaitAll()
        assertEquals(1, refreshes)
        assertTrue(recovered.all { it.state.accessToken == "fake-access-new" })
    }

    @Test fun reloginEvenToSameAccountRejectsOldRequests() = runBlocking {
        var saved = initial
        var refreshes = 0
        val auth = CodexAuthRecovery({ saved }, { refreshes++; it }, messages, { 0L })
        val old = auth.credentials()
        auth.changeLogin { saved = initial.copy(accessToken = "fake-relogin") }
        val failure = runCatching { auth.afterUnauthorized(old) }.exceptionOrNull()
        assertTrue(failure is CodexAuthenticationException)
        assertEquals("changed", failure?.message)
        assertEquals(0, refreshes)
    }

    @Test fun differentAccountRejectsOldRequests() = runBlocking {
        var saved = initial
        val auth = CodexAuthRecovery({ saved }, { it }, messages, { 0L })
        val old = auth.credentials()
        auth.changeLogin { saved = initial.copy(accountId = "account-b") }
        assertEquals("changed", runCatching { auth.ensureCurrent(old) }.exceptionOrNull()?.message)
    }

    @Test fun temporaryRefreshFailureKeepsTokensAndAllowsLaterRetry() = runBlocking {
        var attempts = 0
        val logs = mutableListOf<String>()
        val auth = CodexAuthRecovery({ initial }, {
            attempts++
            throw IOException("must-not-log-fake-token")
        }, messages, { 0L }, logs::add)
        val lease = auth.credentials()
        repeat(2) {
            assertEquals("temporary", runCatching { auth.afterUnauthorized(lease) }.exceptionOrNull()?.message)
        }
        assertEquals(2, attempts)
        assertEquals(initial, auth.credentials().state)
        assertFalse(logs.any { it.contains("fake-token") || it.contains(initial.accessToken) })
    }

    @Test fun revokedRefreshTokenIsNotRetriedByConcurrentCallers() = runBlocking {
        var attempts = 0
        val auth = CodexAuthRecovery({ initial }, {
            attempts++
            throw CodexRefreshRejectedException(400, true)
        }, messages, { 0L })
        val lease = auth.credentials()
        repeat(2) {
            assertEquals("login", runCatching { auth.afterUnauthorized(lease) }.exceptionOrNull()?.message)
        }
        assertEquals(1, attempts)
    }

    @Test fun cancellationStillSavesRotatedCredentialsAndReleasesLock() = runBlocking {
        var saved = initial
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var continued = false
        val auth = CodexAuthRecovery({ saved }, {
            entered.complete(Unit)
            release.await()
            it.copy(accessToken = "fake-rotated", refreshToken = "fake-rotated-refresh").also { saved = it }
        }, messages, { 0L })
        val lease = auth.credentials()
        val job = launch { auth.afterUnauthorized(lease); continued = true }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse(continued)
        assertEquals("fake-rotated-refresh", auth.credentials().state.refreshToken)
    }

    @Test fun eachOperationHasOneRecoveryAttemptEvenAfterFailure() = runBlocking {
        var calls = 0
        val attempt = CodexRecoveryAttempt("rejected")
        val lease = CodexAuthLease(initial, 0)
        runCatching { attempt.recover(lease) { calls++; throw IOException() } }
        assertEquals("rejected", runCatching {
            attempt.recover(lease) { calls++; it }
        }.exceptionOrNull()?.message)
        assertEquals(1, calls)
    }

    @Test fun onlyExpired401TriggersRefresh() {
        assertTrue(isCodexExpiredToken(401, """{"error":{"code":"token_expired"}}"""))
        assertFalse(isCodexExpiredToken(403, """{"error":{"code":"token_expired"}}"""))
        assertFalse(isCodexExpiredToken(401, """{"error":{"code":"account_deactivated"}}"""))
        assertFalse(isCodexExpiredToken(401, "not json"))
    }
}
