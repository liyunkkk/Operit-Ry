package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.api.CodexAuthenticationException
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercises the real shared chat retry loop used by Codex, without Android credential storage. */
class CodexAuthenticationRetryTest {
    @Test fun expiredTokenRebuildsRequestEvenWhenNetworkRetriesAreDisabled() = runBlocking {
        checkScenario(listOf(401, 200), enableRetry = false, failRefresh = false)
    }

    @Test fun secondUnauthorizedStopsWithoutGenericRetries() = runBlocking {
        checkScenario(listOf(401, 401), enableRetry = true, failRefresh = false)
    }

    @Test fun failedRefreshStopsWithoutGenericRetries() = runBlocking {
        checkScenario(listOf(401), enableRetry = true, failRefresh = true)
    }

    private suspend fun checkScenario(statuses: List<Int>, enableRetry: Boolean, failRefresh: Boolean) =
        withContext(Dispatchers.IO) {
            Mockito.mockStatic(AppLogger::class.java).use {
                StreamLogger.setEnabled(false)
                try {
                    val authorizations = mutableListOf<String?>()
                    var refreshes = 0
                    var token = "fake-old"
                    val http = OkHttpClient.Builder().addInterceptor { chain ->
                        authorizations += chain.request().header("Authorization")
                        val status = statuses.getOrElse(authorizations.size - 1) { 401 }
                        val body = if (status == 200)
                            """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                        else """{"error":{"code":"token_expired"}}"""
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(status).message("test")
                            .body(body.toResponseBody("application/json".toMediaType())).build()
                    }.build()
                    val keys = object : ApiKeyProvider {
                        override suspend fun getApiKey() = token
                        override suspend fun getCandidateKeyCount() = 1
                    }
                    val provider = object : OpenAIProvider(
                        apiEndpoint = "https://example.test/v1/chat/completions",
                        apiKeyProvider = keys, modelName = "test-model", client = http,
                    ) {
                        override suspend fun recoverUnauthorizedAuthentication(
                            request: Request, status: Int, body: String, recoveryAllowed: Boolean,
                        ): Boolean {
                            if (!recoveryAllowed) throw CodexAuthenticationException("rejected")
                            refreshes++
                            if (failRefresh) throw CodexAuthenticationException("temporary")
                            token = "fake-new"
                            return true
                        }
                    }
                    val output = StringBuilder()
                    val error = runCatching {
                        provider.sendMessage(
                            context = Mockito.mock(Context::class.java),
                            chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "hello")),
                            modelParameters = emptyList(), enableThinking = false, stream = false,
                            availableTools = null, preserveThinkInHistory = false,
                            onTokensUpdated = { _, _, _ -> }, onNonFatalError = {},
                            enableRetry = enableRetry,
                        ).collect { output.append(it) }
                    }.exceptionOrNull()
                    assertEquals(statuses.size, authorizations.size)
                    assertEquals(1, refreshes)
                    assertEquals("Bearer fake-old", authorizations.first())
                    if (statuses.size == 2) assertEquals("Bearer fake-new", authorizations.last())
                    if (statuses.last() == 200) {
                        assertNull(error)
                        assertTrue(output.toString().contains("ok"))
                    } else {
                        assertTrue("Expected terminal auth failure, got $error", error is CodexAuthenticationException)
                    }
                } finally {
                    StreamLogger.setEnabled(true)
                }
            }
        }
}
