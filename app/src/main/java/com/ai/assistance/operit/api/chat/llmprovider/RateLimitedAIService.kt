package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.MutableSharedStream
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.withEventChannel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore

class RateLimitedAIService(
    private val delegate: AIService,
    private val rateLimiter: SlidingWindowRateLimiter?,
    private val concurrencySemaphore: Semaphore?
) : AIService by delegate {

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        statsCategory: com.ai.assistance.operit.data.stats.TokenStatCategory?
    ): Stream<String> {
        val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        return com.ai.assistance.operit.util.stream.stream {
        rateLimiter?.acquire()
        concurrencySemaphore?.acquire()

        try {
            val response = delegate.sendMessage(
                context = context,
                chatHistory = chatHistory,
                modelParameters = modelParameters,
                enableThinking = enableThinking,
                stream = stream,
                availableTools = availableTools,
                preserveThinkInHistory = preserveThinkInHistory,
                onTokensUpdated = onTokensUpdated,
                onUsageReported = onUsageReported,
                onNonFatalError = onNonFatalError,
                enableRetry = enableRetry,
                statsCategory = statsCategory
            )
            var copiedEvents = 0
            suspend fun drainEvents() {
                val source = (response as? TextStreamEventCarrier)?.eventChannel?.replayCache.orEmpty()
                while (copiedEvents < source.size) events.emit(source[copiedEvents++])
            }
            try {
                response.collect { chunk ->
                    drainEvents()
                    emit(chunk)
                }
            } finally {
                withContext(NonCancellable) { drainEvents() }
            }
        } finally {
            concurrencySemaphore?.release()
        }
        }.withEventChannel(events)
    }
}
