package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import com.ai.assistance.operit.ui.permissions.ToolPermissionDecision
import com.ai.assistance.operit.ui.permissions.permissionDeniedByAutomaticReview
import com.ai.assistance.operit.ui.permissions.permissionBlockedByReviewFailure
import com.ai.assistance.operit.ui.permissions.PermissionReviewFailureKind
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class InputProcessorTest {
    @Test
    fun reviewFailuresNeitherAdvanceNorResetTheDenialCircuit() {
        val chatId = "review-failure-${System.nanoTime()}"
        val turnId = "turn"
        val denied = permissionDeniedByAutomaticReview("denied")
        repeat(2) {
            val result = applyDeferredPermissionReviewCircuit(chatId, turnId, denied, true)
                as ToolPermissionDecision.Denied
            assertFalse(result.interruptTurn)
        }
        repeat(4) {
            val failed = permissionBlockedByReviewFailure(PermissionReviewFailureKind.TIMED_OUT, "")
            assertEquals(failed, applyDeferredPermissionReviewCircuit(chatId, turnId, failed, true))
        }
        val thirdDenial = applyDeferredPermissionReviewCircuit(chatId, turnId, denied, true)
            as ToolPermissionDecision.Denied
        assertTrue(thirdDenial.interruptTurn)
    }

    @Test
    fun disabledPromptHooksReturnRawInputWithoutAccessingHookContext() = runBlocking {
        val rawInput = "review this exact tool call"

        val result =
            InputProcessor.processUserInput(
                context = Mockito.mock(Context::class.java),
                input = rawInput,
                chatId = "review-child",
                roleCardId = null,
                isSubTask = true,
                promptHooksEnabled = false,
            )

        assertEquals(rawInput, result)
    }

    @Test
    fun permissionBatchStartsConcurrentlyAndReturnsInOriginalOrder() = runBlocking {
        val started = Channel<Int>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val result =
            async {
                parallelMapPreservingOrder(listOf(1, 2, 3, 4)) { value ->
                    started.send(value)
                    release.await()
                    value * 10
                }
            }

        val observedStarts = List(4) { started.receive() }.toSet()
        assertEquals(setOf(1, 2, 3, 4), observedStarts)
        release.complete(Unit)
        assertEquals(listOf(10, 20, 30, 40), result.await())
    }

    @Test
    fun toolCallsInsideThinkingBlocksAreNeverExecutable() = runBlocking {
        val hidden =
            """
            <think><tool name="hidden"><param name="value">secret</param></tool></think>
            Visible answer
            """.trimIndent()
        val mixed =
            hidden +
                """

                <tool name="visible"><param name="value">ok</param></tool>
                """.trimIndent()

        Mockito.mockStatic(AppLogger::class.java).use {
            assertTrue(ToolExecutionManager.extractExecutableToolInvocations(hidden).isEmpty())
            assertEquals(
                listOf("visible"),
                ToolExecutionManager.extractExecutableToolInvocations(mixed).map { it.tool.name },
            )
        }
    }

    @Test
    fun deferredCircuitBreakerUsesOriginalDecisionOrder() {
        val denial = permissionDeniedByAutomaticReview("denied")
        val chatId = "ordered-${System.nanoTime()}"
        val turnId = "turn"

        assertFalse(
            (applyDeferredPermissionReviewCircuit(chatId, turnId, denial, true)
                    as ToolPermissionDecision.Denied)
                .interruptTurn
        )
        assertFalse(
            (applyDeferredPermissionReviewCircuit(chatId, turnId, denial, true)
                    as ToolPermissionDecision.Denied)
                .interruptTurn
        )
        assertTrue(
            (applyDeferredPermissionReviewCircuit(chatId, turnId, denial, true)
                    as ToolPermissionDecision.Denied)
                .interruptTurn
        )

        val resetChatId = "ordered-reset-${System.nanoTime()}"
        applyDeferredPermissionReviewCircuit(resetChatId, turnId, denial, true)
        applyDeferredPermissionReviewCircuit(
            resetChatId,
            turnId,
            ToolPermissionDecision.Allowed,
            true,
        )
        assertFalse(
            (applyDeferredPermissionReviewCircuit(resetChatId, turnId, denial, true)
                    as ToolPermissionDecision.Denied)
                .interruptTurn
        )
    }
}
