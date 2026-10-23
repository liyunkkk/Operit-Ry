package com.ai.assistance.operit.api.chat.prediction

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ModelConfigData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerPredictionCoordinatorTest {
    private fun snapshot(turn: String = "turn", chat: String = "chat") = ComposerPredictionSnapshot(
        chatId = chat, sourceTurnId = turn,
        history = listOf(PromptTurn(PromptTurnKind.ASSISTANT, "Final answer")),
        modelConfig = ModelConfigData(id = "model", name = "Model"), modelParameters = emptyList(),
    )

    @Test fun disabledDoesNotRequestAndEmptyIsTerminal() = runTest {
        var calls = 0
        val coordinator = ComposerPredictionCoordinator(this) { calls++; null }
        coordinator.update(false, "context", snapshot()) { true }
        runCurrent()
        assertEquals(0, calls)
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(ComposerPredictionCoordinator.Status.EMPTY, coordinator.status)
        coordinator.update(true, "context", snapshot()) { true }
        coordinator.invalidate()
        coordinator.update(true, "new context", snapshot()) { true }
        runCurrent()
        assertEquals(1, calls)
        assertNull(coordinator.text.value)
    }

    @Test fun typingAndAdoptionRetainCacheWithoutAnotherRequest() = runTest {
        val response = CompletableDeferred<String?>()
        var calls = 0
        val coordinator = ComposerPredictionCoordinator(this) { calls++; response.await() }
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        // Typing only hides at the presentation layer; completion can still populate the cache.
        assertNull(coordinator.textForAdoption(null, false) { true })
        response.complete("Show an example")
        runCurrent()
        assertEquals("Show an example", coordinator.text.value)
        assertNull(coordinator.textForAdoption("old suggestion", true) { true })
        assertNull(coordinator.textForAdoption(null, true) { false })
        assertEquals("Show an example", coordinator.textForAdoption(null, true) { true })
        assertNull(coordinator.textForAdoption(null, false) { true })
        assertEquals("Show an example", coordinator.textForAdoption(null, true) { true })
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun thinkingChangesPreserveReadyTextAndAdoptionWithoutRequestingAgain() = runTest {
        var calls = 0
        val coordinator = ComposerPredictionCoordinator(this) { calls++; "Show an example" }
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        repeat(3) { assertFalse(coordinator.invalidateForThinkingChange()) }
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(ComposerPredictionCoordinator.Status.READY, coordinator.status)
        assertEquals("Show an example", coordinator.text.value)
        assertEquals("Show an example", coordinator.textForAdoption(null, true) { true })
        // Other invalidations, such as a new message, must still clear the cached suggestion.
        coordinator.invalidate()
        assertNull(coordinator.text.value)
    }

    @Test fun thinkingChangesCancelAnUnfinishedPredictionAndIgnoreItsLateResult() = runTest {
        val result = CompletableDeferred<String?>()
        val coordinator = ComposerPredictionCoordinator(this) {
            withContext(NonCancellable) { result.await() }
        }
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        assertTrue(coordinator.invalidateForThinkingChange())
        result.complete("Old thinking settings")
        runCurrent()
        assertEquals(ComposerPredictionCoordinator.Status.IDLE, coordinator.status)
        assertNull(coordinator.text.value)
    }

    @Test fun lateNonCooperativeResultCannotOverwriteNewTurn() = runTest {
        val oldResult = CompletableDeferred<String?>()
        val coordinator = ComposerPredictionCoordinator(this) { source ->
            if (source.sourceTurnId == "old") withContext(NonCancellable) { oldResult.await() }
            else "New result"
        }
        coordinator.update(true, "old context", snapshot("old")) { true }
        runCurrent()
        coordinator.update(true, "new context", snapshot("new")) { true }
        runCurrent()
        assertEquals("New result", coordinator.text.value)
        oldResult.complete("Stale result")
        runCurrent()
        assertEquals("New result", coordinator.text.value)
    }

    @Test fun switchDeleteRevertDisableAndConfigChangesInvalidateCachedAndInflightResults() = runTest {
        for (reason in listOf("switch", "delete", "revert", "disable", "config", "cancel")) {
            val result = CompletableDeferred<String?>()
            val coordinator = ComposerPredictionCoordinator(this) {
                withContext(NonCancellable) { result.await() }
            }
            coordinator.update(true, "before $reason", snapshot(reason)) { true }
            runCurrent()
            coordinator.update(false, null, null) { false }
            result.complete("Late answer")
            runCurrent()
            assertNull(reason, coordinator.text.value)
            assertNull(reason, coordinator.textForAdoption(null, true) { true })
        }
    }

    @Test fun failureDoesNotRetryAndDoesNotEscapeToMainTurn() = runTest {
        var calls = 0
        val coordinator = ComposerPredictionCoordinator(this) { calls++; error("Provider failed") }
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        assertEquals(ComposerPredictionCoordinator.Status.FAILED, coordinator.status)
        coordinator.update(true, "different context", snapshot()) { true }
        runCurrent()
        assertEquals(1, calls)
        assertNull(coordinator.text.value)
    }

    @Test fun requestTimeoutEndsAsFailedWithoutRetryingOrPublishingText() = runTest {
        var calls = 0
        val coordinator = ComposerPredictionCoordinator(this) {
            calls++
            withTimeout(100) { awaitCancellation() }
        }
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        assertEquals(ComposerPredictionCoordinator.Status.RUNNING, coordinator.status)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ComposerPredictionCoordinator.Status.FAILED, coordinator.status)
        assertNull(coordinator.text.value)
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun externalCancellationBeforeTimeoutKeepsInvalidatedState() = runTest {
        val coordinator = ComposerPredictionCoordinator(this) {
            withTimeout(100) { awaitCancellation() }
        }
        coordinator.update(true, "context", snapshot()) { true }
        runCurrent()
        coordinator.invalidate()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ComposerPredictionCoordinator.Status.IDLE, coordinator.status)
        assertNull(coordinator.text.value)
    }

    @Test fun invalidAtCompletionIsNeverPublished() = runTest {
        var valid = true
        val response = CompletableDeferred<String?>()
        val coordinator = ComposerPredictionCoordinator(this) { response.await() }
        coordinator.update(true, "context", snapshot()) { valid }
        runCurrent()
        valid = false
        response.complete("Stale result")
        runCurrent()
        assertNull(coordinator.text.value)
    }

    @Test fun sourceIdentityIncludesChatAndAllowsLaterSuccessfulTurn() = runTest {
        var calls = 0
        val coordinator = ComposerPredictionCoordinator(this) { calls++; it.chatId }
        coordinator.update(true, "first", snapshot("turn", "a")) { true }
        runCurrent()
        coordinator.update(true, "second", snapshot("turn", "b")) { true }
        runCurrent()
        assertEquals(2, calls)
        assertEquals("b", coordinator.text.value)
    }
}
