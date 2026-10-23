package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.stream.StreamLogger
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class Gemini37ProviderTest {
    @Test fun interruptedReplayKeepsSignatureButExcludesUnexecutedTool() {
        val accumulator = GeminiReplayContentAccumulator()
        accumulator.appendContent(JSONObject("""{"role":"model","parts":[
            {"text":"reasoning","thought":true,"thoughtSignature":"signature"},
            {"functionCall":{"name":"terminal","args":{"command":"never executed"}}},
            {"text":"after the tool"}
        ]}"""))
        val retained = requireNotNull(accumulator.buildInterruptedContentOrNull()).getJSONArray("parts")
        assertEquals(1, retained.length())
        assertEquals("signature", retained.getJSONObject(0).getString("thoughtSignature"))
    }

    @Test fun mailboxBoundaryRequiresFinishedReasoningAndNoPriorTool() {
        val accumulator = GeminiReplayContentAccumulator()
        accumulator.appendContent(JSONObject("""{"parts":[{"text":"thinking","thought":true}]}"""))
        assertFalse(accumulator.isReasoningBoundaryBefore(
            JSONObject("""{"parts":[{"text":"still thinking","thought":true}]}""")
        ))
        val tool = JSONObject("""{"parts":[{"functionCall":{"name":"tool","args":{}}}]}""")
        assertTrue(accumulator.isReasoningBoundaryBefore(tool))
        accumulator.appendContent(tool)
        assertFalse(accumulator.isReasoningBoundaryBefore(JSONObject("""{"parts":[{"text":"later"}]}""")))
    }

    @Test
    fun providerDefaults_useStableGemini37AndLeaveGenericConfigurationExplicit() {
        assertEquals(
            GEMINI_37_FLASH_MODEL,
            ApiProviderConfigs.getDefaultModelName(ApiProviderType.GOOGLE),
        )
        assertEquals(
            "",
            ApiProviderConfigs.getDefaultModelName(ApiProviderType.GEMINI_GENERIC),
        )
        assertEquals(
            "",
            ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.GEMINI_GENERIC),
        )
        assertFalse(
            ApiProviderConfigs.requiresApiKey(
                ApiProviderType.GEMINI_GENERIC,
                "https://proxy.example/gemini",
            )
        )
    }

    @Test
    fun generateContentUrl_preservesCustomOriginPrefixAndStreamingQuery() {
        val url =
            buildGeminiGenerateContentUrl(
                apiEndpoint =
                    "https://proxy.example/google/v1beta/models/old:generateContent" +
                        "?tenant=ry&alt=json&key=old-key#",
                modelName = "models/gemini-3.7-flash",
                streaming = true,
                apiKey = "new-key",
            )

        assertEquals("proxy.example", url.host)
        assertEquals(
            "/google/v1beta/models/gemini-3.7-flash:streamGenerateContent",
            url.encodedPath,
        )
        assertEquals("ry", url.queryParameter("tenant"))
        assertEquals("sse", url.queryParameter("alt"))
        assertEquals("new-key", url.queryParameter("key"))
    }

    @Test
    fun generateContentUrl_supportsKeylessEndpointsWithoutCrossHostFallback() {
        val url =
            buildGeminiGenerateContentUrl(
                apiEndpoint = "http://localhost:9000/gemini",
                modelName = GEMINI_37_FLASH_MODEL,
                streaming = false,
                apiKey = "",
            )

        assertEquals("localhost", url.host)
        assertEquals(
            "/gemini/v1beta/models/gemini-3.7-flash:generateContent",
            url.encodedPath,
        )
        assertFalse(url.queryParameterNames.contains("key"))
        assertFalse(url.queryParameterNames.contains("alt"))
    }

    @Test
    fun modelsListUrl_staysOnConfiguredOriginAndRemovesOperationOnlyQueryFields() {
        val url =
            buildGeminiModelsListUrl(
                "https://proxy.example/google/v1beta/models/old:generateContent" +
                    "?tenant=ry&key=endpoint-key&alt=sse&pageToken=stale"
            )

        assertEquals("proxy.example", url.host)
        assertEquals("/google/v1beta/models", url.encodedPath)
        assertEquals("ry", url.queryParameter("tenant"))
        assertEquals("endpoint-key", url.queryParameter("key"))
        assertFalse(url.queryParameterNames.contains("alt"))
        assertFalse(url.queryParameterNames.contains("pageToken"))
    }

    @Test
    fun gemini37ThinkingConfig_usesSupportedLevelsAndLowestLevelWhenDisabled() {
        val generatedHigh = stringParameter("thinking_level", "high")
        val nativeMedium =
            objectParameter(
                "thinkingConfig",
                """{"thinkingLevel":"medium","thinkingBudget":4096}""",
                ParameterCategory.GENERATION,
            )

        val disabledGenerated =
            buildGeminiThinkingConfig(
                enableThinking = false,
                modelParameters = listOf(generatedHigh),
                modelName = GEMINI_37_FLASH_MODEL,
            )!!
        val disabledNative =
            buildGeminiThinkingConfig(
                enableThinking = false,
                modelParameters = listOf(nativeMedium),
                modelName = GEMINI_37_FLASH_MODEL,
            )!!
        val enabledMinimal =
            buildGeminiThinkingConfig(
                enableThinking = true,
                modelParameters = listOf(stringParameter("thinking_level", "minimal")),
                modelName = GEMINI_37_FLASH_MODEL,
            )!!
        val legacyCamelCase =
            buildGeminiThinkingConfig(
                enableThinking = true,
                modelParameters =
                    listOf(
                        intParameter("thinkingBudget", 4096),
                        stringParameter("thinkingLevel", "high"),
                    ),
                modelName = "gemini-2.5-flash",
            )!!

        assertFalse(disabledGenerated.getBoolean("includeThoughts"))
        assertEquals("low", disabledGenerated.getString("thinkingLevel"))
        assertEquals("low", disabledNative.getString("thinkingLevel"))
        assertEquals("low", enabledMinimal.getString("thinkingLevel"))
        assertFalse(enabledMinimal.has("thinkingBudget"))
        assertEquals(4096, legacyCamelCase.getInt("thinkingBudget"))
        assertEquals("high", legacyCamelCase.getString("thinkingLevel"))
    }

    @Test
    fun gemini37Request_omitsUnsupportedSamplingBudgetAndCandidateCount() = runBlocking {
        val capturedRequest = AtomicReference<Request>()
        val capturedBody = AtomicReference<JSONObject>()
        val provider =
            GeminiProvider(
                apiEndpoint = "https://proxy.example/google/v1beta/models",
                apiKeyProvider = SingleApiKeyProvider("test-key"),
                modelName = GEMINI_37_FLASH_MODEL,
                client =
                    clientForGemini { request, body ->
                        capturedRequest.set(request)
                        capturedBody.set(body)
                    },
                customHeaders = mapOf("X-Provider" to "custom"),
                providerType = ApiProviderType.GOOGLE,
            )

        withoutAndroidLogging {
            provider
                .sendMessage(
                    context = mockContext(),
                    chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "hello")),
                    modelParameters =
                        listOf(
                            floatParameter("temperature", 0.2f),
                            floatParameter("top_p", 0.8f),
                            floatParameter("topP", 0.7f),
                            intParameter("top_k", 20),
                            intParameter("topK", 10),
                            intParameter("max_tokens", 4096),
                            intParameter("thinking_budget", 8192),
                            intParameter("thinkingBudget", 4096),
                            stringParameter("thinking_level", "high"),
                            stringParameter("thinkingLevel", "medium"),
                            intParameter("candidate_count", 3),
                            intParameter("candidateCount", 2),
                        ),
                    enableThinking = false,
                    stream = false,
                    availableTools = null,
                    preserveThinkInHistory = false,
                    onTokensUpdated = { _, _, _ -> },
                    onNonFatalError = {},
                    enableRetry = false,
                ).collect {}
        }

        val request = capturedRequest.get()
        val generationConfig = capturedBody.get().getJSONObject("generationConfig")
        assertNotNull(request)
        assertEquals("custom", request.header("X-Provider"))
        assertEquals("test-key", request.url.queryParameter("key"))
        assertEquals(4096, generationConfig.getInt("maxOutputTokens"))
        assertFalse(generationConfig.has("temperature"))
        assertFalse(generationConfig.has("topP"))
        assertFalse(generationConfig.has("topK"))
        assertFalse(generationConfig.has("candidateCount"))
        val thinkingConfig = generationConfig.getJSONObject("thinkingConfig")
        assertFalse(thinkingConfig.getBoolean("includeThoughts"))
        assertEquals("low", thinkingConfig.getString("thinkingLevel"))
        assertFalse(thinkingConfig.has("thinkingBudget"))
    }

    @Test
    fun combinedGoogleSearchAndFunctions_enableServerSideInvocationHistory() = runBlocking {
        val capturedBody = AtomicReference<JSONObject>()
        val provider =
            GeminiProvider(
                apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
                apiKeyProvider = SingleApiKeyProvider("test-key"),
                modelName = GEMINI_37_FLASH_MODEL,
                client = clientForGemini { _, body -> capturedBody.set(body) },
                enableGoogleSearch = true,
                enableToolCall = true,
            )

        withoutAndroidLogging {
            provider
                .sendMessage(
                    context = mockContext(),
                    chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "weather")),
                    modelParameters = emptyList(),
                    enableThinking = true,
                    stream = false,
                    availableTools =
                        listOf(
                            ToolPrompt(
                                name = "get_weather",
                                description = "Get weather",
                                parametersStructured =
                                    listOf(
                                        ToolParameterSchema(
                                            name = "city",
                                            description = "City name",
                                        )
                                    ),
                            )
                        ),
                    preserveThinkInHistory = false,
                    onTokensUpdated = { _, _, _ -> },
                    onNonFatalError = {},
                    enableRetry = false,
                ).collect {}
        }

        val requestJson = capturedBody.get()
        val tools = requestJson.getJSONArray("tools")
        assertTrue(
            (0 until tools.length()).any {
                tools.getJSONObject(it).has("function_declarations")
            }
        )
        assertTrue(
            (0 until tools.length()).any {
                tools.getJSONObject(it).has("googleSearch")
            }
        )
        assertTrue(
            requestJson
                .getJSONObject("toolConfig")
                .getBoolean("includeServerSideToolInvocations")
        )
    }

    @Test
    fun responseHistory_replaysEverySignatureAndExactFunctionCallId() = runBlocking {
        val capturedBody = AtomicReference<JSONObject>()
        val provider =
            GeminiProvider(
                apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
                apiKeyProvider = SingleApiKeyProvider("test-key"),
                modelName = GEMINI_37_FLASH_MODEL,
                client = clientForGemini { _, body -> capturedBody.set(body) },
                enableToolCall = true,
            )
        val providerResponse =
            JSONObject(
                """
                {
                  "candidates": [{
                    "content": {
                      "role": "model",
                      "parts": [
                        {"text":"","thoughtSignature":"empty-text-signature"},
                        {
                          "functionCall": {
                            "name":"get_weather",
                            "args":{"city":"Shanghai"},
                            "id":"call-123"
                          },
                          "thoughtSignature":"function-signature"
                        }
                      ]
                    },
                    "finishReason":"STOP"
                  }]
                }
                """.trimIndent()
            )
        val assistantOutput =
            withoutAndroidLogging {
                provider.extractContentFromJson(
                    context = mockContext(),
                    json = providerResponse,
                    requestId = "response-1",
                    onTokensUpdated = { _, _, _ -> },
                )
            }
        assertTrue(ChatMarkupRegex.extractGeminiContentPayloads(assistantOutput).isNotEmpty())

        withoutAndroidLogging {
            provider
                .sendMessage(
                    context = mockContext(),
                    chatHistory =
                        listOf(
                            PromptTurn(PromptTurnKind.USER, "What is the weather?"),
                            PromptTurn(PromptTurnKind.ASSISTANT, assistantOutput),
                            PromptTurn(
                                PromptTurnKind.TOOL_RESULT,
                                """
                                <tool_result name="get_weather" status="success">
                                  <content>22 C</content>
                                </tool_result>
                                """.trimIndent(),
                            ),
                        ),
                    modelParameters = emptyList(),
                    enableThinking = true,
                    stream = false,
                    availableTools = null,
                    preserveThinkInHistory = false,
                    onTokensUpdated = { _, _, _ -> },
                    onNonFatalError = {},
                    enableRetry = false,
                ).collect {}
        }

        val contents = capturedBody.get().getJSONArray("contents")
        assertEquals(3, contents.length())
        val replayedModelParts = contents.getJSONObject(1).getJSONArray("parts")
        assertEquals(2, replayedModelParts.length())
        assertEquals(
            "empty-text-signature",
            replayedModelParts.getJSONObject(0).getString("thoughtSignature"),
        )
        val replayedCallPart = replayedModelParts.getJSONObject(1)
        assertEquals(
            "function-signature",
            replayedCallPart.getString("thoughtSignature"),
        )
        assertEquals(
            "call-123",
            replayedCallPart.getJSONObject("functionCall").getString("id"),
        )

        val functionResponse =
            contents
                .getJSONObject(2)
                .getJSONArray("parts")
                .getJSONObject(0)
                .getJSONObject("functionResponse")
        assertEquals("get_weather", functionResponse.getString("name"))
        assertEquals("call-123", functionResponse.getString("id"))
    }

    @Test
    fun streamingResponse_emitsOneAggregatedReplayPayloadAfterAllChunks() = runBlocking {
        val responseBody =
            """
            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hel"}]}}]}

            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"lo"},{"text":"","thoughtSignature":"final-signature"}]}}]}

            data: [DONE]
            """.trimIndent()
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/event-stream")
                        .body(responseBody.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
                .build()
        val provider =
            GeminiProvider(
                apiEndpoint = "https://proxy.example/google/v1beta/models",
                apiKeyProvider = SingleApiKeyProvider("test-key"),
                modelName = GEMINI_37_FLASH_MODEL,
                client = client,
            )
        val output = StringBuilder()

        withoutAndroidLogging {
            provider
                .sendMessage(
                    context = mockContext(),
                    chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "hello")),
                    modelParameters = emptyList(),
                    enableThinking = true,
                    stream = true,
                    availableTools = null,
                    preserveThinkInHistory = false,
                    onTokensUpdated = { _, _, _ -> },
                    onNonFatalError = {},
                    enableRetry = false,
                ).collect { chunk -> output.append(chunk) }
        }

        assertTrue(output.startsWith("Hello"))
        val payloads = ChatMarkupRegex.extractGeminiContentPayloads(output.toString())
        assertEquals(1, payloads.size)
        val replayContent =
            JSONObject(
                String(Base64.getDecoder().decode(payloads.single()), Charsets.UTF_8)
            )
        val replayParts = replayContent.getJSONArray("parts")
        assertEquals(2, replayParts.length())
        assertEquals("Hello", replayParts.getJSONObject(0).getString("text"))
        assertEquals(
            "final-signature",
            replayParts.getJSONObject(1).getString("thoughtSignature"),
        )
    }

    @Test
    fun responseHistory_compactsInlineImageAndHydratesExactSignedPart() = runBlocking {
        val ordinary = GeminiReplayContentAccumulator()
        ordinary.appendContent(
            JSONObject(
                """{"role":"model","parts":[{"text":"ordinary response"}]}"""
            )
        )
        assertNull(ordinary.buildContentOrNull())

        val outputDirectory = Files.createTempDirectory("operit-gemini-replay").toFile()
        try {
            val imageBytes = ByteArray(512 * 1024) { index -> (index % 251).toByte() }
            val imageBase64 = Base64.getEncoder().encodeToString(imageBytes)
            val capturedBody = AtomicReference<JSONObject>()
            val provider =
                object :
                    GeminiProvider(
                        apiEndpoint = "https://proxy.example/google/v1beta/models",
                        apiKeyProvider = SingleApiKeyProvider("test-key"),
                        modelName = GEMINI_37_FLASH_MODEL,
                        client = clientForGemini { _, body -> capturedBody.set(body) },
                    ) {
                    override fun getOutputImagesDir(): File = outputDirectory

                    override fun formatOutputImageUri(file: File): String =
                        file.toURI().toString()
                }
            val providerResponse =
                JSONObject().apply {
                    put(
                        "candidates",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "content",
                                JSONObject()
                                    .put("role", "model")
                                    .put(
                                        "parts",
                                        org.json.JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put(
                                                        "inlineData",
                                                        JSONObject()
                                                            .put("mimeType", "image/png")
                                                            .put("data", imageBase64),
                                                    )
                                                    .put("thoughtSignature", "image-signature")
                                            )
                                            .put(
                                                JSONObject().put(
                                                    "toolCall",
                                                    JSONObject()
                                                        .put("name", "google_search")
                                                        .put("id", "server-call-1"),
                                                )
                                            ),
                                    ),
                            )
                        ),
                    )
                }

            val assistantOutput =
                withoutAndroidLogging {
                    provider.extractContentFromJson(
                        context = mockContext(),
                        json = providerResponse,
                        requestId = "image-response",
                        onTokensUpdated = { _, _, _ -> },
                    )
                }
            val payloads = ChatMarkupRegex.extractGeminiContentPayloads(assistantOutput)
            assertEquals(1, payloads.size)
            assertTrue(assistantOutput.length < 4_096)
            assertFalse(assistantOutput.contains(imageBase64.take(1_024)))
            val compactReplay =
                JSONObject(
                    String(Base64.getDecoder().decode(payloads.single()), Charsets.UTF_8)
                )
            val compactImagePart = compactReplay.getJSONArray("parts").getJSONObject(0)
            assertEquals("image-signature", compactImagePart.getString("thoughtSignature"))
            assertFalse(compactImagePart.getJSONObject("inlineData").has("data"))
            val storedFiles = outputDirectory.listFiles().orEmpty()
            assertEquals(1, storedFiles.size)
            assertEquals(imageBytes.size.toLong(), storedFiles.single().length())

            withoutAndroidLogging {
                provider
                    .sendMessage(
                        context = mockContext(),
                        chatHistory =
                            listOf(
                                PromptTurn(PromptTurnKind.USER, "Create an image"),
                                PromptTurn(PromptTurnKind.ASSISTANT, assistantOutput),
                                PromptTurn(PromptTurnKind.USER, "Edit that exact image"),
                            ),
                        modelParameters = emptyList(),
                        enableThinking = true,
                        stream = false,
                        availableTools = null,
                        preserveThinkInHistory = false,
                        onTokensUpdated = { _, _, _ -> },
                        onNonFatalError = {},
                        enableRetry = false,
                    ).collect {}
            }

            val replayedParts =
                capturedBody
                    .get()
                    .getJSONArray("contents")
                    .getJSONObject(1)
                    .getJSONArray("parts")
            val replayedImagePart = replayedParts.getJSONObject(0)
            assertEquals("image-signature", replayedImagePart.getString("thoughtSignature"))
            assertEquals(
                "image/png",
                replayedImagePart.getJSONObject("inlineData").getString("mimeType"),
            )
            assertArrayEquals(
                imageBytes,
                Base64.getDecoder()
                    .decode(replayedImagePart.getJSONObject("inlineData").getString("data")),
            )
            assertFalse(
                replayedImagePart.keys().asSequence().any { key -> key.startsWith("__operit") }
            )
            assertEquals(
                "server-call-1",
                replayedParts.getJSONObject(1).getJSONObject("toolCall").getString("id"),
            )
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun responseHistory_missingOrChangedInlineImageFailsImmediatelyWithoutRetry() = runBlocking {
        val outputDirectory = Files.createTempDirectory("operit-gemini-replay-invalid").toFile()
        try {
            val httpRequests = AtomicInteger()
            val retryNotices = AtomicInteger()
            val provider =
                object :
                    GeminiProvider(
                        apiEndpoint = "https://proxy.example/google/v1beta/models",
                        apiKeyProvider = SingleApiKeyProvider("test-key"),
                        modelName = GEMINI_37_FLASH_MODEL,
                        client = clientForGemini { _, _ -> httpRequests.incrementAndGet() },
                    ) {
                    override fun getOutputImagesDir(): File = outputDirectory
                }

            listOf(
                "missing.png" to "图片文件不存在",
                "changed.png" to "图片文件内容已变化",
            ).forEach { (fileName, expectedDetail) ->
                if (fileName == "changed.png") {
                    File(outputDirectory, fileName).writeBytes(byteArrayOf(1, 2, 3))
                }
                val replayContent =
                    JSONObject()
                        .put("role", "model")
                        .put(
                            "parts",
                            org.json.JSONArray().put(
                                JSONObject()
                                    .put(
                                        "inlineData",
                                        JSONObject().put("mimeType", "image/png"),
                                    )
                                    .put(
                                        "__operitGeminiInlineDataFile",
                                        JSONObject()
                                            .put("fileName", fileName)
                                            .put("sha256", "0".repeat(64)),
                                    )
                                    .put("thoughtSignature", "image-signature"),
                            ),
                        )
                val assistantOutput =
                    ChatMarkupRegex.geminiContentMetaTag(
                        Base64.getEncoder()
                            .encodeToString(replayContent.toString().toByteArray(Charsets.UTF_8))
                    )

                val thrown =
                    try {
                        withTimeout(1_500) {
                            withoutAndroidLogging {
                                provider
                                    .sendMessage(
                                        context = mockContext(),
                                        chatHistory =
                                            listOf(
                                                PromptTurn(PromptTurnKind.USER, "Create an image"),
                                                PromptTurn(
                                                    PromptTurnKind.ASSISTANT,
                                                    assistantOutput,
                                                ),
                                                PromptTurn(PromptTurnKind.USER, "Edit it"),
                                            ),
                                        modelParameters = emptyList(),
                                        enableThinking = true,
                                        stream = false,
                                        availableTools = null,
                                        preserveThinkInHistory = false,
                                        onTokensUpdated = { _, _, _ -> },
                                        onNonFatalError = { retryNotices.incrementAndGet() },
                                        enableRetry = true,
                                    ).collect {}
                            }
                        }
                        null
                    } catch (e: Exception) {
                        e
                    }

                assertTrue(thrown is GeminiProvider.ReplayMediaException)
                assertTrue(thrown?.message.orEmpty().contains(expectedDetail))
            }

            assertEquals(0, httpRequests.get())
            assertEquals(0, retryNotices.get())
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test
    fun rateLimit_withMultipleKeysStillRetriesUsingNextKey() = runBlocking {
        val keyIndex = AtomicInteger()
        val requestedKeys = mutableListOf<String>()
        val retryNotices = AtomicInteger()
        val keyProvider =
            object : ApiKeyProvider {
                private val keys = listOf("key-one", "key-two")

                override suspend fun getApiKey(): String =
                    keys[keyIndex.getAndIncrement().coerceAtMost(keys.lastIndex)]

                override suspend fun getCandidateKeyCount(): Int = keys.size
            }
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedKeys += request.url.queryParameter("key").orEmpty()
                    val isFirstAttempt = requestedKeys.size == 1
                    val body =
                        if (isFirstAttempt) {
                            """{"error":{"code":429,"message":"quota exhausted"}}"""
                        } else {
                            """
                            {
                              "candidates": [{
                                "content": {"role":"model","parts":[{"text":"ok"}]},
                                "finishReason":"STOP"
                              }],
                              "usageMetadata": {
                                "promptTokenCount":1,
                                "candidatesTokenCount":1
                              }
                            }
                            """.trimIndent()
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (isFirstAttempt) 429 else 200)
                        .message(if (isFirstAttempt) "Too Many Requests" else "OK")
                        .header("Content-Type", "application/json")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        val provider =
            GeminiProvider(
                apiEndpoint = "https://proxy.example/google/v1beta/models",
                apiKeyProvider = keyProvider,
                modelName = GEMINI_37_FLASH_MODEL,
                client = client,
            )

        withoutAndroidLogging {
            withTimeout(3_000) {
                provider
                    .sendMessage(
                        context = mockContext(),
                        chatHistory = listOf(PromptTurn(PromptTurnKind.USER, "hello")),
                        modelParameters = emptyList(),
                        enableThinking = true,
                        stream = false,
                        availableTools = null,
                        preserveThinkInHistory = false,
                        onTokensUpdated = { _, _, _ -> },
                        onNonFatalError = { retryNotices.incrementAndGet() },
                        enableRetry = true,
                    ).collect {}
            }
        }

        assertEquals(listOf("key-one", "key-two"), requestedKeys)
        assertEquals(0, retryNotices.get())
    }

    @Test
    fun modelsList_followsPaginationAndUsesCustomHeadersOnConfiguredOrigin() = runBlocking {
        val requestCount = AtomicInteger()
        val requests = mutableListOf<Request>()
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    synchronized(requests) {
                        requests += request
                    }
                    val page = requestCount.incrementAndGet()
                    val body =
                        if (page == 1) {
                            """
                            {
                              "models": [
                                {"name":"models/z-model","displayName":"Z"},
                                {"name":"models/a-model","displayName":"A"}
                              ],
                              "nextPageToken":"next-page"
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "models": [
                                {"name":"models/b-model","displayName":"B"},
                                {"name":"models/a-model","displayName":"A duplicate"}
                              ]
                            }
                            """.trimIndent()
                        }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

        val result =
            withoutAndroidLogging {
                ModelListFetcher.getModelsList(
                    context = mockContext(),
                    apiKey = "",
                    apiEndpoint = "https://proxy.example/google/v1beta/models?tenant=ry",
                    apiProviderType = ApiProviderType.GEMINI_GENERIC,
                    customHeaders = mapOf("Authorization" to "Bearer custom-token"),
                    httpClient = client,
                )
            }

        assertTrue(result.isSuccess)
        assertEquals(listOf("a-model", "b-model", "z-model"), result.getOrThrow().map { it.id })
        assertEquals(2, requestCount.get())
        assertTrue(requests.all { it.url.host == "proxy.example" })
        assertTrue(requests.all { it.url.queryParameter("tenant") == "ry" })
        assertTrue(requests.all { it.url.queryParameter("pageSize") == "1000" })
        assertFalse(requests.first().url.queryParameterNames.contains("pageToken"))
        assertEquals("next-page", requests.last().url.queryParameter("pageToken"))
        assertTrue(requests.all { !it.url.queryParameterNames.contains("key") })
        assertTrue(requests.all { it.header("Authorization") == "Bearer custom-token" })
    }

    private fun clientForGemini(
        onRequest: (Request, JSONObject) -> Unit,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                onRequest(request, JSONObject(buffer.readUtf8()))
                val body =
                    """
                    {
                      "candidates": [{
                        "content": {"role":"model","parts":[{"text":"ok"}]},
                        "finishReason":"STOP"
                      }],
                      "usageMetadata": {
                        "promptTokenCount":1,
                        "candidatesTokenCount":1
                      }
                    }
                    """.trimIndent()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun mockContext(): Context =
        Mockito.mock(Context::class.java) { invocation ->
            if (invocation.method.name == "getString") {
                "test"
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }

    private suspend fun <T> withoutAndroidLogging(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            Mockito.mockStatic(AppLogger::class.java).use {
                try {
                    StreamLogger.setEnabled(false)
                    block()
                } finally {
                    StreamLogger.setEnabled(true)
                }
            }
        }

    private fun intParameter(apiName: String, value: Int) =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.INT,
            category = ParameterCategory.OTHER,
        )

    private fun floatParameter(apiName: String, value: Float) =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.FLOAT,
            category = ParameterCategory.OTHER,
        )

    private fun stringParameter(apiName: String, value: String) =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.STRING,
            category = ParameterCategory.OTHER,
        )

    private fun objectParameter(
        apiName: String,
        value: String,
        category: ParameterCategory,
    ) =
        ModelParameter(
            id = apiName,
            name = apiName,
            apiName = apiName,
            defaultValue = value,
            currentValue = value,
            isEnabled = true,
            valueType = ParameterValueType.OBJECT,
            category = category,
        )
}
