package com.ai.assistance.operit.api.chat.prediction

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.api.chat.llmprovider.OpenCodeSessionContext
import com.ai.assistance.operit.api.chat.llmprovider.providerSessionIdForScope
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.AbstractStream
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.util.stream.StreamLogger
import com.ai.assistance.operit.util.stream.stream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ComposerPredictionServiceTest {
    private val context = Mockito.mock(Context::class.java)
    private val config = ModelConfigData("chat-config", "Chat", modelName = "chat-model")
    private val history = listOf(
        PromptTurn(PromptTurnKind.SYSTEM, "Stable personalized prompt"),
        PromptTurn(PromptTurnKind.USER, "Help me choose"),
        PromptTurn(PromptTurnKind.ASSISTANT, "Here is a comparison"),
    )
    private val snapshot = ComposerPredictionSnapshot("chat-1", "turn-1", history, config, emptyList())

    @Test fun preservesPrefixAndAddsOnlyTailInstruction() {
        val request = predictionHistory(history)
        assertEquals(history, request.dropLast(1))
        assertEquals(PromptTurnKind.USER, request.last().kind)
        assertEquals(3, history.size)
    }

    /**
     * Predictions now declare the completed turn's tools, so the model may answer with a tool call.
     * Providers render one back as text in exactly this shape, and none of it may reach the composer.
     */
    @Test fun rejectsToolCallsRenderedBackAsText() {
        listOf(
            "\n<shell><command>ls</command></shell>",
            "<tool_call name=\"shell\">ls</tool_call>",
            "<function_call name=\"read_file\">{}</function_call>",
            "function_call: shell",
            "tool_call: shell",
        ).forEach { assertNull(it, validatePrediction(it)) }
    }

    @Test fun rejectsProtocolReasoningAndMultipleOrMeaninglessSuggestions() {
        listOf("", "NO_PREDICTION", "...", "null", "<think>Reasoning</think>",
            "<tool name=\"shell\">run</tool>", "{\"message\":\"next\"}",
            "First suggestion\nSecond suggestion", "1. Next message", "Suggestion: More details",
            "x".repeat(241), "Error: Request failed", "谢谢！", "好的", "你好", "Thanks.", "Thank you", "Hi!").forEach { assertNull(it, validatePrediction(it)) }
        assertEquals("Can you explain the tradeoffs?", validatePrediction(" Can you explain the tradeoffs? "))
        assertEquals("详细说说", validatePrediction("详细说说"))
        assertEquals("好的，运行测试", validatePrediction("好的，运行测试"))
        assertEquals("Thanks, can you compare costs?", validatePrediction("Thanks, can you compare costs?"))
    }

    @Test fun stripsDangerousCustomParametersAndCarriesTheTurnsOwnTokenCap() {
        val unsafe = listOf("tools", "messages", "generationConfig", "n", "response_format", "previous_response_id")
        val params = unsafe.map { parameter(it, 99999) } + parameter("temperature", 1) +
            parameter("top_p", 1).copy(isEnabled = false) + parameter("max_tokens", 32_000)
        val result = predictionParameters(params)
        assertEquals(setOf("temperature", "max_tokens"), result.map { it.apiName }.toSet())
        assertEquals(32_000, result.single { it.apiName == "max_tokens" }.currentValue)
        assertEquals(99999, params.first().currentValue)
        // The filter reads no provider field, so it cannot vary by provider; the wire effect of the
        // surviving cap is covered for the OpenAI chat and Responses adapters in
        // ComposerPredictionRequestBodyTest.
    }

    /** A turn that set no cap must not have one invented for it; the provider's default applies. */
    @Test fun inventsNoOutputCapWhenTheCompletedTurnHadNone() {
        assertEquals(emptyList<ModelParameter<*>>(), predictionParameters(emptyList()))
        val names = predictionParameters(listOf(parameter("temperature", 1))).map { it.apiName }
        assertEquals(listOf("temperature"), names)
    }

    /**
     * The completed turn's thinking controls decide what its provider cached against. A prediction
     * that dropped them would reason at a different intensity, so the request would stop matching.
     */
    @Test fun carriesTheCompletedTurnsThinkingControlsItsTokenBudgetsAndItsCaps() {
        val params = listOf(
            parameter("reasoning_effort", "high"),
            parameter("thinking_budget", 8_192),
            parameter("thinkingBudget", 8_192),
            parameter("budget_tokens", 8_192),
            parameter("enable_thinking", true),
            parameter("thinking_level", "medium"),
            objectParameter("thinking", """{"type":"enabled"}"""),
            parameter("max_tokens", 32_000),
            parameter("max_completion_tokens", 32_000),
            parameter("max_output_tokens", 32_000),
        )
        val names = predictionParameters(params).map { it.apiName }.toSet()
        assertEquals(
            setOf(
                "reasoning_effort", "enable_thinking", "thinking_level",
                "budget_tokens", "thinking_budget", "thinkingBudget",
                "max_tokens", "max_completion_tokens", "max_output_tokens",
                "thinking",
            ),
            names,
        )
        assertEquals(
            "high",
            predictionParameters(params)
                .single { it.apiName == "reasoning_effort" }.currentValue,
        )
        // A budget must sit below the request's cap, and the cap here is the completed turn's own,
        // so the budget that turn sent was already valid against exactly this cap.
        assertEquals(
            8_192,
            predictionParameters(params)
                .single { it.apiName == "budget_tokens" }.currentValue,
        )
        // Native reasoning objects are allowed, but a scalar field mislabeled OBJECT is not.
        assertEquals(
            emptyList<ModelParameter<*>>(),
            predictionParameters(listOf(objectParameter("max_tokens", "{}"), objectParameter("reasoning_effort", "{}"))),
        )
    }

    @Test fun preservesNativeThinkingObjectsWithoutChangingValuesCategoryOrOrder() {
        val params = listOf(
            objectParameter("reasoning", """{ "effort": "high", "summary": "auto" }"""),
            objectParameter("thinking", """{"type":"enabled","budget_tokens":8192}"""),
            objectParameter("output_config", """{"effort":"max"}"""),
            objectParameter("thinkingConfig", """{"thinkingLevel":"high","thinkingBudget":-1,"includeThoughts":true}""")
                .copy(category = ParameterCategory.GENERATION),
            parameter("thinking", """{"type":"adaptive"}"""),
        )
        val result = predictionParameters(params)
        assertEquals(params, result)
        params.zip(result).forEach { (original, retained) -> assertSame(original, retained) }
    }

    @Test fun unsupportedThinkingObjectsStopBeforeCreatingAProvider() = runBlocking {
        var acquired = false
        val runner = ComposerPredictionService(context, { _, _ ->
            acquired = true
            error("Must not acquire a provider")
        })
        for ((name, raw) in listOf(
            "reasoning" to """{"effort":"high","messages":[]}""",
            "reasoning" to """{"effort":{"tools":[]}}""",
            "thinking" to """{"type":"enabled","budget_tokens":"8192"}""",
            "thinkingConfig" to """{"includeThoughts":"true"}""",
            "output_config" to """{"effort":"high","format":{"type":"json_schema"}}""",
            "reasoning" to "{",
            "reasoning" to "null",
            "reasoning" to "[]",
        )) {
            try {
                runner.predict(snapshot.copy(modelParameters = listOf(objectParameter(name, raw))))
                fail("Expected unsupported $name to stop the request")
            } catch (_: IllegalArgumentException) {
                assertFalse(acquired)
            }
        }
        assertTrue(predictionParameters(listOf(
            objectParameter("reasoning", "{").copy(isEnabled = false)
        )).isEmpty())
    }

    @Test fun mirrorsTheCompletedTurnInsteadOfDisablingToolsSearchOrBounds() {
        val original = config.copy(
            enableGoogleSearch = true,
            enableToolCall = false,
            customHeaders = """{"x-opencode-session":"foreground","Authorization":"custom"}""",
            maxTokens = 65_536,
            maxTokensEnabled = true,
        )
        val result = predictionConfig(original)
        // Turning either off here would change the cached prefix, and would raise tool calling the
        // completed turn had disabled. The auxiliary request has to look like the completed one.
        assertTrue(result.enableGoogleSearch)
        assertFalse(result.enableToolCall)
        assertEquals("foreground", JSONObject(result.customHeaders).getString("x-opencode-session"))
        assertEquals("custom", JSONObject(result.customHeaders).getString("Authorization"))
        assertEquals(65_536, result.maxTokens)
        assertTrue(result.maxTokensEnabled)
        assertEquals("[]", result.customParameters)
        assertTrue(original.enableGoogleSearch)
        assertEquals("foreground", JSONObject(original.customHeaders).getString("x-opencode-session"))
    }

    @Test fun explicitlyRejectsUnknownAndRetiredProviders() {
        assertFalse(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "plugin-provider")))
        assertFalse(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "MNN")))
        assertFalse(ComposerPredictionService.supports(config.copy(modelName = "gemini-3.1-flash-image")))
        assertTrue(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "OPENCODE_ZEN_FREE", modelName = "mimo-v2.5-free")))
        assertTrue(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "OPENCODE_ZEN_FREE", modelName = "muse-spark-1.3-contributor-free")))
        assertTrue(ComposerPredictionService.supports(config.copy(apiProviderTypeId = "OPENAI_CODEX", modelName = "gpt-6-astra")))
        assertTrue(ComposerPredictionService.supports(config))
    }

    @Test fun reusesTheCompletedTurnsToolsAndIdentitySoItsPrefixCanStillBeHit() = runBlocking {
        val tools = listOf(ToolPrompt(name = "shell", description = "Run a shell command"))
        val shared = snapshot.copy(
            availableTools = tools,
            providerSessionId = "foreground-session",
            workspacePath = "/workspace/project",
        )
        val instances = mutableListOf<FakeService>()
        val runner = runner { FakeService().also(instances::add) }
        assertEquals("Tell me more", runner.predict(shared))
        assertEquals("Tell me more", runner.predict(shared))
        assertEquals(2, instances.size)
        instances.forEach {
            assertEquals(TokenStatCategory.COMPOSER_PREDICTION, it.category)
            assertEquals(tools, it.tools)
            assertFalse(it.thinking)
            assertFalse(it.retry)
            assertEquals("foreground-session", it.session)
            assertEquals("/workspace/project", it.workspace)
            assertTrue(it.cancelled)
            assertTrue(it.released)
        }
    }

    @Test fun keepsAnIsolatedIdentityOnlyWhenTheCompletedTurnHadNone() = runBlocking {
        val instances = mutableListOf<FakeService>()
        val runner = runner { FakeService().also(instances::add) }
        assertEquals("Tell me more", runner.predict(snapshot))
        assertEquals(providerSessionIdForScope("composer-prediction:chat-1"), instances.single().session)
        assertEquals(emptyList<ToolPrompt>(), instances.single().tools)
    }

    /**
     * The reasoning block shares the chunk stream, so it must not be mistaken for the suggestion,
     * and it must not eat the bounded response budget either.
     */
    @Test fun followsTheCompletedTurnsThinkingChoiceAndStripsItsInlineReasoningBlock() = runBlocking {
        val instances = mutableListOf<FakeService>()
        val runner = runner { FakeService(reasoning = true).also(instances::add) }
        val shared = snapshot.copy(
            enableThinking = true,
            modelParameters = listOf(
                parameter("reasoning_effort", "high"),
                parameter("max_tokens", 32_000),
                parameter("budget_tokens", 32_000),
            ),
        )
        assertEquals("Tell me more", runner.predict(shared))
        val service = instances.single()
        assertTrue(service.thinking)
        val sent = requireNotNull(service.parameters).associateBy { it.apiName }
        assertEquals("high", sent.getValue("reasoning_effort").currentValue)
        assertEquals(32_000, sent.getValue("max_tokens").currentValue)
        assertEquals(32_000, sent.getValue("budget_tokens").currentValue)
    }

    /** The read bound is the only ceiling left, so hitting it must fail visibly rather than buffer on. */
    @Test fun failsInsteadOfBufferingAnEndlessStream() = runBlocking {
        val fake = FakeService(oversized = true)
        // The stream fixture lets the failure escape through a builder that logs it, and that logging
        // goes through Android APIs a JVM test cannot provide.
        val loggingWasEnabled = StreamLogger.isEnabled
        StreamLogger.setEnabled(false)
        try {
            runner { fake }.predict(snapshot)
            fail("Expected the read bound to stop the stream")
        } catch (error: IOException) {
            assertEquals("Prediction exceeded response limit", error.message)
        } finally {
            StreamLogger.setEnabled(loggingWasEnabled)
        }
        assertTrue(fake.released)
    }

    /** An unfinished reasoning block is not a suggestion: the prediction completes empty instead. */
    @Test fun rejectsAPredictionThatNeverLeftItsReasoningBlock() = runBlocking {
        val runner = runner { FakeService(reasoning = true, closeReasoning = false) }
        assertNull(runner.predict(snapshot.copy(enableThinking = true)))
    }

    @Test fun timeoutClosesBlockedIndependentCallAndReleasesIt() = runBlocking {
        val fake = FakeService(block = true)
        try {
            runner(timeoutMs = 100) { fake }.predict(snapshot)
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) {
            assertTrue(fake.cancelled)
            assertTrue(fake.released)
        }
    }

    @Test fun errorsNeverBecomeComposerTextAndStillReleaseService() = runBlocking {
        val fake = FakeService(fail = true)
        try {
            runner { fake }.predict(snapshot)
            fail("Expected failure")
        } catch (error: IOException) {
            assertEquals("Provider failed", error.message)
            assertTrue(fake.released)
        }
        val warning = FakeService(warn = true)
        try {
            runner { warning }.predict(snapshot)
            fail("Expected provider warning to fail prediction")
        } catch (error: IOException) {
            assertEquals("Prediction provider reported an error", error.message)
            assertTrue(warning.released)
        }
    }

    private fun runner(timeoutMs: Long = 2_000, factory: () -> FakeService) = ComposerPredictionService(
        context, { model, parameters ->
            val service = factory()
            MultiServiceManager.ServiceLease({ service.release() }, service, model, 0, parameters)
        }, timeoutMs,
    )

    private fun parameter(name: String, value: Int) = ModelParameter(
        name, name, name, defaultValue = value, currentValue = value,
        isEnabled = true, valueType = ParameterValueType.INT,
    )

    private fun parameter(name: String, value: String) = ModelParameter(
        name, name, name, defaultValue = value, currentValue = value,
        isEnabled = true, valueType = ParameterValueType.STRING,
    )

    private fun parameter(name: String, value: Boolean) = ModelParameter(
        name, name, name, defaultValue = value, currentValue = value,
        isEnabled = true, valueType = ParameterValueType.BOOLEAN,
    )

    private fun objectParameter(name: String, value: String) = ModelParameter(
        name, name, name, defaultValue = value, currentValue = value,
        isEnabled = true, valueType = ParameterValueType.OBJECT,
    )

    private class FakeService(
        val block: Boolean = false,
        val fail: Boolean = false,
        val warn: Boolean = false,
        val reasoning: Boolean = false,
        val closeReasoning: Boolean = true,
        val oversized: Boolean = false,
    ) : AIService {
        override val inputTokenCount = 0
        override val cachedInputTokenCount = 0
        override val outputTokenCount = 0
        override val providerModel = "fake:chat"
        @Volatile var cancelled = false
        var released = false
        var category: TokenStatCategory? = null
        var tools: List<ToolPrompt>? = null
        var parameters: List<ModelParameter<*>>? = null
        var thinking = true
        var retry = true
        var session: String? = null
        var workspace: String? = null
        private val stop = CountDownLatch(1)
        override fun resetTokenCounts() = Unit
        override fun cancelStreaming() { cancelled = true; stop.countDown() }
        override fun release() { released = true }
        override suspend fun getModelsList(context: Context) = Result.success(emptyList<ModelOption>())
        override suspend fun testConnection(context: Context, onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?) = Result.success("")
        override suspend fun calculateInputTokens(chatHistory: List<PromptTurn>, availableTools: List<ToolPrompt>?) = 0
        override suspend fun sendMessage(
            context: Context, chatHistory: List<PromptTurn>, modelParameters: List<ModelParameter<*>>,
            enableThinking: Boolean, stream: Boolean, availableTools: List<ToolPrompt>?, preserveThinkInHistory: Boolean,
            onTokensUpdated: suspend (Int, Int, Int) -> Unit,
            onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
            onNonFatalError: suspend (String) -> Unit, enableRetry: Boolean, statsCategory: TokenStatCategory?,
        ): Stream<String> {
            category = statsCategory; tools = availableTools; thinking = enableThinking; retry = enableRetry
            parameters = modelParameters
            val identity = currentCoroutineContext()[OpenCodeSessionContext]
            session = identity?.sessionId
            workspace = identity?.workspacePath
            // Throw directly from the provider boundary. StreamBuilders logs failures through
            // Android APIs on Dispatchers.IO, outside a test-thread static logging mock.
            if (fail) return object : AbstractStream<String>() {
                override suspend fun collect(collector: StreamCollector<String>) {
                    throw IOException("Provider failed")
                }
                override suspend fun emitBufferedItem(item: String) {
                    error("Failing provider fixture must not emit buffered content")
                }
            }
            return com.ai.assistance.operit.util.stream.stream {
                if (block) check(stop.await(2, TimeUnit.SECONDS)) { "Cancellation did not close blocked call" }
                if (warn) onNonFatalError("Provider stream error")
                if (oversized) {
                    emit("x".repeat(ComposerPredictionService.MAX_RESPONSE_CHARS + 1))
                    return@stream
                }
                if (reasoning) {
                    emit(ChatUtils.PROVIDER_REASONING_OPEN_TAG)
                    emit("Weighing the options before answering")
                    if (closeReasoning) emit("</think>")
                }
                emit("Tell me more")
            }
        }
    }
}
