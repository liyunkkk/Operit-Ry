package com.ai.assistance.operit.api.chat.prediction

import com.ai.assistance.operit.core.agent.collaboration.*
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.InputProcessingState
import org.junit.Assert.*
import org.junit.Test

class ComposerPredictionEligibilityTest {
    @Test fun onlyCompletedOwnerTurnPassesActualPublicationGate() {
        val options = ChatTurnOptions()
        for (state in listOf(
            InputProcessingState.Idle, InputProcessingState.Processing("preparing"),
            InputProcessingState.Receiving("streaming"), InputProcessingState.ExecutingTool("tool"),
            InputProcessingState.ProcessingToolResult("tool"), InputProcessingState.Summarizing("continuation"),
            InputProcessingState.Error("failed"),
        )) assertFalse(state.toString(), isComposerPredictionCompletion(state, options, false))
        assertTrue(isComposerPredictionCompletion(InputProcessingState.Completed, options, false))
        for (internal in listOf(
            options.copy(isSubTask = true), options.copy(isCollaborationAgent = true),
            options.copy(deliveredByTool = true), options.copy(persistTurn = false),
            options.copy(hideUserMessage = true),
        )) assertFalse(isComposerPredictionCompletion(InputProcessingState.Completed, internal, false))
        assertFalse(isComposerPredictionCompletion(InputProcessingState.Completed, options, true))
    }

    @Test fun descendantsMustSettleAndMailboxMustBeConsumedBeforePrediction() {
        val root = CollaborationAgent(rootChatId = "chat", path = AgentPath.ROOT, chatId = "chat")
        val child = root.copy(path = "/root/child", chatId = "child", status = CollaborationStatus.RUNNING)
        fun blocked(agents: List<CollaborationAgent>, jobs: Set<String> = emptySet(), reservations: Set<String> = emptySet()) =
            composerPredictionBlockedByCollaboration("chat", false, false, false, jobs, reservations, agents)
        assertTrue(blocked(listOf(root, child)))
        val completed = child.copy(status = CollaborationStatus.COMPLETED)
        assertTrue(blocked(listOf(root, completed), jobs = setOf("chat:/root/child")))
        assertTrue(blocked(listOf(root, completed), reservations = setOf("chat:/root/new")))
        val awaitingConsumption = root.copy(messages = listOf(AgentMessage(
            "message", child.path, root.path, AgentMessageKind.FINAL_ANSWER, "Child result",
        )))
        assertTrue(blocked(listOf(awaitingConsumption, completed)))
        assertFalse(blocked(listOf(root, completed)))
        // Unrelated conversations never delay the owner's composer.
        assertFalse(blocked(listOf(root, child.copy(rootChatId = "other")), jobs = setOf("other:/root/child")))
        // The pure check leaves the final inherited history and mailbox untouched.
        assertEquals(1, awaitingConsumption.messages.size)
    }

    @Test fun keyRotationCursorDoesNotInvalidateButCredentialsAndSettingsStillDo() {
        val config = com.ai.assistance.operit.data.model.ModelConfigData(
            id = "model", name = "Model", modelName = "chat", useMultipleApiKeys = true,
            apiKeyPool = listOf(
                com.ai.assistance.operit.data.model.ApiKeyInfo("first", "first-secret"),
                com.ai.assistance.operit.data.model.ApiKeyInfo("second", "second-secret"),
            ),
        )
        assertEquals(composerPredictionConfigIdentity(config), composerPredictionConfigIdentity(config.copy(currentKeyIndex = 1)))
        assertNotEquals(composerPredictionConfigIdentity(config), composerPredictionConfigIdentity(config.copy(apiKeyPool = emptyList())))
        assertNotEquals(composerPredictionConfigIdentity(config), composerPredictionConfigIdentity(config.copy(apiEndpoint = "changed")))
        assertNotEquals(composerPredictionConfigIdentity(config), composerPredictionConfigIdentity(config.copy(temperature = 0.2f)))
    }

    @Test fun regeneratedTurnIdentityIsStablePerAttemptAndDifferentFromOriginal() {
        assertEquals("42:first", regeneratedPredictionTurnId(42, "first"))
        assertNotEquals("42", regeneratedPredictionTurnId(42, "first"))
        assertNotEquals(regeneratedPredictionTurnId(42, "first"), regeneratedPredictionTurnId(42, "second"))
    }
}
