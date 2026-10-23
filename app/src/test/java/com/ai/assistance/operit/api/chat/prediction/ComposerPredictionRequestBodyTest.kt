package com.ai.assistance.operit.api.chat.prediction

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.OpenAIProvider
import com.ai.assistance.operit.api.chat.llmprovider.CodexProvider
import com.ai.assistance.operit.api.chat.llmprovider.OpenCodeZenFree
import com.ai.assistance.operit.api.chat.llmprovider.OpenAIResponsesProvider
import com.ai.assistance.operit.api.chat.llmprovider.SingleApiKeyProvider
import com.ai.assistance.operit.api.chat.llmprovider.applyCallerSuppliedClaudeThinkingParameters
import com.ai.assistance.operit.api.chat.llmprovider.buildGeminiThinkingConfig
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterValueType
import com.ai.assistance.operit.data.model.ParameterCategory
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.api.CodexAuthManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.util.AppLogger
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/**
 * Exercise actual adapter serialization, including account-specific transformations, instead of
 * assuming equal input objects produce an equal request prefix. No network or account is used.
 */
class ComposerPredictionRequestBodyTest {
    @Test fun codexPredictionPreservesInstructionsToolsReasoningAndInputPrefix() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val provider = CodexProvider(
                authManager = Mockito.mock(CodexAuthManager::class.java),
                modelName = "gpt-6-astra", httpClient = OkHttpClient(), enableToolCall = true,
            )
            val (parent, prediction) = parentAndPredictionBodies(provider)
            assertEquals("Stable system instructions", prediction.getString("instructions"))
            assertEquals(parent.getString("instructions"), prediction.getString("instructions"))
            assertFalse(prediction.getBoolean("store"))
            assertTrue(prediction.getBoolean("stream"))
            assertFalse(prediction.has("max_output_tokens"))
            assertEquals(parent.getJSONArray("include").toString(), prediction.getJSONArray("include").toString())
            assertPrefix(parent, prediction, "input")
        }
    }

    @Test fun zenChatPredictionPreservesThePrefixAndReservedTools() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val provider = OpenAIProvider(
                apiEndpoint = OpenCodeZenFree.CHAT_ENDPOINT,
                apiKeyProvider = SingleApiKeyProvider(""), modelName = "mimo-v2.5-free",
                client = OkHttpClient(), providerType = ApiProviderType.OPENCODE_ZEN_FREE,
                enableToolCall = true,
            )
            val (parent, prediction) = parentAndPredictionBodies(provider, responses = false)
            assertPrefix(parent, prediction, "messages")
            val tools = prediction.getJSONArray("tools")
            assertEquals(setOf("shell", "bash", "read"), (0 until tools.length()).map {
                tools.getJSONObject(it).getJSONObject("function").getString("name")
            }.toSet())
        }
    }

    @Test fun zenResponsesPredictionPreservesThePrefixAndReservedTools() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val provider = OpenAIResponsesProvider(
                responsesApiEndpoint = OpenCodeZenFree.RESPONSES_ENDPOINT,
                apiKeyProvider = SingleApiKeyProvider(""), modelName = "muse-spark-1.3-contributor-free",
                client = OkHttpClient(), responsesProviderType = ApiProviderType.OPENCODE_ZEN_FREE,
                enableToolCall = true,
            )
            val (parent, prediction) = parentAndPredictionBodies(provider)
            assertPrefix(parent, prediction, "input")
            val tools = prediction.getJSONArray("tools")
            assertEquals(setOf("shell", "bash", "read"), (0 until tools.length()).map {
                tools.getJSONObject(it).getString("name")
            }.toSet())
        }
    }

    private fun parentAndPredictionBodies(
        provider: OpenAIProvider,
        responses: Boolean = true,
    ): Pair<JSONObject, JSONObject> {
        val history = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, "Stable system instructions"),
            PromptTurn(PromptTurnKind.USER, "Compare the two choices"),
        )
        val completed = history + PromptTurn(PromptTurnKind.ASSISTANT, "Here is a comparison")
        val parameters = if (responses) {
            listOf(nativeObject("reasoning", """{"effort":"high","summary":"auto"}"""))
        } else listOf(ModelParameter(
            "effort", "effort", "reasoning_effort", defaultValue = "high", currentValue = "high",
            isEnabled = true, valueType = ParameterValueType.STRING,
        ))
        val tools = listOf(ToolPrompt(name = "shell", description = "Run a shell command"))
        return body(provider, parameters, history, tools, true) to
            body(provider, predictionParameters(parameters), predictionHistory(completed), tools, true)
    }

    private fun assertPrefix(parent: JSONObject, prediction: JSONObject, inputKey: String) {
        val original = parent.getJSONArray(inputKey)
        val extended = prediction.getJSONArray(inputKey)
        assertEquals(original.length() + 2, extended.length())
        for (index in 0 until original.length()) {
            assertEquals(original.getJSONObject(index).toString(), extended.getJSONObject(index).toString())
        }
        assertEquals(parent.getJSONArray("tools").toString(), prediction.getJSONArray("tools").toString())
        assertEquals(parent.opt("reasoning")?.toString(), prediction.opt("reasoning")?.toString())
        assertEquals(parent.opt("reasoning_effort"), prediction.opt("reasoning_effort"))
        assertEquals(parent.getString("model"), prediction.getString("model"))
        assertTrue(prediction.getBoolean("stream"))
    }

    @Test fun nativeThinkingObjectsReachAdaptersWithTheCompletedTurnsSettings() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val provider = OpenAIResponsesProvider(
                responsesApiEndpoint = "https://example.test/v1/responses",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "o3-mini",
                client = OkHttpClient(),
            )
            val reasoning = listOf(nativeObject("reasoning", """{"effort":"high","summary":"auto"}"""))
            assertEquals(
                body(provider, reasoning).getJSONObject("reasoning").toString(),
                body(provider, predictionParameters(reasoning)).getJSONObject("reasoning").toString(),
            )
            val claude = listOf(
                nativeObject("thinking", """{"type":"enabled","budget_tokens":8192}"""),
                nativeObject("output_config", """{"effort":"high"}"""),
            )
            val originalClaude = JSONObject()
            val predictionClaude = JSONObject()
            assertTrue(applyCallerSuppliedClaudeThinkingParameters(originalClaude, claude))
            assertTrue(applyCallerSuppliedClaudeThinkingParameters(predictionClaude, predictionParameters(claude)))
            assertEquals(originalClaude.toString(), predictionClaude.toString())

            val gemini = listOf(nativeObject("thinkingConfig",
                """{"thinkingLevel":"high","includeThoughts":true}"""))
            val originalGemini = requireNotNull(buildGeminiThinkingConfig(true, gemini, "gemini-3-pro"))
            val predictionGemini = requireNotNull(buildGeminiThinkingConfig(true, predictionParameters(gemini), "gemini-3-pro"))
            assertEquals("high", predictionGemini.getString("thinkingLevel"))
            assertEquals(originalGemini.toString(), predictionGemini.toString())
        }
    }

    @Test fun carriesTheTurnsOwnOutputCapUnderTheFieldThatTurnUsed() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val chat = body(OpenAIProvider(
                apiEndpoint = "https://example.test/v1/chat/completions",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "gpt-4.1",
                client = OkHttpClient(), providerType = ApiProviderType.OPENAI, enableToolCall = true,
            ), predictionParameters(cap("max_tokens", 20_000)))
            assertEquals(20_000, chat.getInt("max_tokens"))
            assertFalse(chat.has("max_completion_tokens"))
            assertFalse(chat.has("tools"))

            // The Responses adapter is the one that renames the field, exactly as it does for chat.
            val responses = body(OpenAIResponsesProvider(
                responsesApiEndpoint = "https://example.test/v1/responses",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "o3-mini",
                client = OkHttpClient(), enableToolCall = true,
            ), predictionParameters(cap("max_tokens", 20_000)))
            assertEquals(20_000, responses.getInt("max_output_tokens"))
            assertFalse(responses.has("max_tokens"))
            assertFalse(responses.has("tools"))
        }
    }

    /**
     * A turn that set no cap must not have one invented for it: the request has to stay cap-free so
     * the provider's own default applies, exactly as it did for the completed turn.
     */
    @Test fun sendsNoCapWhenTheTurnSentNone() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val request = body(OpenAIProvider(
                apiEndpoint = "https://example.test/v1/chat/completions",
                apiKeyProvider = SingleApiKeyProvider("test-key"), modelName = "gpt-4.1",
                client = OkHttpClient(), providerType = ApiProviderType.OPENAI, enableToolCall = true,
            ), predictionParameters(emptyList()))
            assertFalse(request.has("max_tokens"))
            assertFalse(request.has("max_completion_tokens"))
        }
    }

    private fun cap(name: String, value: Int) = listOf(ModelParameter(
        id = name, name = name, apiName = name,
        defaultValue = value, currentValue = value, isEnabled = true, valueType = ParameterValueType.INT,
    ))

    private fun nativeObject(name: String, value: String) = ModelParameter(
        id = name, name = name, apiName = name,
        defaultValue = value, currentValue = value, isEnabled = true,
        valueType = ParameterValueType.OBJECT, category = ParameterCategory.GENERATION,
    )

    private fun body(
        provider: OpenAIProvider,
        parameters: List<ModelParameter<*>>,
        history: List<PromptTurn> = emptyList(),
        tools: List<ToolPrompt> = emptyList(),
        thinking: Boolean = false,
    ): JSONObject {
        val owner = if (provider is OpenAIResponsesProvider) OpenAIResponsesProvider::class.java else OpenAIProvider::class.java
        val method = owner.declaredMethods.single { it.name == "createRequestBody" && it.parameterCount == 7 }
        method.isAccessible = true
        val body = method.invoke(provider, Mockito.mock(Context::class.java), history, parameters,
            thinking, true, tools, false) as RequestBody
        val buffer = Buffer()
        body.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
}
