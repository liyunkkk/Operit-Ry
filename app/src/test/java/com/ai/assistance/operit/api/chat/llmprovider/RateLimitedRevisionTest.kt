package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.*
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.stream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class RateLimitedRevisionTest {
    @Test fun rollbackDuringRetryWaitSurvivesSteeringAndRateLimiterWrapper() = runBlocking {
        StreamLogger.setEnabled(false)
        val inbox = TurnInputInbox()
        val retried = CompletableDeferred<Unit>()
        val events = MutableSharedStream<TextStreamEvent>(replay = Int.MAX_VALUE)
        val delegate = object : AIService by mock(AIService::class.java) {
            override suspend fun sendMessage(
                context: Context, chatHistory: List<PromptTurn>, modelParameters: List<ModelParameter<*>>,
                enableThinking: Boolean, stream: Boolean, availableTools: List<ToolPrompt>?,
                preserveThinkInHistory: Boolean, onTokensUpdated: suspend (Int, Int, Int) -> Unit,
                onUsageReported: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
                onNonFatalError: suspend (String) -> Unit, enableRetry: Boolean, statsCategory: TokenStatCategory?,
            ): Stream<String> = com.ai.assistance.operit.util.stream.stream {
                events.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "attempt"))
                emit("failed draft")
                events.emit(TextStreamEvent(TextStreamEventType.ROLLBACK, "attempt"))
                retried.complete(Unit)
                awaitCancellation()
            }.withEventChannel(events)
        }
        val service = RateLimitedAIService(delegate, null, null)
        lateinit var response: Stream<String>
        val request = async {
            runSteerableSampling(inbox) {
                response = service.sendMessage(mock(Context::class.java))
                response.collect { }
            }
        }
        retried.await()
        inbox.offer(TurnInputInbox.Input("steer"))
        assertTrue(withTimeout(1_000) { request.await() })
        assertEquals(
            listOf(TextStreamEventType.SAVEPOINT, TextStreamEventType.ROLLBACK),
            (response as TextStreamEventCarrier).eventChannel.replayCache.map { it.eventType },
        )
    }

    @Test fun steeringWhileWaitingForSlotDoesNotReleaseSomeoneElsesPermit() = runBlocking {
        StreamLogger.setEnabled(false)
        val inbox = TurnInputInbox()
        val semaphore = Semaphore(1, acquiredPermits = 1)
        val waiting = CompletableDeferred<Unit>()
        val request = async {
            runSteerableSampling(inbox) {
                val service = RateLimitedAIService(mock(AIService::class.java), null, semaphore)
                val response = service.sendMessage(mock(Context::class.java))
                waiting.complete(Unit)
                response.collect { error("no request should run") }
            }
        }
        waiting.await()
        inbox.offer(TurnInputInbox.Input("new plan"))
        assertTrue(withTimeout(1_000) { request.await() })
        assertEquals(0, semaphore.availablePermits)
        semaphore.release()
    }
}
